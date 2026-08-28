# Architecture

Un seul service Quarkus (Java 25) qui **embarque le solveur Timefold dans la
même JVM** et sert le frontend Angular statique, plus PostgreSQL. Pas de
micro-service, pas de serveur Node en production.

| Couche | Choix |
| --- | --- |
| Backend | Quarkus, REST et ressources statiques dans le même déploiement |
| Optimisation | Timefold Solver Community, `HardMediumSoftScore` |
| Persistance | PostgreSQL + Flyway |
| Frontend | Angular 22 standalone, signals, zoneless, servi par Quinoa |
| Exports | OpenPDF, génération ICS maison — toujours côté serveur |
| MCP | `quarkus-mcp-server-http` ([`mcp.md`](mcp.md)) |
| Toolchain | `mise.toml` : Temurin 25, Maven 3.9.9, Node 24 |

L'arborescence et le rôle de chaque classe se lisent dans le code. Ce document
porte les invariants qui ne s'y voient pas.

## Le cloisonnement par édition est mécanique, pas disciplinaire

Toutes les tables métier portent `edition_id` (migrations V32–V36), avec une clé
primaire composite `(edition_id, id)`.

`JdbcEditionScope` est **le seul endroit** qui emprunte une connexion, lie
l'édition courante au premier paramètre de chaque requête et porte la
transaction. C'est ce qui rend le prédicat systématique plutôt qu'espéré :
`IsolationEditionStructurelleTest` lit le SQL de tout le backend et échoue sur
toute requête visant une table métier sans ce prédicat.

Une nouvelle table du référentiel suit la même convention et passe par ce
helper. Voir
[`decisions/0001-cloisonnement-par-edition.md`](decisions/0001-cloisonnement-par-edition.md).

Un dépôt par famille (`StandRepository`, `AnimateurRepository`, …) plutôt qu'un
dépôt unique : le prédicat reste auditable d'un `grep` sur le paquet. Une seule
écriture traverse toutes les familles — remplacer le référentiel par celui d'un
scénario — et elle emprunte une connexion qu'elle passe à chaque dépôt.

`ReferenceData` est l'interface étroite que la **construction d'un problème**
lit du référentiel, et rien d'autre : c'est elle qui permet aux tests hors CDI
d'exister.

## Un solve fait toujours la même chose

`SolvePipeline` porte les huit étapes ; les quatre façons de lancer un solve y
passent toutes. Deux coutures seulement : comment le problème est construit, et
qui doit tenir le solveur pour pouvoir l'arrêter. Voir
[`api.md`](api.md#résolution) pour ce que ça a corrigé.

Cette seconde couture — « qui tient le solveur » — en porte deux usages, pas
un : l'arrêter, et **le suivre**. `SolverScoreTrace` s'abonne au même
`Solver`, au même instant, pour enregistrer la courbe de score de la
résolution en cours ; elle est lue par `GET /api/jobs/score` et poussée sur le
flux des jobs. Un seul consommateur reçoit donc le solveur, ce qui évite que
les deux besoins se désynchronisent. Échantillonnage et cloisonnement dans
[`api.md`](api.md#courbe-de-score-en-direct).

## Le diagnostic ne passe plus par l'API réservée de Timefold

Décomposer le score contrainte par contrainte alimente beaucoup de monde : les
pages *Contraintes*, *Problèmes* et *Heatmap*, l'explication d'affectation, les
simulations de swap et d'échange, l'assistant de réparation, et leurs
équivalents MCP. Tout cela appelait `SolutionManager.analyze()`, en une douzaine
d'endroits — méthode réservée à l'édition Enterprise à partir de Timefold 2.x.
La portée du verrou dépassait d'ailleurs les écrans : la fin de chaque solve
appelle cette analyse, donc sans licence un solve réussi était rapporté en
échec.

`service/diagnostic/` remplace ces douze appels par une couture unique,
`ConstraintDiagnosticService`, qui rend un `PlanningAnalysis` — un type du
projet, pas de Timefold. Deux implémentations la satisfont :

| Implémentation | Chemin | Licence |
| --- | --- | --- |
| `ScoreDirectorConstraintDiagnosticService` | le score director du solveur | aucune |
| `SolutionManagerConstraintDiagnosticService` | `SolutionManager.analyze()` | Enterprise en 2.x |

**Ce ne sont pas un mode complet et un mode dégradé.** Seule la *façade*
`analyze()` est verrouillée ; le constraint matching qu'elle appelle,
justifications comprises, est du code Community ordinaire. Les deux
implémentations rendent donc la même analyse — même score, mêmes contraintes,
mêmes correspondances, mêmes faits — ce que
`ConstraintDiagnosticServiceContractTest` vérifie en les exécutant côte à côte.
Il n'y a rien à détecter au démarrage, et rien à signaler à l'utilisateur.

Le choix se fait par `planning.diagnostic.mode` (`score-director` par défaut) et
nulle part ailleurs : `ConstraintDiagnosticService.of` est le seul endroit du
code qui sait qu'il existe deux implémentations. Celle basée sur `analyze()`
n'est pas un secours mais l'**oracle** du test de contrat — c'est ce qui rend
tenable de s'appuyer sur `ai.timefold.solver.core.impl`, hors semver : une
montée de version qui change le comportement fait échouer la comparaison et
nomme l'écart, au lieu de déformer cinq écrans en silence. Voir
[`decisions/0013-diagnostic-par-le-score-director.md`](decisions/0013-diagnostic-par-le-score-director.md).

## Deux politiques d'échec sur les mails, séparées structurellement

Un mail qui **accompagne** une opération déjà faite (demande soumise, décision
prise, solve terminé) ne doit jamais la faire échouer : un SMTP en panne ne peut
pas annuler ce qui a eu lieu. Un mail qui **est** l'opération (le code d'accès
sans lequel l'animateur n'entre pas, le planning qu'on croit diffusé) doit au
contraire échouer bruyamment, pour que l'appelant dise qui n'a pas été joint.

Les deux étaient portées par des méthodes de forme identique sur la même classe,
la première réécrite à la main autour de chaque appel dans cinq services. Elles
vivent maintenant à deux endroits : `MailService` pour la seconde,
`service/notification/` pour la première — où le métier émet un **fait**
(`Notification`, interface scellée), `NotificationWriter` le rédige par `switch`
exhaustif, et le `catch` n'est écrit qu'une fois.

## Frontend

Quinoa lance `npm ci && npm run build` pendant `mvn package` et copie le bundle
dans les ressources statiques : déploiement unique. En `quarkus:dev`, il démarre
`ng serve` et proxifie le 4200 pour le rechargement à chaud.

**Un bloc fonctionnel = une route = une page**, en lazy loading. On n'ajoute pas
une section dans une page existante. Les services de `core/` portent l'état
partagé et les appels HTTP ; les composants ne font pas de `fetch`.

L'état circule dans un seul sens : la page Solveur pousse le planning résolu,
les calendriers le relisent en lecture seule et ne lancent jamais de résolution.

### Trois choses lues avant `bootstrapApplication()`

Le catalogue i18n anglais, `/api/config` et `/api/branding`. Le titre de
l'onglet et le logo décident de la première image affichée : aucun écran ne peut
attendre un aller-retour pour savoir comment il s'appelle.

**i18n** : traduction à l'exécution. Le français est écrit en dur dans les
templates, `loadTranslations()` charge `messages.en.json` quand l'anglais est
choisi. Un seul build, pas de bundle par locale — donc aucune configuration
Quinoa supplémentaire.

**Marque** : `logoUrl` vide veut dire « n'affiche rien », jamais « affiche le
logo par défaut ». Une instance par client, aucun client n'hérite de la marque
d'un autre.

> **Limite à connaître.** `mat.theme()` compile toute la palette tonale dans le
> bundle. La couleur d'accent configurable n'alimente que `--app-accent` :
> recolorer les composants Material demande une **recompilation**, pas une
> variable d'environnement.

### Le poll n'est pas un vestige — ne le supprimez pas

L'état « un solveur tourne » n'est **jamais** stocké dans le navigateur : il est
lu sur le serveur, de sorte qu'une résolution lancée depuis une autre fenêtre
verrouille aussi les boutons ici.

Il est lu **de deux façons, à dessein**. Le flux SSE `/api/jobs/stream` donne la
latence — une résolution lancée ailleurs verrouille en une seconde au lieu de
trente. La boucle de polling en dessous est le filet : SSE échoue en silence, et
l'interface resterait figée sur un état périmé sans rien signaler, ce qui est
strictement pire qu'un poll, lequel se répare tout seul au tick suivant.

Le poll garde la main tant que le flux n'a pas prouvé qu'il vit, redescend à
30 s tant qu'il vit, et **reprend la main** après 45 s de silence.

### Un seul écouteur clavier global

`core/keyboard-shortcuts.service.ts` porte l'unique `keydown` posé sur le
document pour les raccourcis (Ctrl+K, `g`+lettre, `/`, `?`, Ctrl+Entrée). Il est
armé et désarmé avec le shell d'administration : `/login` et l'espace animateur
n'ont ni palette ni ces destinations. **N'en ajoutez pas un second** — deux
écouteurs globaux se disputent la même frappe sans que rien ne le signale.

Deux exceptions volontaires : le code Konami du shell (une séquence, son propre
état) et les flèches des calendriers et de la heatmap, locales au composant
affiché. Échap n'est écrit nulle part : aucun dialogue n'utilise
`disableClose`, donc `MatDialog` ferme déjà le dialogue du dessus et rend le
focus.

La table `g`+lettre et les libellés de la palette vivent dans
`core/keyboard-shortcuts.ts` ; les destinations, elles, sont **dérivées de
`app.routes.ts`**, pour qu'une page ajoutée demain soit atteignable au clavier
sans qu'on ait pensé à l'y inscrire.

### Le seul endroit où le frontend réimplémente du domaine

`app/core/horaire-stand.ts` résout les règles d'horaire d'un stand en fenêtres
jour par jour, en miroir de `HoraireStandResolver` côté serveur — pour
prévisualiser une règle sans aller-retour. **À faire évoluer dans le même commit
que son pendant Java.**

### CSS

`src/styles.css` n'est qu'un agrégateur d'`@import` ; un partial par écran sous
`src/styles/`. `branding.css` est importé en premier : tous les autres lisent
`--app-accent`.

## Base de données

`quarkus.flyway.migrate-at-start=true`. **Un changement de schéma = un nouveau
fichier versionné** ; ne jamais modifier une migration déjà appliquée.

`FLYWAY_REPAIR_AT_START` active la réparation de la table d'historique au
démarrage. Elle vaut `false` et n'a de sens qu'en exploitation, le temps de
débloquer une migration interrompue — voir
[`exploitation.md`](exploitation.md).

## Les deux tâches planifiées, et le seul endroit qui écrit sur le disque

`service/backup/` sauvegarde la base chaque nuit par un vrai `pg_dump`, dans le
répertoire que désigne `BACKUP_DIR`, en ne gardant que les `BACKUP_RETENTION`
copies les plus récentes. C'est le **seul fichier écrit hors de la base**, et
elle reste la seule tâche à toucher le disque.

Trois choix structurent le paquet, détaillés dans
[`decisions/0015-sauvegarde-par-pg-dump-restauration-hors-application.md`](decisions/0015-sauvegarde-par-pg-dump-restauration-hors-application.md) :
la **restauration reste hors de l'application**, les dumps ne sont **pas
téléchargeables**, et l'emplacement comme la rétention sont des **variables
d'environnement** — l'écran n'écrit qu'un booléen, celui qui suspend la tâche.

`PgDump` est un bean à part pour une seule raison : c'est la part que les tests
remplacent. Rien dans la suite ne doit lancer ce binaire, dont la présence et la
version appartiennent à la machine — et dont la rotation, elle, se prouve sur un
répertoire temporaire (`BackupStoreTest`), parce que c'est la moitié de la
fonctionnalité qui **supprime**.

### La tâche des notifications planifiées

`service/notification/NotificationsPlanifieesService` est l'autre `@Scheduled`,
et le seul point d'entrée des trois envois de nuit — rappel de la veille
(`RappelVeilleJob`), relance des non-confirmés (`RelanceConfirmationJob`),
alerte sur les demandes d'échange qui dorment (`AlerteEchangeJob`). Le profil
`%test` désactive le planificateur pour les deux tâches ; les tests appellent
les services directement.

Il suit les conventions du paquet `backup` — cron et fuseau configurables,
`SKIP` en cas de recouvrement, échec journalisé et non propagé — avec deux
particularités qui lui sont propres :

- **il boucle sur les éditions**, et une seule décide si elle a le droit
  d'écrire à qui que ce soit. Il n'y a pas d'`X-Edition-Id` sur un fil du
  planificateur : chaque édition est donc entrée explicitement par
  `EditionContext.executeIn`, et l'échec de l'une ne doit pas arrêter les
  suivantes — d'où le `try/catch` **dans** la boucle et non autour ;
- **le cron est horaire**, parce que l'heure d'envoi se règle par édition et
  qu'une tâche unique ne peut pas partir à quatre heures différentes. Se
  réveiller souvent n'est sans danger que grâce à la ligne suivante.

`JournalNotificationsRepository` porte l'idempotence, et il la **réserve** au
lieu de la vérifier : un job pose une clé `(edition_id, type, cle)` en
`INSERT … ON CONFLICT DO NOTHING`, et n'envoie que si l'insertion a écrit une
ligne. Demander « ai-je déjà envoyé ? » puis agir laisserait deux exécutions
lire « non » et envoyer toutes les deux ; là, c'est la base qui tranche, une
fois. Une panne entre la réservation et l'envoi coûte un message manquant
plutôt qu'un message en double — le bon sens, pour un rappel qui se lit aussi
dans l'espace animateur.

## Conteneurisation

`docker-compose.yml` : l'application (build multi-stage, JRE en image finale,
utilisateur non privilégié `uid 1001`), PostgreSQL avec son volume et son
healthcheck, pgAdmin. L'image finale embarque `postgresql-client-18` pour la
sauvegarde de nuit : un `pg_dump` plus ancien que le serveur refuse de tourner,
donc cette version suit celle de l'image `postgres:`. La configuration de production est dans
[`exploitation.md`](exploitation.md) et le durcissement dans
[`securite.md`](securite.md).
