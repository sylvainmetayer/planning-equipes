# Les contrôles de sécurité qui tournent à chaque version

Des analyses automatiques protègent le code et l'image que vous déployez, à chaque poussée. Cette page dit ce qu'elles voient, ce qui bloque une publication et comment une exception se justifie ; c'est la page de l'auditeur ou du relecteur plus que celle de l'exploitant.

## Analyses automatiques

Ce qu'un outil trouve seul est vérifié à chaque poussée, pas découvert par un
audit. Chaque analyse **échoue dans le log** de la PR ; celles qui savent
produire un SARIF le publient en plus dans l'onglet *Security* du dépôt, où une
alerte se trie (corrigée, faux positif, risque accepté) avec sa raison. Le
dépôt étant public, cet onglet ne coûte rien.

| Analyse | Où | Ce qu'elle voit | Ses exceptions |
| --- | --- | --- | --- |
| Trivy, dépendances | `securite.yml`, job `dependances` | CVE corrigeables HIGH/CRITICAL de `pom.xml` et `package-lock.json` | `.trivyignore` |
| Revue des dépendances | `securite.yml`, job `revue-dependances` (PR seulement) | Ce qu'une PR **ajoute** : dépendance vulnérable (HIGH et plus, développement compris), licence non libre | dans le job |
| Semgrep OSS | `securite.yml`, job `code` | Motifs Java, TypeScript, OWASP, Dockerfile, workflows, configuration d'agent | `nosemgrep` site par site, voir ci-dessous |
| actionlint, zizmor | `securite.yml`, job `workflows` | Validité des workflows ; leur sécurité : injection de template, identifiants laissés par le checkout, cache empoisonnable, permissions | `.github/zizmor.yml`, chaque règle avec sa raison |
| hadolint | `securite.yml`, job `workflows` | Le `Dockerfile` et le shell de ses `RUN` | `# hadolint ignore=…` sur la ligne, raison au-dessus |
| Trivy, image | `docker-ghcr.yml` avant signature ; `securite.yml`, job `image`, chaque semaine | Paquets système de l'image : couche Ubuntu, JRE, `postgresql-client` | `.trivyignore` |
| CodeQL | configuration « par défaut » des réglages du dépôt | Flux de données d'une classe à l'autre (Java, TypeScript, Python, workflows) | dans l'onglet *Security* |
| OpenSSF Scorecard | `scorecard.yml` | Pratiques de la chaîne d'approvisionnement du dépôt, note publique (badge du README) | dans l'onglet *Security* |
| OWASP ZAP *baseline* | `tests.yml`, job `dast` | L'application démarrée vue sans compte : en-têtes, cookies, erreurs | `.github/zap-baseline.tsv` |
| GitGuardian | application GitHub | Secrets poussés | `.gitguardian.yaml` |

Le scan de l'image dans `docker-ghcr.yml` **barre la publication** : une image
qui porte une CVE corrigeable HIGH ou CRITICAL ne reçoit ni signature, ni
`:main`, ni numéro de version — elle reste sous son seul `sha-…`, pour le
diagnostic. Le cas ordinaire se règle en reconstruisant : le `Dockerfile`
applique les correctifs Ubuntu du jour (`apt-get upgrade`), mais cette couche
reste en cache tant que l'image de base `eclipse-temurin` ne change pas.
`docker-ghcr.yml` reconstruit donc `:main` **sans cache chaque dimanche**
(3 h UTC, la veille du scan hebdomadaire), comme sur un lancement manuel.
`:latest` et les versions n'en profitent pas — un tag de release n'est jamais
reconstruit : une CVE corrigée après une version se règle par une version de
correctif. Ce qui ne vient d'aucun
paquet n'en profite pas : `pebble`, binaire Go que l'image de base installe à
côté d'`apt` et que l'application ne lance jamais, est retiré par le
`Dockerfile` plutôt qu'attendu. Une version de release
est construite **sans aucun cache** (couches et `.m2`) : un cache GitHub peut
s'écrire depuis d'autres jobs du dépôt, et une version signée ne doit rien en
hériter.

**Ajouter une entrée à `.trivyignore`** (le fichier n'existe pas tant qu'aucune
n'est nécessaire) : l'identifiant de la CVE, et au-dessus, en commentaire, la
date, la raison — chemin de code non atteint, aucun correctif publié pour le
paquet de l'image — et la condition qui permettra de la retirer. Une entrée
sans date ne se relit jamais.

Deux protections sont des **réglages du dépôt**, pas des fichiers, et se
vérifient dans *Settings → Advanced Security* : le *secret scanning* avec la
*push protection* — qui refuse le `git push` porteur d'un secret, là où
GitGuardian ne le voit qu'une fois poussé — et le graphe de dépendances, que
lit la revue des dépendances. CodeQL aussi y vit, en configuration « par
défaut » : GitHub choisit les langages et lance l'analyse sur chaque PR, sans
workflow dans le dépôt. Un workflow CodeQL « avancé » ne peut pas coexister
avec elle — GitHub en refuse les résultats.

## Analyse statique : les suppressions et leur justification

Le job `code` de `securite.yml` (Semgrep OSS) fait échouer la CI sur toute
règle qui trouve, quelle que soit sa sévérité. Une règle atteint aujourd'hui
neuf endroits du code, tous pour la même raison, et tous annotés `nosemgrep`
**site par site** plutôt que désactivés en bloc : une nouvelle concaténation
SQL non annotée continue donc de faire rougir la CI.

`java.lang.security.audit.formatted-sql-string` signale toute requête
construite par concaténation. Dans ce dépôt, ce qui est concaténé est
**toujours un identifiant** — un nom de table ou de colonne —, et SQL ne
permet pas de lier un identifiant en paramètre : `SELECT * FROM ?` n'existe
pas. Ces identifiants viennent sans exception de littéraux écrits dans le code
appelant ou de listes constantes (`DatabaseDumpService.TABLES`), jamais d'une
entrée utilisateur ; l'import de dump vérifie même la sienne contre
`ALLOWED_TABLES`. Les **valeurs**, elles, voyagent toutes en paramètres liés
(`ps.setString`, `ps.setLong`, `ps.setObject`).

**Avant d'ajouter un `nosemgrep`**, vérifier les appelants : si un identifiant
peut venir d'une requête HTTP, c'est une injection SQL et non un faux positif.
C'est ce qui a été fait pour ces neuf-là — chaque site d'appel passe une chaîne
littérale.
