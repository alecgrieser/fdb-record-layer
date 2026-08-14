#!/usr/bin/env python3

#
# mixed_mode_two_branch.py
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
    test_mixed_mode_two_branch.py.
  - Thin, mostly-linear orchestration functions (cmd_*) that call git/gradle and are exercised
    by integration-style tests against real temporary git repositories.

Typical usage:
    python dev-tools/mixed_mode_two_branch.py setup --old-branch feature/wire-format-part1 \\
                                                      --new-branch feature/wire-format-part2
    python dev-tools/mixed_mode_two_branch.py publish-old
    python dev-tools/mixed_mode_two_branch.py prepare-new
    python dev-tools/mixed_mode_two_branch.py test
    # ... iterate: edit either branch, re-run publish-old/prepare-new/test as needed ...
    python dev-tools/mixed_mode_two_branch.py teardown

See .claude/skills/mixed-mode-two-branch/SKILL.md for the higher-level workflow this supports.
"""

import argparse
import glob
import json
import os
import shutil
import subprocess
import sys

# versionutils.py is a CI-invoked script that stays in build/; this tool is not CI-invoked and
# lives alongside its own test in dev-tools/, so the two are no longer siblings on disk.
VERSIONUTILS = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'build', 'versionutils.py')
STASH_MESSAGE = 'mixed-mode-two-branch: new-rewrite'
UPDATE_TYPES = ['MAJOR', 'MINOR', 'BUILD', 'PATCH']


class UserError(Exception):
    """Raised for problems that should be reported to the user and stop the command, without a
    Python traceback (e.g. bad arguments, unmet preconditions, git conflicts)."""


# ---------------------------------------------------------------------------
# Pure functions: no subprocess calls, no filesystem writes. Unit testable directly.
# ---------------------------------------------------------------------------

def parse_worktree_list(output):
    """Parse `git worktree list --porcelain` output into {real_path: branch_name}.

    Keys are resolved with os.path.realpath, not just os.path.abspath: git itself reports fully
    symlink-resolved paths here (e.g. /private/var/... rather than /var/... on macOS), so callers
    must resolve symlinks the same way before looking anything up in this dict.
    """
    entries = {}
    path = None
    for line in output.splitlines():
        if line.startswith('worktree '):
            path = line[len('worktree '):]
        elif line.startswith('branch ') and path:
            branch_ref = line[len('branch '):]
            entries[os.path.realpath(path)] = branch_ref.replace('refs/heads/', '')
            path = None
    return entries


def find_stash_ref(stash_list_output, message_substring):
    """Given `git stash list` output, return the stash ref (e.g. 'stash@{0}') whose message
    contains message_substring, or None if not found. If more than one matches, the most
    recent (first-listed) one is returned."""
    for line in stash_list_output.splitlines():
        if message_substring in line:
            return line.split(':')[0]
    return None


def require_keys(state, keys, hint=''):
    """Raise UserError if any of `keys` are missing/falsy in `state`."""
    missing = [k for k in keys if not state.get(k)]
    if missing:
        raise UserError(('Missing required state: ' + ', '.join(missing) + '. ' + hint).strip())


def worktree_dir(main_root, which):
    return os.path.join(main_root, '.worktrees', 'mixed-mode', which)


def scratch_dir(main_root):
    """Directory the tool uses for its own untracked state (session state, parked rewrite
    patch). Kept as a single directory so it's easy to .gitignore in one line."""
    return os.path.join(main_root, '.mixed-mode-two-branch')


def state_path(main_root):
    return os.path.join(scratch_dir(main_root), 'state.json')


def rewrite_patch_path(main_root):
    return os.path.join(scratch_dir(main_root), 'new-rewrite.patch')


def maven_local_glob(version):
    return os.path.expanduser('~/.m2/repository/org/foundationdb/*/' + version)


def decide_setup_conflict(existing_state, old_branch, new_branch, reconfigure):
    """Return True if `setup` should refuse because an existing session targets different
    branches and --reconfigure was not passed."""
    if not existing_state or reconfigure:
        return False
    return (existing_state.get('old_branch') != old_branch
            or existing_state.get('new_branch') != new_branch)


def decide_publish_action(state, old_sha, force):
    """Decide whether publish-old should 'publish' or 'skip', given branch 1's current HEAD sha."""
    if force:
        return 'publish'
    if state.get('published_version') and state.get('published_from_sha') == old_sha:
        return 'skip'
    return 'publish'


def decide_prepare_action(state, target_version, new_sha):
    """
    Decide what prepare-new needs to do, given branch 1's currently published version and
    branch 2's current HEAD sha. Returns one of:
      'noop'         - branch 2 is already fully prepared for target_version at this sha.
      'bump'         - branch 2 has never been bumped/prepared; bump then rewrite.
      'redo'         - branch 1 republished under a new version; undo the old rewrite, bump
                       again, then rewrite.
      'rewrite_only' - branch 2's sha changed (edit/rebase) but the target version is
                       unchanged; just redo the (idempotent) yamsql rewrite.
    """
    bumped_to = state.get('new_bumped_to')
    prepared_from_sha = state.get('new_prepared_from_sha')

    if bumped_to == target_version and prepared_from_sha == new_sha:
        return 'noop'
    if bumped_to is None:
        return 'bump'
    if bumped_to != target_version:
        return 'redo'
    return 'rewrite_only'


def render_status(state):
    lines = [json.dumps(state, indent=2, sort_keys=True)]
    return '\n'.join(lines)


def parse_version_tuple(version_string):
    return tuple(int(part) for part in version_string.split('.'))


def version_needs_another_bump(candidate_version, previous_version):
    """True if candidate_version is not strictly newer than previous_version, meaning another
    increment is needed. Used by publish-old to guarantee it never republishes under a version
    string already used for different bits, even though inline mode reverts gradle.properties
    back to its pre-bump value after each publish (so a naive single increment from the current
    file would reproduce the same version string every time)."""
    if not previous_version:
        return False
    return parse_version_tuple(candidate_version) <= parse_version_tuple(previous_version)


# ---------------------------------------------------------------------------
# Side-effecting helpers: subprocess/filesystem. Kept thin and mostly linear so that the
# decision logic above carries the real complexity.
# ---------------------------------------------------------------------------

def run(command, cwd=None):
    """Run a command, returning its stdout. Raises UserError on failure."""
    try:
        process = subprocess.run(command, cwd=cwd, check=True, capture_output=True, text=True)
        return process.stdout
    except subprocess.CalledProcessError as e:
        raise UserError('Command failed: {}\n{}\n{}'.format(e.cmd, e.stdout, e.stderr))


def run_gradle(cwd, *gradle_args):
    """Run ./gradlew with the given args, streaming output directly to the console."""
    cmd = [os.path.join(cwd, 'gradlew')] + list(gradle_args)
    result = subprocess.run(cmd, cwd=cwd)
    if result.returncode != 0:
        raise UserError('gradle command failed: {}'.format(' '.join(gradle_args)))


def find_main_repo_root(cwd=None):
    """
    Locate the main repository's root directory, regardless of whether this script is invoked
    from the main checkout or from one of the worktrees this tool manages: worktrees share a
    single "common dir" (the real .git directory), whose parent is always the main repo root.
    """
    common_dir = run(['git', 'rev-parse', '--git-common-dir'], cwd=cwd).strip()
    common_dir = os.path.abspath(os.path.join(cwd or '.', common_dir))
    return os.path.dirname(common_dir)


def load_state(main_root):
    path = state_path(main_root)
    if not os.path.exists(path):
        return {}
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        raise UserError(
            'Could not read session state at {}: {}. If this file was left corrupted by an '
            'interrupted run, delete it and re-run setup.'.format(path, e))


def save_state(main_root, state):
    # Write to a temp file and rename into place, so a crash/interruption mid-write can never
    # leave state.json truncated or otherwise invalid for the next invocation to trip over.
    os.makedirs(scratch_dir(main_root), exist_ok=True)
    path = state_path(main_root)
    tmp_path = path + '.tmp'
    with open(tmp_path, 'w') as f:
        json.dump(state, f, indent=2, sort_keys=True)
        f.write('\n')
    os.replace(tmp_path, path)


def branch_exists(cwd, branch):
    result = subprocess.run(['git', 'rev-parse', '--verify', '--quiet', 'refs/heads/' + branch],
                             cwd=cwd, capture_output=True)
    return result.returncode == 0


def git_rev_parse(cwd, ref):
    return run(['git', 'rev-parse', ref], cwd=cwd).strip()


def git_current_branch(cwd):
    return run(['git', 'branch', '--show-current'], cwd=cwd).strip()


def git_is_dirty(cwd):
    return bool(run(['git', 'status', '--porcelain'], cwd=cwd).strip())


def ensure_worktree(main_root, which, branch):
    path = worktree_dir(main_root, which)
    entries = parse_worktree_list(run(['git', 'worktree', 'list', '--porcelain'], cwd=main_root))
    existing_branch = entries.get(os.path.realpath(path))
    if existing_branch is not None:
        if existing_branch != branch:
            raise UserError(
                "Worktree at {} is already checked out to '{}', not '{}'. Remove it manually "
                '(git worktree remove) or choose a different layout.'.format(path, existing_branch, branch))
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    run(['git', 'worktree', 'add', path, branch], cwd=main_root)


def checkout_branch_inline(main_root, which, state):
    """
    Ensure `state[<which>_branch]` is checked out in the main working directory, switching if
    necessary. Refuses to switch away from a dirty tree unless the dirt is branch 2's own
    pending !current_version rewrite (tracked via `new_bumped_to`), which it parks in a tagged
    stash and restores when branch 2 is checked out again.
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
            raise UserError(
                "Refusing to switch the inline checkout from '{}' to '{}': the working tree has "
                'uncommitted changes that this tool did not create. Commit or stash them '
                'yourself, then re-run.'.format(current, target_branch))
        run(['git', 'stash', 'push', '-u', '-m', STASH_MESSAGE], cwd=main_root)
        state['new_stashed'] = True
        save_state(main_root, state)

    run(['git', 'checkout', target_branch], cwd=main_root)

    if which == 'new' and state.get('new_stashed'):
        stash_ref = find_stash_ref(run(['git', 'stash', 'list'], cwd=main_root), STASH_MESSAGE)
        if stash_ref is None:
            raise UserError(
                "Expected a parked stash for branch 2's yamsql rewrite (tagged '{}') but "
                "couldn't find one; check `git stash list` manually.".format(STASH_MESSAGE))
        result = subprocess.run(['git', 'stash', 'pop', stash_ref], cwd=main_root)
        if result.returncode != 0:
            raise UserError(
                "Restoring branch 2's parked yamsql rewrite ({}) conflicted with the current "
                'working tree. Resolve manually via `git stash list` / `git stash pop`, then '
                're-run prepare-new.'.format(stash_ref))
        state['new_stashed'] = False
        save_state(main_root, state)


def resolve_location(main_root, state, which):
    """Return the directory to operate in for `which` ('old'/'new'), checking out/switching as needed."""
    mode = state[which + '_mode']
    if mode == 'worktree':
        path = worktree_dir(main_root, which)
        if not os.path.isdir(path):
            raise UserError("No worktree found for '{}' at {}; run setup first.".format(which, path))
        return path
    checkout_branch_inline(main_root, which, state)
    return main_root


def run_versionutils_increment(gradle_properties_path, update_type):
    run([sys.executable, VERSIONUTILS, gradle_properties_path, '--increment', '-u', update_type])


def get_version(gradle_properties_path):
    return run([sys.executable, VERSIONUTILS, gradle_properties_path]).strip()


def warn_if_tag_exists(main_root, version):
    result = subprocess.run(['git', 'tag', '--list', version], cwd=main_root, capture_output=True, text=True)
    if result.stdout.strip():
        print("WARNING: a local tag already exists for '{}' — branch 1 may already have been "
              'actually released. Consider using the real released version directly instead of '
              'this local publish/bump workflow.'.format(version))


def capture_yamsql_patch(location, main_root):
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


def remove_maven_local_artifacts(version):
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

def cmd_setup(args, main_root):
    state = load_state(main_root)
    if decide_setup_conflict(state, args.old_branch, args.new_branch, args.reconfigure):
        raise UserError(
            'Existing session found for old={} new={}. Pass --reconfigure to start a new '
            'session (this does not touch already-published artifacts or worktrees; run '
            'teardown first if you want those cleaned up).'
            .format(state.get('old_branch'), state.get('new_branch')))

    branches_and_modes = [('old', args.old_branch, args.old_mode), ('new', args.new_branch, args.new_mode)]
    for which, branch, mode in branches_and_modes:
        if not branch_exists(main_root, branch):
            raise UserError("Branch '{}' does not exist.".format(branch))
        if mode == 'worktree':
            ensure_worktree(main_root, which, branch)

    branches_changed = (state.get('old_branch') != args.old_branch
                         or state.get('new_branch') != args.new_branch)
    if not state or branches_changed:
        # Either there was no prior session, or --reconfigure retargeted to different
        # branches -- either way, any recorded publish-old/prepare-new progress belonged to
        # the old branches and doesn't apply here, so start clean.
        state = {}
    # else: same branches as before (e.g. re-running setup just to tweak a mode or
    # --update-type) -- preserve published_version/new_bumped_to/etc. instead of wiping
    # them, so setup really is safe to re-run without losing progress.

    state['old_branch'] = args.old_branch
    state['new_branch'] = args.new_branch
    state['old_mode'] = args.old_mode
    state['new_mode'] = args.new_mode
    state['update_type'] = args.update_type
    save_state(main_root, state)
    print('Configured: old={} ({}), new={} ({})'.format(
        args.old_branch, args.old_mode, args.new_branch, args.new_mode))


def cmd_publish_old(args, main_root):
    state = load_state(main_root)
    require_keys(state, ['old_branch', 'update_type'], 'Run setup first.')

    old_sha = git_rev_parse(main_root, state['old_branch'])
    action = decide_publish_action(state, old_sha, args.force)
    if action == 'skip':
        print('publish-old: {} unchanged since last publish ({} @ {}); skipping. Use --force to '
              'republish anyway.'.format(state['old_branch'], state['published_version'], old_sha[:8]))
        return

    location = resolve_location(main_root, state, 'old')
    gradle_properties = os.path.join(location, 'gradle.properties')

    # Inline mode reverts gradle.properties back to its pre-bump value immediately after each
    # publish (see below), so a straightforward single increment from the current file would
    # reproduce the exact same version string on every republish -- silently reusing a version
    # already published for different bits. Guard against that by bumping until the result is
    # strictly newer than whatever was published last (a no-op loop in the common case where
    # nothing reverted the file out from under us).
    previous_version = state.get('published_version')
    run_versionutils_increment(gradle_properties, state['update_type'])
    new_version = get_version(gradle_properties)
    while version_needs_another_bump(new_version, previous_version):
        run_versionutils_increment(gradle_properties, state['update_type'])
        new_version = get_version(gradle_properties)

    # Check against the version that will actually be published, not whatever was in
    # gradle.properties before the bump loop above -- a stale pre-bump check would never
    # actually catch a collision with a real release tag.
    warn_if_tag_exists(main_root, new_version)

    print('Publishing {} to maven local as version {} ...'.format(state['old_branch'], new_version))
    run_gradle(location, '-PpublishBuild=true', '-PreleaseBuild=true', 'publishToMavenLocal')

    if state['old_mode'] == 'inline':
        run(['git', 'checkout', '--', 'gradle.properties'], cwd=location)

    state['published_version'] = new_version
    state['published_from_sha'] = old_sha
    save_state(main_root, state)
    print('Published {}. Run prepare-new to pick it up on branch 2.'.format(new_version))


def cmd_prepare_new(args, main_root):
    state = load_state(main_root)
    require_keys(state, ['new_branch', 'published_version', 'update_type'],
                 'Run setup and publish-old first.')

    location = resolve_location(main_root, state, 'new')
    new_sha = git_rev_parse(location, state['new_branch'])
    gradle_properties = os.path.join(location, 'gradle.properties')

    target_version = state['published_version']
    action = decide_prepare_action(state, target_version, new_sha)

    if action == 'noop':
        print('prepare-new: already up to date (version {}, sha {}).'.format(target_version, new_sha[:8]))
        return

    if action == 'redo':
        patch_path = state.get('rewrite_patch')
        if patch_path and os.path.exists(patch_path):
            # The saved patch was captured from staged content (git diff --cached), so undo it
            # with --index too -- a plain `git apply -R` would only revert the working tree,
            # leaving the index still holding the old staged content until the next
            # `updateYamsql` run happens to re-stage every touched file.
            check = subprocess.run(['git', 'apply', '-R', '--index', '--check', patch_path], cwd=location)
            if check.returncode != 0:
                raise UserError(
                    'Cannot cleanly undo the previous !current_version rewrite (the patch no '
                    'longer applies in reverse) — a touched file has likely been edited since. '
                    "Manually restore `!current_version` in place of the literal version string "
                    "'{}', then re-run prepare-new. Saved patch: {}"
                    .format(state.get('new_bumped_to'), patch_path))
            run(['git', 'apply', '-R', '--index', patch_path], cwd=location)

    if action in ('redo', 'bump'):
        # publish-old may have needed more than one increment to skip past an already-used
        # version (see version_needs_another_bump), so keep bumping branch 2 the same way,
        # rather than assuming a single increment lands on the target -- both branches started
        # from the same base and use the same update_type, so matching increment-for-increment
        # is guaranteed to converge, but only if we don't stop after just one.
        while parse_version_tuple(get_version(gradle_properties)) < parse_version_tuple(target_version):
            run_versionutils_increment(gradle_properties, state['update_type'])
    # action == 'rewrite_only': sha moved but version target is unchanged; nothing to bump.

    actual_version = get_version(gradle_properties)
    if actual_version != target_version:
        raise UserError(
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


def cmd_test(args, main_root):
    state = load_state(main_root)
    require_keys(state, ['new_branch', 'published_version'], 'Run publish-old and prepare-new first.')

    location = resolve_location(main_root, state, 'new')

    # Re-derive branch 2's current sha rather than trusting new_bumped_to alone: that only
    # catches branch 1 republishing under a new version, not branch 2 itself having moved
    # (edit or rebase) since the last prepare-new -- decide_prepare_action's 'rewrite_only'
    # case exists exactly for that, and test should refuse the same way prepare-new would.
    new_sha = git_rev_parse(location, state['new_branch'])
    action = decide_prepare_action(state, state['published_version'], new_sha)
    if action != 'noop':
        raise UserError(
            'Branch 2 is not prepared for the current published version/sha. Run prepare-new '
            '(again, if branch 1 was republished or branch 2 was edited/rebased since the last '
            'prepare-new).')

    print('Running ./gradlew :yaml-tests:{} against published version {} ...'.format(args.task, state['published_version']))
    gradle_args = [':yaml-tests:' + args.task, '-PmavenLocalEnabled=true',
                    '-Ptests.mixedModeVersion=' + state['published_version']]
    if args.tests:
        gradle_args += ['--tests', args.tests]
    run_gradle(location, *gradle_args)


def cmd_status(args, main_root):
    state = load_state(main_root)
    if not state:
        print('No session configured. Run `setup` first.')
        return
    print(render_status(state))
    for which in ('old', 'new'):
        branch = state.get(which + '_branch')
        if branch and branch_exists(main_root, branch):
            print('{}: {} @ {}'.format(which, branch, git_rev_parse(main_root, branch)[:8]))


def cmd_teardown(args, main_root):
    state = load_state(main_root)
    if not state:
        print('Nothing to tear down.')
        return

    for which in ('old', 'new'):
        mode = state.get(which + '_mode')
        if mode == 'worktree':
            path = worktree_dir(main_root, which)
            if os.path.isdir(path):
                if git_is_dirty(path) and not args.force:
                    raise UserError(
                        "Worktree for '{}' at {} has uncommitted changes. Commit or discard them "
                        'yourself, or pass --force to teardown to discard them and remove the '
                        'worktree anyway.'.format(which, path))
                result = subprocess.run(['git', 'worktree', 'remove', '-f', path], cwd=main_root, check=False)
                if result.returncode != 0:
                    raise UserError(
                        "Failed to remove worktree for '{}' at {} (exit code {}). It is still on "
                        'disk; resolve manually and re-run teardown.'.format(which, path, result.returncode))
        elif mode == 'inline' and which == 'new' and state.get('new_stashed'):
            print("NOTE: branch '{}' still has a parked stash (tagged '{}') from an inline branch "
                  'switch. Left in place — run `git stash list` / `git stash pop` yourself if you '
                  'want it back.'.format(state['new_branch'], STASH_MESSAGE))

    prune_result = subprocess.run(['git', 'worktree', 'prune'], cwd=main_root, check=False)
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

def build_parser():
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


def main(argv):
    parser = build_parser()
    args = parser.parse_args(argv)
    main_root = find_main_repo_root()
    try:
        args.func(args, main_root)
    except UserError as e:
        print('ERROR: ' + str(e), file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main(sys.argv[1:])
