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
toute requête visant une table métier sans ce prédicat. Il **suit
l'indirection** — constante, variable locale, concaténation, paramètre d'un
helper privé —, si bien qu'écrire la requête dans une variable avant de la
préparer ne la lui cache pas ; une forme qu'il ne sait pas lire fait échouer le
test au lieu d'être ignorée.

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

### Le texte des mails est dans des gabarits, pas dans le Java

Chaque mail est une paire de gabarits Qute sous
`src/main/resources/templates/mail/` : `<nom>.txt`, la partie texte, et
`<nom>.html`, l'alternative HTML, construite sur le `layout.html` commun —
logo, palette et nom du déploiement autour du message. Le Java (`MailService`,
`NotificationWriter`) ne fait plus que nommer le gabarit, décider du sujet et
lui passer ses valeurs ; `service/mail/MailTemplates` rend les deux parties et
assemble le `Mail` (texte **toujours** présent, HTML en alternative, logo en
pièce jointe *inline* référencée par `cid:` — aucune image distante, donc
aucun chargement à l'ouverture). Le `switch` exhaustif de `NotificationWriter`
reste ce qui garantit qu'une notification nouvelle a un gabarit : sans son
`case`, elle ne compile pas.

Deux moteurs, un contrat : sous Quarkus, c'est l'`Engine` validé au
démarrage ; dans les tests unitaires, `MailTemplates.standalone()` construit le
même moteur depuis le classpath, avec les mêmes réglages (lignes de section
supprimées, échappement HTML des valeurs), si bien que les assertions de
libellé continuent de tourner sans conteneur. Le logo vient de
`BRANDING_PDF_LOGO` — des octets que l'application tient —, pas de
`BRANDING_LOGO_URL`, une URL que seul un navigateur sait résoudre ; la palette
est celle des PDF, toujours un `#rrggbb` lisible en style inline.

## L'historique des actions n'a pas une couture, il en a trois

« Tracer **chaque** action » (issue #406) se heurte à une réalité de cette
application : il n'existe aucun point de passage unique. Une requête REST
traverse la chaîne JAX-RS ; un appel d'outil MCP ne la voit jamais, parce que
`quarkus-mcp-server-http` répond avant elle ; une tâche de nuit n'a ni requête
ni édition courante. Trois surfaces, donc trois coutures :

- `api/JournalActionFilter`, filtre de **réponse** non lié, comme
  `EditionHeaderFilter` : il voit toutes les routes, et écrit le statut sur
  lequel l'action s'est terminée — un refus est un fait qu'on cherche plus
  souvent qu'un succès ;
- `mcp/JournalOutilInterceptor`, intercepteur lié à `@Journalise` — une
  annotation à lui, et non `@EditionCiblee` comme au premier jet : les classes
  qui agissent **entre** éditions (`EditionMcpTools`, `SauvegardeMcpTools`) ne
  portent légitimement pas la seconde, si bien que six outils d'écriture, dont
  la suppression d'une édition entière, figuraient au catalogue sans que
  personne ne les intercepte. Deux questions tenaient à une seule annotation ;
  elles en ont chacune une ;
- la tâche de nuit, qui appelle `recordSystemAction` quand un envoi est
  réellement parti — et elle seule : la sauvegarde et la purge sont
  transverses aux éditions, or une ligne appartient à une édition, donc les
  classer sous celle qui se trouvait courante serait un fait inventé.

Ce qui tient la promesse n'est donc pas la discipline au point d'appel — il
n'y en a pas — mais `service/journal/CatalogueActions` et le test qui le lit.
Le catalogue est l'**inventaire métier** des actions, comme `ConstraintCatalog`
l'est des règles du solveur : ce qu'une action *est* d'un côté, quel point
d'entrée l'effectue de l'autre, si bien qu'un stand créé depuis un écran et un
stand créé par un assistant écrivent la même ligne.
`JournalCoverageStructurelleTest` échoue sur toute route ou tout outil qui
écrit sans figurer **ni** dans le catalogue **ni** dans sa liste d'exclusions
motivées : on ne peut pas en sortir en oubliant. Il vérifie aussi les deux
bords que le premier jet avait laissés ouverts — qu'une classe d'outils
journalisés porte bien `@Journalise`, l'appartenance au catalogue n'ayant
jamais prouvé qu'une ligne s'écrive ; et qu'aucune action décrite ne soit
inatteignable, un inventaire mort promettant à l'écran un filtre qui ne
rendra rien.

Les champs réellement modifiés viennent d'ailleurs : seul le service sait ce
qui a changé. `ReferenceDataService` lit déjà la fiche telle qu'elle était
avant l'écriture — pour les avertissements de saisie — et dépose la
comparaison dans `CurrentAction`, que le point d'entrée relève en fin d'appel.
Enrichissement et non obligation : une ligne dont personne n'a fourni les
champs s'écrit quand même.

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

**Thème clair ou sombre** : `mat.theme()` compile les **deux** palettes d'un
coup — chaque couleur `--mat-sys-*` est une paire `light-dark(clair, sombre)` —
et c'est la propriété CSS `color-scheme` de l'élément racine qui décide laquelle
est peinte. Il n'y a donc pas de second thème, pas de feuille de style
parallèle, et rien à recompiler : l'application écrit `light dark` (« suis la
machine », le défaut), `light` ou `dark` sur `<html>`, et la préférence est
retenue dans `localStorage` comme les autres préférences de chrome.

Deux couleurs échappent à `mat.theme()` et portent donc leurs propres paires
`light-dark()` :

- la palette catégorielle des typologies, `styles/typologie-colors.css` — huit
  teintes qu'aucun `--mat-sys-*` ne fournit ;
- `--app-accent`, quand `BRANDING_ACCENT_COLOR` la renseigne. Elle vaut sinon
  `--mat-sys-primary`, déjà une paire ; renseignée, c'est une couleur unique
  choisie sur fond blanc, et elle sert de couleur de texte sur
  `--mat-sys-surface` dans une douzaine de partials. `core/branding.ts` la pose
  donc en paire : la couleur configurée telle quelle en clair, une jumelle
  éclaircie en OKLCH (plancher de clarté, plafond de chroma) en sombre, qui
  garde la teinte de la marque. Un navigateur qui ne sait pas parser la paire
  garde la couleur brute.

Trois surfaces restent volontairement en dehors :

- la **barre d'outils de marque** (administration et espace animateur) garde sa
  couleur d'enseigne dans les deux thèmes : une identité qui change de couleur
  selon l'heure n'est plus une identité ;
- les **cartes** (saisie d'un emplacement, carte de la journée) affichent des
  tuiles OpenStreetMap, c'est-à-dire du contenu et non du chrome ; les
  assombrir demanderait un autre fournisseur de tuiles, pas une variable. Ce
  qui les entoure — pastilles d'état, légende, liste — suit le thème comme le
  reste ;
- les **PDF** sont composés côté serveur, pour être imprimés : ils suivent la
  marque, jamais le thème du navigateur.

Le bouton de bascule, lui, ne vit que dans la barre d'administration. La page
de connexion et l'espace animateur, qui s'affichent hors de ce cadre, suivent
la préférence enregistrée par ce navigateur, et à défaut celle du système : sur
un téléphone, c'est déjà le bon réglage, et un contrôle de plus sur un écran
qui en compte quatre coûterait plus qu'il ne rendrait.

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

Une exception volontaire : le code Konami du shell (une séquence, son propre
état). Les flèches des calendriers, de la heatmap et des tables de données de
référence n'en sont pas : elles sont posées sur l'élément du composant, ne
consomment que les touches qu'elles utilisent, et laissent tout le reste
remonter jusqu'à l'écouteur global — qui s'arrête sur un événement déjà
consommé. C'est ainsi qu'on ajoute un comportement clavier ici. Échap n'est
écrit nulle part : aucun dialogue n'utilise `disableClose`, donc `MatDialog`
ferme déjà le dialogue du dessus et rend le focus.

Le tabindex mobile des cinq tables de données de référence (une seule ligne
atteignable par Tab, flèches, Début/Fin, Entrée pour ouvrir, Espace pour
cocher) est mutualisé dans `core/table-navigation.ts`, à côté de
`core/table-selection.ts` qu'il pilote.

**On entre dans le tableau par le filtre.** Le tabindex mobile est invisible :
la ligne qui porte `tabindex="0"` se trouve derrière la case « tout
sélectionner » et derrière un arrêt de tabulation par en-tête triable, si bien
que le nombre de Tab à taper change chaque fois qu'une colonne devient
triable — une fonctionnalité qu'il faut deviner n'existe pas. Le geste
documenté est donc : « / » saisit le filtre de la page, Flèche bas y entre.
Ce `keydown` est posé sur le champ de `shared/table-filter.ts`, qui émet
`enterTable` ; la page appelle `TableNavigation.focusCurrent()`. Rien de
global n'est ajouté. Un clic sur une ligne la focalise aussi (le `tr` porte un
`tabindex`), donc les flèches enchaînent sans détour. La page des créneaux,
qui n'a pas de filtre rapide, garde la seule entrée par Tab. Le focus s'ancre sur la **ligne**, pas
sur son rang : un tri ne le fait pas sauter ailleurs, et une ligne effacée par
le filtre le repose sur la place que cette ligne occupait en dernier — sans
quoi plus aucune ligne ne porterait `tabindex="0"` et le tableau sortirait de
l'ordre de tabulation. Ce dernier rang est la valeur précédente du signal
lui-même (`linkedSignal`), et non le rang figé à la prise de focus, sinon un
tri passé entre les deux renverrait le focus à une position que la ligne a
quittée depuis longtemps.

Entrée et Espace ne sont consommés qu'une fois quelque chose réellement
ouvert ou coché : une table sans sélection rend l'Espace à la page, et les
créneaux refusent d'ouvrir pendant qu'une résolution verrouille l'édition —
la touche poursuit alors sa route au lieu de mourir en silence, le bandeau de
verrouillage déjà affiché disant pourquoi. La ligne cochée ne porte pas
`aria-selected` : cet attribut n'est une propriété supportée de `role=row` que
dans une `grid`, alors qu'un `mat-table` expose `role="table"`. L'état
accessible est celui de la case à cocher de la ligne, le changement est annoncé
par le `LiveAnnouncer` du CDK, et le surlignage passe par une classe.

**Un en-tête triable ne se nomme pas tout seul.** Material rend la cellule
d'en-tête à l'intérieur d'un `role="button"` qu'il ne nomme jamais : le nom
accessible est calculé à partir du contenu, et ce calcul descend dans les
`aria-label` des contrôles imbriqués. Rendre « Accusé de réception » triable a
donc suffi pour que les six lignes d'explication de son bouton d'aide
deviennent le nom du bouton de tri, relues à chaque passage du focus sur
l'en-tête. `shared/sort-header-name.ts` coupe le couplage : la directive pose
un `aria-labelledby` vers l'élément qui porte le titre de la colonne, si bien
que l'en-tête s'annonce par son titre et le bouton d'aide garde son
explication entière pour lui. Elle n'est nécessaire que sur un en-tête
contenant autre chose que son titre. `sortActionDescription` ne remplace pas :
il alimente `aria-describedby`, qui décrit l'action et laisse le nom
intact ; masquer le bouton du calcul le rendrait invisible au lecteur d'écran
alors qu'il est focalisable.

Les trois grilles à deux axes (calendrier mensuel, heatmap, jours de repos)
gardent leur navigation propre : leur géométrie n'est pas celle d'une liste.

La table `g`+lettre et les libellés de la palette vivent dans
`core/keyboard-shortcuts.ts` ; les destinations, elles, sont **dérivées de
`app.routes.ts`**, pour qu'une page ajoutée demain soit atteignable au clavier
sans qu'on ait pensé à l'y inscrire.

### `leaflet` reste hors du bundle initial

Trois routes affichent une carte — `/emplacements` (saisie d'un point),
`/graphe` (le même sélecteur en lecture seule) et `/carte-jour` (le rejeu d'une
journée). Toutes trois sont en lazy loading, et `leaflet` pèse à lui seul un
morceau de 150 ko : **rien de chargé au démarrage ne doit l'importer**, sinon
ce poids passe dans le bundle initial de tout le monde, y compris de la page de
connexion. Ce qu'elles partagent — les tuiles OpenStreetMap, leur attribution,
la politique de `Referrer` qu'elles exigent et les chemins d'icônes qui
survivent au bundle — vit dans `shared/leaflet-base.ts`, jamais recopié.

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
utilisateur non privilégié `uid 1000`), PostgreSQL avec son volume et son
healthcheck, pgAdmin. L'image finale embarque `postgresql-client-18` pour la
sauvegarde de nuit : un `pg_dump` plus ancien que le serveur refuse de tourner,
donc cette version suit celle de l'image `postgres:`. La configuration de production est dans
[`exploitation.md`](exploitation.md) et le durcissement dans
[`securite.md`](securite.md).
