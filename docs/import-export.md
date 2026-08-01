# Import / export de données

Tout se pilote depuis la page « Data transfer » de l'IHM, ou directement via
l'[API](api.md).

## Export / import SQL

`GET /api/database/export` produit un script SQL autonome (DELETE puis INSERT de
toutes les tables métier), téléchargeable depuis l'IHM.

`POST /api/database/import` rejoue un tel script dans une seule transaction :
seules les instructions `INSERT` / `DELETE` / `TRUNCATE` sur les tables métier
sont acceptées, tout le reste est rejeté (400).

## Import CSV

Un import **remplace l'intégralité** de la table concernée et supprime les
affectations existantes (elles référenceraient des lignes disparues).

Règles de format :

- ligne d'en-tête obligatoire ;
- séparateur `;` ou `,` (détecté automatiquement) ;
- valeurs multiples séparées par `|` ;
- dates ISO `yyyy-MM-dd`, heures ISO `HH:mm`.

| Entité | Colonnes |
| --- | --- |
| `animateurs` | `id;prenom;nom;dateNaissance;manager;competences;joursIndisponibles` |
| `stands` | `id;nom;typologies;effectifMin;effectifMax;reserveMajeurs;premium` |
| `creneaux` | `id;jour;date;heureDebut;heureFin` |

Un import `creneaux` remplace la table dans son intégralité, **tous groupes
de créneaux confondus** — pas seulement ceux du groupe actif — et les lignes
importées sont rattachées au groupe « Défaut ». Voir
[`domaine.md`](domaine.md) pour la notion de groupe de créneaux.

- `manager` : `true` / `false` (colonne facultative, vide = `false`) — anime et encadre d'autres
  animateurs ; tous les animateurs sont payés, il n'existe plus de distinction bénévole/salarié
- `competences` : `STRATEGIE:REFERENT|ENFANT:AUTONOME`
  (typologies : `STRATEGIE, AMBIANCE, ENFANT, COOPERATIF, ADRESSE, ROLE, ENIGME` ;
  niveaux : `DEBUTANT, AUTONOME, REFERENT`)
- `joursIndisponibles` : `2026-07-02|2026-07-03` (colonne facultative, vide = toujours disponible)
- `typologies` : `STRATEGIE|ENFANT`
- `reserveMajeurs` : `true` / `false` (`1`, `oui`, `yes` acceptés)
- `premium` : `true` / `false` (colonne facultative, vide = `false`) — stand éditeur/vedette :
  le solveur évite d'y faire tourner le personnel et privilégie les animateurs expérimentés
  (`AUTONOME`/`REFERENT`)

Exemple :

```csv
id;prenom;nom;dateNaissance;manager;competences;joursIndisponibles
A-1;Ada;Lovelace;1990-05-04;true;STRATEGIE:REFERENT|ENFANT:AUTONOME;2026-07-02
A-2;Alan;Turing;2010-01-15;false;;
```

Une ligne invalide annule tout l'import et renvoie un message précisant le numéro
de ligne et la colonne fautive.

## Chargement de scénario

`POST /api/reference-data/import-scenario?name=...` (bouton « Charger le
planning d'exemple » de la page Data setup) et `POST /api/reference-data/import`
(import générique d'un `PlanningFestival`) partagent la même logique de
remplacement :

- animateurs et stands sont des référentiels globaux, **toujours remplacés en
  totalité** ;
- les créneaux, eux, sont scopés au groupe de créneaux actif : seuls ceux du
  groupe actif sont supprimés puis rechargés avec les créneaux du scénario ;
  les créneaux des autres groupes ne sont pas touchés. L'id de chaque créneau
  importé est automatiquement qualifié avec l'id du groupe actif (transparent
  pour l'utilisateur), pour qu'un même nom (« J1-MATIN », convention commune à
  tous les scénarios fournis) puisse être réutilisé dans plusieurs groupes
  sans collision. Cela permet de charger plusieurs scénarios dans différents
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

Endpoints correspondants dans [`api.md`](api.md).
