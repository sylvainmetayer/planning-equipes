---
twin: installer/securite-roles-et-droits
---

# Knowing what each role can call

Each role of the application (administrator, organiser, stand manager, HR right) opens a precise scope of routes. You will know which, how far an organiser's right goes, and how the repository prevents a new route from opening to a role without someone having decided it. Rights are granted in Settings › Accounts; the accounts themselves come from [the OIDC directory](oidc.md) or from [local accounts](securite-acces-administration.md#les-quatre-portes-de-ladministration).

## What the organiser role does not open

The `organisateur` role opens `/api` only in the editions that its rights in
force name (an "all editions" right covers them all): an edition
outside its rights is refused like a nonexistent edition
(`EDITION_INCONNUE`, 400) and absent from the list of editions. It does not open
the instance routes, reserved to `admin` (403): accounts, webhooks, MCP key,
backups, export and import of the whole database, Debug screen, solve
machine, login journal, simulated clock, creation, renaming,
duplication, activation and deletion of an edition, comparison of two
editions. Scenario import remains open to the organiser, in their current edition
only: an `edition:` section that designates another one, or one to
create, is refused (400). The inventory is kept by `InstanceRoutesStructuralTest` (resources) and by `RoutesOuvertesStructuralTest` (route by route, described above); a new unclassified resource or route fails the build. The `responsable_stand` role opens only `/api/responsable/*`
([ADR 0095](repo:docs/decisions/0095-le-responsable-de-stand-lit-son-stand.md), detailed below). The `lecteur` role (one edition) sees like an organiser, without the staff espace token, but the server refuses any write, any solve and any export of raw data; only PDFs and ICS go through ([ADR 0099](repo:docs/decisions/0099-le-lecteur-voit-sans-rien-ecrire.md), inventory: `RoutesLecteurStructuralTest`). `/mcp`
also accepts an OAuth token from the provider, under the same rights (see
[`mcp-connexion.md`](../comprendre/mcp-connexion.md)); its API key remains. Local accounts with a password, open
even behind a provider, are described in
[local accounts](comptes-locaux.md) and
[administration login](securite-acces-administration.md#les-quatre-portes-de-ladministration).

## Classifying routes by role

`InstanceRoutesStructuralTest` classifies whole resources: to the organiser
(per edition), to the public, to administrators (`@InstanceOnly`) or to the
stand manager. In a resource open to the organiser, a
**new method is open to them too** as long as nobody marks it
`@InstanceOnly`: this is the blind spot that `RoutesOuvertesStructuralTest` closes.
It holds two versioned inventories, one route per line
(`Class#method VERB /path`, the verb and path telling two
overloads apart); a sub-resource or an inherited route, which the inventory would not
see, fails the test:

| File (`src/test/resources/`) | What it lists |
| --- | --- |
| `routes-organisateur.txt` | Every route of a "per edition" resource that is not `@InstanceOnly` |
| `routes-responsable.txt` | The routes of `ResponsableResource` (read-only) |

A route absent from both fails the test, **which prints the line to add**;
a line that no longer designates any route fails too. Adding the line says
"open to the organiser, on purpose"; otherwise, annotate the method `@InstanceOnly`
(and add it to `INSTANCE_ROUTES` of the first test).

## The stand manager: a read right, not one more door

An account (OIDC or local) can carry a `RESPONSABLE_STAND` right, granted in
Settings › Accounts: **one edition, one or more of its stands**, an optional
expiry. It gives the `responsable_stand` role, which opens only
`/api/responsable/*`; `/api/*` outside that prefix answers `403`, like
any account with no organisation right (`role-gestion` does not
contain it), and `@RolesAllowed` doubles the HTTP rule on each method.

- **Read-only**: both routes are `GET`s
  (`ResponsableProjectionStructurelleTest` refuses a method that is not
  one).
- **Active edition only** (ADR 0072): the request names neither edition nor
  stand (no `X-Edition-Id` header). The service reads the active edition and the
  stands of the account's rights in force, and returns an empty view when nothing
  opens (right expired, withdrawn, account disabled, edition inactive): the
  response does not tell "not yours" from "does not exist".
- **A projection, never the domain**: hours, filled and empty seats
  and, depending on the setting, first name and surname. No address, phone, age or
  minor status; `ResponsableProjectionStructurelleTest` reads the types of the
  record. The plan read is the **published** plan, never the working plan.
- **Nothing is cached**: both routes answer `Cache-Control: no-store`.
- **Only administrators set the visibility of names** (`@InstanceOnly`).
- **The sheet follows the same setting**: it is built from the
  same view, not from a second read.
- `ResponsableResourceTest` sweeps all the `GET` routes **with no path
  parameter** of the published contract with the token of a manager of stand S: none answers `403` wrongly, and
  no body contains the name of a staff member of another stand, the name of another
  stand or, when the setting says "hours only", any name at all.

## The HR right: the compliance report, one edition at a time

An account can carry an `RH` right, granted in Settings, *Accounts* tab,
**for one edition** (the database refuses an HR right with no edition). It gives the
`rh` role, which opens only `/api/rh/*` (`role-rh`, also open to the `admin` role);
the whole rest of the API answers `403`. It adds to the other rights of the
account: an HR person who is also an organiser keeps the organiser scope, and an
organiser with no HR right reads none of this.

- **The edition is in the path**, never in `X-Edition-Id`: an account that
  is only HR is organiser of no edition, and the header would be
  refused. `RhService` decides the right, edition by edition; an edition with no
  right and an unknown edition answer **the same `404`**.
- **Every consultation and every export (PDF, CSV) is journalled** by
  `RhService`, in the report's edition, not by the response filter, which
  files under the header's edition. The account identifier is written, never
  its address. The two file routes are in the argued list of
  `CatalogueActions` for that reason.
- **The report reads the published plan**, never a draft (`409` with no publication)
  and contains neither birth date nor address: a name, the minor status.
- **No MCP tool**: the report is named data, it never goes out to an assistant.
- **Emergency contacts and legal representatives** (`/api/rh/editions/{id}/contacts`)
  follow the same rules: HR right or administrator, one edition per right, indistinct `404`,
  reads and writes journalled by the service. Staff members read and write
  their own through their espace (`/api/espace-animateur/{jeton}/contacts`, code
  session required), never another's. The data lives in `animateur_contact`, not in
  `Animateur`: `ContactsEspaceTest` looks for the number entered in the espace in a colleague's espace,
  the records and the exported scenario and finds it nowhere.
- `RhResourceTest` covers: the organiser with no HR right (`403`), the HR person of another
  edition (`404`), the administrator (all editions), the absence of
  the edition header and the trace in the journal.
