# Le fichier de scénario YAML

Vous savez ce qu'un fichier de scénario décrit, ce que son import remplace dans l'édition, ce que l'export garantit au réimport, et ce qu'il faut toucher quand on fait évoluer le format.

Un scénario décrit un événement entier : référentiel, grille, équipe, réglages. C'est le fichier qu'on échange, qu'on versionne et qu'on rejoue en test. Sa structure est le [schéma JSON](repo:docs/schema/scenario-schema.json), généré depuis les DTO du paquet `dev.sylvain.planning.scenario.dto`. Il ne peut donc pas diverger de ce que le code accepte. Ce qui suit porte les règles que le schéma n'exprime pas. Tout est lu et écrit dans l'édition désignée par `X-Edition-Id` (voir [Une édition, une partition des données](cloisonnement-par-edition.md)).

## Où se fait chaque geste

Tout passe par l'écran Fichiers (`/fichiers`), qui réunit exporter, corriger et réimporter, ainsi que l'archive de fin d'événement. Ses trois onglets sont portés par `?onglet=importer|exporter|archive`, la carte ouverte de l'onglet Importer par `?cible=`. Les anciennes adresses `/imports`, `/exports`, `/export-csv` et `/validateur-yaml` y redirigent.

| Geste | Adresse |
| --- | --- |
| Importer un scénario | `/fichiers?cible=scenario` |
| Vérifier un fichier sans rien écrire | `/fichiers?cible=verifier` |
| Charger un exemple livré | `/fichiers?cible=exemples` |
| Exporter l'édition en scénario | `/fichiers?onglet=exporter` |

Les exemples sont rangés « pour découvrir » ou « pour tester un cas », avec un nom lisible et une phrase. Les fichiers `gamme-*` et `extreme-*` sont livrés et se chargent par leur nom, mais ni l'écran ni l'outil MCP `lister_scenarios` ne les listent. Côté API : `POST /api/reference-data/import-scenario-fichier`, `POST /api/reference-data/valider-scenario-fichier` et `GET /api/planning/export-scenario`.

## Ce que l'import remplace

![Import d'un scénario YAML](repo:docs/diagrammes/import-scenario.svg)

<sub>Source : [`diagrammes/import-scenario.puml`](repo:docs/diagrammes/import-scenario.puml).</sub>

- Rien ne sort de l'édition cible.
- Animateurs et stands sont remplacés en totalité : ceux que le fichier désigne sont mis à jour sur place, les autres sont supprimés.
- Les créneaux reçoivent de nouveaux ids en base. Les contraintes ad hoc qui les citaient par leur id de fichier sont réassociées.
- Les affectations et les contraintes ad hoc de l'édition sont supprimées. Ces dernières ne sont réécrites que si le fichier porte la section `contraintesAdHoc`.
- Consignes et préréglages de consigne sont remplacés si le fichier porte la section correspondante, et laissés en place sinon. C'est la règle de tous les référentiels du fichier : une section présente remplace, une section absente ne touche à rien.

Avant d'écrire, l'import range l'état de l'édition dans une sauvegarde que [Restaurer une édition](restaurer-une-edition.md) permet de rejouer.

Une section `edition: { id?, nom? }` route l'import vers une autre édition : celle de cet identifiant si elle existe, sinon la seule édition de ce nom, sinon une édition créée vide sous ce nom, dont l'application attribue l'identifiant. La réponse dit toujours où les données ont atterri, parce que l'opérateur peut regarder une autre édition que celle qui vient d'être écrite.

## Les identifiants du fichier sont des références locales

L'application attribue tous les identifiants ([ADR 0050](repo:docs/decisions/0050-identifiants-generes-par-edition.md)) : `A1`, `A2`… pour les animateurs, `S…` pour les stands, `T…` pour les typologies, `L…` pour les emplacements, `C…` pour les contraintes ad hoc, numérotés dans chaque édition. Dans un fichier, l'identifiant d'une ligne ne sert qu'à la citer ailleurs (un siège, une contrainte, une consigne, une compétence). Ce qu'il désigne dans l'édition cible se décide ligne par ligne :

| Ordre | Animateur | Stand, typologie, emplacement |
| --- | --- | --- |
| 1 | la fiche de même identifiant, si elle a la même adresse e-mail (à défaut d'adresse, les mêmes nom et prénom) | la ligne de même identifiant, si son `code` ne contredit pas celui du fichier |
| 2 | la seule fiche de cette adresse, puis la seule de ces nom et prénom | la ligne de ce `code`, ou de l'identifiant lu comme un code pour un fichier écrit à la main (`STRATEGIE`) |
| 3 | une fiche nouvelle | une ligne nouvelle, qui garde l'identifiant du fichier comme code quand il est lisible |

La règle 1 fait qu'un scénario exporté puis réimporté dans la même édition met à jour au lieu de recréer : les jetons d'espace (les liens imprimés sur les plannings), les sessions, les demandes d'échange et les confirmations survivent. La vérification d'identité qui l'accompagne est nécessaire, car chaque édition numérote à partir de 1 et `A3` d'une autre édition est quelqu'un d'autre. Les contraintes ad hoc, remplacées en bloc, gardent leur identifiant quand l'édition le porte et en reçoivent un sinon.

## Ce que l'export garantit

« Exporter les données actuelles en scénario » écrit toutes les sections que l'import sait relire : les entités, mais aussi `typologies`, `emplacements`, `parametres` (légal, organisation, instance), `journeesTypes`, `contraintes`, `contraintesAdHoc`, `prereglagesConsigne` et `consignes`. Réimporter le fichier reproduit exactement le même problème. C'est la raison d'être de l'export.

Un champ oublié dans une section n'est pas « non surchargeable » : l'import écrit un objet neuf et le champ retombe au défaut. `heureDebutSoiree`, absente du DTO à l'origine, ramenait ainsi une édition réglée à 22 h à 20 h et déplaçait les heures de soirée de l'équité sans un mot. Un fichier sans ces sections retombe de même sur les réglages de l'instance qui l'importe et rejoue un autre problème. Pour qui touche au format : chaque champ lu doit aussi être écrit.

Les créneaux sortent toujours tels quels, avec `couverturePause: true` quand ils couvrent un service de repas (le stand n'y ouvre que la moitié de son effectif, arrondie au supérieur ; le drapeau n'est écrit que lorsqu'il vaut vrai). La liste de sièges `postes`, elle, ne sort pas (voir plus bas).

## `schemaVersion`

Un fichier écrit par l'application commence par `schemaVersion: 4` (`ScenarioBinder.SCHEMA_VERSION`). À la lecture, la clé est facultative et vaut 1 quand elle manque. Un fichier plus ancien que la version courante traverse une **chaîne de conversions** (`ScenarioMigrations`) avant d'être lié : chaque maillon est une fonction pure de la version *n* à la *n + 1*, avec son test, et chaque changement de modèle ajoute le sien. Ce qu'un maillon ne peut pas convertir est ignoré **avec un avertissement dans le rapport d'import** (`avertissements`), jamais en silence. Les scénarios livrés, les archives de fin d'événement et les copies faites avant un vidage restent ainsi lisibles. Le lecteur v1 est gardé pendant toute la 4.x et retiré en 5.0 ([ADR 0101](repo:docs/decisions/0101-schema-yaml-2-et-chaine-de-conversions.md)). Un numéro supérieur à celui que la version lit est refusé avec une phrase claire (« produit par une version plus récente ») plutôt qu'avec un « champ inconnu » sur la première clé nouvelle. Une version de l'application antérieure à la clé refuse tout fichier qui la porte, comme n'importe quelle clé inconnue.

## `contraintes.niveaux` : le niveau choisi par règle

Depuis la version 4 du format, la section `contraintes` porte aussi `niveaux`, le niveau que l'édition a choisi pour une règle d'organisation : `OBLIGATOIRE`, `PREFERENCE`, ou une position (`PREFERENCE_FORTE`, `PREFERENCE_NORMALE`, `PREFERENCE_LEGERE`) qui donne en plus le poids de cette position (25, 5, 1) quand `poids` ne nomme pas la règle. Une règle que le fichier ne nomme pas revient au niveau du catalogue. Une règle qui n'a pas de niveau à choisir (légale, d'affectation, ad hoc, verrou, préférence souple) est **refusée par son nom**. Un fichier 3.x ou v3 n'en porte pas, et se lit comme avant : le maillon 3 → 4 ne change rien ([ADR 0108](repo:docs/decisions/0108-le-niveau-d-une-regle-est-le-niveau-de-son-poids.md)).

```yaml
contraintes:
  niveaux:
    stabiliteDuPlanPublie: OBLIGATOIRE
    equilibrerCharge: PREFERENCE_FORTE
```

## `postes` : absente et vide ne disent pas la même chose

Absente, la section est générée à partir des stands et des créneaux importés, par le constructeur qu'utilise aussi une résolution : un siège par place sur chaque créneau et segment réellement ouvert. Le nombre de places vient de la fenêtre d'ouverture quand elle porte un `effectif`, sinon de `effectifMin`, jamais de `effectifMax` (voir [Le modèle des données](modele-des-donnees.md)).

Présente, elle est reprise telle quelle, y compris vide, ce qui permet un staffing hors règle ; aucun scénario livré ne s'en sert. Une liste écrite répète ce que `effectifMin` dit déjà et fige la grille contre toute modification ultérieure d'un stand. L'export ne l'écrit donc jamais.

## `contraintes` : en bloc, jamais en fusion

Une règle que la section ne cite pas redevient active, à son poids par défaut. Un scénario qui épingle son réglage décrit le problème contre lequel il a été vérifié. Une fusion laisserait en place les restes de l'édition qui importe, et le même fichier résoudrait un problème différent selon l'endroit où il atterrit. Absente, la section ne touche à rien.

Un nom de contrainte absent du catalogue est refusé en 400 au lieu d'être ignoré : c'est une faute de frappe ou un fichier écrit contre une autre version du catalogue, et l'ignorer laisserait croire qu'une règle est éteinte alors qu'elle ne l'est pas.

Les scénarios livrés portent tous cette section, avec les deux seuls poids qui s'écartent du défaut, `appreciationIncompatible: 15` et `maxJoursConsecutifsTravailles: 25`. Le défaut du déploiement est 1 pour une règle dure et 5 pour une règle moyenne ou souple : le dosage voyage avec le scénario.

Les poids se lisent sur l'échelle actuelle (faible 1, normale 5, forte 25, voir [ADR 0057](repo:docs/decisions/0057-importance-d-une-regle-en-trois-positions.md)). Un fichier écrit sur l'ancienne échelle, où le défaut valait 1, ferait peser ses règles citées cinq fois moins que les autres : multipliez par cinq ses poids de règles moyennes et souples avant de l'importer. Le dosage est détaillé dans [Doser et désactiver les règles](doser-et-desactiver-les-regles.md).

`contraintesAdHoc` remplace celles de l'édition, et son absence n'en installe aucune. Elle désigne animateurs, stands et créneaux par les ids du fichier. L'import n'hérite jamais des exceptions de l'édition courante : une exception recopiée dans une autre édition y viserait un stand et un créneau inexistants, et dans sa propre édition elle viserait des créneaux que l'import vient de supprimer. Ce qui préserve les exceptions d'un aller-retour, c'est l'export, qui écrit la section dès que l'édition en porte une. Une exception qui ne peut pas tenir avec une autre du même fichier fait refuser l'import entier avant toute écriture, avec un message qui les nomme : c'est le contrôle de la saisie (voir [Les exceptions ad hoc](exceptions-ad-hoc.md)).

## Horaires d'un stand

Le sélecteur de jours est à plat : `jours` nomme lequel des champs voisins s'applique (`TOUS` par défaut, `JOURS_SEMAINE`, `PLAGE`, `DATES`), et seul celui-là est lu.

- `heureFin` omise signifie « jusqu'à la fermeture » : la fenêtre court jusqu'à la fin du créneau évalué. Une même règle couvre ainsi un jour qui ferme à 20 h et un jour qui ferme à minuit, sans le contournement `23:59` qu'imposait une heure concrète (une fenêtre ne peut pas chevaucher minuit).
- `effectif` omis signifie « hériter de `effectifMin` », le cas le plus courant. Le renseigner sur une fenêtre permet à un stand dont la charge varie dans la journée (4 personnes le matin, 5 le soir) de rester un seul stand. Le champ existe sur une fenêtre de règle récurrente et sur une ouverture datée.

Une portée plus précise prime sur une portée plus large, et une exception datée prime sur toutes les règles pour le seul jour qu'elle nomme. L'arbitrage complet est dans [Horaires, consignes et fenêtres effectives](horaires-et-fenetres-des-stands.md).

## Typologies, journées types et clés retirées

Chaque stand du fichier propose au moins une typologie : `typologiesProposees: []` est refusé par le validateur comme par l'import, qui nomme l'entrée et son id. Une typologie citée sans être déclarée est créée avec un libellé et un code égaux à la valeur citée ; la section `typologies`, appliquée après l'import pour ne pas être écrasée par cette création, fixe un vrai libellé et un `code`. Au plus une typologie porte `polyvalent: true` (en déclarer une retire le drapeau de la précédente). Chaque entrée peut porter `maxCreneauxParAnimateur`, le plafond de créneaux sur l'édition entière, et `description`, la note affichée sur l'écran Typologies.

`journeesTypes` est optionnelle : nom, vacations (avec `couverturePause` pour un relais repas) et dates que chaque journée gouverne. Appliquée après les créneaux, elle remplace journées types et calendrier mais n'écrit aucun créneau : un fichier cohérent liste sous `creneaux` ce que ses journées types disent. Absente, l'import reconnaît les journées types que les créneaux impliquent ([ADR 0032](repo:docs/decisions/0032-journees-types-nommees-vacations-fixes.md)).

Certaines clés sont refusées par leur nom, avec un message qui dit quoi écrire à la place. Les accepter en les ignorant serait pire : une grille d'amplitudes s'importerait comme des vacations de quatorze heures sans un mot, et un fichier vérifié sous un mode de pause serait jugé sous une règle qui n'est plus la sienne.

| Clé refusée | Remplacement |
| --- | --- |
| `parametresDecoupage`, `decoupageAuto` | le découpage automatique n'existe plus ([ADR 0037](repo:docs/decisions/0037-une-grille-est-toujours-des-vacations.md)) ; `dureeVacationMaxMinutes` a rejoint `parametres.legal` |
| `pauseSurPoste`, `pauseMinimaleEntreVacationsMinutes`, `dureePauseMajeurMinutes`, `dureePauseMineurMinutes` (dans `parametres.legal`) | une seule durée, `dureePauseMinutes`, et une seule règle de pause ([ADR 0048](repo:docs/decisions/0048-une-seule-regle-de-pause.md)) |

## Consignes et préréglages

Deux sections optionnelles portent ce qu'un arrêté a imposé à l'édition ([ADR 0043](repo:docs/decisions/0043-consigne-d-edition-fermer-une-bande-sans-rien-detruire.md)). `prereglagesConsigne` liste les préréglages nommés. `consignes` compte une entrée par date : bande interdite, motif, nom du préréglage d'origine, fenêtres par défaut, ouvertures stand par stand avec leur effectif, créneaux ajoutés, et un bloc `repas` quand la date roule sous d'autres fenêtres repas que l'édition.

```yaml
prereglagesConsigne:
  - id: 4f1c…
    nom: Plan canicule
    fermetureDebut: '12:00'
    fermetureFin: '16:00'
    motif: Arrêté préfectoral canicule
    fenetres:
      - debut: '18:00'
        fin: '20:00'
consignes:
  - date: '2033-07-08'
    fermetureDebut: '12:00'
    fermetureFin: '16:00'
    motif: Arrêté préfectoral canicule
    prereglage: Plan canicule
    fenetres:
      - debut: '18:00'
        fin: '20:00'
    ouvertures:
      - standId: STAND-A
        debut: '18:00'
        fin: '20:00'
        effectif: 2
    creneauxAjoutes:
      - date: '2033-07-08'
        heureDebut: '18:00'
        heureFin: '20:00'
    repas:
      soirDebut: '18:00'
      soirFin: '22:00'
      justification: Repas pris pendant la bande fermée
```

Le fichier est tenu aux règles de l'écran : le bloc `repas` n'est écrit que lorsqu'il surcharge quelque chose et sa `justification` est alors obligatoire, une fenêtre repas porte ses deux bornes ou aucune. Une fin écrite `'00:00'` signifie « jusqu'à minuit » et se relit comme une fin absente. L'`effectif` d'une ouverture est celui de sa fenêtre : un stand rouvert sur deux fenêtres à des effectifs différents s'écrit en deux entrées. Un préréglage écrit à la main peut omettre son `id`.

Les créneaux ajoutés voyagent par clé naturelle, parce que les ids de `creneaux` ne survivent pas à un export (l'import renumérote la section). Le créneau sort tel quel sous `creneaux`, et `creneauxAjoutes` le désigne par date, heure de début et heure de fin. Une clé qui ne désigne aucun créneau du fichier est refusée, comme une ouverture sur un stand que le fichier ne déclare pas. Un créneau qu'aucun stand n'ouvre nominalement (une soirée que seule la consigne ouvre) n'aurait aucun siège : la consigne le recrée.

L'import écrit directement dans le référentiel, après les stands et les créneaux, et jamais par le geste de l'écran Consignes. Celui-ci refuse une date passée ou en cours et ajoute lui-même les créneaux que ses fenêtres exigent : deux choses qu'un fichier a déjà tranchées. Une consigne sur une date passée s'importe donc, et les statistiques de cette journée sont celles de ce qui a été fait.

Un scénario résolu directement depuis `src/main/resources/scenarios/` (`PlanningService.buildExample`) ne les applique pas : la couche consigne est posée par `StandService.resolve` sur le référentiel, et ce scénario est résolu sur sa grille nominale. Elles prennent effet une fois le fichier importé.

## Les fichiers livrés

Chaque scénario vit à plat dans `src/main/resources/scenarios/` : les exemples de l'écran Fichiers listent ce dossier et `ScenarioYamlReader.scenarioPath` refuse tout composant de chemin, donc un scénario rangé dans un sous-dossier, ou dans un second dossier `scenarios/` que `getResource` masquerait, n'est choisi par personne. Les tests les lisent par `ScenariosLivres`.

| Fichiers | Rôle |
| --- | --- |
| `scenario-*.yaml`, `scenario.yml` | cas d'école du format |
| `gamme-01-…` à `gamme-30-…` | difficulté croissante, dont cinq cas `infaisable-…` |
| `extreme-01-…` à `extreme-15-…` | cas limites de volume ou de forme |
| `festival-realiste-canicule.yaml`, `festival-hivernal.yaml` | fixtures réalistes anonymisées |
| `scenario-avec-erreur-planning.yaml` | fixture volontairement insoluble |
| `vitrine-fete-du-jeu-de-pierrefeuille.yaml` | démonstration |
| `exemple-animateurs.csv` | modèle du [CSV des animateurs](imports-csv.md#limport-csv-des-animateurs) |

### Fixtures réalistes anonymisées

`festival-realiste-canicule.yaml` est dérivé d'une édition réelle. Il sert à une démonstration grandeur nature et à la régression de convergence de `PlanningServiceScenarioFestivalRealisteTest`. L'anonymisation réécrit prénoms, noms, ids, noms d'emplacements et de stands, et nom d'édition. Heures, effectifs, compétences, horaires, typologies et paramètres ne bougent pas : une fixture qui ne converge pas comme sa source ne teste pas ce qu'elle prétend tester.

Le calendrier est la seule exception. `--date-debut` translate l'événement en bloc pour le détacher des dates réelles (ces fixtures ouvrent le 01/09/2026). Les dates de naissance ne bougent jamais, car les décaler changerait qui est mineur et donc le problème. Un décalage multiple de 7 conserverait les jours de la semaine et l'ancrage des semaines ISO ; celui-ci ne l'est pas, il déplace donc les bornes de semaine et les jours fériés traversés. La convergence est à revérifier après tout changement de cette option.

- Les coordonnées sont translatées en longitude à latitude constante, pas supprimées : une contrainte de qualité pénalise deux emplacements distants.
- Les ids anonymes sont numérotés dans l'ordre d'apparition et l'ordre des sections est préservé, car un tri ou un hachage s'appuyant sur les ids ferait diverger la trajectoire de recherche (`randomSeed=0`).
- Le décalage de calendrier n'est mémorisé que par l'en-tête que le script écrit : régénérer sans repasser `--date-debut` ramène les dates de la source.

```bash
python3 src/main/resources/anonymiser-scenario.py \
    ~/…/reel-2026-canicule.yaml src/main/resources/scenarios/festival-realiste-canicule.yaml \
    --edition-id festival-realiste-canicule --edition-nom "Festival réaliste — canicule" \
    --date-debut 2026-09-01
```

Les sources restent hors dépôt : `docs/reel-*.yaml` et `scenarios/reel-*.yaml` sont dans `.gitignore`, car elles portent des données personnelles réelles. Le préfixe `reel-` est ce qui les rend invisibles à git, d'où le nom `festival-realiste-…` pour la fixture versionnée.

`festival-hivernal.yaml` est dérivé d'une édition saisie en vacations (rotation de midi, relèves du soir portées par les fenêtres d'ouverture) et jouée par `PlanningServiceScenarioFestivalHivernalTest`. Il n'épingle aucun poids de contrainte, volontairement. Son anonymisation va plus loin : animateurs renumérotés au hasard (« Animateur NNN », sur `example.org`, adultes aux dates de naissance tirées au sort, la source n'ayant aucun mineur), lieux translatés sur une commune tirée au sort avec les longitudes remises à l'échelle du cosinus de la latitude, calendrier avancé de trente semaines (jours de la semaine et ancrage ISO conservés), stands et typologies renommés de façon cohérente. La convergence se relit sur le test, pas sur la source, dont la trajectoire de recherche n'est plus celle-là.

### La fixture insoluble

`scenario-avec-erreur-planning.yaml` est la seule fixture livrée qui ne doit pas converger : six stands, cinq animateurs, dix créneaux, et un défaut d'ouverture par stand (fenêtre hors créneau, effectif minimum au-dessus de l'effectif total, règle récurrente hors amplitude, heure de fin mal saisie, typologie que personne ne maîtrise…). Elle montre ce que disent les pages Problèmes, Besoin en animateurs, Ouvertures et l'analyse de faisabilité quand la saisie est fausse. `PlanningServiceUnsolvableScenarioTest` fige l'intention (accepté à l'import, score dur jamais nul), aucun score.

## Pour qui touche au format

Après avoir modifié un DTO, régénérez le schéma :

```bash
./mvnw process-classes -Pgenerate-schema
```

Le schéma ne peut pas exprimer les règles conditionnelles d'un sélecteur d'horaires, vérifiées par `StandValidator`, ni les références croisées, vérifiées à l'import réel.

Le fichier est lu en une seule passe, par `ScenarioYamlReader.buildFromScenarioText` ([`ScenarioYamlReader`](repo:src/main/java/dev/sylvain/planning/service/scenario/ScenarioYamlReader.java)), qui rend le planning et toutes les sections optionnelles ensemble (`ScenarioImporte`, `ScenarioSections`). Ajouter une section optionnelle, c'est un champ dans `ScenarioSections` et son remplissage dans `ScenarioDomainMapper.sections`, pas une méthode publique de plus : une méthode par section lirait de nouveau le fichier entier à chaque appel, soit autant de parses complets que de sections pour un seul clic.
