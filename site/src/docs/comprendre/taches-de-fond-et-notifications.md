# Tâches planifiées, mails, webhooks, journal des actions

Ce qui part de l'application sans qu'une personne clique, et ce qui trace ce que chacun fait : les quatre tâches planifiées, les deux politiques d'échec des mails, les gabarits, les webhooks, l'alerte météo, le réalisé figé, la sauvegarde et le journal des actions. Vous saurez pour chacun ce qui garantit qu'il n'écrit pas deux fois, n'écrit pas à la mauvaise édition, ou ne s'oublie pas en ajoutant une route.

## Les quatre tâches `@Scheduled`

| Tâche | Quand | Ce qu'elle fait |
| --- | --- | --- |
| `BackupService` | `BACKUP_CRON`, 4 h par défaut | `pg_dump` de la base ([la sauvegarde](#la-sauvegarde-seul-endroit-qui-écrit-sur-le-disque)) |
| `NotificationsPlanifieesService` | `NOTIFICATIONS_CRON`, toutes les heures à la minute 5 | Les quatre envois de nuit, les purges d'historique |
| `WebhookDeliverer` | `WEBHOOKS_CRON`, chaque minute | Reprend les livraisons de webhooks dont le réessai est échu |
| `WeatherAlertJob` | `METEO_CRON`, 6 h par défaut | Interroge Open-Meteo pour l'édition active |

Les fuseaux suivent `NOTIFICATIONS_TIMEZONE` (`Europe/Paris` par défaut). Le profil `%test` coupe le planificateur : les tests appellent les services directement. Le réalisé figé n'est plus une tâche ([plus bas](#le-réalisé-figé-à-la-demande)).

## Les envois de nuit

`NotificationsPlanifieesService` est le seul point d'entrée de quatre envois : le rappel de la veille (`RappelVeilleJob`), la relance des non-confirmés (`RelanceConfirmationJob`), l'alerte sur les demandes d'échange qui dorment (`AlerteEchangeJob`) et la relance de la collecte des disponibilités aux invités qui n'ont rien déclaré (`RelanceCollecteJob`). Son cron est horaire parce que l'heure d'envoi se règle par édition et qu'une tâche unique ne peut pas partir à quatre heures différentes. Se réveiller souvent n'est sans danger que grâce à l'idempotence ci-dessous.

Comme `backup`, il a un cron et un fuseau configurables, saute son tour en cas de recouvrement (`SKIP`) et journalise un échec sans le propager. Il a deux règles à lui :

- Il ne sert que l'édition active ([cloisonnement](cloisonnement-par-edition.md#une-seule-édition-parle-à-lextérieur)). Un fil de planificateur n'a pas d'`X-Edition-Id` : la tâche entre dans l'édition active par `EditionContext.executeIn`, et sans édition active rien ne part. Une édition qui porterait des créneaux le même jour qu'une autre ne peut donc plus rappeler les mêmes personnes avec deux plannings différents.
- L'idempotence est réservée, pas vérifiée. `JournalNotificationsRepository` pose une clé `(edition_id, type, cle)` par `INSERT … ON CONFLICT DO NOTHING` et le message ne part que si l'insertion a écrit une ligne. Demander « ai-je déjà envoyé ? » avant d'agir laisserait deux exécutions lire « non » ; ici la base tranche une seule fois. Une panne entre la réservation et l'envoi coûte un message manquant plutôt qu'un doublon, ce qui convient à un rappel qui se lit aussi dans l'espace animateur.

La même passe purge, avec la durée de `JOURNAL_RETENTION` (90 jours par défaut), l'historique des actions, les résultats d'envoi de mail et le journal des connexions, et, avec sa propre échéance de trente jours, les livraisons de webhooks. Une purge qui échoue laisse grossir une table, elle ne fait pas échouer la nuit.

## Deux politiques d'échec pour les mails

Un mail qui accompagne une opération déjà faite (demande soumise, décision prise, calcul terminé) ne doit jamais la faire échouer : un SMTP en panne n'annule pas ce qui a eu lieu. Un mail qui est l'opération (le code d'accès sans lequel l'animateur n'entre pas, le planning qu'on croit diffusé) doit échouer bruyamment pour que l'appelant dise qui n'a pas été joint. La séparation est structurelle :

- `MailService` ne porte que ce qu'un administrateur demande explicitement : l'échec se propage.
- Tout le reste passe par `service/notification/`. Le métier émet un fait (`Notification`, interface scellée), `NotificationWriter` le rédige par un `switch` exhaustif, et `NotificationDispatcher` l'observe et porte l'unique `catch`.

N'ajoutez donc pas de `notifierXxx` à `MailService` : ce serait remettre deux politiques opposées derrière des méthodes de même forme.

### Le résultat remonte sans changer la politique

Chaque courriel quitte l'application par `MailMetrics`, qui le compte par gabarit. Quand il est adressé à un animateur, le même point enregistre le résultat dans `envoi_mail` (`MailDeliveryRepository`) : parti, ou en échec avec une `MailFailureCategory` lue sur l'exception (relais injoignable, authentification, adresse refusée, temporaire, autre). Jamais l'adresse. L'identifiant vient de l'appelant, par un paramètre de `MailService` ou par le sous-type `Notification.ToAnimateur`. L'échec est enregistré puis relancé : `MailService` le propage toujours et le répartiteur l'avale toujours.

Deux appelants avaient besoin de l'issue sans changer de politique, les tâches de nuit. Elles passent par `NotificationDispatcher.deliver`, qui envoie tout de suite et **rend** l'issue au lieu de la propager. Sur un échec, la relance ne passe pas la personne à `RELANCE` et laisse une alerte `ALERTE` sur l'accueil, comme la relance manuelle.

Les clés de `JournalNotificationsRepository` encodent la suite :

- la clé que la nuit partage avec la main (`animateurId|publication`) est rendue en cas d'échec, pour que « Relancer maintenant » la trouve libre ;
- la nuit garde une clé à elle (suffixe `|nuit`), une tentative par personne et par publication, pour ne pas réécrire chaque heure à un relais en panne. Seule une adresse refusée écarte la personne tant que sa fiche n'a pas été modifiée ;
- l'alerte d'un échec a sa propre clé (suffixe `|echec`), pour que l'avertissement « aucune adresse » levé avant ne l'avale pas.

## Le texte des mails est dans des gabarits

Chaque mail est une paire de gabarits Qute sous `src/main/resources/templates/mail/` : `<nom>.txt` et `<nom>.html`, ce dernier bâti sur `layout.html` (logo, palette, nom du déploiement). Le Java nomme le gabarit, décide du sujet et passe ses valeurs ; ajouter un mail, c'est ajouter les deux fichiers. `MailTemplates` rend les deux parties et assemble le `Mail` :

- la partie texte est toujours envoyée, le HTML est une alternative ;
- le logo voyage en pièce jointe `cid:` en ligne, jamais en image distante, que les clients bloquent et que les filtres antispam pénalisent. Il vient de la clé `pdf-logo` de `BRANDING_IMAGES` (des octets que l'application détient), pas de la clé `logo` (une URL que seul un navigateur sait résoudre) ;
- le `switch` exhaustif de `NotificationWriter` garantit qu'une notification nouvelle a son gabarit.

Sous Quarkus, c'est l'`Engine` validé au démarrage ; dans les tests unitaires, `MailTemplates.standalone()` construit le même moteur depuis le classpath, avec les mêmes réglages. Les assertions de libellé tournent ainsi sans conteneur.

## Les webhooks sortants

`WebhookDeliverer` ([ADR 0074](repo:docs/decisions/0074-deux-jobs-planifies-de-plus-webhooks-et-meteo.md)) reprend chaque minute les livraisons dont le réessai est échu, après 1 min, 5 min, 30 min, 2 h puis 12 h, y compris après un redémarrage. Le premier essai part aussitôt, sur un petit groupe de fils à lui, jamais sur celui de l'opération qui a causé l'événement. Un bail en base, pris ligne par ligne juste avant l'essai et exigé pour en noter l'issue, empêche le premier essai et la reprise d'envoyer la même ligne.

Le chemin d'un événement :

- `WebhookEmitter` observe les `Notification` par un `switch` exhaustif (chaque cas donne un événement, ou explicitement aucun) et l'événement `PlanningPublished` que tire la publication. C'est un `@Observes` simple, pas `AFTER_SUCCESS` : sans transaction JTA, l'annotation ne différerait rien. Il insère une livraison par webhook actif abonné, dans un `try/catch` qui ne propage jamais ;
- une édition n'émet que si `OutboundEditionPolicy` le permet, et la question se repose à chaque essai : un réessai ou un « Renvoyer » sur une édition devenue inactive est abandonné. L'échec de la sauvegarde nocturne, événement d'instance, part toujours ;
- `WebhookDeliverer` rend le corps au format du webhook (`WebhookFormats`, une méthode pure par format, signature HMAC pour le format générique), passe l'adresse à `OutboundGuard`, qui la résout et la vérifie (https, ports 443 et 8443, aucune adresse interne sauf `WEBHOOKS_RESEAUX_AUTORISES`), puis envoie par le client Vert.x vers l'adresse vérifiée. Le corps de la réponse est jeté.

Les corps portent des comptes et des identifiants, jamais une personne (`WebhookFormatsTest`). La configuration est celle de l'instance : `webhook` et `webhook_livraison` n'ont pas d'`edition_id` et restent hors de l'export SQL. Les secrets y sont chiffrés en AES-GCM avec `WEBHOOKS_SECRET_KEY`, sans laquelle le démarrage est refusé dès qu'un webhook existe. Aucun outil MCP ne les configure. La garde est détaillée dans [les appels sortants](../installer/securite-appels-sortants.md).

## L'alerte météo

`WeatherAlertJob` entre chaque matin dans l'édition active, si son alerte est activée, et laisse `WeatherAlertService` faire le reste. Elle suggère un préréglage de consigne ; elle n'en pose jamais.

- Dates. Celles de l'événement dans `[aujourd'hui ; + horizon]`, où « aujourd'hui » est celui de `JourJClock`, intersectées avec ce qu'Open-Meteo prévoit vraiment : du vrai jour courant à ce jour plus 15. Si l'intersection est vide, rien n'est interrogé et l'écran dit « hors prévision ».
- Lieux. Les emplacements géolocalisés des stands ouverts ces jours-là, lus par `listSolvedStands()` (consignes comprises), arrondis à 0,01° et dédoublonnés. Une seule requête (`OpenMeteoClient`, le client HTTP du JDK) les porte tous.
- Alerte. Par date et par phénomène (chaleur, rafales, orage), une ligne de `notification_planifiee` est réservée sous la clé `date|PHÉNOMÈNE|palier`. Les paliers inférieurs sont réservés comme simples verrous : seule une prévision qui s'aggrave reparle. Suivent un courriel à l'administrateur (`Notification.WeatherAlert`) et l'événement de webhook `meteo.alerte`. Pour aujourd'hui, une consigne ne se pose plus : l'alerte dit la prévision sans suggestion ni lien.
- Service muet. Il n'est jamais propagé : `meteo_etat` garde « injoignable depuis… » et un seul avertissement part au deuxième jour.

Les réglages (`parametres_meteo`) suivent l'édition : exportés, recopiés par une duplication éteints. L'état (`meteo_etat`) reste celui de la machine. `METEO_URL` est un choix de l'exploitant, la garde SSRF des webhooks ne s'y applique pas.

## Le réalisé figé, à la demande

Aucune tâche ne mesure le réalisé ([ADR 0092](repo:docs/decisions/0092-le-realise-se-calcule-a-la-demande.md), qui remplace [0070](repo:docs/decisions/0070-un-troisieme-job-planifie-fige-le-realise.md)). `RealisedVsPlannedService` répond à la demande ; à la première lecture faite une fois le dernier créneau de l'édition terminé, et quelque chose de publié, il écrit dans `kpi_realise` la mesure par typologie et pour l'événement : des comptes, aucune personne.

Cette écriture est un cache qui n'écrit qu'une fois : une édition qui a ses lignes est laissée telle quelle (`ON CONFLICT DO NOTHING` derrière la vérification), si bien que `fige_le` est la première lecture après l'événement. Un passé figé désactivé n'est pas mis en cache et le dit dans le journal ; une écriture qui échoue ne fait jamais échouer la lecture ; refiger est un `DELETE` SQL de l'exploitant sur les lignes de l'édition, jamais une route. La table, sans clé étrangère, garde les noms de l'édition et des typologies : la mesure survit à la suppression de l'édition.

La mesure vient de `RealisedVsPlannedAnalyzer`, au-dessus de `ChangementsJourneeService.compareSeats` (jamais une comparaison à part). Chaque journée écoulée, dont le dernier créneau est terminé, est comparée à la publication en vigueur au début de son premier créneau. Une publication qui sert de référence à une journée écoulée refuse la suppression (`PlanSnapshotService.delete`). Le réalisé est lu derrière `RealisedSource` : la source déclarée (`DeclaredRealisedSource`) aujourd'hui, un pointage de présence demain, sans toucher au calcul.

## La sauvegarde, seul endroit qui écrit sur le disque

`BackupService` sauvegarde la base chaque nuit par un vrai `pg_dump` dans `BACKUP_DIR` et ne garde que les `BACKUP_RETENTION` copies les plus récentes (10 par défaut). Trois choix structurent le paquet ([ADR 0015](repo:docs/decisions/0015-sauvegarde-par-pg-dump-restauration-hors-application.md)), et aucun n'est un oubli :

- la restauration reste hors de l'application, une opération d'infrastructure avec `pg_restore` : un bouton « tout restaurer » serait le geste même que la sauvegarde existe pour défaire ;
- les sauvegardes ne se téléchargent jamais, car elles portent le nom, la date de naissance et l'adresse de chaque animateur, mineurs compris ;
- l'emplacement et la rétention sont des variables d'environnement. L'écran n'écrit qu'un booléen, celui qui suspend la tâche.

Un échec est enregistré et affiché, pas propagé. `PgDump` est un bean à part pour une seule raison : c'est la part que les tests remplacent, car rien dans la suite ne lance ce binaire, dont la présence et la version appartiennent à la machine. La rotation, la moitié qui supprime, se prouve sur un répertoire temporaire (`BackupStoreTest`). Le client `pg_dump` embarqué dans l'image suit la version de PostgreSQL ([vue d'ensemble](vue-d-ensemble.md#la-conteneurisation)).

## Le journal des actions a trois coutures

Tracer chaque action se heurte au fait qu'il n'existe aucun point de passage unique. Une requête REST traverse la chaîne JAX-RS ; un appel d'outil MCP ne la voit jamais, car `quarkus-mcp-server-http` répond avant elle ; une tâche de nuit n'a ni requête ni édition courante. Trois surfaces, trois coutures :

- `api/JournalActionFilter`, un filtre de réponse non lié (comme `EditionHeaderFilter`). Il voit toutes les routes et écrit le statut sur lequel l'action s'est terminée : un refus est un fait qu'on cherche plus souvent qu'un succès ;
- `mcp/JournalOutilInterceptor`, lié à `@Journalise`, une annotation à lui. Les classes qui agissent entre éditions (`EditionMcpTools`, `SauvegardeMcpTools`) ne portent légitimement pas `@EditionCiblee` : la réutiliser avait laissé six outils d'écriture sans interception ;
- `JournalActionService.recordSystemAction`, que les tâches système appellent quand un fait s'est réellement produit : envoi de nuit parti, alerte météo levée, vérification du besoin terminée, réalisé figé. Les sauvegardes et la purge, transverses aux éditions, n'en passent pas : une ligne appartient à une édition, et les classer sous celle qui se trouvait courante serait un fait inventé.

Ce qui tient la promesse n'est donc pas la discipline au point d'appel, mais `service/journal/CatalogueActions`, l'inventaire métier des actions (comme `ConstraintCatalog` pour les règles), et le test qui le lit. Un stand créé depuis un écran et un stand créé par un assistant écrivent la même ligne. `JournalCoverageStructurelleTest` échoue sur :

- toute route ou tout outil qui écrit sans figurer ni dans le catalogue ni dans sa liste d'exclusions motivées ;
- tout `GET` qui rend un fichier (un type fichier dans son `@Produces`, celui de la méthode sinon celui de la classe, lu par réflexion ; ou un `Content-Disposition` écrit par le corps de la méthode, `CsvDownload` compris) et n'est ni catalogué ni exclu avec son motif. Un téléchargement catalogué se déclare par `export(…)`, dont le drapeau alimente le filtre « Exports » de l'historique ; celui de l'espace n'est tracé qu'une fois l'identité prouvée (`routeWhenProven`) ;
- toute route du catalogue ou des exclusions qui ne désigne plus aucune méthode (c'est ainsi qu'`EXPORT_BASE` a été retrouvé muet après un renommage) ;
- une classe d'outils journalisés sans `@Journalise`, et une action décrite que rien n'atteint, car un inventaire mort promettrait à l'écran un filtre qui ne rendra rien.

Deux choses n'entrent jamais dans la table : une valeur (une modification enregistre les noms des champs changés, jamais leur contenu) et une identité (un identifiant seul ; le nom est joint à la lecture, de sorte qu'une fiche supprimée laisse une ligne qui ne nomme personne).

L'auteur d'une ligne est l'identifiant du compte (jamais son adresse), et `GET /api/historique?auteur=` le sélectionne en base, sur toute la rétention : un identifiant de compte ou d'animateur, `mcp`, ou `systeme` pour les tâches de nuit. Une valeur qui n'est ni un auteur de l'édition ni un compte connu est refusée (400), jamais ignorée. Avant les comptes nominatifs (migration V130), tous les administrateurs partageaient le compte de configuration et leurs lignes disent `admin` quel que soit celui qui agissait : elles ne sont pas réécrites. L'écran affiche donc « Auteur fiable depuis le … », date lue dans `flyway_schema_history` (installation de V130, `ReliableAuthorSince`), exacte pour l'instance et antérieure à toute ligne d'une installation neuve, plutôt que « la première ligne portant un compte », qui n'existerait pas tant que personne n'a ouvert de compte nominatif.

Les champs modifiés viennent du service, seul à savoir ce qui a changé : `ReferenceDataService` lit déjà la fiche telle qu'elle était avant l'écriture, pour les avertissements de saisie, et dépose la comparaison dans `CurrentAction`, que le point d'entrée relève en fin d'appel. C'est un enrichissement, pas une obligation : une ligne dont personne n'a fourni les champs s'écrit quand même.
