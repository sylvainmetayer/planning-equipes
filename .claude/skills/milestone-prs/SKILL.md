---
name: milestone-prs
description: Turn a GitHub milestone of planning-equipes-private into a set of pull requests on planning-equipes — read every ticket, ask the clarifying questions up front as options, group tickets of the same kind into as few PRs as makes merging easy, implement them, run a high-effort code review on each PR once it is open, and follow every PR until its CI is green. Use when the user asks to implement a milestone, a batch of tickets, or "create the PRs for milestone X".
---

# Milestone → pull requests

The user hands over a milestone (for example
`https://github.com/sylvainmetayer/planning-equipes-private/milestone/1`) and
expects every ticket in it implemented, reviewed and green. **The user merges;
this skill never does**, unless the user says "merge" in so many words.

## 1. Read everything before asking anything

- List the milestone's issues (`mcp__github__list_issues` on
  `sylvainmetayer/planning-equipes-private`, filtered by milestone) and read each one
  **with its comments** (`issue_read`, methods `get` and `get_comments`).
- For each ticket, note its acceptance criteria, the layers it touches
  (backend, frontend, CI, docs, migration), and every choice it leaves open.

## 2. Ask the questions, as options

Use `AskUserQuestion`, with the recommended option first and marked
"(Recommandé)". Ask about every point that would otherwise be a guess. At
minimum ask:

- **Grouping**: the proposed PRs, one line each: its tickets and why they
  belong together. Group tickets of the same kind (operations/CI, a backend
  capability, frontend views…), so that each PR has one reviewer profile and
  the PRs touch as few shared files as possible.
- **Stacking**, asked every time:
  - **linear stack** (recommended): each PR branches off the previous one and
    they merge in order;
  - **independent PRs on `main`**: only when the groups share no file.
- **Branch names and ticket references.** The defaults are:
  - branches: the session's designated branch for the first PR, then
    `<branch>-2-<slug>`, `<branch>-3-<slug>`…;
  - references: `Refs sylvainmetayer/planning-equipes-private#N` in the PR body
    only, never in a commit (AGENTS.md: commits carry no issue reference).
- **Every open choice of every ticket**, as its own question with options
  (one question per point, up to four per call, several calls if needed).

Do not start writing code before the answers are in.

## 3. Implement

- One worktree per PR (`git worktree add`), and one commit per ticket. Commits
  follow AGENTS.md:
  - French subject under 72 characters, with a conventional prefix;
  - `git commit -s`, with the `Co-Authored-By` trailer the session prescribes.
- Independent groups may be delegated to parallel subagents. Give each one:
  - its worktree and its own PostgreSQL database;
  - `-Dquarkus.http.test-port=0`;
  - a ban on running the full suite, on pushing and on opening a PR.

  Never run two full suites at once, and never run a full suite in a worktree
  whose branch you are about to switch: the machine is shared.
- Before each push, run the repository's own checks:
  - the full backend suite (the `sandbox-tests` skill when there is no Docker);
  - for the frontend, from `src/main/webui`: `npm run lint`, `format-check`,
    `i18n-check`, `i18n-check-modifies -- <base>`, `api-types-check`,
    `css-scope-check`, and `language-policy-modifies -- <base>` (it refuses a
    French TypeScript identifier outside the glossary);
  - a regenerated `docs/schema/openapi.json` when the API changed, and
    `docs/licences-tierces.md` when a dependency changed.
- Open each PR with its base branch (`main`, or the previous PR's branch when
  stacked). The body is in French and says what changes and why, how it was
  verified, and the choices the user made. It opens with a line such as
  « Pile n/N — base : #… » when the PR is stacked.

## 4. Review each PR once it is open

- For every open PR, launch a subagent on Opus 5.5 (`Agent` with
  `model: "opus"`), in a worktree on the PR head, that runs the `code-review`
  skill at level `high` on the diff against the PR's base. It only reports.
- Verify each finding against the code. Then fix it as a
  `fixup! <exact subject>` of the commit that introduced the code.
- Squash the fixups with `rebase -i --autosquash` and restack every upper PR
  with `git rebase --onto <new base> <old base SHA> <branch>`. Check that
  nothing else changed by comparing trees:
  `git rev-parse <commit>^{tree}`.
- Push with `--force-with-lease`, branch by branch.

## 5. Follow every PR until it is green

- Call `subscribe_pr_activity` on each PR. Arm a check-in with `send_later`
  about an hour out, and re-arm it silently while a PR is open.
- A stacked PR's `pull_request` workflows may not start after a force-push.
  Dispatch them on the branch with `actions_run_trigger`: `tests.yml`,
  `e2e.yml`, and `restauration.yml` when the scripts changed.
- On red CI, read the job log and root-cause it. Push a fix, or comment on the
  PR once saying why the failure is not this PR's.

  Two failures this repository has already produced:
  - state leaking between frontend spec files through `localStorage`;
  - a test that waits for a UI element the PR changed.
- **When the user merges a PR** (they merge by rebase, so the SHAs change):
  1. Rebase the next PR with `--onto origin/main <old head of the merged PR>`.
  2. Check that `main`'s tree equals the tree of the merged branch.
  3. Point the next PR's base at `main`.
  4. Push with lease.

## Traps already paid for

- Shell variables do not survive from one Bash call to the next. Write rebases
  with explicit SHAs: an empty variable turns `git rebase --onto A $old B` into
  a reset of the branch. Recover through `git reflog show <branch>`.
- Resolve a conflict in an append-only file (`json-contract.txt`,
  `messages.en.json`) by taking the union of both sides, never one side.
- A fixup that also touches code introduced by a *later* commit conflicts when
  autosquashed. Keep each fixup's diff within its target commit.
- A host blocked by the network policy (SonarCloud, a job log store) stays
  blocked even after the user opens it, until the session restarts. Say so
  instead of guessing the finding.
