---
name: add-page
description: Scaffold a new Angular route/page in planning-equipes's frontend (app/pages folder, route registration, sidenav entry). Use when the user asks to add a new page, view, screen, or route to the frontend.
---

# Add a new frontend page

This repo enforces **one route = one page = one block** (see `AGENTS.md`,
Frontend section). Adding a functional block always means a new route and a
new `app/pages/<block>/` folder — never a new section bolted onto an existing
page.

## 1. Confirm the block is genuinely new

Check `src/main/webui/src/app/pages/` and the existing routes in
`src/main/webui/src/app/app.routes.ts` first. If the feature is a variant of
an existing page's data, prefer extending that page; only scaffold a new one
if it's a distinct functional block.

## 2. Create the page folder

Under `src/main/webui/src/app/pages/<block>/`, create a standalone Angular
component (no `NgModule`). Match the conventions already used by sibling
pages (e.g. `stands`, `animateurs`, `creneaux`):

- Standalone component, `signal()` / `computed()` for state.
- New control flow (`@if` / `@for`) in the template, not `*ngIf`/`*ngFor`.
- Angular Material components for UI; use `--mat-sys-*` tokens for any custom
  CSS, don't hand-roll colors.
- If the page needs HTTP calls, go through `app/core/api.service.ts` — it's
  the only place doing HTTP. Don't call `fetch` directly from the component.
- If the page is read-only over solved-planning data, read from
  `app/core/planning-state.service.ts`; don't trigger a solve from a
  secondary page.

## 3. Register the route

Add a lazy-loaded route entry to `src/main/webui/src/app/app.routes.ts`,
consistent with the existing entries (lazy `loadComponent`).

## 4. Wire up navigation

Add an entry to the `mat-sidenav` navigation in `src/main/webui/src/app/app.ts`,
under the correct existing group (Planning / Reference data / Views) — or ask
the user which group fits if it's ambiguous.

## 5. Styling

Don't create a monolithic stylesheet. If the page needs styles beyond what
Material provides, add a new partial under `src/main/webui/src/styles/` and
`@import` it from `src/styles.css`, following the pattern of `pages.css`,
`calendar-month.css`, etc.

## 6. Tests

Add a Vitest spec (`*.spec.ts`) next to the component if it has non-trivial
logic (state transformations, computed signals) — favour testing that logic
over heavy component-rendering tests, per `AGENTS.md`.

## 7. Verify

From `src/main/webui`:

```
npm run build
npm test
```

Then confirm the route renders via `./mvnw quarkus:dev` (proxies `ng serve`)
if you can drive a browser.
