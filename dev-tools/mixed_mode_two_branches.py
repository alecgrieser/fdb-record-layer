#!/usr/bin/env python3

#
# mixed_mode_two_branches.py
#
# This source file is part of the FoundationDB open source project
#
# Copyright 2015-2026 Apple Inc. and the FoundationDB project authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""
Automates validating a feature split across two stacked branches/PRs, where the first branch
adds the ability to deserialize a new wire format (without yet generating it) and the second
branch starts generating it. This lets a developer run the second branch's yaml-tests against
a server built from the first branch, using maven local as the transport, without waiting for
the first branch to actually be released.

This module is split into two halves:
  - Pure decision/parsing functions (no subprocess, no filesystem writes) that decide *what*
    should happen given some state. These are unit tested directly in
    test_mixed_mode_two_branches.py.
  - Thin, mostly-linear orchestration functions (cmd_*) that call git/gradle and are exercised
    by integration-style tests against real temporary git repositories.

This tool is for developers, not end users: on failure it lets Python's own traceback surface
rather than wrapping every precondition failure in a friendly message -- whoever is running it
can read a traceback and the stack trace itself is often the fastest way to see exactly which
git/gradle invocation misbehaved.

Typical usage:
    python dev-tools/mixed_mode_two_branches.py setup --old-branch feature/wire-format-part1 \\
                                                      --new-branch feature/wire-format-part2
    python dev-tools/mixed_mode_two_branches.py publish-old
    python dev-tools/mixed_mode_two_branches.py prepare-new
    python dev-tools/mixed_mode_two_branches.py test
    # ... iterate: edit either branch, re-run publish-old/prepare-new/test as needed ...
    python dev-tools/mixed_mode_two_branches.py teardown

See .claude/skills/mixed-mode-two-branches/SKILL.md for the higher-level workflow this supports.
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import uuid
from typing import Sequence

# versionutils.py is a CI-invoked script that stays in build/; this tool is not CI-invoked and
# lives alongside its own test in dev-tools/, so the two are no longer siblings on disk.
VERSIONUTILS = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'build', 'versionutils.py')
STASH_MESSAGE = 'mixed-mode-two-branches: new-rewrite'
UPDATE_TYPES = ['MAJOR', 'MINOR', 'BUILD', 'PATCH']
MERGE_STRATEGIES = ['enforce', 'auto-merge', 'auto-rebase']
VERSION_LINE = re.compile(r'version\s*=\s*\d+\.\d+\.\d+\.\d+')


# ---------------------------------------------------------------------------
# Pure functions: no subprocess calls, no filesystem writes. Unit testable directly.
# ---------------------------------------------------------------------------

def parse_worktree_list(output: str) -> dict[str, str]:
    """Parse `git worktree list --porcelain` output into {real_path: branch_name}.

    Keys are resolved with os.path.realpath, not just os.path.abspath: git itself reports fully
    symlink-resolved paths here (e.g. /private/var/... rather than /var/... on macOS), so callers
    must resolve symlinks the same way before looking anything up in this dict.
    """
    entries: dict[str, str] = {}
    path: str | None = None
    for line in output.splitlines():
        if line.startswith('worktree '):
            path = line[len('worktree '):]
        elif line.startswith('branch ') and path:
            branch_ref = line[len('branch '):]
            entries[os.path.realpath(path)] = branch_ref.replace('refs/heads/', '')
            path = None
    return entries


def find_stash_ref(stash_list_output: str, tag: str) -> str | None:
    """Given `git stash list` output, return the stash ref (e.g. 'stash@{0}') whose message
    contains `tag` -- a per-push UUID persisted in state.json, not just the tool's constant
    message prefix -- or None if not found. Matching the exact tag (rather than a shared
    substring like STASH_MESSAGE) guards against picking up an unrelated leftover stash that
    happens to carry the same generic text, e.g. one left behind by an interrupted earlier
    session. If more than one matches, the most recent (first-listed) one is returned."""
    for line in stash_list_output.splitlines():
        if tag in line:
            return line.split(':')[0]
    return None


def require_keys(state: dict, keys: Sequence[str], hint: str = '') -> None:
    """Raise RuntimeError if any of `keys` are missing/falsy in `state`."""
    missing = [k for k in keys if not state.get(k)]
    if missing:
        raise RuntimeError(('Missing required state: ' + ', '.join(missing) + '. ' + hint).strip())


def worktree_dir(main_root: str, which: str) -> str:
    return os.path.join(main_root, '.worktrees', 'mixed-mode', which)


def scratch_dir(main_root: str) -> str:
    """Directory the tool uses for its own untracked state (session state, parked rewrite
    patch). Kept as a single directory so it's easy to .gitignore in one line."""
    return os.path.join(main_root, '.mixed-mode-two-branches')


def state_path(main_root: str) -> str:
    return os.path.join(scratch_dir(main_root), 'state.json')


def rewrite_patch_path(main_root: str) -> str:
    return os.path.join(scratch_dir(main_root), 'new-rewrite.patch')


def maven_local_glob(version: str) -> str:
    return os.path.expanduser('~/.m2/repository/org/foundationdb/*/' + version)


def decide_setup_conflict(existing_state: dict, old_branch: str, new_branch: str, reconfigure: bool) -> bool:
    """Return True if `setup` should refuse because an existing session targets different
    branches and --reconfigure was not passed."""
    if not existing_state.get('old_branch') or reconfigure:
        return False
    return (existing_state.get('old_branch') != old_branch
            or existing_state.get('new_branch') != new_branch)


def decide_publish_action(state: dict, old_sha: str, force: bool) -> bool:
    """Decide whether publish-old should publish (True) or skip (False), given branch 1's
    current HEAD sha."""
    if force:
        return True
    if state.get('published_version') and state.get('published_from_sha') == old_sha:
        return False
    return True


def decide_prepare_action(state: dict, target_version: str, new_sha: str) -> str:
    """
    Decide what prepare-new needs to do, given branch 1's currently published version and
    branch 2's current HEAD sha. Returns one of:
      'noop'         - branch 2 is already fully prepared for target_version at this sha.
      'prepare'      - branch 2 has never been bumped to target_version (either it's never been
                       prepared at all, or a previous rewrite under a *different* version needs
                       to be undone first): bump to target_version, then rewrite.
      'rewrite_only' - branch 2 is already bumped to target_version but its sha changed
                       (edit/rebase); just redo the (idempotent) yamsql rewrite.

    Branch 1 always republishes under the same version string once one has been chosen (see
    cmd_publish_old), so target_version does not change across an ordinary session -- 'prepare'
    mainly covers the first-ever prepare-new, plus the defensive case of state.json having been
    hand-edited or carried over from a differently-configured session.
    """
    bumped_to = state.get('new_bumped_to')
    prepared_from_sha = state.get('new_prepared_from_sha')

    if bumped_to == target_version and prepared_from_sha == new_sha:
        return 'noop'
    if bumped_to == target_version:
        return 'rewrite_only'
    return 'prepare'


def render_status(state: dict) -> str:
    return json.dumps(state, indent=2, sort_keys=True)


def parse_version_tuple(version_string: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version_string.split('.'))


# ---------------------------------------------------------------------------
# Session state: a thin dict subclass so existing state['x']/state.get('x') access keeps
# working, while giving call sites descriptive precondition checks (e.g.
# state.require_ready_for_test()) instead of a generic require_keys(state, [...], hint) call
# that makes them re-derive which raw keys a given step actually needs.
# ---------------------------------------------------------------------------

class State(dict):
    """Session state, persisted as JSON at state_path(main_root)."""

    #: Filled in on load for any session that predates a given key, so older state.json files
    #: don't need a migration step -- new fields just quietly default in.
    DEFAULTS = {
        'merge_strategy': 'enforce',
    }

    @classmethod
    def load(cls, main_root: str) -> 'State':
        path = state_path(main_root)
        if not os.path.exists(path):
            data = {}
        else:
            try:
                with open(path) as f:
                    data = json.load(f)
            except (OSError, ValueError) as e:
                raise RuntimeError(
                    'Could not read session state at {}: {}. If this file was left corrupted by an '
                    'interrupted run, delete it and re-run setup.'.format(path, e))
        state = cls(data)
        for key, default in cls.DEFAULTS.items():
            state.setdefault(key, default)
        return state

    def save(self, main_root: str) -> None:
        # Write to a temp file and rename into place, so a crash/interruption mid-write can
        # never leave state.json truncated or otherwise invalid for the next invocation to trip
        # over.
        os.makedirs(scratch_dir(main_root), exist_ok=True)
        path = state_path(main_root)
        tmp_path = path + '.tmp'
        with open(tmp_path, 'w') as f:
            f.write(render_status(self))
            f.write('\n')
        os.replace(tmp_path, path)

    def require_ready_to_publish(self) -> None:
        require_keys(self, ['old_branch', 'update_type'], 'Run setup first.')

    def require_ready_to_prepare(self) -> None:
        require_keys(self, ['old_branch', 'new_branch', 'published_version', 'update_type'],
                      'Run setup and publish-old first.')

    def require_ready_for_test(self) -> None:
        require_keys(self, ['new_branch', 'published_version'], 'Run publish-old and prepare-new first.')

    def is_configured(self) -> bool:
        """False for a session that has never been through `setup` -- i.e. the state dict
        holds nothing beyond the keys State.load() fills in as defaults for every session,
        configured or not."""
        return bool(set(self) - set(self.DEFAULTS))


def load_state(main_root: str) -> State:
    return State.load(main_root)


def save_state(main_root: str, state: dict) -> None:
    if not isinstance(state, State):
        state = State(state)
    state.save(main_root)


# ---------------------------------------------------------------------------
# Side-effecting helpers: subprocess/filesystem. Kept thin and mostly linear so that the
# decision logic above carries the real complexity.
# ---------------------------------------------------------------------------

def run(command: Sequence[str], cwd: str | None = None, capture_output: bool = True,
        check: bool = True) -> subprocess.CompletedProcess | str | None:
    """Run a command.

    capture_output=True (the default) captures stdout/stderr as text, keeping them out of this
    tool's own console output; capture_output=False lets them stream straight through instead
    (gradle's own progress output, or a git conflict's diagnostic messages the developer needs
    to see directly).

    check=True (the default) raises RuntimeError on a non-zero exit -- with the command and its
    captured output embedded in the message -- and returns the captured stdout (or None if
    capture_output=False). check=False never raises; it's for commands where a non-zero exit is
    itself a meaningful result rather than a failure (e.g. `merge-base --is-ancestor`, `apply
    --check`, `git worktree remove`), and returns the whole CompletedProcess so the caller can
    inspect .returncode (and .stdout, if captured) itself.
    """
    process = subprocess.run(command, cwd=cwd, capture_output=capture_output, text=capture_output)
    if not check:
        return process
    if process.returncode != 0:
        raise RuntimeError('Command failed: {}\n{}\n{}'.format(
            command, process.stdout if capture_output else '', process.stderr if capture_output else ''))
    return process.stdout if capture_output else None


def run_gradle(cwd: str, *gradle_args: str) -> None:
    """Run ./gradlew with the given args, streaming output directly to the console."""
    cmd = [os.path.join(cwd, 'gradlew')] + list(gradle_args)
    result = run(cmd, cwd=cwd, capture_output=False, check=False)
    if result.returncode != 0:
        raise RuntimeError('gradle command failed: {}'.format(' '.join(gradle_args)))


def find_main_repo_root(cwd: str | None = None) -> str:
    """
    Locate the main repository's root directory, regardless of whether this script is invoked
    from the main checkout or from one of the worktrees this tool manages: worktrees share a
    single "common dir" (the real .git directory), whose parent is always the main repo root.
    """
    common_dir = run(['git', 'rev-parse', '--git-common-dir'], cwd=cwd).strip()
    common_dir = os.path.abspath(os.path.join(cwd or '.', common_dir))
    return os.path.dirname(common_dir)


def branch_exists(cwd: str, branch: str) -> bool:
    result = run(['git', 'rev-parse', '--verify', '--quiet', 'refs/heads/' + branch],
                 cwd=cwd, check=False)
    return result.returncode == 0


def git_rev_parse(cwd: str, ref: str) -> str:
    return run(['git', 'rev-parse', ref], cwd=cwd).strip()


def git_current_branch(cwd: str) -> str:
    return run(['git', 'branch', '--show-current'], cwd=cwd).strip()


def git_is_dirty(cwd: str) -> bool:
    return bool(run(['git', 'status', '--porcelain'], cwd=cwd).strip())


def branch_contains_ancestor(main_root: str, ancestor_ref: str, descendant_ref: str) -> bool:
    """True if descendant_ref's history includes ancestor_ref as an ancestor (or the same
    commit) -- i.e. branch 2 actually contains branch 1's current tip. Works from main_root
    regardless of which branch (if any) is currently checked out there."""
    result = run(['git', 'merge-base', '--is-ancestor', ancestor_ref, descendant_ref],
                 cwd=main_root, check=False)
    return result.returncode == 0


def ensure_worktree(main_root: str, which: str, branch: str) -> None:
    path = worktree_dir(main_root, which)
    entries = parse_worktree_list(run(['git', 'worktree', 'list', '--porcelain'], cwd=main_root))
    existing_branch = entries.get(os.path.realpath(path))
    if existing_branch is not None:
        if existing_branch != branch:
            raise RuntimeError(
                "Worktree at {} is already checked out to '{}', not '{}'. Remove it manually "
                '(git worktree remove) or choose a different layout.'.format(path, existing_branch, branch))
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    run(['git', 'worktree', 'add', path, branch], cwd=main_root)


def checkout_branch_inline(main_root: str, which: str, state: State) -> None:
    """
    Ensure `state[<which>_branch]` is checked out in the main working directory, switching if
    necessary.

    The rule this enforces: changes to branch 2 are allowed to be dirty here and are handled
    automatically -- this tool's own pending !current_version rewrite (tracked via
    `new_bumped_to`) is parked in a tagged stash (tagged with a fresh UUID recorded in
    state.json, not just a shared constant message, so a pop can't accidentally grab an
    unrelated leftover stash) and restored when branch 2 is checked out again. Changes to
    branch 1, by contrast, are never auto-stashed: they must be committed by the developer --
    publish-old itself refuses to run against a dirty branch 1 (see cmd_publish_old) -- and, if
    branch 2 doesn't yet contain them, either merged/rebased in by hand or by this tool via
    --merge-strategy (see branch_contains_ancestor's use in cmd_prepare_new).
    """
    target_branch = state[which + '_branch']
    current = git_current_branch(main_root)
    if current == target_branch:
        return

    if git_is_dirty(main_root):
        is_new_rewrite = (current == state.get('new_branch')
                           and state.get('new_mode') == 'inline'
                           and state.get('new_bumped_to'))
        if not is_new_rewrite:
            raise RuntimeError(
                "Refusing to switch the inline checkout from '{}' to '{}': the working tree has "
                'uncommitted changes that this tool did not create. Commit or stash them '
                'yourself, then re-run.'.format(current, target_branch))
        stash_tag = str(uuid.uuid4())
        run(['git', 'stash', 'push', '-u', '-m', STASH_MESSAGE + ' ' + stash_tag], cwd=main_root)
        state['new_stashed'] = True
        state['new_stash_tag'] = stash_tag
        save_state(main_root, state)

    run(['git', 'checkout', target_branch], cwd=main_root)

    if which == 'new' and state.get('new_stashed'):
        stash_tag = state.get('new_stash_tag')
        stash_ref = find_stash_ref(run(['git', 'stash', 'list'], cwd=main_root), stash_tag)
        if stash_ref is None:
            raise RuntimeError(
                "Expected a parked stash for branch 2's yamsql rewrite (tagged with this "
                "session's id '{}') but couldn't find one; check `git stash list` "
                'manually.'.format(stash_tag))
        result = run(['git', 'stash', 'pop', stash_ref], cwd=main_root, capture_output=False, check=False)
        if result.returncode != 0:
            raise RuntimeError(
                "Restoring branch 2's parked yamsql rewrite ({}) conflicted with the current "
                'working tree. Resolve manually via `git stash list` / `git stash pop`, then '
                're-run prepare-new.'.format(stash_ref))
        state['new_stashed'] = False
        state['new_stash_tag'] = None
        save_state(main_root, state)


def resolve_location(main_root: str, state: State, which: str) -> str:
    """Return the directory to operate in for `which` ('old'/'new'), checking out/switching as needed."""
    mode = state[which + '_mode']
    if mode == 'worktree':
        path = worktree_dir(main_root, which)
        if not os.path.isdir(path):
            raise RuntimeError("No worktree found for '{}' at {}; run setup first.".format(which, path))
        return path
    checkout_branch_inline(main_root, which, state)
    return main_root


def run_versionutils_increment(gradle_properties_path: str, update_type: str) -> None:
    run([sys.executable, VERSIONUTILS, gradle_properties_path, '--increment', '-u', update_type])


def get_version(gradle_properties_path: str) -> str:
    return run([sys.executable, VERSIONUTILS, gradle_properties_path]).strip()


def set_version(gradle_properties_path: str, version_string: str) -> None:
    """Overwrite the version= line in gradle_properties_path with an exact version string,
    without incrementing. Used to republish branch 1 under the same version it was already
    published under (see cmd_publish_old) -- versionutils.py's CLI is increment-only, so this
    small in-place rewrite covers the "set to an exact value" case it doesn't."""
    with open(gradle_properties_path) as f:
        lines = f.readlines()
    new_lines = []
    found = False
    for line in lines:
        if VERSION_LINE.match(line):
            new_lines.append('version={}\n'.format(version_string))
            found = True
        else:
            new_lines.append(line)
    if not found:
        raise RuntimeError('Unable to find a version= line in {}'.format(gradle_properties_path))
    with open(gradle_properties_path, 'w') as f:
        f.writelines(new_lines)


def warn_if_tag_exists(main_root: str, version: str) -> None:
    stdout = run(['git', 'tag', '--list', version], cwd=main_root)
    if stdout.strip():
        print("WARNING: a local tag already exists for '{}' — branch 1 may already have been "
              'actually released. Consider using the real released version directly instead of '
              'this local publish/bump workflow.'.format(version))


def capture_yamsql_patch(location: str, main_root: str) -> str | None:
    """Save the staged !current_version rewrite as a patch, so it can be surgically undone later."""
    names = run(['git', 'diff', '--cached', '--name-only', '--', '*.yamsql'], cwd=location).strip()
    if not names:
        return None
    os.makedirs(scratch_dir(main_root), exist_ok=True)
    patch_path = rewrite_patch_path(main_root)
    diff = run(['git', 'diff', '--cached', '--', '*.yamsql'], cwd=location)
    with open(patch_path, 'w') as f:
        f.write(diff)
    return patch_path


def remove_maven_local_artifacts(version: str) -> None:
    matches = glob.glob(maven_local_glob(version))
    if not matches:
        print('No maven-local artifacts found for version {}.'.format(version))
        return
    for path in matches:
        shutil.rmtree(path)
        print('Removed ' + path)


# ---------------------------------------------------------------------------
# Subcommands
# ---------------------------------------------------------------------------

def cmd_setup(args: argparse.Namespace, main_root: str) -> None:
    state = load_state(main_root)
    if decide_setup_conflict(state, args.old_branch, args.new_branch, args.reconfigure):
        raise RuntimeError(
            'Existing session found for old={} new={}. Pass --reconfigure to start a new '
            'session (this does not touch already-published artifacts or worktrees; run '
            'teardown first if you want those cleaned up).'
            .format(state.get('old_branch'), state.get('new_branch')))

    branches_and_modes = [('old', args.old_branch, args.old_mode), ('new', args.new_branch, args.new_mode)]
    for which, branch, mode in branches_and_modes:
        if not branch_exists(main_root, branch):
            raise RuntimeError("Branch '{}' does not exist.".format(branch))
        if mode == 'worktree':
            ensure_worktree(main_root, which, branch)

    branches_changed = (state.get('old_branch') != args.old_branch
                         or state.get('new_branch') != args.new_branch)
    if not state.is_configured() or branches_changed:
        # Either there was no prior session, or --reconfigure retargeted to different
        # branches -- either way, any recorded publish-old/prepare-new progress belonged to
        # the old branches and doesn't apply here, so start clean.
        state = State()
    # else: same branches as before (e.g. re-running setup just to tweak a mode or
    # --update-type) -- preserve published_version/new_bumped_to/etc. instead of wiping
    # them, so setup really is safe to re-run without losing progress.

    state['old_branch'] = args.old_branch
    state['new_branch'] = args.new_branch
    state['old_mode'] = args.old_mode
    state['new_mode'] = args.new_mode
    state['update_type'] = args.update_type
    state['merge_strategy'] = args.merge_strategy
    save_state(main_root, state)
    print('Configured: old={} ({}), new={} ({}), merge-strategy={}'.format(
        args.old_branch, args.old_mode, args.new_branch, args.new_mode, args.merge_strategy))


def cmd_publish_old(args: argparse.Namespace, main_root: str) -> None:
    state = load_state(main_root)
    state.require_ready_to_publish()

    old_sha = git_rev_parse(main_root, state['old_branch'])
    if not decide_publish_action(state, old_sha, args.force):
        print('publish-old: {} unchanged since last publish ({} @ {}); skipping. Use --force to '
              'republish anyway.'.format(state['old_branch'], state['published_version'], old_sha[:8]))
        return

    location = resolve_location(main_root, state, 'old')

    # Branch 1 must be committed before it can be (re)published: publishing whatever happens to
    # be sitting uncommitted in the working tree would silently disappear the moment something
    # else checks that branch out again, making the published artifact impossible to reproduce
    # or trace back to a specific commit. Ask the developer to commit rather than guessing.
    if git_is_dirty(location):
        raise RuntimeError(
            "Branch '{}' has uncommitted changes at {}. Commit them before publish-old can "
            '(re)publish -- publishing an uncommitted state would not be reproducible from the '
            'branch history.'.format(state['old_branch'], location))

    gradle_properties = os.path.join(location, 'gradle.properties')
    previous_version = state.get('published_version')

    if previous_version:
        # Republishing the same bits under a fix, or forced via --force: overwrite maven-local
        # under the exact version already recorded, rather than bumping to a new one. This keeps
        # tests.mixedModeVersion and branch 2's already-rewritten !current_version literal valid
        # without a cascading redo every time branch 1 gets another fix.
        set_version(gradle_properties, previous_version)
        new_version = previous_version
    else:
        run_versionutils_increment(gradle_properties, state['update_type'])
        new_version = get_version(gradle_properties)

    warn_if_tag_exists(main_root, new_version)

    print('Publishing {} to maven local as version {} ...'.format(state['old_branch'], new_version))
    run_gradle(location, '-PpublishBuild=true', '-PreleaseBuild=true', 'publishToMavenLocal')

    if state['old_mode'] == 'inline':
        run(['git', 'checkout', '--', 'gradle.properties'], cwd=location)

    state['published_version'] = new_version
    state['published_from_sha'] = old_sha
    save_state(main_root, state)
    print('Published {}. Run prepare-new to pick it up on branch 2.'.format(new_version))


def cmd_prepare_new(args: argparse.Namespace, main_root: str) -> None:
    state = load_state(main_root)
    state.require_ready_to_prepare()

    location = resolve_location(main_root, state, 'new')
    old_sha = git_rev_parse(main_root, state['old_branch'])
    new_sha = git_rev_parse(location, state['new_branch'])

    if not branch_contains_ancestor(main_root, old_sha, state['new_branch']):
        merge_strategy = state.get('merge_strategy', 'enforce')
        if merge_strategy == 'enforce':
            raise RuntimeError(
                "Branch 2 ('{}') does not yet contain branch 1's current tip ({}) -- merge or "
                "rebase branch 1's latest change into it before mixed-mode validation can be "
                'trusted (a stale branch 2 could pass tests against code branch 1 no longer '
                'has). By default this tool only enforces that precondition; re-run `setup` '
                'with --merge-strategy auto-merge or auto-rebase to let it do this for you '
                'instead.'.format(state['new_branch'], old_sha[:8]))
        git_subcommand = 'rebase' if merge_strategy == 'auto-rebase' else 'merge'
        print("Branch 2 does not yet contain branch 1's tip; running `git {} {}` "
              '(--merge-strategy={}) ...'.format(git_subcommand, old_sha[:8], merge_strategy))
        run(['git', git_subcommand, old_sha], cwd=location)
        new_sha = git_rev_parse(location, state['new_branch'])

    gradle_properties = os.path.join(location, 'gradle.properties')
    target_version = state['published_version']
    action = decide_prepare_action(state, target_version, new_sha)

    if action == 'noop':
        print('prepare-new: already up to date (version {}, sha {}).'.format(target_version, new_sha[:8]))
        return

    if action == 'prepare':
        patch_path = state.get('rewrite_patch')
        if patch_path and os.path.exists(patch_path):
            # A previous rewrite (under a different version) is still applied; undo it first.
            # The saved patch was captured from staged content (git diff --cached), so undo it
            # with --index too -- a plain `git apply -R` would only revert the working tree,
            # leaving the index still holding the old staged content until the next
            # `updateYamsql` run happens to re-stage every touched file.
            check = run(['git', 'apply', '-R', '--index', '--check', patch_path],
                        cwd=location, capture_output=False, check=False)
            if check.returncode != 0:
                raise RuntimeError(
                    'Cannot cleanly undo the previous !current_version rewrite (the patch no '
                    'longer applies in reverse) — a touched file has likely been edited since. '
                    "Manually restore `!current_version` in place of the literal version string "
                    "'{}', then re-run prepare-new. Saved patch: {}"
                    .format(state.get('new_bumped_to'), patch_path))
            run(['git', 'apply', '-R', '--index', patch_path], cwd=location)

        while parse_version_tuple(get_version(gradle_properties)) < parse_version_tuple(target_version):
            run_versionutils_increment(gradle_properties, state['update_type'])
    # action == 'rewrite_only': sha moved but version target is unchanged; nothing to bump.

    actual_version = get_version(gradle_properties)
    if actual_version != target_version:
        raise RuntimeError(
            "After bumping, branch 2's version ({}) does not match branch 1's published "
            'version ({}). This usually means gradle.properties diverged between the two '
            'branches independent of this tool. Resolve manually.'.format(actual_version, target_version))

    print('Running ./gradlew updateYamsql for version {} ...'.format(target_version))
    run_gradle(location, 'updateYamsql', '-PreleaseBuild=true')

    patch_path = capture_yamsql_patch(location, main_root)
    state['new_bumped_to'] = target_version
    state['new_prepared_from_sha'] = new_sha
    state['rewrite_patch'] = patch_path
    save_state(main_root, state)
    print('Branch 2 prepared for version {}.'.format(target_version))


def cmd_test(args: argparse.Namespace, main_root: str) -> None:
    state = load_state(main_root)
    state.require_ready_for_test()

    location = resolve_location(main_root, state, 'new')

    # Re-derive branch 2's current sha rather than trusting new_bumped_to alone: that only
    # catches branch 1 republishing under a new version, not branch 2 itself having moved
    # (edit or rebase) since the last prepare-new -- decide_prepare_action's 'rewrite_only'
    # case exists exactly for that, and test should refuse the same way prepare-new would.
    new_sha = git_rev_parse(location, state['new_branch'])
    action = decide_prepare_action(state, state['published_version'], new_sha)
    if action != 'noop':
        raise RuntimeError(
            'Branch 2 is not prepared for the current published version/sha. Run prepare-new '
            '(again, if branch 1 was republished or branch 2 was edited/rebased since the last '
            'prepare-new).')

    print('Running ./gradlew :yaml-tests:{} against published version {} ...'.format(args.task, state['published_version']))
    gradle_args = [':yaml-tests:' + args.task, '-PmavenLocalEnabled=true',
                    '-Ptests.mixedModeVersion=' + state['published_version']]
    if args.tests:
        gradle_args += ['--tests', args.tests]
    run_gradle(location, *gradle_args)


def cmd_status(args: argparse.Namespace, main_root: str) -> None:
    state = load_state(main_root)
    if not state.is_configured():
        print('No session configured. Run `setup` first.')
        return
    print(render_status(state))
    for which in ('old', 'new'):
        branch = state.get(which + '_branch')
        if branch and branch_exists(main_root, branch):
            print('{}: {} @ {}'.format(which, branch, git_rev_parse(main_root, branch)[:8]))


def cmd_teardown(args: argparse.Namespace, main_root: str) -> None:
    state = load_state(main_root)
    if not state.is_configured():
        print('Nothing to tear down.')
        return

    for which in ('old', 'new'):
        mode = state.get(which + '_mode')
        if mode == 'worktree':
            path = worktree_dir(main_root, which)
            if os.path.isdir(path):
                if git_is_dirty(path) and not args.force:
                    raise RuntimeError(
                        "Worktree for '{}' at {} has uncommitted changes. Commit or discard them "
                        'yourself, or pass --force to teardown to discard them and remove the '
                        'worktree anyway.'.format(which, path))
                result = run(['git', 'worktree', 'remove', '-f', path], cwd=main_root,
                             capture_output=False, check=False)
                if result.returncode != 0:
                    raise RuntimeError(
                        "Failed to remove worktree for '{}' at {} (exit code {}). It is still on "
                        'disk; resolve manually and re-run teardown.'.format(which, path, result.returncode))
        elif mode == 'inline' and which == 'new' and state.get('new_stashed'):
            print("NOTE: branch '{}' still has a parked stash (tagged with this session's id "
                  "'{}') from an inline branch switch. Left in place — run `git stash list` / "
                  '`git stash pop` yourself if you want it back.'.format(
                      state['new_branch'], state.get('new_stash_tag')))

    prune_result = run(['git', 'worktree', 'prune'], cwd=main_root, capture_output=False, check=False)
    if prune_result.returncode != 0:
        print('WARNING: `git worktree prune` failed (exit code {}); stale worktree metadata may '
              'remain. Check `git worktree list`.'.format(prune_result.returncode))

    version = state.get('published_version')
    if version:
        if args.keep_maven_local:
            print('Leaving maven-local artifacts for {} in place '
                  '(~/.m2/repository/org/foundationdb/*/{}) per --keep-maven-local.'.format(version, version))
        else:
            remove_maven_local_artifacts(version)

    scratch = scratch_dir(main_root)
    if os.path.isdir(scratch):
        shutil.rmtree(scratch)
    print('Teardown complete.')


# ---------------------------------------------------------------------------
# CLI wiring
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description='Validate a feature split across two stacked branches by publishing the '
                     'first to maven local and running the second yaml-tests against it.')
    sub = parser.add_subparsers(dest='command', required=True)

    p_setup = sub.add_parser('setup', help='Configure the two branches and their checkout modes')
    p_setup.add_argument('--old-branch', required=True, help='Branch that will be published as the external server')
    p_setup.add_argument('--new-branch', required=True, help='Branch whose yaml-tests will run against it')
    p_setup.add_argument('--old-mode', choices=['inline', 'worktree'], default='inline')
    p_setup.add_argument('--new-mode', choices=['inline', 'worktree'], default='inline')
    p_setup.add_argument('--update-type', choices=UPDATE_TYPES, default='BUILD',
                          help='Version component to bump (passed through to versionutils.py)')
    p_setup.add_argument('--merge-strategy', choices=MERGE_STRATEGIES, default='enforce',
                          help="How prepare-new reacts when branch 2 doesn't yet contain branch "
                               "1's current tip: 'enforce' (default) only refuses and asks the "
                               "developer to merge/rebase manually; 'auto-merge'/'auto-rebase' "
                               'let this tool do it for you.')
    p_setup.add_argument('--reconfigure', action='store_true',
                          help='Allow replacing an existing session with different branches')
    p_setup.set_defaults(func=cmd_setup)

    p_publish = sub.add_parser('publish-old', help="Bump branch 1's version and publish it to maven local")
    p_publish.add_argument('--force', action='store_true', help='Republish even if branch 1 has not changed')
    p_publish.set_defaults(func=cmd_publish_old)

    p_prepare = sub.add_parser(
        'prepare-new',
        help="Match branch 2's version to branch 1's published version and rewrite !current_version markers")
    p_prepare.set_defaults(func=cmd_prepare_new)

    p_test = sub.add_parser('test', help='Run the yaml-tests round trip against the published server')
    p_test.add_argument('--task', choices=['mixedModeTest', 'test'], default='mixedModeTest')
    p_test.add_argument('--tests', help="Restrict to a single test (gradle's --tests filter), "
                                         "e.g. 'YamlIntegrationTests.selectAStar'")
    p_test.set_defaults(func=cmd_test)

    p_status = sub.add_parser('status', help='Print the current session state')
    p_status.set_defaults(func=cmd_status)

    p_teardown = sub.add_parser(
        'teardown', help='Remove worktrees and (by default) delete the published maven-local artifact')
    p_teardown.add_argument('--keep-maven-local', action='store_true',
                             help='Do not delete the published maven-local artifact')
    p_teardown.add_argument('--force', action='store_true',
                             help='Remove worktrees even if they have uncommitted changes, discarding them')
    p_teardown.set_defaults(func=cmd_teardown)

    return parser


def main(argv: Sequence[str]) -> None:
    parser = build_parser()
    args = parser.parse_args(argv)
    main_root = find_main_repo_root()
    args.func(args, main_root)


if __name__ == '__main__':
    main(sys.argv[1:])
