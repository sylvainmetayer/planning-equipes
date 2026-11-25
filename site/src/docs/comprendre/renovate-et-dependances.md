# Dépendances : Renovate et licences

Vous saurez comment les mises à jour de dépendances arrivent en PR, quels fichiers dérivés les workflows de rattrapage recalculent à la place de Renovate, et comment se tient l'inventaire des licences que l'image redistribue.

## Renovate

Renovate (`renovate.json`) couvre Maven, le wrapper Maven, Docker, les actions GitHub, npm et `mise.toml`, le lundi avant 6 h. Il attend sept jours après la publication d'une version avant de la proposer, et rien n'est fusionné automatiquement. Les règles qui valent d'être connues :

- **Les mises à jour mineures et correctives sont groupées** : dépendances de test, plugins de build Maven, actions GitHub, images de conteneur, dépendances frontend hors Angular.
- **Quarkus et Timefold sont groupés par écosystème**, et les paquets `@angular/*` dans une seule PR : une montée partielle casse le build. Ces groupes portent les labels `quarkus`, `timefold` et `frontend`, qui gouvernent les workflows lourds.
- **Ce qui est épinglé deux fois est groupé.** Maven (`mise.toml` et le wrapper), Playwright (le paquet `@playwright/test` et l'image du conteneur e2e), Quarkus (`quarkus-maven-plugin` et le BOM partagent `quarkus.platform.version`, et le `quarkus` de `mise.toml` rejoint le groupe). Dans les deux premiers cas un gestionnaire `custom.regex` lit la seconde épingle sous le nom de la première : le Maven de `mise.toml` comme l'artefact `org.apache.maven:apache-maven`, la balise de l'image Playwright comme le paquet npm `@playwright/test`. Lue comme une image, celle-ci n'avait pas de date de publication sur `mcr.microsoft.com`, et le délai de sept jours la retenait indéfiniment. Une dépendance, une date, une PR. `ToolchainPinsStructuralTest` refuse en outre une balise d'image qui ne serait pas la version verrouillée du paquet.
- **Les majeures de Java, PostgreSQL, victools et TypeScript passent par le tableau de bord** (`dependencyDashboardApproval`). TypeScript attend qu'Angular accepte la majeure : `@angular/compiler-cli` en borne la plage.
- **Une branche de maintenance `release/X.Y` ne reçoit que les correctifs de sécurité.** Renovate la suit (`baseBranchPatterns`) mais toute autre mise à jour y est désactivée ; seules les alertes de vulnérabilité y ouvrent une PR.
- **Les sujets de commit tiennent sous 72 caractères.** Pour Maven, où `depName` vaut `groupId:artifactId`, le sujet ne garde que l'artifactId (`fix(deps): update timefold-solver-core to v2.6.0`). Les PR Renovate passent par le même `check-commits.sh` que les autres.
- **Une image lancée par un `run:` de workflow** échappe au gestionnaire `github-actions`, qui ne lit que `uses:`, `container:` et `services:`. Un gestionnaire `custom.regex` la rattrape si l'image est dans une variable d'environnement précédée de son annotation, comme le fait le job Semgrep de `securite.yml` :

```yaml
env:
  # renovate: datasource=docker depName=semgrep/semgrep
  SEMGREP_IMAGE: semgrep/semgrep:1.185.0
```

Toute image porte une balise explicite, y compris dans `docker-compose.yml` : sans balise ou sous `latest`, Renovate n'a rien à proposer et la version installée dépend du jour du `pull`.

Ce que Renovate ne lit pas (le `node-version` d'`application.properties`, les `java-version:` des workflows, les `FROM` du `Dockerfile`) est tenu d'accord avec `mise.toml` par `ToolchainPinsStructuralTest` : une montée qui n'en fait qu'une partie casse le build en nommant le fichier en retard.

### Les fichiers que Renovate ne sait pas recalculer

Renovate change les versions puis recalcule les fichiers qui en dérivent. Quand ce calcul échoue (« Artifact update problem »), il pousse le seul fichier source, et trois workflows complètent la PR. Chacun suit le même découpage en deux jobs : le premier n'a que `contents: read` et exécute du code issu de la PR (plugins Maven, scripts npm), le second, qui détient le jeton d'écriture, ne voit aucun build. La poussée n'est jamais forcée : si Renovate a rebasé entre-temps, elle est refusée et le run suivant repose le commit. Une poussée faite avec `GITHUB_TOKEN` ne déclenche aucun workflow, donc chaque job relance lui-même Tests (E2E compris) et Sécurité par `workflow_dispatch`, et les workflows lourds sous les labels qui les gouvernent, après avoir annulé ce qui tournait sur le commit remplacé. `gitIgnoredAuthors` dans `renovate.json` évite que Renovate tienne la branche pour « modifiée » et cesse de la rebaser.

| Workflow | Ce qu'il recalcule | Piège |
| --- | --- | --- |
| `licences-renovate.yml` | `docs/licences-tierces.md`, qu'un bump fait échouer par construction | le job `test` le contrôle en premier, avant les tests |
| `artefacts-renovate.yml` | le verrou npm et le wrapper Maven, sur toute PR qui touche un `package.json` ou `maven-wrapper.properties` | voir ci-dessous |
| `angular-renovate.yml` | les migrations `ng update` d'une PR qui touche un `@angular/…` du `package.json` | voir ci-dessous |

**`artefacts-renovate.yml`.** Le verrou npm est recalculé à partir de l'existant, avec le npm du `packageManager`, en installant chaque paquet monté à la version que la PR propose, la borne basse de la nouvelle plage (`.github/scripts/renovate-proposed-versions.js`). Un recalcul nu prendrait la dernière version de la plage, plus jeune que les sept jours que Renovate attend ; `npm --before` ne convient pas non plus, car il cache aussi les versions du verrou publiées dans la semaine et reproduit l'`ERESOLVE … @angular/common@undefined` de Renovate. Le wrapper est régénéré par `maven-wrapper-plugin` sous Java 25, parce que `.mvn/jvm.config` porte des options que seul Java 24+ lit (`--sun-misc-unsafe-memory-access`) et qu'une règle `constraints` impose Java 25 à Renovate pour ce gestionnaire. L'inventaire des licences suit le verrou du frontend dans le même commit. Un vrai conflit de pairs, deux paquets d'un même monorepo montés dans deux PR, échoue en le disant : c'est la PR qu'il faut regrouper.

**`angular-renovate.yml`.** Renovate ne lance pas les schematics d'Angular, et son `postUpgradeTasks` n'existe qu'auto-hébergé. La PR est reconnue au diff du `package.json`, pas au nom de la branche : pour les mineures et les correctifs, le groupe « frontend dependencies » supplante le groupe « angular ». Le job installe la version proposée puis lance `ng update <paquet> --migrate-only --from <verrou de la base> --to <version installée>` pour `core`, `cli`, `cdk` et `material` (`--migrate-only`, car la cible est déjà installée et `ng update` répondrait « déjà à jour »). Les versions de départ viennent du verrou du point de départ de la PR (`merge-base`), pas de la pointe de la base. Le second job extrait le correctif à la tête exacte où il a été construit, vérifie qu'il ne touche que `src/main/webui/` et l'inventaire des licences, l'applique et pousse sans forcer ; un refus fait échouer le job, il ne promet pas un rejeu qu'un commit poussé avec `GITHUB_TOKEN` ne déclencherait pas. L'inventaire est régénéré dans le même correctif, et `licences-renovate.yml` s'efface sur les PR que ce workflow prend en charge (`.github/scripts/angular-bump.sh`, lu par les deux) : deux workflows qui poussent sur la même branche se refusent l'un l'autre, et un groupe de concurrence partagé ne l'empêcherait pas, GitHub ne gardant qu'une exécution en attente par groupe. Le workflow ne rejoue pas une migration déjà commitée sur la branche.

## L'inventaire des licences

L'image est distribuée sous AGPL-3.0-only, mais elle redistribue aussi ses dépendances, qui gardent leurs licences : MIT, BSD ou Apache demandent que leur notice voyage avec le binaire. L'[inventaire des licences](../installer/licences-tierces.md) est **généré**, jamais écrit à la main :

```bash
./mvnw license:add-third-party          # depuis la racine : le côté Java
cd src/main/webui && npm run licences   # fusionne les deux côtés dans le document
```

Côté Java, `license-maven-plugin` résout la fermeture des scopes `compile` et `runtime`, ce que l'image embarque dans `target/quarkus-app/`, et ramène les noms de licence à leur identifiant SPDX (six orthographes d'Apache-2.0 sinon, chaque POM écrivant la sienne). Le plugin n'est lié à aucune phase : résoudre tout l'arbre à chaque build coûterait une minute que personne n'a demandée. Côté npm, le script lit `package-lock.json` et rien d'autre : un verrou v3 porte le champ `license` de chaque paquet, donc ni `node_modules` ni outil supplémentaire.

Le job `test` refait les deux et compare au fichier commité (`npm run licences-check`) : une dépendance ajoutée, retirée ou relicenciée par une montée de version fait échouer la branche tant que l'inventaire n'a pas suivi. C'est le même cliquet que pour le contrat OpenAPI : un fichier commité que le dépôt ne produit plus est un fichier qu'on cesse de croire.

Deux limites. Le côté npm liste la fermeture des dépendances de production, donc un surensemble de ce que le bundle embarque (sous-estimer coûterait une notice manquante, surestimer coûte une ligne). Et les paquets du système de base de l'image (JRE, client PostgreSQL, distribution) n'y sont pas : ils relèvent du SBOM attaché à chaque image publiée.
