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
- **Après** : elle écrit `schemaVersion: 3`. Elle lit toujours les fichiers v1 (clé absente ou égale à 1) : ils traversent une chaîne de conversions, maillon par maillon, au fil des changements de modèle de la 4.x. Ce que la chaîne ne peut pas convertir est ignoré, et chaque élément ignoré apparaît dans les avertissements du rapport d'import, jamais en silence. Le maillon 1 → 2 renomme déjà la clé `ninja` des typologies en `polyvalent`, sans rien perdre.
- **Ce que vous faites** : rien pour relire vos anciens fichiers, archives de fin d'événement ou copies avant vidage. Un scénario v2 ou v3 ne s'importe pas dans une application 3.x (« format plus récent ») : gardez un export v1 avant de monter, c'est votre retour arrière. Le lecteur v1 est retiré en 5.0.

### Noms des règles du planning

À venir : correspondance des anciens noms, règles fusionnées ou retirées.

### Outils MCP

**Avant** : 157 outils aux noms presque tous en verbe d'abord (`lister_stands`), avec une dizaine d'exceptions (`equite_planning`, `volumes`…). **Après** : les outils se nomment `<verbe>_<objet>`, le verbe pris dans un ensemble fermé ([ADR 0100](repo:docs/decisions/0100-convention-de-nommage-des-outils-mcp.md)), certains sont regroupés ou retirés, et il n'existe **aucun alias** : un outil n'est publié que sous son nom courant. La correspondance, ancien nom puis nouveau nom ou « retiré », est dans [les anciens noms d'outils](../comprendre/mcp-outils.md#anciens-noms-doutils), remplie au fil des changements de la 4.0 (pour l'instant aucun outil n'est renommé).

**Ce que vous faites** : si vous avez écrit des instructions, des invites ou des scripts qui nomment des outils, relisez-les avec cette table ; un assistant qui se reconnecte relit la liste des outils et n'a rien d'autre à changer.

### Les paramètres de l'édition en quatre sections

- **Avant (3.x)** : quatre racines d'API (`/api/parametres-legaux`, `-qualite`, `-solveur`, `-notifications`), huit outils MCP (`consulter_parametres_legaux`, `modifier_parametres_legaux`, et leurs trois équivalents) et, dans un scénario YAML, les clés `parametresLegaux`, `parametresQualite` et `parametresSolveur` au premier niveau.
- **Après (4.0)** : les paramètres ont quatre sections, `legal`, `organisation` (les anciens « qualité »), `notifications` et `instance` (l'ancien « solveur », le budget de calcul). L'API les sert sous `GET /api/parametres` (les quatre) et `GET`/`PUT /api/parametres/<section>`, qui refuse en 400 un champ qui n'est pas de la section. Deux outils MCP les remplacent : `consulter_parametres` et `modifier_parametres`, chacun avec un argument `section` (même refus). Un scénario les range sous une seule clé, `parametres:` (`legal`, `organisation`, `instance` ; les notifications n'en font pas partie, comme avant). La base ne change pas : aucune migration SQL.
- **Ce que vous faites** : rien pour vos données ni vos fichiers : les scénarios v1 et v2 sont convertis à la lecture. Les anciennes racines d'API répondent toujours, comme alias déclarés obsolètes (`deprecated` dans l'OpenAPI), pendant toute la 4.x, et ignorent comme avant un champ inconnu ; elles sont retirées en 5.0, comme l'ancien format. Si un script ou une invite nomme les anciens outils MCP, relisez-les avec la table des [outils MCP](../comprendre/mcp-outils.md#anciens-noms-doutils) ; aucun alias d'outil n'est publié.

### La typologie « ninja » devient « polyvalent »

- **Avant** : la typologie qui rend ses titulaires disponibles sur tous les stands s'appelait « ninja » : drapeau `ninja` des typologies (REST, YAML, CSV, outils MCP) et colonne `typologie.ninja`.
- **Après** : elle s'appelle « polyvalent ». La migration `V140` renomme la colonne et son index, et corrige les noms de champs déjà inscrits au journal des actions. Le nom de la règle (`preserverBufferPolyvalents`), ses poids et son interrupteur ne changent pas. Les réponses de l'API, les exports YAML et CSV et le champ des outils MCP disent `polyvalent` ; les clés dérivées suivent (`polyvalentConfigure` des rapports de fragilité et de tension, `typologiePolyvalenteDefinie` du rapport de besoin). Les écrans disent « Typologie polyvalente ». Le drapeau `premium` des stands garde son nom et s'affiche « Stand premium ».
- **Ce que vous faites** : rien pour vos données ni vos fichiers. Les anciens noms sont encore lus pendant toute la 4.x (clé JSON et YAML `ninja`, en-tête CSV `ninja`, ce dernier signalé ligne par ligne dans le rapport d'import) et retirés en 5.0. Seuls vos scripts qui *lisent* une clé `ninja` d'une réponse de l'API ou d'un export doivent passer à son nouveau nom.

### Le covoiturage se réduit à validé ou non

- **Avant (3.x)** : une demande « Je viens avec… » passait par quatre états (`EN_ATTENTE`, `VALIDEE`, `ECARTEE`, `ANNULEE`), l'organisation pouvait écrire un motif, et trois courriels partaient : validation, demande écartée, arrivée groupée annulée.
- **Après (4.0)** : une demande est non validée (`EN_ATTENTE`) ou validée (`VALIDEE`). Écarter une demande ou annuler une arrivée groupée supprime la demande, sans motif et sans courriel. Un seul courriel subsiste : la validation, envoyée à chaque membre. La règle `arriveeGroupee` ne change pas, ni l'ajustement qu'une validation crée. La migration `V142` retire de la table les demandes déjà écartées ou annulées, et la colonne du motif : elles sont gardées, motif compris, dans l'archive `declaration_coequipier_avant_4_0`, que l'application ne lit plus et que la 5.0 supprimera ([durée de conservation](conservation-des-donnees.md#larchive-du-covoiturage-3x)) ; l'API ne rend plus `reason`, et les routes `.../ecart` et `.../annulation` répondent `204` sans corps. Le statut rendu par `lister_demandes_covoiturage` n'a plus que deux valeurs.
- **Ce que vous faites** : rien pour vos données. Après une annulation, prévenez vous-même le groupe, puisque plus aucun courriel ne part. Un script qui lisait `ECARTEE`, `ANNULEE` ou `reason` doit s'en passer. Ajoutez l'archive à votre registre des traitements, avec sa durée ([les limites à consigner](rgpd-limites-a-consigner.md#les-demandes-de-covoiturage)).

### Les instantanés deviennent des versions

- **Avant (3.x)** : un plan gardé s'appelait un « instantané » (table `plan_snapshot`), et ce mot recouvrait trois choses qu'on devinait de deux drapeaux : la copie demandée à la main, celle prise d'office avant un calcul (`automatique`) et la publication (`publie_le`). Les routes étaient `/api/planning/snapshots`, les outils MCP `capturer_instantane`, `lister_instantanes`, `consulter_instantane`, `comparer_instantanes`, `restaurer_instantane` et `supprimer_instantane`.
- **Après (4.0)** : un seul type, la **version**, à trois origines écrites dans une colonne : `MANUELLE`, `AVANT_CALCUL`, `PUBLIEE` ([ADR 0108](repo:docs/decisions/0108-un-seul-type-version-a-trois-origines.md)). La migration `V143` renomme la table en `version_plan` : aucune ligne n'est copiée, déplacée ou modifiée, et les traces d'envoi qui visent une publication la visent toujours. Les routes deviennent `/api/planning/versions` ; les anciennes racines `/api/planning/snapshots` répondent encore, avec le même corps, et la réponse porte toujours `automatique`, à côté du nouveau champ `origine`. Les outils MCP deviennent `creer_version`, `lister_versions`, `consulter_version`, `comparer_versions`, `restaurer_version` et `supprimer_version` (sans alias, comme tous les outils), et leurs réponses disent `origine` à la place de `automatique`. Le calcul de comparaison, la purge des copies automatiques (`planning.snapshots.automatiques-conservees`) et la règle qui interdit de supprimer la dernière publication, ou celle qui sert de référence à une journée écoulée, ne changent pas. L'événement webhook `planning.publie` garde son champ `instantaneId`.
- **Ce que vous faites** : rien pour vos données. Un script qui appelle `/api/planning/snapshots` doit passer à `/api/planning/versions` avant la 5.0, qui retire l'alias et `automatique`. Les instructions, invites ou scripts qui nomment un outil `*_instantane*` suivent le tableau des [outils MCP](../comprendre/mcp-outils.md#anciens-noms-doutils). Avant de monter, la sauvegarde habituelle de la base suffit : un retour vers la 3.x passe par la restauration de cette sauvegarde, la 3.x ne connaissant pas `version_plan`.

### La copie avant vidage devient un export YAML

- **Avant (3.x)** : avant « Vider cette édition », un import de scénario ou une restauration, l'application rangeait le scénario de l'édition (texte YAML) **et**, dans une colonne JSON à part (`sauvegarde_avant_vidage.plan`), son planning ; la restauration enchaînait l'import, puis un code propre remettait les sièges et les verrous.
- **Après (4.0)** : la copie est **un seul export YAML**, dont la dernière section, `plan:`, porte les sièges et les verrous ; la restauration est l'import normal de ce fichier, qui remet le planning ([ADR 0112](repo:docs/decisions/0112-la-copie-avant-vidage-est-un-export-yaml-avec-son-plan.md)). La migration `V147` rend seulement la colonne `plan` facultative : **aucune ligne n'est copiée, réécrite ni supprimée**. Les copies déjà rangées par la 3.x restent restaurables et téléchargeables : leur colonne est relue comme la section `plan:` du même fichier. Le fichier téléchargé d'une copie récente porte cette section ; il reste un scénario v3 valide, et l'import à la main d'un tel fichier remet aussi le planning. Les droits (administrateur seul) et la durée (celle de l'édition, aucune purge) ne changent pas.
- **Ce que vous faites** : rien pour vos copies. Si votre registre des traitements décrit « scénario et planning par identifiants », il reste exact ; ajoutez que le fichier est un export YAML complet, à garder comme la base ([les copies avant vidage](rgpd-limites-a-consigner.md#les-copies-avant-vidage)). Le chemin de lecture de l'ancienne colonne est retiré en 5.0, comme le lecteur v1 : avant la 5.0, restaurez ou supprimez les copies de la 3.x dont vous avez encore besoin.

### Données et migrations

À venir : les migrations qui convertissent des données, et ce qu'elles font de vos éditions existantes.
