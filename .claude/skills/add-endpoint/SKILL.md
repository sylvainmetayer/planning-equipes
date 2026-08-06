---
name: add-endpoint
description: Scaffold a new JAX-RS REST endpoint end-to-end for planning-equipes (resource method, service-layer call, docs/api.md entry, test, optional frontend ApiService wiring). Use when the user asks to add, create, or expose a new backend API endpoint.
---

# Add a new REST endpoint

This repo keeps a strict layering: JAX-RS resources in `api/` never hold
business logic — they delegate to a `service/` class. Follow this checklist
for every new endpoint; it mirrors the `add-constraint`/`add-migration`/
`add-page` skills and the doc-ownership table in `doc-sync-check`.

## 1. Place the endpoint

Check `src/main/java/dev/sylvain/planning/api/` for an
existing `*Resource.java` that already owns the relevant `@Path` root
(`PlanningResource`, `SolverJobResource`, `ReferenceDataResource`,
`ConstraintResource`, `CsvImportResource`, `DatabaseResource`,
`PlanningExportResource`, `PlanningHoursResource`). Add a method to the
matching resource; only create a new `*Resource` class for a genuinely new
`@Path` root that doesn't fit an existing one — read a couple of existing
resources first to match style (constructor injection via `@Inject` fields,
`@Produces(MediaType.APPLICATION_JSON)` at class or method level, `Response`
only when you need custom headers/status, a plain DTO/record return
otherwise).

## 2. Put the logic in the service layer

The resource method should be a thin delegate to a `service/` class
(`PlanningService`, `SolverJobService`, `ReferenceDataService`,
`CsvImportService`, `DatabaseDumpService`, `PlanningExportService`, or a new
one if the capability is genuinely new). Business logic, persistence access
and validation belong there, not in the `api/` method body.

## 3. Design the DTO

Prefer a small `record` for the request/response shape over exposing a
Timefold domain/planning entity directly, unless the resource already
returns entities elsewhere in the same file (match existing convention in
that resource). Keep field names in French business vocabulary if they mirror
domain concepts (`animateur`, `creneau`), English for purely technical
wrapper fields.

## 4. Consider personal-data exposure

If the endpoint returns `Animateur` fields (nom, prénom, date de naissance,
disponibilités) or another export of personal data, keep the response
minimal to what the caller actually needs — don't default to returning the
full entity. If in doubt, this is worth a pass from the `rgpd-conformite`
persona before merging.

## 5. Write the test

Add or extend a `src/test/java/.../api/*ResourceTest.java` (or the matching
`*IT.java` if it needs a real Quarkus/DB boot) covering the happy path and
the obvious error case (not-found, invalid input).

## 6. Update documentation

Add the endpoint to `docs/api.md` (French, matching the existing table/style
— see "Documentation rules" in `AGENTS.md`). If the endpoint changes
CSV/dump/PDF/ICS formats, also update `docs/import-export.md`.

## 7. Wire the frontend if needed

If the endpoint is meant to be called from the UI, add the call to
`src/main/webui/src/app/core/api.service.ts` — it's the only place doing
HTTP; components must never call `fetch`/`HttpClient` directly (see the
`add-page` skill for the rest of a new-page workflow).

## 8. Verify

```
./mvnw test -Dtest=<TheResourceTest>
./mvnw test
```

If the frontend was wired up:

```
cd src/main/webui && npm run build && npm test
```
