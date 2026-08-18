---
name: mixed-mode-two-branches
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

All the mechanics live in `dev-tools/mixed_mode_two_branches.py`. This skill is the decision layer
on top of it: which subcommand to run when, how to interpret failures, and what to tell the
developer about tradeoffs.

# Subcommands

```
python3 dev-tools/mixed_mode_two_branches.py setup       --old-branch <b1> --new-branch <b2> \
                                                    [--old-mode inline|worktree] [--new-mode inline|worktree] \
                                                    [--update-type MAJOR|MINOR|BUILD|PATCH] \
                                                    [--merge-strategy enforce|auto-merge|auto-rebase] [--reconfigure]
python3 dev-tools/mixed_mode_two_branches.py publish-old  [--force]
python3 dev-tools/mixed_mode_two_branches.py prepare-new
python3 dev-tools/mixed_mode_two_branches.py test         [--task mixedModeTest|test] [--tests <filter>]
python3 dev-tools/mixed_mode_two_branches.py status
python3 dev-tools/mixed_mode_two_branches.py teardown     [--keep-maven-local] [--force]
```

Run the sequence in order the first time: `setup` → `publish-old` → `prepare-new` → `test`.
Every subcommand is safe to re-run — each keys off git HEAD shas (and, for `setup`, the
configured branch names) recorded in `.mixed-mode-two-branches/state.json` (gitignored) and does
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

`--merge-strategy` controls what `prepare-new` does when branch 2 doesn't yet contain branch 1's
current tip (see "Iterating on a failure" below). Defaults to `enforce`: refuse and ask the
developer to merge/rebase manually. `auto-merge`/`auto-rebase` let the script do it for them
instead.

`test --tests <filter>` restricts the run to a single test via gradle's `--tests` flag (e.g.
`--tests YamlIntegrationTests.selectAStar`) — handy while narrowing down a specific failure
instead of re-running the whole suite each iteration.


# Setup prerequisite

`.worktrees/` and `.mixed-mode-two-branches/` are already gitignored. If working from an older
checkout that predates this, confirm `.gitignore` has:
```
/.mixed-mode-two-branches/
/.worktrees/
```

# Iterating on a failure

`test` failures usually mean one of the two branches needs a fix. Both directions are equally
common — don't assume it's always branch 2.

**Fixing branch 2**: edit the files (the script checks out branch 2 automatically before the
next subcommand that needs it, if inline). Then:
- No new `!current_version` marker added → just re-run `test`.
- New `!current_version` marker added → think about *why* before re-running `prepare-new`. A
  marker only belongs on something branch 2 actually **inherited from branch 1** (a
  `supported_version` gate on the new wire format itself, or other code that predates branch
  2). If the marker is on something genuinely new *in branch 2* — a test or gate that only
  exists because of branch 2's own commits — it doesn't need `!current_version` at all: branch
  2's own local build always satisfies runtime version gates via `SemanticVersion.current()`
  (see "Why branch 2 needs no second version bump for the runtime check" below), so a literal
  marker there is at best redundant and at worst confusing about which branch actually
  introduced the gate. Flag this to the developer rather than silently rewriting it. Once you've
  confirmed the marker belongs, re-run `prepare-new` (idempotent — only touches remaining
  unrewritten markers), then `test`.

**Fixing branch 1**: commit the fix (branch 1 must be committed before `publish-old` will
(re)publish it — publishing an uncommitted state wouldn't be reproducible from the branch
history, so the script refuses with a clear message if branch 1 is dirty), then re-run
`publish-old`. The script detects branch 1's HEAD moved and republishes **under the same version
string as before** — maven-local is simply overwritten with the fixed bits, so nothing about
branch 2's already-rewritten `!current_version` literal or `tests.mixedModeVersion` goes stale.

This does, however, mean branch 2 now needs to actually *contain* branch 1's fix — it's stacked
on branch 1, so a stale branch 2 could otherwise pass tests against code branch 1 no longer has.
`prepare-new` enforces this: it checks (via `git merge-base --is-ancestor`) that branch 2's
history includes branch 1's current tip, and refuses with a clear message if not. By default
(`--merge-strategy enforce`, set at `setup` time) it only enforces and asks the developer to
merge or rebase branch 1's fix into branch 2 by hand; `--merge-strategy auto-merge` or
`auto-rebase` let the script do that merge/rebase itself before proceeding. If an auto-merge or
auto-rebase conflicts, git surfaces that directly — resolve it the same way as any other
merge/rebase conflict, then re-run `prepare-new`.

# Inline-mode branch switching

The rule: changes to branch 2 are allowed to be dirty and are handled automatically. When a
branch is `inline`, the script switches the main checkout to it as needed and refuses to switch
away from a dirty tree unless the dirt is branch 2's own tracked scratch edit (the pending
`!current_version` rewrite), which it parks in a tagged stash — tagged with a fresh id
generated for that specific park, not just a shared label, so a later pop can never grab the
wrong stash — and restores automatically when branch 2 is checked out again.

Changes to branch 1 are different: they must be committed by the developer (see "Fixing branch
1" above) — the script never auto-stashes branch 1's dirt, and `publish-old` refuses outright if
branch 1 isn't committed. If it refuses to switch because of the developer's own uncommitted
changes on either branch, don't work around it — tell the developer to commit or stash manually
first. If a stash restore reports a conflict, surface it for manual resolution the same way as a
merge/rebase conflict above.

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

`python3 dev-tools/test_mixed_mode_two_branches.py` runs its unit tests (pure decision logic plus
git-mechanics integration tests against real temporary repos — no gradle/FDB required, runs in
a few seconds). Run this after modifying `dev-tools/mixed_mode_two_branches.py`.
