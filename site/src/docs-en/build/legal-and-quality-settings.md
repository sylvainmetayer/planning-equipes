---
twin: comprendre/parametres-legaux-et-qualite
---
# Shifts, legal settings, meals and quality

You will know what a shift is and what the grid checks about it, which settings are set by law and which by comfort, how a due break is calculated and deducted from effective work, and how a scenario file pins its own values.

## A grid is made of shifts

A timeslot is a **shift**: a slice of real work on which someone is assigned. There is no other kind of timeslot. The **opening span** (the opening day from end to end) no longer exists as a timeslot: an event knows its opening hours and projects them into shifts through its [typical days](modele-des-donnees.md#les-journées-types-génèrent-les-créneaux), and the automatic splitting that used to slice an opening span is gone ([decision 0037](repo:docs/decisions/0037-une-grille-est-toujours-des-vacations.md)). Likewise, the "relay families" (several staggered variants of each shift) have been removed ([decision 0029](repo:docs/decisions/0029-retrait-des-familles-de-relais.md)): a grid carries a single variant of each shift and any stand sits on any open timeslot.

### What the grid check reports

`CreneauGridService` (`GET /api/creneaux/controle`) sorts its anomalies into errors (the grid is malformed or guarantees a breach of a hard rule) and warnings (suspicious, possibly intended, never a refusal). Three concern duration and meals:

| Anomaly | Severity | Raised when |
| --- | --- | --- |
| `VACATION_TROP_LONGUE` | Warning | The shift exceeds `dureeVacationMaxMinutes` (6 h by default, the threshold of art. [L3121-16]). It will contain a break that needs a relay: check that a colleague at the stand holds a seat at that time, or split the day in two |
| `REPOS_QUOTIDIEN_IMPOSSIBLE` | Error | The shift exceeds `1440 − reposQuotidienMinimalMinutes`: any assignment would produce a breach of a hard rule |
| `RELAIS_REPAS_HORS_FENETRE` | Warning | A `couverturePause` shift outside every meal window: staffing is halved there, yet no break can be taken in it |

A shift longer than 6 h is therefore not refused: it is legal if a colleague relays the person during the break. The threshold lives in `ParametresLegaux`, because it is an event rule judged on the grid the organiser has, however that grid was written.

### Overlap is a demand peak

Two consecutive shifts may overlap for the length of a handover: two seats then exist on the same stand, and staffing is guaranteed as a temporary surplus, never a shortfall. Two shifts of the same day that overlap are therefore never an anomaly. It is also the main source of peaks: **if all stands hand over at the same moment, the number of seats to fill doubles at that exact moment.** This doubling can exceed the staff available at that moment: the problem is then infeasible by construction, without any shortage of staff. The lever is to spread the handovers so they do not all fall on the same minute.

The feasibility analysis (`GET /api/feasibility`) **does not see this peak**: it is an optimistic estimate, aggregated per day. It does not read skills, and counts a minor only where they can sit (next to an adult, outside their night, on a day that is not a public holiday and at a stand not restricted to adults). It may answer "feasible" while the solver does not bring the hard score to zero. After a solve, the reliable signal is the **actual hard score**, not `feasible` alone.

### Covering a stand during the meal

Three ways to handle lunchtime, each written by hand on the grid (timeslot form or typical day):

| What you want | How you write it |
| --- | --- |
| The stand closes | No shift over the window |
| Full staffing during the break | An ordinary shift over the window |
| Reduced staffing | A shift marked `couverturePause` |

`couverturePause` generates only **half of the open segment's staffing, rounded up** (`Creneau.siegesSegment`). Rounding up means a stand run by a single person keeps that person instead of closing: closing remains an explicit decision, not a side effect of integer division. A full shift over the meal window should be handled with care on a scenario that is already tight: one more team per break on every stand concerned, concentrated on the same window, is exactly the peak described above.

### A single seat count

The number of seats of an open segment (at least one, half rounded up on a break-cover shift) is `Creneau.siegesSegment`, and a stand's busiest segment on a timeslot is `Creneau.siegesSimultanes`. Seat generation, the feasibility analysis, the fragility analysis and the `eviterRoulementStandsPremium` rule all read these two methods: what the solver must fill and what the screens announce cannot be two different numbers.

## The legal settings

`ParametresLegaux` is a problem fact (`@ProblemFactCollectionProperty` on `PlanningEvenement`, like `ContrainteAdHoc`), stored **per edition**, adjustable through `GET` and `PUT /api/parametres/legal` (the `legal` section of the settings, next to `organisation`, `notifications` and `instance`; `GET /api/parametres` returns all four). It carries both the ceilings set by law and the rules the organisation sets for itself:

| Field | Default | Origin |
| --- | --- | --- |
| `dureeHebdomadaireMaxMinutes` | 48 h | French Labour Code (*Code du travail*) art. [L3121-20], public policy. Constraint `dureeHebdomadaireMax`, for adults |
| `dureeHebdomadaireMaxMineurMinutes` | 35 h | Art. [L3162-1]; art. [D4153-3] for ages 14 to under 16. Constraint `dureeHebdomadaireMax`, for minors |
| `reposQuotidienMinimalMinutes` | 11 h | Art. L3131-1 |
| `dureeVacationMaxMinutes` | 6 h | Threshold of the grid check (art. L3121-16) |
| `dureePauseMinutes` | 30 min | Legal floor of 20 min, see [the legal break](#la-pause-légale) |
| `coupureRepasMinutes`, lunch and evening windows | 60 min; 12:00-14:00; 19:00-21:00 | Organisation rule, see [the meal break](#la-coupure-repas) |
| `heureDebutSoiree` | 20:00 | Start of the evening for the fairness reading, an organisation rule. A minor's legal night remains carried by `Creneau` |

`ParametresValidator` refuses anything that crosses a public-policy ceiling, naming the article that grounds it: above 48 h or 35 h, a break under 20 minutes. A value more protective than the law stays free. A negative daily rest, a zero shift duration, a meal window missing either bound and a missing `heureDebutSoiree` are also refused, because "no evening" would empty a column of the fairness screen without a word.

The other legal thresholds are constants in the code, declared with their article in `LegalConstraints` and `PlafondsLegauxMajeurs`: daily duration (10 h), continuous work (6 h), weekly rest (35 h), days worked per week (6). These are public-policy minima and maxima that an administrator has no legitimate reason to relax.

The collective agreement for Animation (ÉCLAT, [IDCC 1518]) is not cited as a source: the organisation confirmed that it does not fall under this agreement, and the former citation of art. 5.2 was wrong (it deals with rest days). The Code alone grounds the values. The comment of the migration `V7__parametres_legaux.sql` still carries that citation: an applied migration is not modified, its fingerprint is frozen (`FlywayMigrationsFrozenTest`).

Two counting conventions:

- **Attachment to the week.** A timeslot is attached entirely to the ISO week of its start date (`Creneau.semaineIso`). A timeslot on Sunday from 20:00 to midnight counts in the week that is ending. The convention simplifies counting and stays conservative as long as night timeslots are short.
- **Opening span and effective work.** A timeslot's duration measures an opening span, whereas the articles cited concern effective work. The model represents no break inside a timeslot, so the two coincide, which amounts to assuming that no break is taken in it. That is why breaks are **gaps between two timeslots** or relays.

The two weekly constraints group the `PosteAffectation` by staff member and by **day**, then the days by ISO week, and penalise the excess in proportion to the minutes over. The intermediate per-day level is used to deduct the breaks due, whose calculation is daily.

### The legal break

The Code requires the break to be **real**, not to be scheduled: an organiser who relieves each staff member for thirty minutes inside a 13:00-20:00 shift complies with art. [L3121-16]. The application therefore knows two ways of taking a break, and no third ([decision 0048](repo:docs/decisions/0048-une-seule-regle-de-pause.md)):

- **a gap** of at least `dureePauseMinutes` between two shifts. It breaks the sequence (`PauseSurPoste.sequences`), so nothing more is due on either side;
- **a relay**: another staff member at the **same stand** holding a seat for the whole break (`PauseSurPoste.relayableBy`).

A break that is due and has neither is a **hard breach**, one point per break, carried by `travailContinuMax`, plus the eligibility-exclusion flat penalty for a minor. There is nothing to declare: a `pauseSurPoste` parameter used to exist and switched these two rules off, and it has been removed.

The duration is **a single setting**, `dureePauseMinutes` (section `parametres.legal` of a scenario): 30 minutes by default, floor of 20 (art. L3121-16), raised to at least 30 for a minor (art. L3162-3) by `ParametresLegaux.dureePauseMinutes(mineur)`.

**The calculation is written once.** `PauseSurPoste.dues(postes, parametres)` returns the breaks of one (staff member, day) pair: a sequence of `L` minutes beyond the ceiling `cap` owes `ceil((L − cap) / (cap + pause))` of them, the k-th one at the latest when the sequence reaches its k-th ceiling. The solver, the breaks report, the staff PDF and the ICS feed all read this method, so none of them can describe a break that another does not owe.

**The deduction is unconditional.** A break that is due is rest, not effective work (art. L3121-1), so it is subtracted from the opening span: daily and weekly ceilings, hours report, fairness, Staffing need, KPIs. Nothing is hidden by this: in a plan with no hard breach, every deducted break was indeed taken, and that is what the two hard rules guarantee. A 14:00-midnight day is thus worth 9 h 30 of effective work; 13:00-midnight is worth 10 h 30 and is still refused. The calculation lives in `EffectiveWork`. The places that still count the opening span, and why, are in [the legal rules and breaks](regles-legales-et-pauses.md).

## The meal break

`FenetreRepas` is a problem fact, **one per declared window**: the start, the end and the duration of the required break. It is read by `coupureRepasObligatoire` and `coupureRepasPlacementPrefere`, whose rule is in [the legal rules and breaks](regles-legales-et-pauses.md). One fact per window rather than one object carrying both: a day straddling noon *and* the evening produces **one breach per window**, each naming its hours, rather than an aggregate nobody can act on.

The values live in `ParametresLegaux` (`coupure_repas_*` columns), and `FenetreRepas.from(...)` projects them. The meal break is not a legal obligation but the rule the organisation sets for itself, judged on every grid: it is therefore set with the other rules. A [consigne](horaires-et-fenetres-des-stands.md#restater-la-coupure-repas-pour-une-date) (an official order, such as an arrêté, relayed to the whole event) can restate them for its date.

**A scenario is judged on the windows it declares**, not on those in the database: `ScenarioYamlReader` places them on the plan it builds, and the pure Java harnesses do the same. Without that, a grid would be cut with one set of values and scored with another. In production, `ReferenceDataService` reads the edition's row.

A window is discarded if its bounds are missing, if it is empty or inverted, if the duration is zero, or if it is **shorter than the break it requires**: nobody could satisfy it, and a rule that no assignment can reach would sanction an entry rather than a schedule.

## The quality settings

`ParametresQualite` is a problem fact of the same kind, for the thresholds that set a schedule's **comfort** and not the law. It is a `record`: a problem fact is immutable by nature, the solver reads it thousands of times per second and never writes it. The thresholds are **stored per edition** (`GET` and `PUT /api/parametres-qualite`); the `planning.contraintes.*` block of the configuration is only the default for an edition that has never opened the screen. Nothing imposes a floor here: a setting that makes a rule inert is a legitimate way of saying "not here".

| Field | Default | Default property | Constraint that reads it |
| --- | --- | --- | --- |
| `maxEmplacementsDistinctsParJour` | 3 | `planning.contraintes.max-emplacements-par-jour` | `limiterEmplacementsParJour` |
| `heureServiceTardif` | 22:00 | `planning.contraintes.heure-service-tardif` | `eviterFermeturePuisOuverture` |
| `heureServiceMatinal` | 10:00 | `planning.contraintes.heure-service-matinal` | `eviterFermeturePuisOuverture` |
| `reposSouhaiteApresServiceTardifMinutes` | 720 | `planning.contraintes.repos-souhaite-apres-service-tardif-minutes` | `eviterFermeturePuisOuverture` |
| `typologiesDistinctesMax` | 2 | `planning.contraintes.typologies-distinctes-max` | `limiterTypologiesDistinctesParAnimateur` |
| `joursConsecutifsMax` | 8 | `planning.contraintes.jours-consecutifs-max` | `maxJoursConsecutifsTravailles`, at every level |
| `vitesseMarcheKmH` | 4 | `planning.contraintes.vitesse-marche-km-h` | `trajetInsuffisantEntrePostes` |
| `facteurDetour` | 1.3 | `planning.contraintes.facteur-detour` | `trajetInsuffisantEntrePostes` |
| `toleranceTrajetMinutes` | 5 | `planning.contraintes.tolerance-trajet-minutes` | `trajetInsuffisantEntrePostes` |
| `toleranceArriveeGroupeeMinutes` | 30 | `planning.contraintes.tolerance-arrivee-groupee-minutes` | `arriveeGroupee` |
| `placementRepas` | `FIN_MIDI_DEBUT_SOIR` | none: set per edition | `coupureRepasPlacementPrefere` |

A few notes on reading it:

- an hour left **empty** reads as absent, not as midnight: this is how a deployment neutralises `eviterFermeturePuisOuverture`. A desired rest of zero, or at most equal to the legal floor, makes the rule inert: only what it asks beyond the legal rest is penalised;
- `typologiesDistinctesMax` counts over the whole edition, not per day, and all-rounders are exempt;
- `joursConsecutifsMax` is 8 because it is a feasibility condition more than a comfort: the rule requires a rest day in every window of `max + 1` days. A ceiling that is too tight with the hard form enabled leaves seats empty, and no weight buys what arithmetic forbids. Weekly rest remains held hard by `maxJoursTravaillesParSemaine` and `reposHebdomadaireMinimal` ([decision 0045](repo:docs/decisions/0045-le-niveau-de-la-regle-des-jours-d-affilee.md));
- `ParametresValidator` refuses only what would mean nothing: a ceiling below 1, a negative desired rest, a non-positive walking speed, a grouped-arrival tolerance outside 0 to 240 minutes.

`SolveRunner.prepareProblem` systematically overwrites this fact with the edition's value: a caller cannot loosen a quality threshold by sending it in the payload.

## Scenario sections

A scenario file can set its own settings, under `parametres:`: `legal` and `organisation` (the quality thresholds). The solve budget is no longer part of it: it is an instance setting, and an old file that carries it (`parametresSolveur`) is read and ignored, the import saying so. For `legal`, only the fields to override are given and the others keep the default **of the Java class**, never the value in the database: a scenario thus does not depend on what a previous scenario left behind. A file that does not carry the section takes the settings of the edition that imports it. In `organisation`, the two hours are read as they are (a section that names neither describes an edition where `eviterFermeturePuisOuverture` says nothing, a legitimate setting) and omitted numbers take their default.

Removed sections and keys are **refused by name** rather than ignored (`ScenarioBinder`): `parametresDecoupage` and `decoupageAuto` (splitting has gone: a grid of opening spans read as shifts would give 14-hour days without a word), and the keys `pauseSurPoste`, `pauseMinimaleEntreVacationsMinutes`, `dureePauseMajeurMinutes`, `dureePauseMineurMinutes` of `parametres.legal`, each with its replacement. The same mechanism refuses any unknown key and a `schemaVersion` newer than the one the version reads. The full formats are in [scenario-yaml](scenario-yaml.md).

<!-- Links to Légifrance. Each reference points to the search by article
     number, which always resolves to the version in force: a LEGIARTI
     identifier designates a dated version, and silently goes stale. -->
[D4153-3]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=D4153-3
[IDCC 1518]: https://www.legifrance.gouv.fr/conv_coll/id/KALICONT000005635177
[L3121-16]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3121-16
[L3121-20]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3121-20
[L3162-1]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3162-1
