#!/usr/bin/env python3

#
# test_mixed_mode_two_branches.py
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

"""Unit tests for mixed_mode_two_branches.py"""

import argparse
import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.dirname(__file__))
from mixed_mode_two_branches import (
    State,
    branch_contains_ancestor,
    branch_exists,
    capture_yamsql_patch,
    checkout_branch_inline,
    cmd_prepare_new,
    cmd_publish_old,
    cmd_setup,
    cmd_status,
    cmd_teardown,
    cmd_test,
    decide_prepare_action,
    decide_publish_action,
    decide_setup_conflict,
    ensure_worktree,
    find_main_repo_root,
    find_stash_ref,
    get_version,
    git_current_branch,
    git_is_dirty,
    git_rev_parse,
    load_state,
    maven_local_glob,
    parse_version_tuple,
    parse_worktree_list,
    remove_maven_local_artifacts,
    render_status,
    require_keys,
    resolve_location,
    rewrite_patch_path,
    save_state,
    scratch_dir,
    set_version,
    state_path,
    worktree_dir,
)


def run_git(cwd, *args):
    """Run a git command in cwd, raising on failure. Used only by test setup/assertions."""
    return subprocess.run(['git'] + list(args), cwd=cwd, check=True, capture_output=True, text=True).stdout


def init_repo(path):
    """Initialize a git repo at path with one commit on 'main', config'd for commits to work
    in isolation from the developer's own global git config (no signing, fixed identity local
    to this throwaway repo only). Also gitignores the tool's own scratch dir and worktrees dir,
    mirroring the real repo's .gitignore, so git_is_dirty() checks behave the same way here as
    they do for real."""
    run_git(path, 'init', '-q', '-b', 'main')
    run_git(path, 'config', 'user.email', 'mixed-mode-two-branches-test@example.invalid')
    run_git(path, 'config', 'user.name', 'mixed-mode-two-branches tests')
    run_git(path, 'config', 'commit.gpgsign', 'false')
    run_git(path, 'config', 'tag.gpgsign', 'false')
    with open(os.path.join(path, 'gradle.properties'), 'w') as f:
        f.write('version=1.0.0.0\n')
    with open(os.path.join(path, '.gitignore'), 'w') as f:
        f.write('/.mixed-mode-two-branches/\n/.worktrees/\n')
    run_git(path, 'add', 'gradle.properties', '.gitignore')
    run_git(path, 'commit', '-q', '-m', 'initial commit')


def make_branch(path, name):
    run_git(path, 'branch', name)


def advance_branch(repo, branch, filename='advance.txt'):
    """Add a commit to `branch`'s tip, to simulate a fix landing on it. If `branch` is already
    the active checkout in the main working directory, commits there directly; otherwise uses a
    throwaway linked worktree so as not to disturb whatever else is checked out or dirty in
    `repo` right now (git refuses to check out a branch that's already checked out elsewhere)."""
    if git_current_branch(repo) == branch:
        with open(os.path.join(repo, filename), 'w') as f:
            f.write('advance\n')
        run_git(repo, 'add', filename)
        run_git(repo, 'commit', '-q', '-m', 'advance ' + branch)
        return
    with tempfile.TemporaryDirectory() as tmp:
        run_git(repo, 'worktree', 'add', '-q', tmp, branch)
        with open(os.path.join(tmp, filename), 'w') as f:
            f.write('advance\n')
        run_git(tmp, 'add', filename)
        run_git(tmp, 'commit', '-q', '-m', 'advance ' + branch)
        run_git(repo, 'worktree', 'remove', tmp)


def fake_update_yamsql(cwd, *gradle_args):
    """Stand-in for the real `./gradlew updateYamsql` invocation, used as run_gradle()'s mocked
    side effect in cmd_prepare_new tests: rewrites `!current_version` markers in *.yamsql files
    to whatever version is currently in gradle.properties and stages them, mirroring what the
    real gradle task does, without requiring an actual gradle build in these tests."""
    if not gradle_args or gradle_args[0] != 'updateYamsql':
        return
    version = get_version(os.path.join(cwd, 'gradle.properties'))
    changed = []
    for name in os.listdir(cwd):
        if name.endswith('.yamsql'):
            path = os.path.join(cwd, name)
            with open(path) as f:
                content = f.read()
            if '!current_version' in content:
                with open(path, 'w') as f:
                    f.write(content.replace('!current_version', version))
                changed.append(name)
    if changed:
        run_git(cwd, 'add', *changed)


# ---------------------------------------------------------------------------
# Pure function tests: no subprocess calls, no filesystem.
# ---------------------------------------------------------------------------

class TestParseWorktreeList(unittest.TestCase):
    """Tests for parse_worktree_list()"""

    def test_single_worktree(self):
        output = 'worktree /repo\nHEAD abcdef\nbranch refs/heads/main\n'
        self.assertEqual(parse_worktree_list(output), {os.path.abspath('/repo'): 'main'})

    def test_multiple_worktrees(self):
        output = (
            'worktree /repo\nHEAD abc\nbranch refs/heads/main\n\n'
            'worktree /repo/.worktrees/mixed-mode/old\nHEAD def\nbranch refs/heads/feature-old\n\n'
            'worktree /repo/.worktrees/mixed-mode/new\nHEAD ghi\nbranch refs/heads/feature-new\n'
        )
        result = parse_worktree_list(output)
        self.assertEqual(result[os.path.abspath('/repo')], 'main')
        self.assertEqual(result[os.path.abspath('/repo/.worktrees/mixed-mode/old')], 'feature-old')
        self.assertEqual(result[os.path.abspath('/repo/.worktrees/mixed-mode/new')], 'feature-new')

    def test_detached_worktree_has_no_branch_entry(self):
        # A detached-HEAD worktree emits no 'branch' line at all, only 'detached'.
        output = 'worktree /repo/detached\nHEAD abc\ndetached\n'
        self.assertEqual(parse_worktree_list(output), {})

    def test_empty_output(self):
        self.assertEqual(parse_worktree_list(''), {})


class TestFindStashRef(unittest.TestCase):
    """Tests for find_stash_ref()

    find_stash_ref matches on a per-run UUID tag, not a shared substring, so a stale/unrelated
    stash from an earlier or interrupted session can never be mistaken for the one being looked
    for."""

    def test_finds_matching_stash(self):
        output = (
            'stash@{0}: On feature: mixed-mode-two-branches: new-rewrite deadbeef-0000\n'
            'stash@{1}: On main: some other stash\n'
        )
        self.assertEqual(find_stash_ref(output, 'deadbeef-0000'), 'stash@{0}')

    def test_no_match_returns_none(self):
        output = 'stash@{0}: On main: unrelated stash\n'
        self.assertIsNone(find_stash_ref(output, 'deadbeef-0000'))

    def test_does_not_match_a_different_tag_with_the_same_prefix_message(self):
        # Two different runs could both use the shared STASH_MESSAGE prefix; only the exact tag
        # (UUID) must match, not the shared text.
        output = 'stash@{0}: On feature: mixed-mode-two-branches: new-rewrite other-tag-1111\n'
        self.assertIsNone(find_stash_ref(output, 'deadbeef-0000'))

    def test_empty_list_returns_none(self):
        self.assertIsNone(find_stash_ref('', 'anything'))

    def test_returns_most_recent_match_first(self):
        output = (
            'stash@{0}: On feature: mixed-mode-two-branches: new-rewrite deadbeef-0000\n'
            'stash@{1}: On feature: mixed-mode-two-branches: new-rewrite deadbeef-0000\n'
        )
        self.assertEqual(find_stash_ref(output, 'deadbeef-0000'), 'stash@{0}')


class TestRequireKeys(unittest.TestCase):
    """Tests for require_keys()"""

    def test_all_present_does_not_raise(self):
        require_keys({'a': 1, 'b': 2}, ['a', 'b'])

    def test_missing_key_raises(self):
        with self.assertRaises(RuntimeError):
            require_keys({'a': 1}, ['a', 'b'])

    def test_falsy_value_counts_as_missing(self):
        with self.assertRaises(RuntimeError):
            require_keys({'a': None}, ['a'])

    def test_hint_included_in_message(self):
        with self.assertRaises(RuntimeError) as ctx:
            require_keys({}, ['old_branch'], 'Run setup first.')
        self.assertIn('Run setup first.', str(ctx.exception))
        self.assertIn('old_branch', str(ctx.exception))


class TestState(unittest.TestCase):
    """Tests for State's named precondition checks"""

    def test_require_ready_to_publish_raises_when_missing(self):
        with self.assertRaises(RuntimeError):
            State().require_ready_to_publish()

    def test_require_ready_to_publish_passes_when_present(self):
        State({'old_branch': 'a', 'update_type': 'BUILD'}).require_ready_to_publish()

    def test_require_ready_to_prepare_raises_when_missing(self):
        with self.assertRaises(RuntimeError):
            State().require_ready_to_prepare()

    def test_require_ready_to_prepare_passes_when_present(self):
        State({'old_branch': 'a', 'new_branch': 'b', 'published_version': '1.0.1.0',
               'update_type': 'BUILD'}).require_ready_to_prepare()

    def test_require_ready_for_test_raises_when_missing(self):
        with self.assertRaises(RuntimeError):
            State().require_ready_for_test()

    def test_require_ready_for_test_passes_when_present(self):
        State({'new_branch': 'b', 'published_version': '1.0.1.0'}).require_ready_for_test()

    def test_load_missing_file_returns_defaults(self):
        with tempfile.TemporaryDirectory() as tmp:
            state = State.load(tmp)
            self.assertEqual(state.get('merge_strategy'), 'enforce')

    def test_load_fills_in_missing_default_keys(self):
        # Simulates a state.json saved by an older version of this tool, before merge_strategy
        # existed -- load() should fill the default in rather than leaving it entirely absent.
        with tempfile.TemporaryDirectory() as tmp:
            os.makedirs(scratch_dir(tmp))
            with open(state_path(tmp), 'w') as f:
                json.dump({'old_branch': 'a'}, f)
            state = State.load(tmp)
            self.assertEqual(state['old_branch'], 'a')
            self.assertEqual(state['merge_strategy'], 'enforce')

    def test_load_preserves_explicit_value_over_default(self):
        with tempfile.TemporaryDirectory() as tmp:
            State({'merge_strategy': 'auto-merge'}).save(tmp)
            self.assertEqual(State.load(tmp)['merge_strategy'], 'auto-merge')


class TestPathHelpers(unittest.TestCase):
    """Tests for the small path-computing helpers"""

    def test_worktree_dir(self):
        self.assertEqual(worktree_dir('/repo', 'old'), '/repo/.worktrees/mixed-mode/old')
        self.assertEqual(worktree_dir('/repo', 'new'), '/repo/.worktrees/mixed-mode/new')

    def test_scratch_dir(self):
        self.assertEqual(scratch_dir('/repo'), '/repo/.mixed-mode-two-branches')

    def test_state_path(self):
        self.assertEqual(state_path('/repo'), '/repo/.mixed-mode-two-branches/state.json')

    def test_rewrite_patch_path(self):
        self.assertEqual(rewrite_patch_path('/repo'), '/repo/.mixed-mode-two-branches/new-rewrite.patch')

    def test_maven_local_glob(self):
        glob_pattern = maven_local_glob('4.12.19.0')
        self.assertTrue(glob_pattern.endswith('/org/foundationdb/*/4.12.19.0'))
        self.assertNotIn('~', glob_pattern)


class TestDecideSetupConflict(unittest.TestCase):
    """Tests for decide_setup_conflict()"""

    def test_no_existing_state_never_conflicts(self):
        self.assertFalse(decide_setup_conflict({}, 'old', 'new', False))

    def test_reconfigure_always_allowed(self):
        state = {'old_branch': 'a', 'new_branch': 'b'}
        self.assertFalse(decide_setup_conflict(state, 'x', 'y', True))

    def test_same_branches_no_conflict(self):
        state = {'old_branch': 'a', 'new_branch': 'b'}
        self.assertFalse(decide_setup_conflict(state, 'a', 'b', False))

    def test_different_old_branch_conflicts(self):
        state = {'old_branch': 'a', 'new_branch': 'b'}
        self.assertTrue(decide_setup_conflict(state, 'x', 'b', False))

    def test_different_new_branch_conflicts(self):
        state = {'old_branch': 'a', 'new_branch': 'b'}
        self.assertTrue(decide_setup_conflict(state, 'a', 'y', False))


class TestDecidePublishAction(unittest.TestCase):
    """Tests for decide_publish_action(): True means publish, False means skip."""

    def test_never_published_publishes(self):
        self.assertTrue(decide_publish_action({}, 'sha1', False))

    def test_force_always_publishes(self):
        state = {'published_version': '1.0.0.0', 'published_from_sha': 'sha1'}
        self.assertTrue(decide_publish_action(state, 'sha1', True))

    def test_unchanged_head_skips(self):
        state = {'published_version': '1.0.0.0', 'published_from_sha': 'sha1'}
        self.assertFalse(decide_publish_action(state, 'sha1', False))

    def test_moved_head_republishes(self):
        state = {'published_version': '1.0.0.0', 'published_from_sha': 'sha1'}
        self.assertTrue(decide_publish_action(state, 'sha2', False))


class TestDecidePrepareAction(unittest.TestCase):
    """Tests for decide_prepare_action()

    Branch 1 always republishes under the same version once one has been chosen (see
    cmd_publish_old), so these no longer exercise a version-changed 'redo' path -- only whether
    branch 2 has ever been bumped to the (single, stable) target version, and whether its sha
    has moved since."""

    def test_never_prepared_is_prepare(self):
        self.assertEqual(decide_prepare_action({}, '1.0.1.0', 'sha1'), 'prepare')

    def test_up_to_date_is_noop(self):
        state = {'new_bumped_to': '1.0.1.0', 'new_prepared_from_sha': 'sha1'}
        self.assertEqual(decide_prepare_action(state, '1.0.1.0', 'sha1'), 'noop')

    def test_sha_moved_same_target_is_rewrite_only(self):
        state = {'new_bumped_to': '1.0.1.0', 'new_prepared_from_sha': 'sha1'}
        self.assertEqual(decide_prepare_action(state, '1.0.1.0', 'sha2'), 'rewrite_only')

    def test_bumped_to_different_version_is_prepare(self):
        # Defensive case: state.json bumped to a version that no longer matches the target
        # (e.g. hand-edited, or carried over from a different session).
        state = {'new_bumped_to': '1.0.0.0', 'new_prepared_from_sha': 'sha1'}
        self.assertEqual(decide_prepare_action(state, '1.0.1.0', 'sha1'), 'prepare')


class TestParseVersionTuple(unittest.TestCase):
    """Tests for parse_version_tuple()"""

    def test_parses_four_part_version(self):
        self.assertEqual(parse_version_tuple('4.12.17.0'), (4, 12, 17, 0))

    def test_compares_numerically_not_lexicographically(self):
        self.assertLess(parse_version_tuple('4.2.0.0'), parse_version_tuple('4.10.0.0'))


class TestRenderStatus(unittest.TestCase):
    """Tests for render_status()"""

    def test_renders_valid_json_containing_state(self):
        state = {'old_branch': 'feature-old', 'published_version': '1.0.1.0'}
        rendered = render_status(state)
        self.assertEqual(json.loads(rendered), state)

    def test_renders_without_wrapping_join(self):
        # render_status should be exactly json.dumps(..., indent=2, sort_keys=True), with no
        # extra join/wrapping layer around it.
        state = {'b': 1, 'a': 2}
        self.assertEqual(render_status(state), json.dumps(state, indent=2, sort_keys=True))


# ---------------------------------------------------------------------------
# State file I/O tests: real filesystem, no git.
# ---------------------------------------------------------------------------

class TestLoadSaveState(unittest.TestCase):
    """Tests for load_state()/save_state()"""

    def setUp(self):
        self.tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmpdir.cleanup)

    def test_load_missing_state_returns_empty_dict_with_defaults(self):
        state = load_state(self.tmpdir.name)
        self.assertEqual(state.get('old_branch'), None)
        self.assertEqual(state['merge_strategy'], 'enforce')

    def test_save_then_load_round_trips(self):
        state = {'old_branch': 'a', 'new_branch': 'b', 'published_version': '1.0.1.0'}
        save_state(self.tmpdir.name, state)
        loaded = load_state(self.tmpdir.name)
        for key, value in state.items():
            self.assertEqual(loaded[key], value)

    def test_save_ends_with_newline(self):
        save_state(self.tmpdir.name, {'a': 1})
        with open(state_path(self.tmpdir.name)) as f:
            contents = f.read()
        self.assertTrue(contents.endswith('\n'))

    def test_save_matches_render_status_plus_newline(self):
        state = {'a': 1, 'b': 2}
        save_state(self.tmpdir.name, state)
        with open(state_path(self.tmpdir.name)) as f:
            contents = f.read()
        self.assertEqual(contents, render_status(state) + '\n')


# ---------------------------------------------------------------------------
# Integration tests against real temporary git repositories. These exercise the
# git-mechanics helpers directly; gradle/publish steps are out of scope here (they're
# covered by manual dry-run verification, not unit tests).
# ---------------------------------------------------------------------------

class GitRepoTestCase(unittest.TestCase):
    """Base class providing a throwaway git repo with an initial commit on 'main'."""

    def setUp(self):
        self.tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmpdir.cleanup)
        self.repo = self.tmpdir.name
        init_repo(self.repo)


class TestFindMainRepoRoot(GitRepoTestCase):
    """Tests for find_main_repo_root()"""

    def test_main_checkout_is_its_own_root(self):
        self.assertEqual(os.path.realpath(find_main_repo_root(self.repo)), os.path.realpath(self.repo))

    def test_worktree_resolves_to_main_root(self):
        make_branch(self.repo, 'feature')
        worktree_path = os.path.join(self.repo, '.worktrees', 'mixed-mode', 'old')
        os.makedirs(os.path.dirname(worktree_path))
        run_git(self.repo, 'worktree', 'add', worktree_path, 'feature')
        self.assertEqual(
            os.path.realpath(find_main_repo_root(worktree_path)), os.path.realpath(self.repo))


class TestGitStatusHelpers(GitRepoTestCase):
    """Tests for branch_exists()/git_rev_parse()/git_current_branch()/git_is_dirty()"""

    def test_branch_exists_true_for_existing_branch(self):
        make_branch(self.repo, 'feature')
        self.assertTrue(branch_exists(self.repo, 'feature'))

    def test_branch_exists_false_for_missing_branch(self):
        self.assertFalse(branch_exists(self.repo, 'does-not-exist'))

    def test_git_rev_parse_matches_head(self):
        head_sha = run_git(self.repo, 'rev-parse', 'HEAD').strip()
        self.assertEqual(git_rev_parse(self.repo, 'main'), head_sha)

    def test_git_current_branch(self):
        self.assertEqual(git_current_branch(self.repo), 'main')
        make_branch(self.repo, 'feature')
        run_git(self.repo, 'checkout', '-q', 'feature')
        self.assertEqual(git_current_branch(self.repo), 'feature')

    def test_git_is_dirty_false_on_clean_tree(self):
        self.assertFalse(git_is_dirty(self.repo))

    def test_git_is_dirty_true_with_untracked_file(self):
        with open(os.path.join(self.repo, 'untracked.txt'), 'w') as f:
            f.write('scratch')
        self.assertTrue(git_is_dirty(self.repo))

    def test_git_is_dirty_true_with_modified_tracked_file(self):
        with open(os.path.join(self.repo, 'gradle.properties'), 'a') as f:
            f.write('extra=1\n')
        self.assertTrue(git_is_dirty(self.repo))


class TestBranchContainsAncestor(GitRepoTestCase):
    """Tests for branch_contains_ancestor()"""

    def test_true_when_descendant_contains_ancestor_tip(self):
        make_branch(self.repo, 'feature')
        head = git_rev_parse(self.repo, 'main')
        self.assertTrue(branch_contains_ancestor(self.repo, head, 'feature'))

    def test_true_for_identical_shas(self):
        head = git_rev_parse(self.repo, 'main')
        self.assertTrue(branch_contains_ancestor(self.repo, head, 'main'))

    def test_false_when_ancestor_has_diverged_commit_descendant_lacks(self):
        make_branch(self.repo, 'feature')
        advance_branch(self.repo, 'main')
        self.assertFalse(branch_contains_ancestor(self.repo, git_rev_parse(self.repo, 'main'), 'feature'))

    def test_true_after_feature_merges_main(self):
        make_branch(self.repo, 'feature')
        advance_branch(self.repo, 'main')
        run_git(self.repo, 'checkout', '-q', 'feature')
        run_git(self.repo, 'merge', '-q', 'main')
        run_git(self.repo, 'checkout', '-q', 'main')
        self.assertTrue(branch_contains_ancestor(self.repo, git_rev_parse(self.repo, 'main'), 'feature'))


class TestSetVersion(GitRepoTestCase):
    """Tests for set_version()"""

    def test_overwrites_version_line_exactly(self):
        path = os.path.join(self.repo, 'gradle.properties')
        set_version(path, '4.12.19.0')
        with open(path) as f:
            self.assertIn('version=4.12.19.0\n', f.read())

    def test_preserves_other_lines(self):
        path = os.path.join(self.repo, 'gradle.properties')
        with open(path, 'a') as f:
            f.write('otherProp=hello\n')
        set_version(path, '4.12.19.0')
        with open(path) as f:
            contents = f.read()
        self.assertIn('otherProp=hello', contents)

    def test_missing_version_line_raises(self):
        path = os.path.join(self.repo, 'no-version.properties')
        with open(path, 'w') as f:
            f.write('otherProp=hello\n')
        with self.assertRaises(RuntimeError):
            set_version(path, '4.12.19.0')

    def test_get_version_matches_after_set(self):
        path = os.path.join(self.repo, 'gradle.properties')
        set_version(path, '4.12.19.0')
        self.assertEqual(get_version(path), '4.12.19.0')


class TestEnsureWorktree(GitRepoTestCase):
    """Tests for ensure_worktree()"""

    def test_creates_worktree_for_branch(self):
        make_branch(self.repo, 'feature')
        ensure_worktree(self.repo, 'old', 'feature')
        path = worktree_dir(self.repo, 'old')
        self.assertTrue(os.path.isdir(path))
        self.assertEqual(git_current_branch(path), 'feature')

    def test_is_idempotent_for_same_branch(self):
        make_branch(self.repo, 'feature')
        ensure_worktree(self.repo, 'old', 'feature')
        ensure_worktree(self.repo, 'old', 'feature')  # should not raise
        self.assertTrue(os.path.isdir(worktree_dir(self.repo, 'old')))

    def test_raises_if_existing_worktree_has_different_branch(self):
        make_branch(self.repo, 'feature-a')
        make_branch(self.repo, 'feature-b')
        ensure_worktree(self.repo, 'old', 'feature-a')
        with self.assertRaises(RuntimeError):
            ensure_worktree(self.repo, 'old', 'feature-b')


class TestCheckoutBranchInline(GitRepoTestCase):
    """Tests for checkout_branch_inline()"""

    def setUp(self):
        super().setUp()
        make_branch(self.repo, 'feature')
        self.state = State({
            'old_branch': 'main',
            'new_branch': 'feature',
            'old_mode': 'inline',
            'new_mode': 'inline',
        })

    def test_switches_branch_on_clean_tree(self):
        checkout_branch_inline(self.repo, 'new', self.state)
        self.assertEqual(git_current_branch(self.repo), 'feature')

    def test_noop_if_already_on_target_branch(self):
        checkout_branch_inline(self.repo, 'old', self.state)
        self.assertEqual(git_current_branch(self.repo), 'main')

    def test_refuses_to_switch_with_unrelated_dirt(self):
        with open(os.path.join(self.repo, 'gradle.properties'), 'a') as f:
            f.write('extra=1\n')
        with self.assertRaises(RuntimeError):
            checkout_branch_inline(self.repo, 'new', self.state)
        # tree should be untouched -- still on main, still dirty
        self.assertEqual(git_current_branch(self.repo), 'main')
        self.assertTrue(git_is_dirty(self.repo))

    def test_parks_and_restores_new_branch_rewrite_across_switch(self):
        # Simulate prepare-new's yamsql rewrite: dirty tree on branch 'feature', tracked via
        # new_bumped_to.
        run_git(self.repo, 'checkout', '-q', 'feature')
        with open(os.path.join(self.repo, 'gradle.properties'), 'a') as f:
            f.write('rewrite=1\n')
        self.state['new_bumped_to'] = '1.0.1.0'

        # Switch away to 'old' -- should park the dirty change in a tagged stash.
        checkout_branch_inline(self.repo, 'old', self.state)
        self.assertEqual(git_current_branch(self.repo), 'main')
        self.assertFalse(git_is_dirty(self.repo))
        self.assertTrue(self.state['new_stashed'])
        self.assertTrue(self.state['new_stash_tag'])

        # Switch back to 'new' -- should pop the parked stash and restore the dirt.
        checkout_branch_inline(self.repo, 'new', self.state)
        self.assertEqual(git_current_branch(self.repo), 'feature')
        self.assertTrue(git_is_dirty(self.repo))
        self.assertFalse(self.state['new_stashed'])
        self.assertIsNone(self.state['new_stash_tag'])
        with open(os.path.join(self.repo, 'gradle.properties')) as f:
            self.assertIn('rewrite=1', f.read())

    def test_two_successive_parks_use_distinct_tags(self):
        # Regression coverage for find_stash_ref's move from a shared substring to a per-push
        # UUID tag: two separate park/restore cycles must not reuse the same tag, so a stale
        # tag left in state.json (e.g. from a crash) could never accidentally match a fresh
        # stash pushed later for an unrelated reason.
        run_git(self.repo, 'checkout', '-q', 'feature')
        with open(os.path.join(self.repo, 'gradle.properties'), 'a') as f:
            f.write('rewrite=1\n')
        self.state['new_bumped_to'] = '1.0.1.0'
        checkout_branch_inline(self.repo, 'old', self.state)
        first_tag = self.state['new_stash_tag']
        checkout_branch_inline(self.repo, 'new', self.state)

        with open(os.path.join(self.repo, 'gradle.properties'), 'a') as f:
            f.write('rewrite=2\n')
        checkout_branch_inline(self.repo, 'old', self.state)
        second_tag = self.state['new_stash_tag']
        self.assertNotEqual(first_tag, second_tag)

    def test_conflicting_pop_raises_and_leaves_stash_in_place(self):
        run_git(self.repo, 'checkout', '-q', 'feature')
        with open(os.path.join(self.repo, 'gradle.properties'), 'a') as f:
            f.write('rewrite=1\n')
        self.state['new_bumped_to'] = '1.0.1.0'
        # Parks the pending rewrite in a tagged stash (based on feature's current HEAD) and
        # switches cleanly to main.
        checkout_branch_inline(self.repo, 'old', self.state)
        self.assertEqual(git_current_branch(self.repo), 'main')

        # Advance feature with a *committed*, conflicting change to the same line the stash
        # touches, without going through checkout_branch_inline (this simulates the developer
        # fixing something on feature directly while it's not the active inline checkout).
        run_git(self.repo, 'checkout', '-q', 'feature')
        with open(os.path.join(self.repo, 'gradle.properties'), 'a') as f:
            f.write('rewrite=CONFLICTING\n')
        run_git(self.repo, 'add', 'gradle.properties')
        run_git(self.repo, 'commit', '-q', '-m', 'conflicting change on feature')
        run_git(self.repo, 'checkout', '-q', 'main')

        # Switching back to 'new' now checks out feature cleanly (no dirt to block it), then
        # tries to pop the stash -- whose diff is based on feature's *old* HEAD -- onto
        # feature's new HEAD, which has a different edit at the same spot: a real
        # `git stash pop` conflict, reaching the actual returncode-!=-0 handling this test is
        # meant to guard.
        with self.assertRaises(RuntimeError):
            checkout_branch_inline(self.repo, 'new', self.state)
        self.assertEqual(git_current_branch(self.repo), 'feature')
        stash_list = run_git(self.repo, 'stash', 'list')
        self.assertIn('mixed-mode-two-branches', stash_list)


class TestResolveLocation(GitRepoTestCase):
    """Tests for resolve_location()"""

    def test_worktree_mode_returns_worktree_dir(self):
        make_branch(self.repo, 'feature')
        state = State({'old_branch': 'feature', 'old_mode': 'worktree'})
        ensure_worktree(self.repo, 'old', 'feature')
        self.assertEqual(
            os.path.realpath(resolve_location(self.repo, state, 'old')),
            os.path.realpath(worktree_dir(self.repo, 'old')))

    def test_worktree_mode_missing_worktree_raises(self):
        state = State({'old_branch': 'feature', 'old_mode': 'worktree'})
        with self.assertRaises(RuntimeError):
            resolve_location(self.repo, state, 'old')

    def test_inline_mode_returns_main_root_and_switches(self):
        make_branch(self.repo, 'feature')
        state = State({
            'old_branch': 'main', 'new_branch': 'feature',
            'old_mode': 'inline', 'new_mode': 'inline',
        })
        location = resolve_location(self.repo, state, 'new')
        self.assertEqual(os.path.realpath(location), os.path.realpath(self.repo))
        self.assertEqual(git_current_branch(self.repo), 'feature')


class TestCaptureYamsqlPatch(GitRepoTestCase):
    """Tests for capture_yamsql_patch()"""

    def test_returns_none_if_nothing_staged(self):
        self.assertIsNone(capture_yamsql_patch(self.repo, self.repo))

    def test_captures_staged_yamsql_changes(self):
        yamsql_path = os.path.join(self.repo, 'some-test.yamsql')
        with open(yamsql_path, 'w') as f:
            f.write('supported_version: !current_version\n')
        run_git(self.repo, 'add', 'some-test.yamsql')
        run_git(self.repo, 'commit', '-q', '-m', 'add yamsql fixture')

        with open(yamsql_path, 'w') as f:
            f.write('supported_version: 1.0.1.0\n')
        run_git(self.repo, 'add', 'some-test.yamsql')

        patch_path = capture_yamsql_patch(self.repo, self.repo)
        self.assertIsNotNone(patch_path)
        self.assertTrue(os.path.exists(patch_path))
        with open(patch_path) as f:
            contents = f.read()
        self.assertIn('some-test.yamsql', contents)
        self.assertIn('!current_version', contents)

    def test_ignores_staged_non_yamsql_changes(self):
        with open(os.path.join(self.repo, 'gradle.properties'), 'a') as f:
            f.write('extra=1\n')
        run_git(self.repo, 'add', 'gradle.properties')
        self.assertIsNone(capture_yamsql_patch(self.repo, self.repo))


def ns(**kwargs):
    """Build an argparse.Namespace the way argparse would after parsing a subcommand, for
    calling cmd_* functions directly without going through build_parser()."""
    return argparse.Namespace(**kwargs)


def read_file(path):
    with open(path) as f:
        return f.read()


# ---------------------------------------------------------------------------
# Integration tests for the cmd_* orchestration functions, against real temporary git
# repositories. run_gradle is mocked throughout (no real ./gradlew invocation) since these tests
# are about the git/state mechanics cmd_* wires together, not gradle itself -- gradle command
# lines were verified manually per the design doc. Where a subcommand's behavior depends on what
# gradle actually did to the working tree (prepare-new's !current_version rewrite), the mock's
# side effect is fake_update_yamsql(), which performs the same rewrite-and-stage a real
# `./gradlew updateYamsql` run would.
# ---------------------------------------------------------------------------

class TestCmdSetup(GitRepoTestCase):
    """Tests for cmd_setup()"""

    def setUp(self):
        super().setUp()
        make_branch(self.repo, 'old-branch')
        make_branch(self.repo, 'new-branch')

    def _args(self, **overrides):
        defaults = dict(old_branch='old-branch', new_branch='new-branch',
                         old_mode='inline', new_mode='inline', update_type='BUILD',
                         merge_strategy='enforce', reconfigure=False)
        defaults.update(overrides)
        return ns(**defaults)

    def test_creates_state_for_inline_branches(self):
        cmd_setup(self._args(), self.repo)
        state = load_state(self.repo)
        self.assertEqual(state['old_branch'], 'old-branch')
        self.assertEqual(state['new_branch'], 'new-branch')
        self.assertEqual(state['old_mode'], 'inline')
        self.assertEqual(state['update_type'], 'BUILD')
        self.assertEqual(state['merge_strategy'], 'enforce')

    def test_missing_branch_raises(self):
        with self.assertRaises(RuntimeError):
            cmd_setup(self._args(new_branch='does-not-exist'), self.repo)

    def test_worktree_mode_creates_worktree(self):
        cmd_setup(self._args(old_mode='worktree'), self.repo)
        self.assertTrue(os.path.isdir(worktree_dir(self.repo, 'old')))

    def test_rerun_same_branches_preserves_progress(self):
        cmd_setup(self._args(), self.repo)
        state = load_state(self.repo)
        state['published_version'] = '1.0.1.0'
        save_state(self.repo, state)

        cmd_setup(self._args(update_type='MINOR'), self.repo)
        state = load_state(self.repo)
        self.assertEqual(state['published_version'], '1.0.1.0')
        self.assertEqual(state['update_type'], 'MINOR')

    def test_conflicting_session_without_reconfigure_raises(self):
        cmd_setup(self._args(), self.repo)
        make_branch(self.repo, 'other-branch')
        with self.assertRaises(RuntimeError):
            cmd_setup(self._args(new_branch='other-branch'), self.repo)

    def test_reconfigure_with_different_branches_resets_progress(self):
        cmd_setup(self._args(), self.repo)
        state = load_state(self.repo)
        state['published_version'] = '1.0.1.0'
        save_state(self.repo, state)

        make_branch(self.repo, 'other-branch')
        cmd_setup(self._args(new_branch='other-branch', reconfigure=True), self.repo)
        state = load_state(self.repo)
        self.assertEqual(state['new_branch'], 'other-branch')
        self.assertNotIn('published_version', state)

    def test_records_merge_strategy(self):
        cmd_setup(self._args(merge_strategy='auto-rebase'), self.repo)
        state = load_state(self.repo)
        self.assertEqual(state['merge_strategy'], 'auto-rebase')


class TestCmdPublishOld(GitRepoTestCase):
    """Tests for cmd_publish_old()"""

    def setUp(self):
        super().setUp()
        make_branch(self.repo, 'old-branch')

    def _gradle_properties(self, location=None):
        return os.path.join(location or self.repo, 'gradle.properties')

    def test_first_publish_bumps_publishes_and_reverts_inline(self):
        save_state(self.repo, {'old_branch': 'old-branch', 'update_type': 'BUILD', 'old_mode': 'inline'})
        with patch('mixed_mode_two_branches.run_gradle') as mock_gradle:
            cmd_publish_old(ns(force=False), self.repo)
        mock_gradle.assert_called_once()

        state = load_state(self.repo)
        self.assertEqual(state['published_version'], '1.0.1.0')
        self.assertEqual(git_current_branch(self.repo), 'old-branch')
        # Inline mode reverts the bump immediately after publishing -- the working tree should
        # show the original version again, not the bumped one.
        self.assertIn('version=1.0.0.0', read_file(self._gradle_properties()))

    def test_skip_when_unchanged(self):
        save_state(self.repo, {'old_branch': 'old-branch', 'update_type': 'BUILD', 'old_mode': 'inline'})
        with patch('mixed_mode_two_branches.run_gradle') as mock_gradle:
            cmd_publish_old(ns(force=False), self.repo)
            cmd_publish_old(ns(force=False), self.repo)
        self.assertEqual(mock_gradle.call_count, 1)

    def test_force_republishes_under_same_version(self):
        # Republishing (whether via --force or because branch 1's HEAD moved) now overwrites
        # the same version string rather than bumping to a new one.
        save_state(self.repo, {'old_branch': 'old-branch', 'update_type': 'BUILD', 'old_mode': 'inline'})
        with patch('mixed_mode_two_branches.run_gradle'):
            cmd_publish_old(ns(force=False), self.repo)
            first_version = load_state(self.repo)['published_version']
            cmd_publish_old(ns(force=True), self.repo)
            second_version = load_state(self.repo)['published_version']
        self.assertEqual(first_version, '1.0.1.0')
        self.assertEqual(second_version, '1.0.1.0')

    def test_republish_after_branch_moves_reuses_version(self):
        save_state(self.repo, {'old_branch': 'old-branch', 'update_type': 'BUILD', 'old_mode': 'inline'})
        with patch('mixed_mode_two_branches.run_gradle') as mock_gradle:
            cmd_publish_old(ns(force=False), self.repo)
            advance_branch(self.repo, 'old-branch')
            cmd_publish_old(ns(force=False), self.repo)
        self.assertEqual(mock_gradle.call_count, 2)
        state = load_state(self.repo)
        self.assertEqual(state['published_version'], '1.0.1.0')
        self.assertEqual(state['published_from_sha'], git_rev_parse(self.repo, 'old-branch'))

    def test_raises_if_branch_is_dirty(self):
        save_state(self.repo, {'old_branch': 'old-branch', 'update_type': 'BUILD', 'old_mode': 'inline'})
        run_git(self.repo, 'checkout', '-q', 'old-branch')
        with open(os.path.join(self.repo, 'untracked.txt'), 'w') as f:
            f.write('dirt')
        with patch('mixed_mode_two_branches.run_gradle') as mock_gradle:
            with self.assertRaises(RuntimeError):
                cmd_publish_old(ns(force=False), self.repo)
        mock_gradle.assert_not_called()

    def test_warns_if_tag_already_exists_for_version_about_to_publish(self):
        # The version about to be published is 1.0.1.0 (a single BUILD bump from 1.0.0.0) --
        # tag collision must be checked against that, not the pre-bump 1.0.0.0 still on disk.
        run_git(self.repo, 'tag', '1.0.1.0')
        save_state(self.repo, {'old_branch': 'old-branch', 'update_type': 'BUILD', 'old_mode': 'inline'})
        buf = io.StringIO()
        with patch('mixed_mode_two_branches.run_gradle'):
            with contextlib.redirect_stdout(buf):
                cmd_publish_old(ns(force=False), self.repo)
        self.assertIn('WARNING', buf.getvalue())
        self.assertIn('1.0.1.0', buf.getvalue())

    def test_worktree_mode_does_not_revert_gradle_properties(self):
        save_state(self.repo, {'old_branch': 'old-branch', 'update_type': 'BUILD', 'old_mode': 'worktree'})
        ensure_worktree(self.repo, 'old', 'old-branch')
        with patch('mixed_mode_two_branches.run_gradle'):
            cmd_publish_old(ns(force=False), self.repo)
        self.assertIn('version=1.0.1.0', read_file(self._gradle_properties(worktree_dir(self.repo, 'old'))))


class TestCmdPrepareNew(GitRepoTestCase):
    """Tests for cmd_prepare_new()"""

    YAMSQL_NAME = 'gated-feature.yamsql'

    def setUp(self):
        super().setUp()
        run_git(self.repo, 'checkout', '-q', '-b', 'new-branch')
        with open(os.path.join(self.repo, self.YAMSQL_NAME), 'w') as f:
            f.write('supported_version: !current_version\n')
        run_git(self.repo, 'add', self.YAMSQL_NAME)
        run_git(self.repo, 'commit', '-q', '-m', 'add gated feature test')
        run_git(self.repo, 'checkout', '-q', 'main')
        save_state(self.repo, {'new_branch': 'new-branch', 'new_mode': 'inline', 'old_branch': 'main',
                                'old_mode': 'inline', 'update_type': 'BUILD', 'published_version': '1.0.1.0',
                                'merge_strategy': 'enforce'})

    def _yamsql_contents(self):
        return read_file(os.path.join(self.repo, self.YAMSQL_NAME))

    def test_bump_action_rewrites_and_records_state(self):
        with patch('mixed_mode_two_branches.run_gradle', side_effect=fake_update_yamsql):
            cmd_prepare_new(ns(), self.repo)

        self.assertIn('version=1.0.1.0', read_file(os.path.join(self.repo, 'gradle.properties')))
        self.assertIn('1.0.1.0', self._yamsql_contents())
        state = load_state(self.repo)
        self.assertEqual(state['new_bumped_to'], '1.0.1.0')
        self.assertEqual(state['new_prepared_from_sha'], git_rev_parse(self.repo, 'new-branch'))
        self.assertIsNotNone(state['rewrite_patch'])
        self.assertTrue(os.path.exists(state['rewrite_patch']))

    def test_noop_when_already_prepared(self):
        with patch('mixed_mode_two_branches.run_gradle', side_effect=fake_update_yamsql) as mock_gradle:
            cmd_prepare_new(ns(), self.repo)
            cmd_prepare_new(ns(), self.repo)
        self.assertEqual(mock_gradle.call_count, 1)

    def test_rewrite_only_when_branch_moves_but_target_version_unchanged(self):
        with patch('mixed_mode_two_branches.run_gradle', side_effect=fake_update_yamsql) as mock_gradle:
            cmd_prepare_new(ns(), self.repo)
            advance_branch(self.repo, 'new-branch')
            cmd_prepare_new(ns(), self.repo)
        self.assertEqual(mock_gradle.call_count, 2)
        state = load_state(self.repo)
        self.assertEqual(state['new_bumped_to'], '1.0.1.0')
        self.assertEqual(state['new_prepared_from_sha'], git_rev_parse(self.repo, 'new-branch'))

    def test_redo_undoes_prior_rewrite_when_bumped_to_a_different_version(self):
        # Defensive/legacy path: if state.json somehow has new_bumped_to pointing at a version
        # different from the current target (branch 1 no longer bumps to a new version on its
        # own, but state could have been carried over or hand-edited), prepare-new should still
        # undo the stale rewrite before reapplying against the real target.
        with patch('mixed_mode_two_branches.run_gradle', side_effect=fake_update_yamsql):
            cmd_prepare_new(ns(), self.repo)
            state = load_state(self.repo)
            state['published_version'] = '1.0.3.0'
            save_state(self.repo, state)
            cmd_prepare_new(ns(), self.repo)

        self.assertIn('version=1.0.3.0', read_file(os.path.join(self.repo, 'gradle.properties')))
        self.assertIn('1.0.3.0', self._yamsql_contents())
        self.assertNotIn('!current_version', self._yamsql_contents())
        state = load_state(self.repo)
        self.assertEqual(state['new_bumped_to'], '1.0.3.0')

    def test_redo_raises_if_prior_rewrite_was_since_edited(self):
        with patch('mixed_mode_two_branches.run_gradle', side_effect=fake_update_yamsql):
            cmd_prepare_new(ns(), self.repo)

        # Edit the exact line the saved patch expects to find (still on new-branch, the current
        # inline checkout after prepare-new) and commit it, so the patch no longer applies in
        # reverse.
        yamsql_path = os.path.join(self.repo, self.YAMSQL_NAME)
        with open(yamsql_path, 'w') as f:
            f.write('supported_version: CONFLICTING\n')
        run_git(self.repo, 'add', self.YAMSQL_NAME)
        run_git(self.repo, 'commit', '-q', '-m', 'independent edit conflicting with the rewrite')

        state = load_state(self.repo)
        state['published_version'] = '1.0.2.0'
        save_state(self.repo, state)

        with patch('mixed_mode_two_branches.run_gradle', side_effect=fake_update_yamsql):
            with self.assertRaises(RuntimeError):
                cmd_prepare_new(ns(), self.repo)
        # The conflicting edit must survive untouched -- `--check` failing must stop before any
        # real `git apply -R` runs.
        self.assertIn('CONFLICTING', self._yamsql_contents())

    def test_raises_when_branch2_lacks_branch1_tip_and_strategy_is_enforce(self):
        advance_branch(self.repo, 'main')
        with patch('mixed_mode_two_branches.run_gradle', side_effect=fake_update_yamsql) as mock_gradle:
            with self.assertRaises(RuntimeError):
                cmd_prepare_new(ns(), self.repo)
        mock_gradle.assert_not_called()

    def test_auto_merges_when_strategy_is_auto_merge(self):
        advance_branch(self.repo, 'main')
        state = load_state(self.repo)
        state['merge_strategy'] = 'auto-merge'
        save_state(self.repo, state)

        with patch('mixed_mode_two_branches.run_gradle', side_effect=fake_update_yamsql):
            cmd_prepare_new(ns(), self.repo)

        self.assertTrue(
            branch_contains_ancestor(self.repo, git_rev_parse(self.repo, 'main'), 'new-branch'))

    def test_auto_rebases_when_strategy_is_auto_rebase(self):
        advance_branch(self.repo, 'main')
        state = load_state(self.repo)
        state['merge_strategy'] = 'auto-rebase'
        save_state(self.repo, state)

        with patch('mixed_mode_two_branches.run_gradle', side_effect=fake_update_yamsql):
            cmd_prepare_new(ns(), self.repo)

        self.assertTrue(
            branch_contains_ancestor(self.repo, git_rev_parse(self.repo, 'main'), 'new-branch'))


class TestCmdTest(GitRepoTestCase):
    """Tests for cmd_test()"""

    def setUp(self):
        super().setUp()
        run_git(self.repo, 'checkout', '-q', '-b', 'new-branch')
        run_git(self.repo, 'checkout', '-q', 'main')
        save_state(self.repo, {'new_branch': 'new-branch', 'new_mode': 'inline', 'old_branch': 'main',
                                'old_mode': 'inline', 'update_type': 'BUILD', 'published_version': '1.0.1.0',
                                'merge_strategy': 'enforce'})
        with patch('mixed_mode_two_branches.run_gradle', side_effect=fake_update_yamsql):
            cmd_prepare_new(ns(), self.repo)

    def test_runs_gradle_with_expected_args_when_prepared(self):
        with patch('mixed_mode_two_branches.run_gradle') as mock_gradle:
            cmd_test(ns(task='mixedModeTest', tests=None), self.repo)
        mock_gradle.assert_called_once()
        call_args = mock_gradle.call_args[0]
        self.assertEqual(call_args[1], ':yaml-tests:mixedModeTest')
        self.assertIn('-PmavenLocalEnabled=true', call_args)
        self.assertIn('-Ptests.mixedModeVersion=1.0.1.0', call_args)

    def test_passes_tests_filter_through(self):
        with patch('mixed_mode_two_branches.run_gradle') as mock_gradle:
            cmd_test(ns(task='mixedModeTest', tests='YamlIntegrationTests.selectAStar'), self.repo)
        call_args = mock_gradle.call_args[0]
        self.assertIn('--tests', call_args)
        self.assertIn('YamlIntegrationTests.selectAStar', call_args)

    def test_raises_when_branch2_moved_since_prepare(self):
        advance_branch(self.repo, 'new-branch')
        with patch('mixed_mode_two_branches.run_gradle') as mock_gradle:
            with self.assertRaises(RuntimeError):
                cmd_test(ns(task='mixedModeTest', tests=None), self.repo)
        mock_gradle.assert_not_called()


class TestCmdStatus(GitRepoTestCase):
    """Tests for cmd_status()"""

    def test_prints_message_when_no_session(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            cmd_status(ns(), self.repo)
        self.assertIn('No session configured', buf.getvalue())

    def test_prints_state_and_branch_shas(self):
        save_state(self.repo, {'old_branch': 'main', 'published_version': '1.0.1.0'})
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            cmd_status(ns(), self.repo)
        output = buf.getvalue()
        self.assertIn('"published_version": "1.0.1.0"', output)
        self.assertIn('old: main @ ' + git_rev_parse(self.repo, 'main')[:8], output)


class TestCmdTeardown(GitRepoTestCase):
    """Tests for cmd_teardown()"""

    def test_nothing_to_teardown_when_no_state(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            cmd_teardown(ns(force=False, keep_maven_local=False), self.repo)
        self.assertIn('Nothing to tear down', buf.getvalue())

    def test_removes_clean_worktree_and_scratch_dir(self):
        make_branch(self.repo, 'old-branch')
        ensure_worktree(self.repo, 'old', 'old-branch')
        save_state(self.repo, {'old_branch': 'old-branch', 'old_mode': 'worktree'})

        cmd_teardown(ns(force=False, keep_maven_local=False), self.repo)
        self.assertFalse(os.path.isdir(worktree_dir(self.repo, 'old')))
        self.assertFalse(os.path.isdir(scratch_dir(self.repo)))

    def test_refuses_dirty_worktree_without_force(self):
        make_branch(self.repo, 'old-branch')
        ensure_worktree(self.repo, 'old', 'old-branch')
        path = worktree_dir(self.repo, 'old')
        with open(os.path.join(path, 'untracked.txt'), 'w') as f:
            f.write('dirt')
        save_state(self.repo, {'old_branch': 'old-branch', 'old_mode': 'worktree'})

        with self.assertRaises(RuntimeError):
            cmd_teardown(ns(force=False, keep_maven_local=False), self.repo)
        self.assertTrue(os.path.isdir(path))

    def test_force_removes_dirty_worktree(self):
        make_branch(self.repo, 'old-branch')
        ensure_worktree(self.repo, 'old', 'old-branch')
        path = worktree_dir(self.repo, 'old')
        with open(os.path.join(path, 'untracked.txt'), 'w') as f:
            f.write('dirt')
        save_state(self.repo, {'old_branch': 'old-branch', 'old_mode': 'worktree'})

        cmd_teardown(ns(force=True, keep_maven_local=False), self.repo)
        self.assertFalse(os.path.isdir(path))

    def test_surfaces_worktree_removal_failure(self):
        make_branch(self.repo, 'old-branch')
        ensure_worktree(self.repo, 'old', 'old-branch')
        save_state(self.repo, {'old_branch': 'old-branch', 'old_mode': 'worktree'})

        real_run = subprocess.run

        def fake_run(cmd, *args, **kwargs):
            if cmd[:3] == ['git', 'worktree', 'remove']:
                return subprocess.CompletedProcess(cmd, 1)
            return real_run(cmd, *args, **kwargs)

        with patch('mixed_mode_two_branches.subprocess.run', side_effect=fake_run):
            with self.assertRaises(RuntimeError):
                cmd_teardown(ns(force=False, keep_maven_local=False), self.repo)
        # The failure must be surfaced, not swallowed -- the worktree is still on disk.
        self.assertTrue(os.path.isdir(worktree_dir(self.repo, 'old')))

    def test_warns_about_parked_stash_for_inline_new_branch(self):
        save_state(self.repo, {'new_branch': 'main', 'new_mode': 'inline', 'new_stashed': True,
                                'new_stash_tag': 'deadbeef-0000'})
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            cmd_teardown(ns(force=False, keep_maven_local=False), self.repo)
        self.assertIn('parked stash', buf.getvalue())

    def test_deletes_published_maven_local_artifact_by_default(self):
        with tempfile.TemporaryDirectory() as tmp_home:
            with patch.dict(os.environ, {'HOME': tmp_home}):
                target = os.path.join(tmp_home, '.m2', 'repository', 'org', 'foundationdb',
                                       'fdb-relational-server', '1.0.1.0')
                os.makedirs(target)
                save_state(self.repo, {'published_version': '1.0.1.0'})
                cmd_teardown(ns(force=False, keep_maven_local=False), self.repo)
                self.assertFalse(os.path.exists(target))

    def test_keep_maven_local_flag_preserves_artifact(self):
        with tempfile.TemporaryDirectory() as tmp_home:
            with patch.dict(os.environ, {'HOME': tmp_home}):
                target = os.path.join(tmp_home, '.m2', 'repository', 'org', 'foundationdb',
                                       'fdb-relational-server', '1.0.1.0')
                os.makedirs(target)
                save_state(self.repo, {'published_version': '1.0.1.0'})
                cmd_teardown(ns(force=False, keep_maven_local=True), self.repo)
                self.assertTrue(os.path.exists(target))


class TestRemoveMavenLocalArtifacts(unittest.TestCase):
    """Tests for remove_maven_local_artifacts()"""

    def setUp(self):
        self.tmp_home = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp_home.cleanup)
        self.home_patch = patch.dict(os.environ, {'HOME': self.tmp_home.name})
        self.home_patch.start()
        self.addCleanup(self.home_patch.stop)

    def _artifact_dir(self, artifact, version):
        return os.path.join(self.tmp_home.name, '.m2', 'repository', 'org', 'foundationdb', artifact, version)

    def test_removes_matching_version_directories(self):
        target = self._artifact_dir('fdb-relational-server', '4.12.19.0')
        os.makedirs(target)
        with open(os.path.join(target, 'fdb-relational-server-4.12.19.0-all.jar'), 'w') as f:
            f.write('fake jar')

        remove_maven_local_artifacts('4.12.19.0')
        self.assertFalse(os.path.exists(target))

    def test_leaves_other_versions_untouched(self):
        target = self._artifact_dir('fdb-relational-server', '4.12.19.0')
        other = self._artifact_dir('fdb-relational-server', '4.12.18.0')
        os.makedirs(target)
        os.makedirs(other)

        remove_maven_local_artifacts('4.12.19.0')
        self.assertFalse(os.path.exists(target))
        self.assertTrue(os.path.exists(other))

    def test_no_matches_does_not_raise(self):
        remove_maven_local_artifacts('9.9.9.9')  # should just print and return


if __name__ == '__main__':
    unittest.main()
