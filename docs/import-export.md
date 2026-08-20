# Import / export de données

Tout se pilote depuis la page « Data transfer » de l'IHM, ou directement via
l'[API](api.md).

Sauf mention contraire, tout ce qui suit est lu et écrit dans l'**édition**
désignée par l'en-tête `X-Edition-Id` de la requête — voir
[`editions.md`](editions.md).

## Export / import SQL

`GET /api/database/export` produit un script SQL autonome (DELETE puis INSERT de
toutes les tables métier), téléchargeable depuis l'IHM.

C'est la seule opération qui reste **globale à l'instance** : c'est une
sauvegarde de la base, toutes éditions comprises (la table `edition` en tête du
script). L'export cloisonné par édition existe sous une autre forme — l'export
de scénario YAML ci-dessous, qui suit l'édition courante.

`POST /api/database/import` rejoue un tel script dans une seule transaction :
seules les instructions `INSERT` / `DELETE` / `TRUNCATE` sur les tables métier
sont acceptées, tout le reste est rejeté (400).

## Chargement de scénario

`POST /api/reference-data/import-scenario?name=...` (bouton « Charger le
planning d'exemple » de la page Data setup) et `POST /api/reference-data/import`
(import générique d'un `PlanningFestival`) partagent la même logique de
remplacement :

- **rien ne sort de l'édition courante** : un import dans « Année 2026 » ne
  touche aucune donnée de « Année 2025 ». C'est le chemin nominal pour peupler
  une édition vierge ;
- à l'intérieur de cette édition, animateurs et stands sont **toujours
  remplacés en totalité** ;
- les créneaux, eux, sont scopés à la grille de créneaux active : seuls ceux de
  la grille active sont supprimés puis rechargés avec les créneaux du scénario ;
  les créneaux des autres grilles ne sont pas touchés. Chaque créneau importé
  reçoit un nouvel id généré par la base ; les contraintes ad hoc qui
  référençaient un créneau du scénario par son id d'origine sont réassociées
  au nouvel id. Cela permet de charger plusieurs scénarios dans différentes
  grilles (par exemple un planning normal et un planning de repli) sans que
  l'un écrase les créneaux de l'autre. Voir [`domaine.md`](domaine.md) pour la
  notion de grille de créneaux.
- les affectations (`poste_affectation`) et les contraintes ad hoc de
  l'édition restent supprimées en totalité à chaque import, quelle que soit la
  grille, puisqu'elles n'ont pas de notion de grille propre.

Le bouton « Importer un fichier » de la page Data setup fait la même chose
(`POST /api/reference-data/import-scenario-fichier`) à partir d'un fichier
YAML choisi sur le poste de l'utilisateur, plutôt qu'un scénario nommé du
dossier `scenarios/` — typiquement celui produit par « Exporter les données
actuelles en scénario », ou un fichier écrit à la main dans le même format.
Un fichier invalide (YAML mal formé, section obligatoire manquante) n'importe
rien et affiche une notification avec le détail de l'erreur.

Le format YAML d'un stand couvre `typologiesProposees`, `effectifMin/Max`,
`reserveMajeurs`, `premium`, `niveauEffort` (`NORMAL` par défaut si absent —
voir [`domaine.md`](domaine.md)) ainsi que son planning d'ouverture : les règles
récurrentes `horaires` et les fenêtres datées `indisponibilites` (fermetures) /
`ouvertures` qui les surchargent. Tout cela va dans les deux sens : « Exporter
les données actuelles en scénario » l'écrit, l'import (nommé ou fichier) le relit
à l'identique, `emplacementId` et la section `emplacements` comprises.

### Ce que l'export garantit

« Exporter les données actuelles en scénario » écrit **toutes** les sections que
l'import sait relire, pas seulement les entités : `typologies`, `emplacements`,
`parametresLegaux`, `parametresDecoupage`, `parametresSolveur` et, quand la
grille active a été produite par un découpage automatique, `decoupageAuto`.
Réimporter le fichier reproduit donc exactement le même problème — c'est la
raison d'être de l'export. Un fichier sans ces sections retombait silencieusement
sur les réglages de l'instance qui l'importe (sa durée de résolution, ses durées
de vacation, ses plafonds légaux) : le « même » scénario rejoué ailleurs
résolvait un autre problème.

Deux formes en sortent, selon la grille active :

- une grille saisie à la main exporte ses propres `creneaux` **et** la liste de
  sièges qu'ils impliquent (`postes`) ;
- une grille issue d'un découpage exporte les **amplitudes sources** et
  `decoupageAuto`, **sans** section `postes` : l'import rejoue le découpage et
  régénère les sièges à partir des vacations qu'il recrée, seule façon de garder
  des ids cohérents (les créneaux générés reçoivent de nouveaux ids en base).

### Horaires d'un stand

Une entrée d'`horaires` porte son sélecteur de jours **à plat** : `jours` nomme
lequel des champs voisins s'applique, et seul celui-là est lu. Absent, il vaut
`TOUS`, ce qui ramène le cas courant à deux lignes. `heureFin` **omise** signifie
« jusqu'à la fermeture » : la fenêtre court jusqu'à la fin du créneau évalué, ce
qui permet à une même règle de couvrir un jour fermant à 20 h et un jour fermant
à minuit — et remplace le contournement `23:59` qu'imposait une heure de fin
concrète (une fenêtre ne peut pas chevaucher minuit).

```yaml
stands:
  - id: "AUTRES-BOURSE"
    nom: "Autres - Bourse"
    typologiesProposees: [ANIMATION]
    effectifMin: 2
    effectifMax: 2
    horaires:
      # Ouvert 10h-12h puis 14h jusqu'à la fermeture, tous les jours du festival :
      # une règle à deux fenêtres, là où la forme datée demandait 24 lignes.
      - mode: OUVERTURE
        fenetres:
          - { heureDebut: "10:00", heureFin: "12:00" }
          - { heureDebut: "14:00" }
      # Le week-end, ouverture dès 10h sans coupure : portée plus précise, donc
      # elle prime sur la précédente ces jours-là.
      - mode: OUVERTURE
        jours: JOURS_SEMAINE
        joursSemaine: [SATURDAY, SUNDAY]
        fenetres:
          - { heureDebut: "10:00" }
    # Une exception datée prime sur toutes les règles, pour ce seul jour.
    indisponibilites:
      - date: 2026-07-14
        heureDebut: "10:00"
        motif: Férié
```

Les autres portées sont `PLAGE` (avec `dateDebut`/`dateFin`, bornes incluses) et
`DATES` (avec `dates`). Un fichier peut continuer à tout écrire en fenêtres
datées : les deux formes coexistent, et [`domaine.md`](domaine.md#horaires-récurrents)
décrit l'arbitrage entre elles.

La section `postes` (un poste par place à pourvoir, référençant un
`standId`/`creneauId`) est optionnelle : absente du fichier, elle est générée
automatiquement à partir des stands et créneaux importés, avec les mêmes
règles que « Lancer le solveur » depuis les données de référence — un poste
par place (`stand.effectifMin`, pas `effectifMax`) sur chaque créneau ×
segment réellement ouvert (voir `PlanningService.construirePostes`). Fournir
la section reste possible pour un staffing qui s'écarte de cette règle
(certains scénarios, ex. `scenario-complet.yaml`, l'énumèrent explicitement) ;
dans ce cas elle est reprise telle quelle.

Une section optionnelle `edition: { id, nom? }` en tête de fichier désigne
l'édition dans laquelle l'import doit écrire, au lieu de l'édition courante de
l'appelant : si elle n'existe pas, elle est créée vide (avec `nom` comme
libellé, `id` à défaut) puis reçoit l'import ; si elle existe, elle est
réutilisée telle quelle (son libellé en base prime sur celui du fichier). La
réponse de l'import indique toujours où les données ont atterri et si
l'édition a été créée (`editionId`, `editionNom`, `editionCreee`), et
l'interface affiche systématiquement ce récapitulatif — l'opérateur peut être
en train de consulter une autre édition que celle qui vient d'être écrite.
Sans cette section, l'import écrit dans l'édition courante, comme avant.

Un scénario écrit directement en amplitudes (ex. `scenario-continu.yaml`) peut
fixer une section `decoupageAuto: {}` en tête de fichier pour que ces deux
imports (nom ou fichier) déclenchent eux-mêmes le découpage en vacations
plutôt que de laisser l'opérateur repasser par la page Créneaux : les
créneaux importés sont découpés **en place** (issue #172 — l'édition ne porte
qu'une grille) et une notification prévient l'opérateur. Les anciens champs
`groupeSourceNom`/`groupeCibleNom` de la section sont acceptés mais ignorés ;
`decoupageAuto: false` désactive explicitement. Absente, l'import se comporte
comme ci-dessus. Voir
[`domaine.md`](domaine.md#découpage-automatique-en-vacations).

Les typologies (`typologiesProposees` d'un stand, `competences`/`souhaits`
d'un animateur) référencent le référentiel `typologie` (id + libellé,
CRUD-managé via `/api/typologies` — plus un enum figé). Tout id de typologie
que le fichier référence sans le déclarer explicitement se voit créé à
l'import avec un libellé identique à son id (ex. id `ENF` -> libellé `ENF`).
Une section `typologies: [{ id, label }, ...]` optionnelle en tête de fichier
permet de fixer un vrai libellé pour ces ids (ex. `ENF` -> `Enfance`) : elle
est appliquée après l'import de la planification elle-même, pour ne pas être
écrasée par la création automatique ci-dessus. Une typologie déjà présente en
base (créée par un import précédent ou via l'écran de gestion) voit son
libellé mis à jour si le scénario la redéclare.

Chaque entrée de cette section accepte un champ optionnel `ninja: true` pour
désigner la typologie « ninja » du référentiel (au plus une : la déclarer
retire le drapeau de la précédente). L'**export** réécrit la section
`typologies:` en entier, libellés et drapeau ninja compris : un aller-retour
export/import les conserve.

## Schéma de validation d'un fichier de scénario

[`docs/schema/scenario-schema.json`](schema/scenario-schema.json) décrit la
structure attendue d'un fichier de scénario (sections obligatoires `festival`,
`creneaux`, `stands` — dont `niveauEffort`, `horaires` et `ouvertures` par
stand —,
`animateurs`, et les sections optionnelles `postes` (voir plus haut),
`parametresLegaux`, `parametresDecoupage`, `parametresSolveur`,
`decoupageAuto`, `typologies`) : types de champs, sections/champs
obligatoires, durées non négatives, valeurs d'enum (`NiveauCompetence`,
`NiveauEffort`, `StrategieCouverturePendantPause`, `ModeHoraire`,
`TypeJoursHoraire`). Il ne peut pas exprimer les règles conditionnelles d'un
sélecteur d'`horaires` (`joursSemaine` requis pour `JOURS_SEMAINE`,
`dateDebut`/`dateFin` pour `PLAGE`, `dates` pour `DATES`) : celles-là sont
vérifiées à l'écriture par `ReferenceDataService.validateHoraires`. Les ids de typologie
eux-mêmes (`typologiesProposees`, `competences`, `souhaits`, et la section
`typologies`) sont de simples chaînes, pas un enum : le référentiel
`typologie` est CRUD-managé, pas figé dans le code.

Le schéma n'est pas écrit à la main : il est **généré** à partir des DTOs
Jackson + Bean Validation de `dev.sylvain.planning.scenario.dto`
(`ScenarioDto` et les classes qu'il référence), pour qu'il ne puisse pas
diverger de ce que ces DTOs acceptent. Les fichiers de scénario livrés dans
`src/main/resources/scenarios/` pointent vers lui via un commentaire
`# yaml-language-server: $schema=...` en tête de fichier, ce qui active
l'auto-complétion et la validation à l'édition dans les éditeurs équipés de
l'extension YAML (ex. redhat.vscode-yaml).

Après avoir modifié un DTO de scénario, régénérer le schéma et committer le
fichier obtenu :

```bash
./mvnw process-classes -Pgenerate-schema
```

Un fichier peut aussi être validé en ligne de commande, indépendamment de
l'IHM, via `ScenarioValidator` :

```bash
./mvnw compile exec:java \
  -Dexec.mainClass=dev.sylvain.planning.scenario.ScenarioValidator \
  -Dexec.args=src/main/resources/scenarios/scenario.yml
```

Ce validateur et ce schéma ne couvrent que la forme du fichier (types, champs
requis, plages de valeurs) : ils ne remplacent pas le chargement réel par
`PlanningService`, qui reste plus permissif sur certains points (ex.
`creneaux[].jour` et `postes[].animateurId` sont acceptés mais ignorés à
l'import) et seul à vérifier les références croisées (`standId`/`creneauId`
d'un poste correspondant bien à un stand/créneau déclaré).

## Exports de planning (PDF / ICS)

Générés **côté serveur** — pas de génération dans le navigateur :

- **PDF** (OpenPDF) : planning individuel par animateur, ou ZIP de tous les
  plannings individuels. Quand un stand est rattaché à un emplacement géocodé,
  la liste détaillée affiche un lien OpenStreetMap cliquable sous le nom du
  stand ;
- **ICS** : planning individuel importable directement dans Google Calendar,
  Apple Calendar ou Outlook, ou ZIP de tous les plannings. Les événements dont
  le stand a un emplacement portent aussi les champs `LOCATION` (nom du lieu)
  et `GEO` (latitude/longitude) quand ils sont disponibles.

Les deux affichent l'horaire *effectif* du poste (`PosteAffectation.heureDebutEffective`/
`heureFinEffective`), pas celui, plus large, de son créneau : un poste réduit
par une fermeture partielle de stand (issue #60, voir [`domaine.md`](domaine.md))
montre à l'animateur les heures qu'il couvre réellement, pas la plage fermée.

Endpoints correspondants dans [`api.md`](api.md).
