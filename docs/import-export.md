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
