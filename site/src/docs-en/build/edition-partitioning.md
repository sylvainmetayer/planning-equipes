---
twin: comprendre/cloisonnement-par-edition
---
# One edition, one partition of the data

No request can read or write another edition, and this is not a matter of discipline: the edition predicate is set by a single helper and checked by a test that reads all the backend's SQL. You will know how a request's edition is resolved, how a write travels through the code up to the commit, how two editions are compared without ever being mixed, and what changes when only one is active.

The initial choices, and what made them evolve, are in [ADR 0001](repo:docs/decisions/0001-cloisonnement-par-edition.md).

## The principle

All business tables carry `edition_id`, and their business identifiers a composite primary key `(edition_id, id)`. An edition is a complete set of reference data, a timeslot grid and a solved schedule: "Année 2025" and "Année 2026" coexist without anything flowing from one to the other.

The client designates its edition with the `X-Edition-Id` header, request by request. It is not a global state: two tabs work on two editions at the same time. Routes that work in no edition (list of editions, configuration, authentication) go without it; the HTTP detail is in [the API conventions](api-conventions.md).

## How a request's edition is resolved

`EditionContext` answers the only question every SQL query on the reference data asks: which edition am I in? It decides in this order:

1. an edition forced on the current thread by `EditionContext.executeIn`: a solve or a scheduled task writes in the edition it was launched for, even if the browser has changed edition since;
2. the edition of the staff espace token carried by the request;
3. the `X-Edition-Id` header, which must designate a known edition, otherwise `400 EDITION_INCONNUE`;
4. nothing else: without a header, `400 EDITION_REQUISE`.

There is no fallback to a "default edition". Answering "the first one" is exactly how one edition's data ends up in another: a tab left on a deleted edition, an assistant that forgot its argument. The frontend chooses its edition before any screen (`editionChosenGuard`).

Outside a request, `EditionContext` throws instead of guessing. Work that outlives its request, such as a solve or the notification scheduler, must name its edition through `executeIn`. Only a request context that no client opened (the MCP transport around a tool not targeted at an edition, the test harness) resolves to the active edition; a targeted MCP tool first refuses the call that lacks its `edition` argument.

![A write on the reference data, from the X-Edition-Id header to the commit](repo:docs/diagrammes/edition-requete.svg)

<sub>Source: [`diagrammes/edition-requete.puml`](repo:docs/diagrammes/edition-requete.puml).</sub>

## The predicate is set by a helper, checked by a test

`JdbcEditionScope` prepares each query with the current edition already bound to its first parameter (`prepareScoped`): the `edition_id = ?` predicate is written first and the other parameters count from 2. It also opens transactions (see below).

It is not the helper that guarantees the predicate, it is `IsolationEditionStructurelleTest`. It reads the SQL of the whole backend and fails on any query aimed at a business table without `edition_id`. It follows indirection: `static final` constant, local variable, concatenation, parameter of a private helper. Writing the query in a variable before preparing it therefore does not hide it from the test.

A form it cannot follow (`StringBuilder`, `String.join`, a constant of another class, a variable reassigned after its declaration) makes the test fail, unless it is listed in `INDIRECTIONS_ASSUMEES` with a written reason. Every table named by a prepared query that it resolves must be declared either partitioned (`TABLES_METIER`) or global with its motive (`TABLES_HORS_EDITION`).

The net has edges, which you need to know: SQL passed through a plain `Statement` (`DatabaseDumpService`, the temporary table of `EditionRepository`) is not anchored, and a query whose table name is itself a variable has no name to classify.

A new reference-data table follows the same convention and goes through this helper. There is one repository per family (`StandRepository`, `AnimateurRepository`…) rather than a single repository, so that the predicate stays auditable with a `grep` on the package. The only write that crosses all the families, replacing the reference data with that of a scenario, goes through `ReferenceDataImportRepository`, which borrows a connection and passes it to each repository.

`ReferenceData` is the narrow interface that the construction of a problem reads from the reference data, and nothing else. This is what lets tests that have neither container nor database build problems.

## A transaction is a `scope.write`

There is no transaction manager. Each `JdbcEditionScope.write` or `writeAndReturn` opens a transaction, commits or rolls it back, and restores the connection's autocommit before returning it to the pool.

A unit of work that must write everything or nothing (a stand with the game categories and the location it names, an import) does its writes under a single `write` and passes its `Connection` to the repositories and services that take one. A method that takes a `Connection` joins the caller's transaction; one that does not commits on its own. The rule is in the signature, and that is why `@Transactional` was not chosen: an interceptor would give the same call one meaning or the other depending on an annotation placed several frames up, and the scope's manual `commit()` would throw under JTA.

Narayana is nevertheless already on the classpath, and a prototype showed that six lines in the scope would make it a JTA participant. The study is closed without adoption, with its quantified safeguards, in [ADR 0028](repo:docs/decisions/0028-transactions-declaratives-narayana.md).

## Comparing two editions without mixing them

Comparing two snapshots, or the reference data of two editions (`EditionDeltaService`), requires no cross-edition query. Each side is read in its own edition, through `EditionContext.executeIn` and the ordinary services, then the comparison is done in memory.

It never matches two rows by their identifier: two editions number their records from the same counter, so that `A151` can designate two people ([ADR 0050](repo:docs/decisions/0050-identifiants-generes-par-edition.md)). For the same reason, no date is compared as is: the grid, and the dated windows or date-based rules of a stand, are aligned on the rank of the opening day. Constraint settings are compared by their effective value: a weight entered equal to the default is not a difference.

## Only one edition speaks to the outside

At most one edition is active (`edition.active`, partial unique index); none is a valid state, between two events ([ADR 0072](repo:docs/decisions/0072-une-seule-edition-active.md)). Every edition is born inactive (creation, duplication, import) and stays fully usable internally. Only the active edition publishes, sends e-mails to staff and opens the staff espace, the ICS feed and the wall display.

Four mechanisms carry this rule, so that a new path cannot forget it:

- a service method that sends a mail or publishes carries `@RequiresActiveEdition` (`service/edition/`), and the interceptor answers `409 EDITION_INACTIVE`;
- `NotificationDispatcher` drops any `Notification` of an inactive edition; `editionScoped` is an exhaustive `switch` there, so a new case does not compile until someone has decided;
- the token filters answer for an inactive edition exactly as for an unknown token;
- webhooks and the weather alert query `OutboundEditionPolicy`, and nowhere else ([background jobs](taches-de-fond-et-notifications.md)).

`ActiveEditionEmissionStructuralTest` fails on any `MailService` send or outward MCP tool that is neither guarded nor justified.

The "one solve at a time" lock is, for its part, global to the instance, not per edition.
