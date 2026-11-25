# Passer de la 3.x à la 4.0

Vous exploitez une instance en 3.x et vous voulez savoir ce que la 4.0 vous demandera. Cette page est un **squelette** : la 4.0 est en préparation, chaque changement qui casse quelque chose y ajoute son paragraphe au moment où il est fusionné, et aucune date de sortie n'est promise. Ne montez pas de version à partir de ce guide tant que la 4.0.0 n'est pas publiée.

## Où en est-on

- La **3.5** est la dernière version publiée depuis la ligne de développement. La ligne `3.x` ne reçoit plus que des correctifs, sur la branche de maintenance `release/3.5` ([comment elle se tient](publier-une-version.md#3-patcher-une-version-antérieure)).
- Les ruptures de la 4.0 sont suivies dans les tickets [#271](https://github.com/sylvainmetayer/planning-equipes/issues/271) (modèle), [#272](https://github.com/sylvainmetayer/planning-equipes/issues/272) (gel, versions, paramètres), [#273](https://github.com/sylvainmetayer/planning-equipes/issues/273) (règles), [#274](https://github.com/sylvainmetayer/planning-equipes/issues/274) (MCP et écrans) et [#281](https://github.com/sylvainmetayer/planning-equipes/issues/281) (lots de contraintes).
- Combien de temps la 3.x reste soutenue : voir [la politique de maintenance](versioning.md#combien-de-temps-une-ligne-est-corrigée).

## Avant de monter

1. Restez sur la dernière 3.x et lisez ses notes de version : une 3.x peut déjà avertir de ce que la 4.0 retire.
2. Faites une [sauvegarde](sauvegarde-et-restauration.md#2-sauvegarder-à-la-main). Le schéma ne va que vers l'avant : une base migrée en 4.0 ne redescend pas sous une image 3.x.
3. Lisez l'entrée « ⚠️ Attention » des notes de la 4.0, puis les sections ci-dessous.

## Ce qui change

Chaque paragraphe dit *avant*, *après* et *ce que vous faites*. Les paragraphes s'écrivent au fil des fusions ; ceux qui ne le sont pas encore portent « À venir ».

### Variables d'environnement

À venir : tableau ancien nom, nouveau nom.

### Le mode en-tête de proxy est retiré

- **Avant (3.x)** : `REMOTE_USER_ENABLED=true`, avec `REMOTE_USER_SECRET`, laissait un proxy d'accès authentifier l'administrateur (`REMOTE_USER_ADMIN_EMAIL`) et ouvrir l'espace animateur sans code, par un en-tête. Une 3.x l'avertissait déjà « déprécié ».
- **Après (4.0)** : le mécanisme n'existe plus. Les cinq variables `REMOTE_USER_ENABLED`, `REMOTE_USER_HEADER`, `REMOTE_USER_SECRET_HEADER`, `REMOTE_USER_SECRET` et `REMOTE_USER_ADMIN_EMAIL` n'ont plus aucun effet. Si l'une est renseignée, le démarrage le dit dans un avertissement et continue ; l'avertissement disparaît en 5.0. L'en-tête d'un proxy n'ouvre plus rien, ni l'administration ni l'espace d'un animateur.
- **Ce que vous faites** : **avant** de monter, configurez [la connexion par un fournisseur OIDC](oidc.md) ou créez des [comptes locaux](comptes-locaux.md), et vérifiez qu'un compte administrateur nominatif s'ouvre. Les animateurs reprennent le code à six chiffres envoyé par courriel. Retirez ensuite les `REMOTE_USER_*` de votre environnement. Votre proxy peut rester devant l'instance comme filtre réseau, il ne remplace plus la connexion.

### Le lanceur de machine à la demande est retiré

- **Avant (3.x)** : `SOLVER_WORKER_HOOK_URL` et `SOLVER_WORKER_HOOK_SECRET` branchaient un lanceur qui créait une machine louée le temps d'un calcul, avec `SOLVER_WORKER_INSTANCE`, `SOLVER_WORKER_START_TIMEOUT` et `SOLVER_WORKER_IDLE_TIMEOUT`. L'écran Solveur montrait l'état de cette machine, avec « Préparer » et « Arrêter », et le dépôt livrait `deploy/launcher/`.
- **Après (4.0)** : l'application ne démarre ni n'arrête plus aucune machine. Les cinq variables ci-dessus n'ont plus aucun effet : si l'une est renseignée, le démarrage le dit dans un avertissement et continue ; l'avertissement disparaît en 5.0. Le **worker permanent** (`SOLVER_WORKER_URL`, `SOLVER_WORKER_SECRET`, `SOLVER_WORKER_TLS_FINGERPRINT`, `SOLVER_WORKER_DEFAULT`) ne change pas, ni la règle qu'un échec distant fait échouer la tâche. Le choix « Sur place » ou « Sur la machine de calcul » reste sur l'écran Solveur.
- **Ce que vous faites** : si vous utilisiez le lanceur, louez et allumez la machine par les outils de votre hébergeur, lancez-y le worker ([la procédure](calcul-sur-une-autre-machine.md)) et renseignez `SOLVER_WORKER_URL` et `SOLVER_WORKER_SECRET`. Sans cela, vos calculs tournent sur place. Retirez ensuite les cinq variables de votre environnement.

### Format du scénario YAML

- **Avant** : l'application écrit `schemaVersion: 1`.
- **Après** : elle écrit `schemaVersion: 3`. Elle lit toujours les fichiers v1 (clé absente ou égale à 1) : ils traversent une chaîne de conversions, maillon par maillon, au fil des changements de modèle de la 4.x. Ce que la chaîne ne peut pas convertir est ignoré, et chaque élément ignoré apparaît dans les avertissements du rapport d'import, jamais en silence. Le premier maillon (1 → 2) ne change aucune clé ; le second (2 → 3) convertit les horaires des stands en plages (voir [Horaires des stands](#horaires-des-stands)).
- **Ce que vous faites** : rien pour relire vos anciens fichiers, archives de fin d'événement ou copies avant vidage. Un scénario v2 ne s'importe pas dans une application 3.x (« format plus récent ») : gardez un export v1 avant de monter, c'est votre retour arrière. Le lecteur v1 est retiré en 5.0.

### Noms des règles du planning

À venir : correspondance des anciens noms, règles fusionnées ou retirées.

### Outils MCP

**Avant** : 157 outils aux noms presque tous en verbe d'abord (`lister_stands`), avec une dizaine d'exceptions (`equite_planning`, `volumes`…). **Après** : les outils se nomment `<verbe>_<objet>`, le verbe pris dans un ensemble fermé ([ADR 0100](repo:docs/decisions/0100-convention-de-nommage-des-outils-mcp.md)), certains sont regroupés ou retirés, et il n'existe **aucun alias** : un outil n'est publié que sous son nom courant. La correspondance, ancien nom puis nouveau nom ou « retiré », est dans [les anciens noms d'outils](../comprendre/mcp-outils.md#anciens-noms-doutils), remplie au fil des changements de la 4.0 (pour l'instant aucun outil n'est renommé).

**Ce que vous faites** : si vous avez écrit des instructions, des invites ou des scripts qui nomment des outils, relisez-les avec cette table ; un assistant qui se reconnecte relit la liste des outils et n'a rien d'autre à changer.

### Horaires des stands

- **Avant** : un stand disait ses horaires par des **règles récurrentes** (« tous les jours de 10 h à 12 h puis de 14 h à la fermeture », ou chaque samedi, ou une plage de dates, ou des dates précises), plus des exceptions datées. L'application repliait ces règles à chaque lecture, et l'écran proposait de « compacter » des saisies datées en règles.
- **Après** : un stand porte une **liste de plages datées**, une ouverture ou une fermeture par jour ([décision 0104](repo:docs/decisions/0104-les-horaires-d-un-stand-sont-une-liste-de-plages-datees.md)). Une récurrence n'est plus stockée : elle sert à écrire des plages. La migration `V141` convertit vos règles sur les jours de chaque édition (du premier au dernier créneau, plus le lendemain d'un créneau qui passe minuit), **avec la lecture exacte que l'application en avait** : un test la rejoue sur chacun des scénarios livrés et compare, jour par jour, les fenêtres obtenues à celles de la 3.x. Le YAML passe de même en version 3, la clé `plages` remplaçant `horaires`, `ouvertures` et `indisponibilites`.
- **Ce que vous faites** :
  - **Exportez chaque édition en YAML avant de monter** (Fichiers, « Exporter les données actuelles en scénario »). C'est votre retour arrière, et le seul moyen de retrouver le détail de vos règles : la migration ne redescend pas.
  - **Une édition sans aucun créneau n'a pas de jour où poser ses règles.** Elles ne sont ni converties ni perdues : la migration les garde, à part, dans les tables `stand_horaire_avant_4_0` et `stand_horaire_fenetre_avant_4_0` (supprimées en 5.0), et écrit un avertissement par édition concernée dans le journal de démarrage (`Édition X : ses horaires récurrents de stand ne sont pas convertis…`). Pour les retrouver : ajoutez les créneaux à l'export YAML de cette édition puis réimportez-le (le maillon de conversion pose les règles sur les jours des créneaux **du fichier** ; un fichier sans créneau les écarte, avec un avertissement dans le rapport d'import), ou saisissez des plages.
  - **Un jour ajouté après coup** à l'événement (un créneau sur une date que l'édition ne comptait pas) n'a plus de règle pour le gouverner : le stand y est ouvert toute la journée, même s'il « n'ouvrait que le week-end ».
  - L'écran de saisie des horaires récurrents, l'action « Compacter les horaires » et l'outil MCP `compacter_horaires_stands` n'ont plus rien à faire : l'écran est remplacé par un éditeur de plages dans un changement suivant, et pendant ce temps l'API refuse d'enregistrer une règle (`400`, jamais une perte silencieuse). L'outil MCP `ajouter_horaire_stand` existe toujours : il écrit des plages sur les jours des créneaux de l'édition, et refuse une édition sans créneau. `effacer_horaires_stand` est retiré (voir [les anciens noms d'outils](../comprendre/mcp-outils.md#anciens-noms-doutils)).

### Données et migrations

Les migrations qui convertissent des données sont décrites ci-dessus, une par changement, au fil de leur fusion : pour l'instant, [les horaires des stands](#horaires-des-stands).
