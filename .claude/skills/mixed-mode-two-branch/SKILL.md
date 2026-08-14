---
name: mixed-mode-two-branch
description: Use this skill when validating a feature split across two stacked branches/PRs
  via yaml-tests mixed-mode, where the first branch adds deserialization support for a new wire
  format and the second branch starts generating it. Automates publishing the first branch to
  maven local and running the second branch's yaml-tests against it.
  Some example usages:
  "Validate that my two stacked PRs round-trip correctly before I merge them"
  "Run branch B's yaml-tests against branch A published to maven local"
  "I fixed a bug on the old branch, redo the mixed-mode validation"
---

Always apply the `using-gradle` skill for general Gradle task syntax.

# What this validates

Two branches, stacked: branch 1 ("old") adds the ability to *deserialize* a new wire format
without yet generating it; branch 2 ("new"), stacked on branch 1, starts *generating* that
format. Before merging either, publish branch 1's server build to maven local and run branch
2's yaml-tests against it as the mixed-mode "external server" — proving the round trip works
without waiting for branch 1 to actually be released.

All the mechanics live in `dev-tools/mixed_mode_two_branch.py`. This skill is the decision layer
on top of it: which subcommand to run when, how to interpret failures, and what to tell the
developer about tradeoffs.

# Subcommands

```
python3 dev-tools/mixed_mode_two_branch.py setup       --old-branch <b1> --new-branch <b2> \
                                                    [--old-mode inline|worktree] [--new-mode inline|worktree] \
                                                    [--update-type MAJOR|MINOR|BUILD|PATCH] [--reconfigure]
python3 dev-tools/mixed_mode_two_branch.py publish-old  [--force]
python3 dev-tools/mixed_mode_two_branch.py prepare-new
python3 dev-tools/mixed_mode_two_branch.py test         [--task mixedModeTest|test] [--tests <filter>]
python3 dev-tools/mixed_mode_two_branch.py status
python3 dev-tools/mixed_mode_two_branch.py teardown     [--keep-maven-local] [--force]
```

Run the sequence in order the first time: `setup` → `publish-old` → `prepare-new` → `test`.
Every subcommand is safe to re-run — each keys off git HEAD shas (and, for `setup`, the
configured branch names) recorded in `.mixed-mode-two-branch/state.json` (gitignored) and does
the minimum necessary work. Re-running `setup` with the same branches preserves any
`publish-old`/`prepare-new` progress already recorded; pass `--reconfigure` to retarget to
different branches, which does start that progress over (it does not touch already-published
artifacts or worktrees — run `teardown` first if those need cleaning up too).

`--update-type` defaults to `BUILD`.

`--old-mode`/`--new-mode` default to `inline` (operates on whichever branch is currently
checked out in the main working directory, switching branches as needed) since most developers
already have one or both branches checked out there. Use `--mode worktree` for a branch that
should get an isolated checkout under `.worktrees/mixed-mode/<old|new>/` instead — e.g. when
actively editing branch 2 while validating branch 1 unattended, or vice versa.

`test --tests <filter>` restricts the run to a single test via gradle's `--tests` flag (e.g.
`--tests YamlIntegrationTests.selectAStar`) — handy while narrowing down a specific failure
instead of re-running the whole suite each iteration.


# Setup prerequisite

`.worktrees/` and `.mixed-mode-two-branch/` are already gitignored. If working from an older
checkout that predates this, confirm `.gitignore` has:
```
/.mixed-mode-two-branch/
/.worktrees/
```

# Iterating on a failure

`test` failures usually mean one of the two branches needs a fix. Both directions are equally
common — don't assume it's always branch 2.

**Fixing branch 2**: edit the files (the script checks out branch 2 automatically before the
next subcommand that needs it, if inline). Then:
- No new `!current_version` marker added → just re-run `test`.
- New `!current_version` marker added (e.g. a new SQL feature gated on the current build) →
  re-run `prepare-new` first (idempotent — only touches remaining unrewritten markers), then
  `test`.

**Fixing branch 1**: edit the files, then re-run `publish-old`. The script detects branch 1's
HEAD moved and republishes under a fresh, higher version (never reuses a version string for
different bits). This makes branch 2's `*.yamsql` rewrite stale — re-run `prepare-new`, which
undoes its own prior rewrite via a saved patch before redoing it against the new version. If
that patch no longer applies cleanly (branch 2 was independently edited in the same spot),
`prepare-new` will stop and ask for manual resolution rather than guessing — don't try to force
it; look at the reported file/patch and resolve the conflict by hand.

Both `publish-old` and `prepare-new` also pick up branch 2 being *rebased* onto an updated
branch 1 — that changes branch 2's HEAD sha exactly like a direct edit would, and triggers the
same re-run needed above.

# Inline-mode branch switching

When a branch is `inline`, the script switches the main checkout to it as needed and refuses to
switch away from a dirty tree unless the dirt is its own tracked scratch edit (branch 2's
pending `!current_version` rewrite, parked in a tagged stash and restored automatically). If it
refuses because of the developer's own uncommitted changes, don't work around it — tell the
developer to commit or stash manually first. If a stash restore reports a conflict, surface it
for manual resolution the same way as the branch-1-republish patch conflict above.

# Cleanup

`teardown` removes any worktrees it created and, by default, deletes the maven-local artifact
published under this session's version (`~/.m2/repository/org/foundationdb/*/<version>`) since
it's scratch state that could otherwise pollute unrelated local builds also using
`mavenLocalEnabled=true`. Pass `--keep-maven-local` to keep testing against it without
republishing. Never deletes the branches themselves.

`teardown` refuses to remove a `worktree`-mode checkout that has uncommitted changes, so it
never silently discards work the developer hasn't committed — pass `--force` to discard those
changes and remove it anyway. If `git worktree remove` or `git worktree prune` itself fails
(e.g. filesystem permissions), that failure is surfaced rather than swallowed; resolve it
manually and re-run `teardown`.

# Why branch 2 needs no second version bump for the runtime check

`prepare-new` bumps branch 2's `gradle.properties` to match branch 1's published version purely
so `updateYamsql`'s text substitution turns `!current_version` into that literal string — it is
not what makes branch 2's own local build pass version gates at runtime. That's a separate
mechanism: `supported_version` gates go through `SupportedVersionCheck.parse`, compared against
`SemanticVersion.current()` — a special `CURRENT`-type singleton. Local/embedded/JDBC-in-process
connections always report `SemanticVersion.current()`, never a literal parsed version, and
`SemanticVersionType` orders `MIN < NORMAL < CURRENT < MAX` by ordinal (type compared before
numeric value) — so `CURRENT` always outranks any parsed `NORMAL` version regardless of
`-SNAPSHOT`. If asked to explain this, be precise about it: it's the `CURRENT`-singleton
special-casing, not `-SNAPSHOT` ordering, that makes this safe. (For the record: `-SNAPSHOT`
actually sorts *older* than the same numeric version in `SemanticVersion.compareTo`, opposite of
what intuition might suggest — see `SemanticVersionTest.java` fixtures — but that ordering isn't
what's load-bearing here.)

# Testing the script itself

`python3 dev-tools/test_mixed_mode_two_branch.py` runs its unit tests (pure decision logic plus
git-mechanics integration tests against real temporary repos — no gradle/FDB required, runs in
a few seconds). Run this after modifying `dev-tools/mixed_mode_two_branch.py`.
