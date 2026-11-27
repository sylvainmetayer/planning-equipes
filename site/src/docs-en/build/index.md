---
twin: comprendre/index
titre: Understand how it is built
pour: "For anyone who develops, integrates or audits: the model, the rules, the API, the build."
docs:
  - overview
  - edition-partitioning
  - access-and-accounts
  - background-jobs-and-notifications
  - frontend
  - how-the-schedule-is-solved
  - data-model
  - stand-hours-and-windows
  - plan-locks-and-publication
  - legal-and-quality-settings
  - ad-hoc-exceptions
  - why-these-rules
  - legal-rules-and-breaks
  - rest-and-consecutive-days
  - coverage-and-comfort-rules
  - weight-and-disable-rules
  - add-a-rule
  - api-conventions
  - api-solve
  - api-schedule-and-publication
  - api-rules-and-diagnosis
  - api-reference-data
  - api-stand-hours
  - api-input-warnings
  - api-event-day-and-staff
  - api-feasibility-and-breaks
  - api-staff-espace
  - api-integrations
  - mcp-connection
  - mcp-tool-conventions
  - mcp-tools
  - scenario-yaml
  - csv-imports
  - exports-and-archives
  - restore-an-edition
  - getting-started
  - tests
  - scenario-tests
  - load-tests
  - tune-the-solver
  - ci-and-delivery
  - renovate-and-dependencies
  - repository-conventions
---
# Understand how it is built

Planning Équipes is a Quarkus application that embeds the Timefold solver, an Angular interface and a PostgreSQL database, all in a single service. These pages show how the pieces fit together, why each rule exists and what a change must not break.

If you are new to the code, read [the overview](vue-d-ensemble.md), [how the schedule is solved](comment-le-planning-est-calcule.md) and [the data model](modele-des-donnees.md) in that order. Otherwise, start from the question you have.

## I am discovering the project

- I want to know how the code is organised: [The stack and how the code is split](vue-d-ensemble.md).
- I want to know how editions stay separate: [One edition, one partition of the data](cloisonnement-par-edition.md).
- I want to know who can do what: [Accounts, roles and access](acces-et-comptes.md).
- I want to know what runs without being launched, and what goes out by e-mail or webhook: [Background jobs and notifications](taches-de-fond-et-notifications.md).
- I am working on the interface: [The Angular frontend](frontend.md).

## I want to understand the solve

- [From problem to plan: the solver and its two engines](comment-le-planning-est-calcule.md)
- [The data model and its invariants](modele-des-donnees.md)
- [Hours, consignes and effective windows of stands](horaires-et-fenetres-des-stands.md)
- [Locks, freeze, re-planning, swaps and the published plan](plan-verrous-et-publication.md)
- [Shifts, legal settings, meals and quality](parametres-legaux-et-qualite.md)
- [Ad hoc exceptions and their contradictions](exceptions-ad-hoc.md)

## I want to know why a rule exists, or add one

- [The solver's rules and their catalogue](pourquoi-ces-regles.md)
- [Breaks, meals and legal durations](regles-legales-et-pauses.md)
- [Rest, weeks and consecutive days](repos-et-jours-d-affilee.md)
- [Coverage, fairness and comfort](regles-de-couverture-et-de-confort.md)
- [Weighting a rule, switching it off](doser-et-desactiver-les-regles.md)
- [Adding a rule](ajouter-une-regle.md)

## I call the API or connect an assistant

- [Edition, authentication and errors](api-conventions.md)
- [Starting, following and stopping a solve](api-calcul.md)
- [Consignes, publication and reference data freeze](api-planning-et-publication.md)
- [Rules, explanations and repairs](api-regles-et-diagnostic.md)
- [The reference data](api-referentiels.md), [their hours](api-horaires-des-stands.md) and [input warnings](api-avertissements-de-saisie.md)
- [Event day and the wall display](api-jour-j-et-animateurs.md), [feasibility and breaks](api-faisabilite-et-pauses.md), [the staff espace](api-espace-animateur.md)
- [Webhooks, weather, journal, exports](api-integrations.md)
- [Connecting an AI assistant](mcp-connexion.md), [the MCP tool rules](mcp-conventions-des-outils.md) and [the tools](mcp-outils.md)

## I load or export data

- [The YAML scenario file](scenario-yaml.md)
- [CSV imports](imports-csv.md)
- [PDF, ICS, CSV and end-of-event archive](exports-et-archives.md)
- [Starting from an edition, restoring after an import](restaurer-une-edition.md)

## I contribute

- [Setting up the environment and running](demarrer.md)
- [Tests](tests.md) and [load scenarios](tests-de-scenarios.md)
- [Load testing a public demo](tests-de-charge.md)
- [Tune the solver](regler-le-solveur.md)
- [Continuous integration and delivery](ci-et-livraison.md), [Renovate and licences](renovate-et-dependances.md)
- [Repository conventions](conventions-du-depot.md)

## What lives in the repository

Architecture decisions are one page per choice in [`docs/decisions/`](repo:docs/decisions/README.md). The API contract is [`docs/schema/openapi.json`](repo:docs/schema/openapi.json). Diagrams are PlantUML sources in [`docs/diagrammes/`](repo:docs/diagrammes). [The project memory](repo:docs/memoire-du-projet.md) gathers the measurements and the abandoned leads behind some solver settings.

To host the application rather than modify it, see [Install it yourself](../installer/index.md); to use it, [The rules of the game](../regles/index.md).
