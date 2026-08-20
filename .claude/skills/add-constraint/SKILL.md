---
name: add-constraint
description: Scaffold a new Timefold constraint end-to-end (constraint method, ConstraintCatalog entry, unit test, docs/contraintes.md, hard-constraint regression test). Use when the user asks to add, create, or implement a new planning constraint for planning-equipes.
---

# Add a new constraint

Follow this checklist for every new constraint in this repo. Do not consider the
task done until all steps are complete — this mirrors the "Domain invariants"
section of `AGENTS.md`.

## 1. Clarify placement

Ask (or infer from the request) which family the constraint belongs to, in
`src/main/java/dev/sylvain/planning/solver/constraints/`:

- `AffectationConstraints` — seat/slot assignment mechanics (coverage,
  min/max effectif, double-booking).
- `LegalConstraints` — legal/regulatory rules (minor/adult status, rest time,
  max hours).
- `AdHocConstraints` — per-instance overrides (`ContrainteAdHoc`). The
  prescriptive types (`INDISPONIBILITE_FORCEE`, `INCOMPATIBILITE`,
  `AFFECTATION_FORCEE`) are always `HardScore`, same priority as legal/minor
  constraints — never demote these. `AFFINITE` is the one deliberate
  exception: a soft reward (issue #80) — never promote it to hard.
- `QualiteConstraints` — quality-of-assignment heuristics (skill/typologie
  match, continuity).
- `PreferenceConstraints` — soft preferences (animateur wishes, stand
  affinity).

Also decide the score level: `Hard` (must never be violated in a valid solved
plan), `Medium`, or `Soft`.

## 2. Implement the constraint method

Add one private method per constraint to the chosen `*Constraints.java` file,
following the existing style in that file (Timefold `ConstraintStream` DSL,
one method registered in the class's `defineConstraints`/constraint list
method). Read a couple of neighboring methods first to match naming and
penalty conventions before writing.

## 3. Register in ConstraintCatalog

Add a matching entry to
`src/main/java/dev/sylvain/planning/solver/ConstraintCatalog.java`
(business description served by `GET /api/constraints`). Every new constraint
must appear here — this is enforced by convention, not by the compiler, so
don't skip it.

## 4. Write the unit test

Add a test method to the matching
`src/test/java/dev/sylvain/planning/solver/constraints/*ConstraintsTest.java`
(Timefold `ConstraintVerifier`, no Quarkus/DB, shared `ConstraintTestBase`).
At minimum:

- one case that triggers the penalty,
- one valid case that doesn't.

## 5. Hard constraints only: extend the regression test

If the new constraint is `HardScore`, extend
`src/test/java/dev/sylvain/planning/solver/PlanningHardConstraintsTest.java`
so `solved.getScore().hardScore()` stays zero on the nominal scenario. Don't
consider the change done until this passes.

## 6. Update documentation

Add the constraint to `docs/contraintes.md` (French, matching the existing
table/section style — see "Documentation rules" in `AGENTS.md`). If the
constraint introduces a new business capability visible to end users, also
check whether `README.md` section *Fonctionnalités métier* needs a line —
but keep that section free of class names/file paths.

## 7. Verify

Run the targeted test class, then the full suite before wrapping up:

```
./mvnw test -Dtest=<TheConstraintsTest>
./mvnw test -Dtest=PlanningHardConstraintsTest
./mvnw test
```
