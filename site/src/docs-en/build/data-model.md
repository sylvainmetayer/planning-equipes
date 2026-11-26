---
twin: comprendre/modele-des-donnees
---
# The data model and its invariants

You know which objects carry the scheduling problem, what the application derives instead of storing, how a seat to fill is represented, and which rules of the model must not be bypassed when you add a feature.

The schedule is a *shift rostering* problem in Timefold's sense: staff to place on seats, under constraints, with a three-level score (`HardMediumSoftScore`). The classes and their fields can be read in [`domain/`](repo:src/main/java/dev/sylvain/planning/domain). This page carries what the code does not say. Class and field names stay in French business vocabulary (`Animateur`, `Creneau`, `PosteAffectation`), comments and non-business identifiers in English; the glossary is in [`AGENTS.md`](repo:AGENTS.md). On the organiser side, [the thread of an edition](../regles/fil-d-une-edition.md) presents the same objects.

![The Timefold model: solution, entity, variable](repo:docs/diagrammes/domaine.svg)

<sub>Source: [`diagrammes/domaine.puml`](repo:docs/diagrammes/domaine.puml).</sub>

## What the application never stores

Minor, adult or under-16 status is derived from `dateNaissance` **at the date of the timeslot** (`isMineurOn`, `isMajeurOn`). Three legal regimes depend on it and a boolean would drift out of sync.

The "all-rounder" flag is derived when the problem is loaded: a staff member is an all-rounder because their skills contain the game category marked `polyvalent`.

Game categories are rows of a CRUD reference set (table `typologie`), not a Java enum. `Stand.typologiesProposees` and `Animateur.competences` point to it through a foreign key, so you create or rename them without touching the code. A game category carries two more fields:

- an optional **quota**, `maxCreneauxParAnimateur`, the maximum number of timeslots a staff member holds in it over the whole edition; empty, there is no ceiling. The hard constraint `plafondCreneauxParTypologie` reads it. A seat counts for each game category that **its stand offers**, not for those its staff member masters. The quota is carried by the game category rather than by a fifth type of ad hoc exception ([decision 0042](repo:docs/decisions/0042-quota-par-typologie-sur-la-typologie.md));
- a free **description**, the organiser's note to themselves. It appears in the game categories form, in the record and in the "Planning, by typologie" view, and nowhere else: neither on a PDF, nor in the staff espace.

## A seat is a place

A `PosteAffectation` is created **per place to fill**, never one per stand × timeslot pair. An unfilled seat keeps `animateur = null`.

The planning variable declares `allowsUnassigned = true`: a place may stay empty during the search, and it is `posteDoitEtrePourvu` that makes filling it a hard requirement. In the constraints, `forEach(...)` **excludes** unfilled seats and only `forEachIncludingUnassigned(...)` sees them. `posteDoitEtrePourvu` uses the latter, and an `animateur != null` test after a `forEach` is useless.

On the event day, a place of a timeslot under way can be split at "now" into two seats (`suiteDe`) that remain a single place: see [the frozen past](horaires-et-fenetres-des-stands.md#le-passé-est-figé).

## The bounds of the edition are derived

An `Edition` carries neither dates nor an "ongoing" flag: it is a partition, not a calendar. The event runs from the **first to the last timeslot** of the edition, bounds included, and nothing else is authoritative. The derivation is written once (`JoursEvenement`, in `service/referentiel/`) and read by the availability collection as well as by the input warnings. Three consequences, all intended:

- a gap day between two timeslot dates (a Monday off between two weekends) is **inside** the event. The set of dates that carry a timeslot, for its part, serves the availability collection, which asks a different question;
- an edition with no timeslot has **no** bounds. Nothing in it is "outside the event" and the warnings stay silent: the alternative would be a false positive on the screen where an edition begins;
- adding or deleting a timeslot moves the bounds. A warning already displayed is not re-evaluated: it describes the moment of the write.

Being inside the event is not enough to be usable, and the gap is paid for on unavailability. The declaration form offers only the timeslot dates, and applying a declaration replaces `joursIndisponibles` as a whole. An unavailability set on a gap day is therefore erased at the first accepted declaration, without the staff member ever having seen it. The CSV import **rejects** that row; manual entry **warns** without blocking (`INDISPONIBILITE_JOUR_SANS_CRENEAU`). Two deliberate doctrines, never a silence.

## Day templates generate the timeslots

An edition describes each kind of day once ("Normal day", "Late opening", "Set-up") by its name and its shifts, then lays these day templates over a calendar of dates ([decision 0032](repo:docs/decisions/0032-journees-types-nommees-vacations-fixes.md)). It is the first object that carries dates independently of timeslots: an edition's dates are entered there, before the first timeslot.

The calendar is a **generator**, never the truth. Applying the calendar materialises the timeslots by difference on the key `(date, début, fin)`, the one the grid check calls a duplicate:

| Case | Effect |
| --- | --- |
| Identical timeslot | It keeps its id, its seats and its locks. Only its meal relay flag can change in place |
| Missing timeslot | It is created |
| Timeslot that the day template does not name, on a governed date | It is deleted with its seats, after a preview with figures |
| Date that the calendar does not assign | It is never touched |

Everything downstream (seats, locks, `JoursEvenement`) keeps reading the timeslots. The logic is pure (`JourneesTypesMaterialisation`) and the service only persists it.

Modifying a day template afterwards propagates nothing: its dates become "out of line" and the organiser applies again. **Recognition** goes the opposite way: it reads the day templates that a grid implies (each date with the same shifts and the same flags is the same day) and replaces day templates and calendar. It follows every replacement of the grid (scenario import, derivation from the stands' hours), so that an imported edition and a typed edition read the same on the Timeslots screen.

The timeslots that a [consigne](horaires-et-fenetres-des-stands.md#la-consigne-quatrième-couche) (an official order relayed to the whole event) added to the grid are neither counted as out of line nor deleted on apply: a day template never names them, they belong to the consigne, which removes them when it is lifted. The card shows these dates as "under consigne" (`datesSousConsigne`).

## Invariants not to break

- A seat is a place, never a stand × timeslot pair. A stand's minimum and maximum headcount is held by counting the seats filled per stand and timeslot, not by a dedicated constraint class.
- A stand has at least one game category. Without it only all-rounders could staff it, the tightest bottleneck of the model, born of an entry that nothing flagged. The write refuses it through every path (CRUD, bulk, MCP, import). An old row with no game category stays readable and solvable, and will be refused at its next save.
- Minor status is computed, never stored.
- `souhaits` is a `Set`, with no order or priority: do not turn it into an ordered list.
- A timeslot is a shift, a slice of work that can actually be assigned, never a raw opening span ([legal settings and grid](parametres-legaux-et-qualite.md#une-grille-est-faite-de-vacations)).
- Prescriptive ad hoc exceptions stay hard. Only affinity and grouped arrival are soft, by design ([ad hoc exceptions](exceptions-ad-hoc.md)).
- The bounds of an edition are derived from its timeslots.
- A place of a timeslot that has begun is taken from the saved plan and pinned by every solve; it counts in the rules and none of them holds it against anyone ([decision 0044](repo:docs/decisions/0044-le-passe-est-fige.md)).
- The staff espace never shows the working schedule: what a person sees is what they were sent ([published plan](plan-verrous-et-publication.md#le-plan-publié)).
- All timestamps are `timestamptz`.
- Domain names stay in French business vocabulary.

### What the database guarantees

`creneau` keeps its primary key `(id)`, so that published identifiers do not move, and carries a unique key `(edition_id, id)`. The keys that point to it are composite: a seat cannot point to another edition's timeslot. Those that can outlive the timeslot (a lock, an ad hoc exception) use `ON DELETE SET NULL (creneau_id)`: without a column list, the `SET NULL` would empty `edition_id` too. The landing of a solve never overwrites another edition's timeslot, an identifier conflict stops it.

Every reference to another object carries its key. The target of a directed swap (timeslot, stand) disappears with what it points to, like the timeslot and the stand of the request: a request whose target no longer exists has no meaning. The continuation of a split seat, the constraint that a teammate's declaration created, a staff member's notified plan and the pre-solve snapshot are emptied (`SET NULL`) when their target disappears. The last three keys are deferred, because a plan or a dump is written in one block.

Partitioning by edition and its safeguards are in [edition-partitioning](cloisonnement-par-edition.md); accounts and their rights in [accounts and roles](acces-et-comptes.md).
