---
name: doc-sync-check
description: Check whether a code change in planning-equipes requires a matching documentation update (README, docs/*, ConstraintCatalog), per the ownership rules in AGENTS.md. Use before wrapping up a task, or when the user asks "did I miss a doc update?" or "is the documentation in sync?".
---

# Documentation sync check

This repo has a strict, intentional doc layout (see "Documentation rules" in
`AGENTS.md`). Use this checklist against the current diff (`git diff` /
`git status`) before calling a task done.

## Ownership table

Match what changed to the doc that owns it:

| Change | Doc(s) to update |
| --- | --- |
| New/changed REST endpoint (`api/*Resource.java`) | the published OpenAPI — refresh `docs/schema/openapi.json` with `npm run api-schema` from `src/main/webui`. `docs/api.md` only if there is a cross-cutting invariant or trap to record: it does **not** list endpoints |
| New/changed Timefold constraint | `solver/ConstraintCatalog.java`, which `GET /api/constraints` serves. `docs/contraintes.md` only for the *why* and the cost: it no longer enumerates the constraints |
| New business capability visible to end users | `README.md` section 2 (*Fonctionnalités métier*) — business language only, no class names, no file paths |
| New/changed **screen behaviour an operator must understand** (new button or workflow, changed meaning of a banner/badge/setting, new lifecycle rule like retention or locking) | the in-app help page: `src/main/webui/src/app/pages/aide/aide-content.ts` (+ its new keys in `public/i18n/messages.en.json`). The guide answers "how do I use this screen" in business language — update the section covering that screen, don't create a doc-like dump. Skip for invisible or purely technical changes |
| Change to Timefold model / invariants (`domain/*`) | `docs/domaine.md` |
| Change to backend/frontend module layout, new top-level package | `docs/architecture.md` |
| Change to CSV/dump/PDF/ICS import-export formats | `docs/import-export.md` |
| New command, CI job, tooling, Renovate behaviour | `docs/developpement.md` |
| New route/page | verify `docs/architecture.md` frontend section still matches if the page list is enumerated there, and add the page to the in-app help (`aide-content.ts`) |

## Rules to enforce

1. **No new root-level Markdown files** and no new `README.md` sections —
   everything technical goes under `docs/`, with a new row added to
   `docs/README.md`'s index table if it's a new file.
2. **No `CLAUDE.md` or `.github/copilot-instructions.md`** — `AGENTS.md` is
   the single memory file. If repo conventions changed, edit `AGENTS.md`
   directly.
3. **Don't create planning/notes/tracking Markdown files** in the repo at
   all (scratch notes belong outside the repo).
4. Docs are written in **French**, code/comments in **English**; domain
   identifiers (`Animateur`, `Creneau`, `TypologieJeu`, …) stay in French
   business vocabulary everywhere.
5. `README.md` must keep exactly its two sections (*Démarrer l'application en
   local*, *Fonctionnalités métier*) plus the optional intro/doc-index table
   — nothing else.

## How to run this check

1. `git diff --stat` (or the relevant file list) to see what changed.
2. For each changed file, map it through the ownership table above.
3. Grep the target doc to see if it already mentions the changed
   behaviour — don't assume it's missing just because you didn't write it
   this session.
4. Report a short list: doc already in sync / doc needs updating (and
   propose the update) / no doc impact.
