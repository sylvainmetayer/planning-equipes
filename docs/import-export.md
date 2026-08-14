# Import / export de données

Tout se pilote depuis la page « Data transfer » de l'IHM, ou directement via
l'[API](api.md).

## Export / import SQL

`GET /api/database/export` produit un script SQL autonome (DELETE puis INSERT de
toutes les tables métier), téléchargeable depuis l'IHM.

`POST /api/database/import` rejoue un tel script dans une seule transaction :
seules les instructions `INSERT` / `DELETE` / `TRUNCATE` sur les tables métier
sont acceptées, tout le reste est rejeté (400).

## Chargement de scénario

`POST /api/reference-data/import-scenario?name=...` (bouton « Charger le
planning d'exemple » de la page Data setup) et `POST /api/reference-data/import`
(import générique d'un `PlanningFestival`) partagent la même logique de
remplacement :

- animateurs et stands sont des référentiels globaux, **toujours remplacés en
  totalité** ;
- les créneaux, eux, sont scopés au groupe de créneaux actif : seuls ceux du
  groupe actif sont supprimés puis rechargés avec les créneaux du scénario ;
  les créneaux des autres groupes ne sont pas touchés. Chaque créneau importé
  reçoit un nouvel id généré par la base ; les contraintes ad hoc qui
  référençaient un créneau du scénario par son id d'origine sont réassociées
  au nouvel id. Cela permet de charger plusieurs scénarios dans différents
  groupes (par exemple un planning normal et un planning de repli) sans que
  l'un écrase les créneaux de l'autre. Voir [`domaine.md`](domaine.md) pour la
  notion de groupe de créneaux.
- les affectations (`poste_affectation`) et les contraintes ad hoc restent
  supprimées en totalité à chaque import, quel que soit le groupe, puisqu'elles
  n'ont pas de notion de groupe propre.

Le bouton « Importer un fichier » de la page Data setup fait la même chose
(`POST /api/reference-data/import-scenario-fichier`) à partir d'un fichier
YAML choisi sur le poste de l'utilisateur, plutôt qu'un scénario nommé du
dossier `scenarios/` — typiquement celui produit par « Exporter les données
actuelles en scénario », ou un fichier écrit à la main dans le même format.
Un fichier invalide (YAML mal formé, section obligatoire manquante) n'importe
rien et affiche une notification avec le détail de l'erreur.

Le format YAML d'un stand couvre `typologiesProposees`, `effectifMin/Max`,
`reserveMajeurs`, `premium`, `niveauEffort` (`NORMAL` par défaut si absent —
voir [`domaine.md`](domaine.md)) ainsi que ses fenêtres `indisponibilites`
(fermetures) et `ouvertures`, dans les deux sens : « Exporter les données
actuelles en scénario » les écrit, l'import (nommé ou fichier) les relit à
l'identique. `emplacementId` reste import seulement — l'export n'écrit pas
encore la section `emplacements` correspondante, à traiter séparément.

La section `postes` (un poste par place à pourvoir, référençant un
`standId`/`creneauId`) est optionnelle : absente du fichier, elle est générée
automatiquement à partir des stands et créneaux importés, avec les mêmes
règles que « Lancer le solveur » depuis les données de référence — un poste
par place (`stand.effectifMin`, pas `effectifMax`) sur chaque créneau ×
segment réellement ouvert (voir `PlanningService.construirePostes`). Fournir
la section reste possible pour un staffing qui s'écarte de cette règle
(certains scénarios, ex. `scenario-complet.yaml`, l'énumèrent explicitement) ;
dans ce cas elle est reprise telle quelle.

Un scénario écrit directement en amplitudes (ex. `scenario-continu.yaml`) peut
fixer une section `decoupageAuto: { groupeSourceNom, groupeCibleNom }` en tête
de fichier pour que ces deux imports (nom ou fichier) déclenchent eux-mêmes le
découpage en vacations plutôt que de laisser l'opérateur repasser par l'écran
« Découpage » : les créneaux importés atterrissent dans un groupe source
`groupeSourceNom` (créé si besoin, jamais activé), le découpage tourne dessus
et le groupe cible `groupeCibleNom` (créé si besoin) reçoit les vacations et
devient le groupe actif. Une notification prévient alors l'opérateur du nom du
groupe activé. Absente, l'import se comporte comme ci-dessus. Voir
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

## Schéma de validation d'un fichier de scénario

[`docs/schema/scenario-schema.json`](schema/scenario-schema.json) décrit la
structure attendue d'un fichier de scénario (sections obligatoires `festival`,
`creneaux`, `stands` — dont `niveauEffort` et `ouvertures` par stand —,
`animateurs`, et les sections optionnelles `postes` (voir plus haut),
`parametresLegaux`, `parametresDecoupage`, `parametresSolveur`,
`decoupageAuto`, `typologies`) : types de champs, sections/champs
obligatoires, durées non négatives, valeurs d'enum (`NiveauCompetence`,
`NiveauEffort`, `StrategieCouverturePendantPause`). Les ids de typologie
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
