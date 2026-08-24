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

## Conteneurisation

`docker-compose.yml` : l'application (build multi-stage, JRE en image finale,
utilisateur non privilégié `uid 1001`), PostgreSQL avec son volume et son
healthcheck, pgAdmin. La configuration de production est dans
[`exploitation.md`](exploitation.md) et le durcissement dans
[`securite.md`](securite.md).
