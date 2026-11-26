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

#### Marque blanche et mentions légales : vingt-six variables deviennent huit

- **Avant (3.x)** : quatorze variables `BRANDING_*` (nom, organisation, logos, mascottes, couleurs, palette des PDF, adresse de support) et douze `LEGAL_*` (mentions légales, déclaration d'accessibilité, drapeau de démonstration).
- **Après (4.0)** : huit variables. `BRANDING_PRODUCT_NAME`, `BRANDING_ORGANISATION`, `BRANDING_SUPPORT_EMAIL` et `LEGAL_DEMO_INSTANCE` ne changent pas. Quatre variables **groupées**, de la forme `clé=valeur;clé=valeur` (un point-virgule littéral s'écrit `\;`), remplacent les autres : `BRANDING_IMAGES`, `BRANDING_COLORS`, `LEGAL_MENTIONS` et `LEGAL_ACCESSIBILITE`. Les anciens noms sont **encore lus** pendant toute la 4.x : le démarrage avertit pour chacun, la clé groupée l'emporte quand les deux sont posées, et ils seront retirés en 5.0. Le refus de démarrer sans les cinq mentions exigées reste en place.
- **Ce que vous faites** : à votre rythme avant la 5.0, remplacez chaque ancienne variable par sa clé, puis retirez-la de votre environnement (compose, `.env`, CI de déploiement).

| Ancienne variable | Variable groupée, clé |
| --- | --- |
| `BRANDING_LOGO_URL`, `BRANDING_MASCOT_URL`, `BRANDING_MASCOT_ICON_URL` | `BRANDING_IMAGES` : `logo`, `mascot`, `mascot-icon` |
| `BRANDING_PDF_LOGO`, `BRANDING_PDF_STRIP` | `BRANDING_IMAGES` : `pdf-logo`, `pdf-strip` |
| `BRANDING_ACCENT_COLOR` | `BRANDING_COLORS` : `accent` |
| `BRANDING_PDF_HEADLINE`, `BRANDING_PDF_MUTED`, `BRANDING_PDF_ACCENT`, `BRANDING_PDF_HIGHLIGHT`, `BRANDING_PDF_PILL` | `BRANDING_COLORS` : `pdf-headline`, `pdf-muted`, `pdf-accent`, `pdf-highlight`, `pdf-pill` |
| `LEGAL_EDITEUR`, `LEGAL_HEBERGEUR`, `LEGAL_CONTACT`, `LEGAL_BASE_LEGALE`, `LEGAL_CONSERVATION`, `LEGAL_RESPONSABLE_TRAITEMENT`, `LEGAL_DIRECTEUR_PUBLICATION` | `LEGAL_MENTIONS` : `editeur`, `hebergeur`, `contact`, `base-legale`, `conservation`, `responsable-traitement`, `directeur-publication` |
| `LEGAL_ACCESSIBILITE_ETAT`, `LEGAL_ACCESSIBILITE_DATE_AUDIT`, `LEGAL_ACCESSIBILITE_CONTENUS`, `LEGAL_ACCESSIBILITE_SIGNALEMENT` | `LEGAL_ACCESSIBILITE` : `etat`, `date-audit`, `contenus`, `signalement` |

Le détail et un exemple sont dans [les variables d'environnement](variables-environnement.md#marque-et-documents) et [les mentions légales](mentions-legales.md).

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
- **Après** : elle écrit `schemaVersion: 3`. Elle lit toujours les fichiers v1 (clé absente ou égale à 1) : ils traversent une chaîne de conversions, maillon par maillon, au fil des changements de modèle de la 4.x. Ce que la chaîne ne peut pas convertir est ignoré, et chaque élément ignoré apparaît dans les avertissements du rapport d'import, jamais en silence. Le maillon 1 → 2 renomme la clé `ninja` des typologies en `polyvalent`, sans rien perdre ; le maillon 2 → 3 convertit les horaires des stands en plages (voir [Horaires des stands](#horaires-des-stands)).
- **Ce que vous faites** : rien pour relire vos anciens fichiers, archives de fin d'événement ou copies avant vidage. Un scénario v2 ne s'importe pas dans une application 3.x (« format plus récent ») : gardez un export v1 avant de monter, c'est votre retour arrière. Le lecteur v1 est retiré en 5.0.

### Noms des règles du planning

À venir : correspondance des anciens noms, règles fusionnées ou retirées.

### Outils MCP

**Avant** : 157 outils aux noms presque tous en verbe d'abord (`lister_stands`), avec une dizaine d'exceptions (`equite_planning`, `volumes`…). **Après** : les outils se nomment `<verbe>_<objet>`, le verbe pris dans un ensemble fermé ([ADR 0100](repo:docs/decisions/0100-convention-de-nommage-des-outils-mcp.md)), certains sont regroupés ou retirés, et il n'existe **aucun alias** : un outil n'est publié que sous son nom courant. La correspondance, ancien nom puis nouveau nom ou « retiré », est dans [les anciens noms d'outils](../comprendre/mcp-outils.md#anciens-noms-doutils), remplie au fil des changements de la 4.0 (pour l'instant aucun outil n'est renommé).

**Ce que vous faites** : si vous avez écrit des instructions, des invites ou des scripts qui nomment des outils, relisez-les avec cette table ; un assistant qui se reconnecte relit la liste des outils et n'a rien d'autre à changer.

### La typologie « ninja » devient « polyvalent »

- **Avant** : la typologie qui rend ses titulaires disponibles sur tous les stands s'appelait « ninja » : drapeau `ninja` des typologies (REST, YAML, CSV, outils MCP) et colonne `typologie.ninja`.
- **Après** : elle s'appelle « polyvalent ». La migration `V140` renomme la colonne et son index, et corrige les noms de champs déjà inscrits au journal des actions. Le nom de la règle (`preserverBufferPolyvalents`), ses poids et son interrupteur ne changent pas. Les réponses de l'API, les exports YAML et CSV et le champ des outils MCP disent `polyvalent` ; les clés dérivées suivent (`polyvalentConfigure` des rapports de fragilité et de tension, `typologiePolyvalenteDefinie` du rapport de besoin). Les écrans disent « Typologie polyvalente ». Le drapeau `premium` des stands garde son nom et s'affiche « Stand premium ».
- **Ce que vous faites** : rien pour vos données ni vos fichiers. Les anciens noms sont encore lus pendant toute la 4.x (clé JSON et YAML `ninja`, en-tête CSV `ninja`, ce dernier signalé ligne par ligne dans le rapport d'import) et retirés en 5.0. Seuls vos scripts qui *lisent* une clé `ninja` d'une réponse de l'API ou d'un export doivent passer à son nouveau nom.

### Horaires des stands

- **Avant** : un stand disait ses horaires par des **règles récurrentes** (« tous les jours de 10 h à 12 h puis de 14 h à la fermeture », ou chaque samedi, ou une plage de dates, ou des dates précises), plus des exceptions datées. L'application repliait ces règles à chaque lecture, et l'écran proposait de « compacter » des saisies datées en règles.
- **Après** : un stand porte une **liste de plages datées**, une ouverture ou une fermeture par jour ([décision 0104](repo:docs/decisions/0104-les-horaires-d-un-stand-sont-une-liste-de-plages-datees.md)). Une récurrence n'est plus stockée : elle sert à écrire des plages. La migration `V141` convertit vos règles sur les jours de chaque édition (du premier au dernier créneau, plus le lendemain d'un créneau qui passe minuit), **avec la lecture exacte que l'application en avait** : un test la rejoue sur chacun des scénarios livrés et compare, jour par jour, les fenêtres obtenues à celles de la 3.x. Le YAML passe de même en version 3, la clé `plages` remplaçant `horaires`, `ouvertures` et `indisponibilites`.
- **Ce que vous faites** :
  - **Exportez chaque édition en YAML avant de monter** (Fichiers, « Exporter les données actuelles en scénario »). C'est votre retour arrière, et le seul moyen de retrouver le détail de vos règles : la migration ne redescend pas.
  - **Une édition sans aucun créneau n'a pas de jour où poser ses règles.** Elles ne sont ni converties ni perdues : la migration les garde, à part, dans les tables `stand_horaire_avant_4_0` et `stand_horaire_fenetre_avant_4_0` (supprimées en 5.0), et écrit un avertissement par édition concernée dans le journal de démarrage (`Édition X : ses horaires récurrents de stand ne sont pas convertis…`). Pour les retrouver : ajoutez les créneaux à l'export YAML de cette édition puis réimportez-le (le maillon de conversion pose les règles sur les jours des créneaux **du fichier** ; un fichier sans créneau les écarte, avec un avertissement dans le rapport d'import), ou saisissez des plages.
  - **Un jour ajouté après coup** à l'événement (un créneau sur une date que l'édition ne comptait pas) n'a plus de règle pour le gouverner : le stand y est ouvert toute la journée, même s'il « n'ouvrait que le week-end ».
  - L'écran de saisie des horaires récurrents, l'action « Compacter les horaires » et l'outil MCP `compacter_horaires_stands` n'ont plus rien à faire : l'écran est remplacé par un éditeur de plages dans un changement suivant, et pendant ce temps une règle que l'écran actuel envoie est convertie aussitôt en plages sur les jours de l'événement (refusée en `400` dans une édition sans créneau, jamais perdue en silence). L'outil MCP `ajouter_horaire_stand` existe toujours : il écrit des plages sur les jours des créneaux de l'édition, et refuse une édition sans créneau. `effacer_horaires_stand` est retiré (voir [les anciens noms d'outils](../comprendre/mcp-outils.md#anciens-noms-doutils)).

### Données et migrations

Les migrations qui convertissent des données sont décrites ci-dessus, une par changement, au fil de leur fusion : pour l'instant, [les horaires des stands](#horaires-des-stands).
