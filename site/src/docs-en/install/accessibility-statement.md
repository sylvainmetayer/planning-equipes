---
twin: installer/accessibilite
---
# Publish your accessibility statement

If your organisation is subject to the digital accessibility obligation, fill in the four `LEGAL_ACCESSIBILITE_*` variables: the application then publishes its statement at `/declaration-accessibilite`, linked from the footer, the drawer and the legal menu of the staff espace. This page also says where the application stands (RGAA 4.1 standard, audit carried out, automated checks, open points), which is what you can base your declaration on; the writing conventions for developers are in [`developpement.md`](../comprendre/demarrer.md#accessibilité).

## Fill in the statement

Article 47 of law no. 2005-102 requires a statement from public bodies and from companies above a turnover threshold. **The obligation falls on the organisation that deploys the application**, not on the project: only the organisation knows how far its instance has been audited, when, and where to receive a report. The `/declaration-accessibilite` page shows what the deployment configures, and says "not provided" for the rest.

| Variable | Content |
| --- | --- |
| `LEGAL_ACCESSIBILITE_ETAT` | `totale`, `partielle` or `non`; any other value reads as not provided |
| `LEGAL_ACCESSIBILITE_DATE_AUDIT` | the date of the audit the status rests on |
| `LEGAL_ACCESSIBILITE_CONTENUS` | the inaccessible content, and why; a line break makes a paragraph |
| `LEGAL_ACCESSIBILITE_SIGNALEMENT` | where to report a defect; if empty, the contact address of the legal notice (`LEGAL_CONTACT`) |

## The standard and the scope

The standard is **RGAA 4.1**, which transposes the A and AA criteria of
WCAG 2.1. Three scopes, each with its own audience:

| Scope | Audience | Read on |
| --- | --- | --- |
| Staff espace (4 screens) | ~150 people outside the organisation, including **minors**, with no known equipment | a phone, from a link received by e-mail |
| Public pages (`/login`, legal notice, privacy, terms of use, accessibility statement) | anyone who arrives without going through the rest | any device |
| Administration (about fifty screens) | the organising team | a desktop computer |

The staff espace is the only scope that is truly for the general public: it is
handled first and exercised by the tests on a phone viewport.

## What has been audited

A static criterion-by-criterion audit was carried out on all the templates,
completed by four tooled measurements: the accessibility rules of
`angular-eslint`, a contrast ratio calculation on the colours written
by this repository, a structural sweep of the templates (headings, `caption`, `scope`,
`role`, `tabindex`, event handlers), and a probe of the actual
behaviour of `<mat-icon>`. All the gaps it found have been fixed:

| RGAA theme | Gap found | Fix |
| --- | --- | --- |
| 1 — Images | icon `aria-label`s never read out (`MatIcon` hides them) | `aria-hidden="false"` hard-coded, checked by `icon-labels-check` |
| 3 — Colours | brand bar at 3.24:1; deployment accent with no light-mode ceiling | `#007eb2` (4.54:1); OKLCH lightness bounded in both schemes, measured by `branding.spec.ts` |
| 5 — Tables | `role="grid"` without keyboard, tables without `caption`, `scope` missing from `mat-table`s | real grid or native table; `caption`; `scope` checked by `table-headers-check` |
| 7 — Scripts | drag and drop with no keyboard equivalent; unreachable pivot cell; single-key shortcuts | "Move … to" dialog; roving tabindex; shortcuts switch |
| 7.5 — Status messages | about thirty inert errors and verdicts | `app-status-message`, checked by `status-messages-check` |
| 9 — Structure | screens without `<h1>`, `mat-card-title`s that are not headings | checked by `headings-check` |
| 12 — Navigation | staff espace without skip link or focus restoration | skip link and `<main>` refocused in both shells |
| 13 — Consultation | unannounced new windows; untagged PDFs; no statement | `NewWindowLink` directive; PDF title and language; `/declaration-accessibilite` page |

Compliant without correction, and recorded so that a later audit does not
look into it again: the **forms** (theme 11: one `mat-label` per
field, errors in `mat-error`, `autocomplete` where the data is the user's
own), the absence of **frames** and **multimedia**, the **mandatory
elements** (`<html lang>` rewritten with the catalogue, one
`<title>` per route), the **presentation** (no `user-scalable=no`, a single
`outline: none`, offset by a `:focus-visible`) and the **time limits**
(none imposed).

## What keeps the gaps from coming back

Each family of corrected gaps has its CI check, modelled on the repository's
structural tests: it says which line to write, and every exception is
argued.

| Check | Where | What it refuses |
| --- | --- | --- |
| 12 a11y rules of `angular-eslint` | `npm run lint` | the eight families of regressions the template linter sees (click without keyboard, positive `tabindex`, `autofocus`…) |
| `icon-labels-check` | `frontend` job | an informative icon that is hidden, or has no name |
| `headings-check` | `frontend` job | a routed screen without exactly one `<h1>`, a `<mat-card-title>` element |
| `status-messages-check` | `frontend` job | an error or a verdict in an element without a `role` |
| `table-headers-check` | `frontend` job | a `<th>` without `scope` |
| `new-window-check` | `frontend` job | a `target="_blank"` link that does not announce it |
| `e2e/accessibilite.spec.ts` | `e2e` job of the `tests.yml` workflow | any *serious* or *critical* axe-core anomaly outside the baseline, on the representative screens (the four screens of the staff espace are replayed on a phone) |

The axe spec measures what no linter sees: contrast once the themes
are applied, regions, accessible names computed at run time. It starts from a
**frozen baseline**, screen by screen (`LIGNE_DE_BASE`), which can only
shrink: an axe-core rule that no longer shows up makes the spec fail until it
is removed from the list.

## What remains open

- **No user journey has been played under a screen reader** (NVDA, JAWS, VoiceOver).
  Reading-order and verbosity defects are covered neither by the static
  audit nor by axe. A declaration of compliance requires a human audit on the full
  grid.
- **The PDFs** carry a title and a language, but **are not tagged**:
  OpenPDF builds no structure on what `Document.add` writes, and a
  PDF declared as tagged on an empty tree reads as a blank page, worse
  than an untagged PDF, which reads as a text stream. The staff espace is the
  accessible version of the same schedule (criterion 13.3, "if necessary").
- **The redirect to `/login` on an expired session is silent** and
  loses a form being filled in. No time limit is imposed,
  but session expiry acts as one in practice.
- **The `--mat-sys-*` tokens** of `mat.theme()` are not recalculated: they
  are deemed compliant by construction, and only axe measures them once
  applied.
