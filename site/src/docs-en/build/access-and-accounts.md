---
twin: comprendre/acces-et-comptes
---

# Accounts, roles and access

Several people administer the same instance, and the history has to say who did what. You will learn how an account, its rights and its role are derived, what each of the four roles opens, and which tests keep the repository from opening a new route to a role without someone having decided it.

The operator side (plugging in a directory, creating local accounts) is in [Connecting your directory](../installer/oidc.md) and [Choosing and protecting the sign-in](../installer/securite-acces-administration.md#les-quatre-portes-de-ladministration). The organiser side is in [Manage accounts and rights](../regles/comptes-et-droits.md). Headers, status codes and authentication routes are described in [the API conventions](api-conventions.md).

## The account and the right

Two global tables, outside any edition: one person serves every edition.

| Table | What it says |
| --- | --- |
| `compte` | The person: an address (unique, case-insensitive), an optional name, and `oidc_sujet` when a provider has vouched for them |
| `habilitation` | A right: a role, an edition (`NULL` for all) and an optional expiry |

Five rules hold the model together:

- Nothing is deleted. An account is deactivated (`desactive_le`), a right is withdrawn (`retiree_le`): the history can then say who held which right on which date. Deleting an edition withdraws, in the name of `systeme`, the rights that were specific to it.
- The role is code, the scope is data. `RoleHabilitation` is an enumeration, with no configurable permission matrix: each role has its own tests, and a matrix would open combinations nobody tests. A new role comes with a migration that widens the `CHECK` on `habilitation.role` and with the routes it opens.
- There is always one active administrator. Withdrawing the last `ADMIN` right in force, or deactivating the last account that holds it, answers `409`, atomically. You cannot deactivate your own account either.
- A right is not granted twice while the first one is in force.
- The history names the actor by account id, never by address or name; the screen joins the name at read time, as for a staff member. `habilitation.cree_par` follows the same rule.

No column in these two tables holds a secret: the sign-in adds its own through its own migrations.

## From token to role: the provider says who, the application says what

A session opens through an OIDC provider, through a local account (address and password, with a code received by e-mail by default) or through the `admin` account of the configuration. Whatever the door, the role never comes from the token: `CompteIdentityAugmentor` rereads the account from the database on every authenticated request and gives it exactly the roles of its rights in force, in lower case (`admin`, `organisateur`, `responsable_stand`, `rh`).

| Session | What happens |
| --- | --- |
| OIDC provider | Attached to the account by the issuer and subject pair. An unknown or deactivated account has no role, whatever the token says; nobody is created by signing in |
| Local account | The account is reread on every request: deactivating cuts the session on the next call, without waiting for the cookie to expire |
| `admin` account form, emergency door closed | The session loses its role: a cookie issued before the closing does not survive the incident |
| MCP API key | Passes as is |

Three consequences to know:

- Attachment. An account without a subject is claimed by the first session whose address matches and which the provider declares verified (`email_verified`). After that it belongs to that subject: the same address taken over by someone else at the provider opens nothing, and a change of address does not lock its holder out. `OIDC_REQUIRE_EMAIL_VERIFIED=false` is the exception for directories whose addresses are the organisation's own. ([ADR 0093](repo:docs/decisions/0093-connexion-par-un-fournisseur-oidc-et-secours-ferme.md))
- The `admin` account of the configuration is not a row. It is an implicit account that the history names as long as no account is linked to the session. Behind a provider, its form is closed by default (`409` on `/j_security_check`), and `ADMIN_LOCAL_SECOURS=true` reopens it. Local accounts, for their part, stay open: what is dangerous is a shared, anonymous password, not a password that belongs to one person. Entering `admin` on the local-account path opens a session with no role at all. ([ADR 0094](repo:docs/decisions/0094-comptes-locaux-par-mot-de-passe.md))

The price of rereading on every request (two short reads) is a deactivation that takes effect at once, with no cache.

## What each role opens

| Role | What it opens |
| --- | --- |
| `admin` | The whole instance: accounts (`/api/comptes`), webhooks, MCP key, backups, export and import of the whole database, the Debug screen, the solve machine, the sign-in log, the simulated clock, creating, renaming, duplicating, activating and deleting an edition |
| `organisateur` | Everything an edition carries, in the editions that its rights in force name (an "all editions" right covers them all), and nothing that belongs to the instance (`403`) |
| `responsable_stand` | `/api/responsable/*` and nothing else |
| `rh` | `/api/rh/*` and nothing else; added to the account's other rights |

An edition outside an organiser's rights is refused like a nonexistent edition (`EDITION_INCONNUE`, `400`) and is absent from the list of editions. Scenario import stays open to them, in their current edition only: an `edition:` section that designates another one, or one to be created, is refused.

On the configuration side there are four HTTP policies (`role-admin`, `role-gestion` for `admin` and `organisateur`, `role-responsable`, `role-rh` for `rh` and `admin`), of which the stand manager's and the HR one are doubled by `@RolesAllowed` on every method. MCP also accepts an OAuth token from the provider, under the same rights as the account ([MCP connection](mcp-connexion.md)).

### A route is not opened by oversight

In a resource open to the organiser, a new method is open to them too until someone marks it `@InstanceOnly`. Two tests close this blind spot.

`InstanceRoutesStructuralTest` classifies whole resources: to the organiser (per edition), to the public, to administrators (`@InstanceOnly`) or to the stand manager. `RoutesOuvertesStructuralTest` goes down to the route and keeps two versioned inventories in `src/test/resources/`, one route per line (`Class#method VERB /path`):

| File | What it lists |
| --- | --- |
| `routes-organisateur.txt` | Every route of a "per edition" resource that is not `@InstanceOnly` |
| `routes-responsable.txt` | The routes of `ResponsableResource` |

A route missing from both fails the test, which prints the line to add; a line that no longer designates any route fails too. Adding the line says "open to the organiser, on purpose"; otherwise you annotate the method `@InstanceOnly` and add it to `INSTANCE_ROUTES` in the first test. A sub-resource or an inherited route that the inventory would not see also fails the test.

## The stand manager: a read right

A `RESPONSABLE_STAND` right is granted for one edition and one or more of its stands, with an optional expiry. The account signs in like an organiser; the staff espace stays the staff members' own, because a profile's token is not a right on a stand, does not expire and is not withdrawn per edition ([ADR 0095](repo:docs/decisions/0095-le-responsable-de-stand-lit-son-stand.md)).

The role opens only `/api/responsable/*`. The rest of `/api/*` answers `403`, as to any account with no organiser right.

- Read-only. Both routes are `GET`s (`ResponsableProjectionStructurelleTest` refuses any other method) and answer `Cache-Control: no-store`.
- Active edition, published plan. The request names neither an edition nor a stand: `ResponsableService`, the only door to the seats, reads the active edition and the stands of the rights in force. An expired or withdrawn right, a deactivated account, an inactive edition all give the same empty view, which does not tell "not yours" from "does not exist".
- A projection, never the domain. `ResponsableView` carries the hours, the filled and empty seats, and a name only if `ReglagesResponsables` says so at that moment: the edition's default, a per-stand exception in either direction, each with an optional opening date. Never an address, a phone number, an age or a minor status; the test reads the record's types. The PDF sheet is built from the same view, never from a second read.
- Names are set on the administrator side (`@InstanceOnly`); the setting request deletes only the exceptions it names. An edition duplication copies these settings closed ("Shifts only").
- No foreign key to `stand`. The scope of a right lives in `habilitation_stand`, with no key to `stand` (as `habilitation.edition_id` has none to `edition`): importing a dump deletes then recreates the stands, and a cascade would erase the rights of the receiving instance. Wiping an edition therefore withdraws them explicitly.

`ResponsableResourceTest` sweeps every `GET` route without a path parameter of the published contract with a stand manager's token: none answers `403` wrongly, and no body contains the name of a staff member of another stand, the name of another stand, or, in "Shifts only" mode, any name at all.

## The HR right: the compliance report

An `RH` right is granted for a single edition (the database refuses an HR right with no edition) and opens the compliance report and the emergency contacts ([ADR 0096](repo:docs/decisions/0096-le-droit-rh-lit-le-rapport-de-conformite.md)). It adds to the other rights: an organiser without this right reads nothing of the report, and an HR user who is also an organiser keeps the organiser scope.

- The edition is in the path, never in `X-Edition-Id`: an account that is only HR is an organiser of no edition, and the header would be refused. `RhService` decides the right edition by edition; an edition without a right and an unknown edition answer the same `404`.
- The report reads the published plan (`409` with no publication) and applies the legal ceilings as the rules read them, whether or not the organiser switched the rule off. No birth date and no address: a name and the minor status.
- Consultations and exports are journalled by `RhService`, in the report's edition, and not by the response filter, which files under the edition of the header that these routes do not send; the two file routes are in `CatalogueActions`'s argued list for that reason.
- Never through MCP: the report is nominative.
- The contacts live in `animateur_contact`, not in `Animateur`: that class is a fact of the solver problem, serialised by the scenario, the exports, MCP and the colleagues' espace. A third party's data that must leave through none of them does not belong in the type they all read. The staff member reads and writes their own in their espace, never another's; a concurrent write answers `409 MODIFICATION_CONCURRENTE`; an edition duplication does not copy them. `ContactsEspaceTest` searches for the number entered in a colleague's espace, the profiles and the exported scenario, and finds it nowhere.
