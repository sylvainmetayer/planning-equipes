---
twin: comprendre/conventions-du-depot
---
# Repository conventions

You will know who decides formatting, which language is written where, how a PlantUML diagram enters a page and stays faithful to the code, how the stylesheets are kept, how several branches change the same files without a conflict, and where the showcase site and the sources of this documentation live.

## Formatting is decided by a tool

No review debates formatting: a tool decides, and you comply.

| Side | Tool | Command | Check |
| --- | --- | --- | --- |
| Java | `spotless-maven-plugin`, `palantir-java-format`, unused imports removed and sorted | `./mvnw spotless:apply` | `spotless:check`, bound to `validate`: every `./mvnw test`, `package` or `verify` refuses an unformatted file and says what to do (`quarkus:dev` is not affected) |
| Frontend | `prettier`, with the repository's `.prettierrc`, on TypeScript, CSS and scripts | `npm run format` | `npm run format-check` in CI |

Angular HTML templates are excluded on purpose: in a template, a blank between two inline elements is rendering, and prettier's reflow was changing one. The `template:` templates written as a literal in a component are protected the same way by `embeddedLanguageFormatting: off`.

The two commits that reformatted the existing code are listed in `.git-blame-ignore-revs`; `git config blame.ignoreRevsFile .git-blame-ignore-revs` makes `git blame` skip them. The repository merges by rebase: it is the SHAs that `main` received that must appear there, not those of the branch. `.github/scripts/check-blame-ignore-revs.sh` checks that every listed revision is an ancestor of `HEAD`.

## The language of the code and commits

The code conventions live in [`AGENTS.md`](repo:AGENTS.md), the file read by code assistants and, before them, by contributors. The agents' memories (`.claude/agents/`, `.github/chatmodes/`) derive from it: you modify the source, never the copies.

- **Comments and non-business identifiers are in English**, domain names in French (`Animateur`, `Creneau`, `PosteAffectation`). A verb or a common noun is always English, and so is the word order. `LanguagePolicyStructuralTest` reads the backend sources and refuses a comment block in French or a declared French name. The glossary in `AGENTS.md` sets the vocabulary of English prose: *timeslot* for `Creneau`, *seat* for `PosteAffectation`, *shift* for `Vacation`, *opening span* for `Amplitude` (two false friends: *vacation* and *amplitude* mean something else in English).
- **A JSON key is a contract.** `JsonContractTest` freezes the keys of each exposed type in `src/test/resources/json-contract.txt`: renaming an accessor or a record component moves a public key, and this test makes it loud.
- **A test you touch gets an English method name.** The old French names are left as they are, the debt is paid where the work is done.
- **Public links go through `ApplicationLinks`**, never through concatenation: a URL printed in a mail or a PDF targets a route of the Angular SPA, not a JAX-RS path.
- **A refusal throws `BusinessError`**, and a resource never writes a `try/catch` for it: the sealed hierarchy carries the HTTP status and `BusinessErrorMapper` translates it.
- **The `ObjectMapper` is injected**, never `new ObjectMapper()`: a bare mapper lacks the modules Quarkus registers (JSR-310).
- **A commit message is short.** Subject under 72 characters, conventional prefix, no trailing period; a body only if it carries what the diff does not say. Subjects become the body of the release (git-cliff, `cliff.toml`) and the prefix decides the version, see [versions and releases](../installer/versioning.md). Two trailers are allowed, `Signed-off-by` and `Co-Authored-By`, no other: `.github/scripts/check-commits.sh` enforces these rules on every PR.

## PlantUML diagrams

Sequence, state and class diagrams live in [`docs/diagrammes/`](repo:docs/diagrammes/): a hand-written `.puml`, and next to it the `.svg` it produces, committed too. The page that owns the subject shows it under the paragraph it illustrates, with a link to its source (the site publishes the SVGs under `/docs/diagrammes/`):

```markdown
![Sequence of a solve launched from the reference data](repo:docs/diagrammes/solve.svg)

<sub>Source: [`diagrammes/solve.puml`](repo:docs/diagrammes/solve.puml).</sub>
```

After modifying or adding a `.puml`, regenerate the SVGs and commit both:

```bash
./mvnw validate -Pgenerate-diagrams
```

The profile calls only PlantUML (MIT build, test dependency: neither the image nor the licence inventory sees it). State and class diagrams declare `!pragma layout smetana`, the Java port of Graphviz, so no tool has to be installed besides the JVM.

[`DiagramsStructuralTest`](repo:src/test/java/dev/sylvain/planning/DiagramsStructuralTest.java) ties the diagrams to the code, in the same way as `DocumentationStructuralTest` does for prose: what a diagram names must exist.

| Rule | What makes it fail |
| --- | --- |
| The SVG is the rendering of its `.puml` | A `.puml` modified without regeneration. PlantUML embeds its source in the SVG: the test decodes it and compares it without redrawing anything, so without depending on the machine's fonts |
| Every diagram is shown by a page of `site/src/docs/` (a Markdown image, outside a code block) and every image shown exists | An orphan diagram, a link to a missing SVG |
| Every page that shows a diagram is in the paths of the Tests workflow | A page added without its line: a push touching only that page would not run the check above |
| A `participant`, or a `class`, `abstract class`, `interface` or `enum` box, named like a Java type is one | A renamed class. Roles that are not code are written `actor`, `boundary`, `control` or `database`, or carry a space or a dot in their name: they are not checked |
| A `method(…)` message sent to such a participant is declared by its type or one of its project supertypes | A renamed method. A label that is not a call does not start with `identifier(`; a double-headed arrow is not checked |
| A cited `VERB /api/…` route exists | A renamed or removed route |
| A state diagram that carries `' enum: Name` draws exactly the constants of that enum | A constant added and forgotten, a state that no longer exists |
| A `name : Type` field drawn in a box is a field declared by the class (or a component of its record); a constant drawn in an `enum` box is one | A renamed model field, a removed constant |

The test sees neither the order of the calls nor the meaning of a label: a diagram remains prose, to be reread when the flow it draws changes.

## Stylesheets

There are two kinds. `src/styles.css` imports the partials that every screen loads: scaffolding of cards, forms and tables, feedback, bulk actions, game category colours, fonts and brand. Each page carries in `styleUrl` what it alone draws, with `encapsulation: ViewEncapsulation.None`: the selectors stay global, and the file travels with the route's chunk instead of weighing on the initial bundle.

A class that several pages use goes in `pages.css`. `npm run css-scope-check` (from `src/main/webui`) refuses a page that uses a class that only another route loads, which nobody else would see since unit tests have no CSS, and a class that is defined but that no template or TypeScript literal uses: a dead rule is removed with the template that carried it.

## The showcase site

The presentation page, which also carries this documentation, is a separate Eleventy site in `site/`. It has its own dependencies (`site/package.json`, none of which enters the application), its workflow `.github/workflows/site.yml` and its publication by Cloudflare Pages. The workflow is filtered on `site/**`, on `docs/**` (the site publishes the diagrams and the licence inventory) and on `ConstraintCatalog.java`, whose Labour Code articles are reread by `site/test/articles.test.mjs`. The texts are edited in Pages CMS, configured by `.pages.yml` at the root of the repository.

`mise run site`, from the root, installs the dependencies and starts the Eleventy server. The commands, the tests, the shooting of the screenshots and the Cloudflare settings are in [`site/README.md`](repo:site/README.md), and the design system (tokens, fonts, rules, the "Documentation" part) in [`site/design.md`](repo:site/design.md), to be read before any change to `site/`. The scenario `vitrine-fete-du-jeu-de-pierrefeuille.yaml`, shipped with the others, is the backdrop of the screenshots: it does not change without redoing them.

## Files that several branches change

Several PRs open at the same time touch the same files, and every conflict costs one more merge and one more CI run. These files are cut so that two branches do not write the same lines.

| File | Shape | What holds it |
| --- | --- | --- |
| "Moving from 3.x to 4.0" guide | one section per file, in `site/src/docs/installer/passer-a-la-4-0/` and `site/src/docs-en/install/moving-to-4-0/` | the site build |
| Number of rules of the catalogue | never written in figures: `{{ regles.total }}` in a page, "every rule of the catalogue" in the code and `AGENTS.md` | `DocumentationStructuralTest` |
| `src/test/resources/migrations-empreintes.txt` | one line per migration, sorted by version | `FlywayMigrationsFrozenTest` |
| `docs/decisions/README.md` | one line per ADR, sorted by number, the table at the end of the file | `DocumentationStructuralTest` |
| `src/test/resources/json-contract.txt` | one key per line, `Type.key`, sorted | `JsonContractTest` |
| `src/main/resources/mcp-renommages.tsv` | one line per tool, sorted by former name | `McpRenommageTest` |
| `src/main/webui/public/i18n/messages.en.json` | sorted by id | `npm run i18n-check` |

The site counts `{{ regles.total }}` on the catalogue table of [Why these rules](pourquoi-ces-regles.md), which `DocumentationStructuralTest` holds equal to `ConstraintCatalog`.

The four lists of one line per entry are merged as a union of lines (`merge=union` in `.gitattributes`): two branches that each add a line no longer get in each other's way. A union can leave lines out of order, or two lines for one entry when both sides changed it: the test of each file refuses it, and the fix is made by hand. The English catalogue stays out of the union, because of the commas of JSON; a conflict there is resolved by keeping both sides, then `npm run i18n-check` reports the orphans:

```bash
jq -S -s '.[0] * .[1]' <(git show MERGE_HEAD:src/main/webui/public/i18n/messages.en.json) <(git show HEAD:src/main/webui/public/i18n/messages.en.json) > src/main/webui/public/i18n/messages.en.json
```

### Adding a section to the 4.0 moving guide

The page `passer-a-la-4-0.md` keeps only the introduction; each change is a file of the folder `passer-a-la-4-0/`, which the site appends to the page. The published page, its address and its anchors stay the same.

1. Create `site/src/docs/installer/passer-a-la-4-0/<topic>.md`. It opens on a level 3 heading and carries a numeric `ordre:` in its front matter:

   ```markdown
   ---
   ordre: 245
   ---

   ### The title of the change
   ```

2. Create its English twin in `site/src/docs-en/install/moving-to-4-0/`, under the **same file name** and with the same `ordre:`.
3. The sections are sorted by increasing `ordre:`, then by file name. The sections already there go in steps of 10, and "Data and migrations" carries 1000 to stay last: take a value between two tens, where the section must appear, so as not to cross another branch.
4. Write the links as in the page itself, relative to the `installer/` folder and not to the section's.

The build refuses a section without a numeric `ordre:` and an English page whose sections are not the French one's in the same order; the twin check compares the assembled pages.

### Migration numbers

Nobody reserves a migration number: a branch takes the next one of `main` when it pushes. If another PR takes it before the merge, the coordinator runs the renumbering script on the clean branch, right before merging it:

```bash
git fetch origin
python3 .github/scripts/renumeroter-migration.py origin/main
```

When the lowest migration the branch adds is no longer above the highest of `main`, the script renames them all, in their order, to the numbers that follow. It carries the change **into the lines the branch added only**: the file name and every `Vnnn`, the Flyway targets of the tests (`target("…")`, `fromVersion("…")`), the fingerprint, recomputed, and the order of the fingerprints file. A line of `main` that speaks of its own migration of the same number is not touched. It prints every change and leaves the whole to commit. It refuses, writing nothing, a modified tree, two added migrations under one number and an added line that names the `main` file of that number. Its test, `.github/scripts/test_renumeroter_migration.py`, plays it on a temporary repository in the `frontend` job.

## Where the documentation sources live

The site's pages are Markdown files arranged by part in `site/src/docs/`, which the Eleventy of `site/` reads at build and publishes under `/docs/`. Three parts, one folder each:

| Folder | For whom | Content |
| --- | --- | --- |
| `regles/` | the organiser, the stand manager, discovering the product | task guides, with no class, route or variable name, that name the screens as the interface does |
| `installer/` | whoever hosts the application | install, configure, secure, back up, update; `variables-environnement.md` is the complete inventory, checked against `application.properties` |
| `comprendre/` | whoever develops or audits | architecture, model, solver rules, API, MCP, import and export, build |

The `index.md` of each folder is the part's home. Its front matter (`titre`, `pour`, `docs:`) names it, says who it is for and lists its pages in reading order: a page missing from the list fails the site build and `DocumentationStructuralTest`. Adding a topic means adding a page to the right part and its slug to this index, not a file at the root or a README section.

A few writing rules, enforced by the site and its tests:

- **A page speaks to its reader** ("you"), carries the name of what they want to do and says nothing about the documentation itself.
- **A link between pages is relative** (`../installer/oidc.md`). A link to anything outside the site (code, ADR, OpenAPI contract, `AGENTS.md`) is written with the `repo:` prefix followed by the path from the root (`repo:AGENTS.md`) and becomes a GitHub link. A link to a nonexistent page fails the build, and so does an anchor not found.
- **A screenshot** is `![alt](/images/fr/x.webp "caption")`, in `site/src/images/`, and **a diagram** is written the same way, with `repo:docs/diagrammes/` in front of the `.svg` file name.
- **The API contract is the published OpenAPI**, refreshed by `npm run api-schema`, and `npm run api-types-check` fails when the frontend models drift. A new constraint is written in `ConstraintCatalog`, which `GET /api/constraints` serves: the pages explain why a rule exists and what it costs, never the list.
- **Architecture decisions** are one per file in [`docs/decisions/`](repo:docs/decisions/README.md), named `NNNN-short-title.md` and listed in its index. A decision answers why and is never rewritten: once revised, it receives a status and a link to the one that replaces it. Two safeguards make it publishable: no issue reference, and no client operating data (figures only if they can be reproduced on a versioned scenario of `src/main/resources/scenarios/`).

The `docs/` folder at the root keeps only what serves contributors and tools: `decisions/` (never published on the site), `schema/` (the OpenAPI contract and the scenario schema, generated), `diagrammes/` (PlantUML sources and SVGs), `licences-tierces.md` (generated, [published in the installation part](../installer/licences-tierces.md)) and [`memoire-du-projet.md`](repo:docs/memoire-du-projet.md), which keeps what the history carried: the measurements that settled a solver setting, the abandoned leads and the incident behind each safeguard.
