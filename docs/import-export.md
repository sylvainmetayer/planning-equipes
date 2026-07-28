# Import / export de données

Tout se pilote depuis la section « Data transfer » de la page Administration, ou
directement via l'[API](api.md).

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
| `animateurs` | `id;prenom;nom;dateNaissance;statut;competences;joursIndisponibles` |
| `stands` | `id;nom;typologies;effectifMin;effectifMax;reserveMajeurs` |
| `creneaux` | `id;jour;date;heureDebut;heureFin` |

- `statut` : `BENEVOLE` ou `SALARIE`
- `competences` : `STRATEGIE:REFERENT|ENFANT:AUTONOME`
  (typologies : `STRATEGIE, AMBIANCE, ENFANT, COOPERATIF, ADRESSE, ROLE, ENIGME` ;
  niveaux : `DEBUTANT, AUTONOME, REFERENT`)
- `joursIndisponibles` : `2026-07-02|2026-07-03` (colonne facultative, vide = toujours disponible)
- `typologies` : `STRATEGIE|ENFANT`
- `reserveMajeurs` : `true` / `false` (`1`, `oui`, `yes` acceptés)

Exemple :

```csv
id;prenom;nom;dateNaissance;statut;competences;joursIndisponibles
A-1;Ada;Lovelace;1990-05-04;BENEVOLE;STRATEGIE:REFERENT|ENFANT:AUTONOME;2026-07-02
A-2;Alan;Turing;2010-01-15;SALARIE;;
```

Une ligne invalide annule tout l'import et renvoie un message précisant le numéro
de ligne et la colonne fautive.

## Exports de planning (PDF / ICS)

Générés **côté serveur** — pas de génération dans le navigateur :

- **PDF** (OpenPDF) : planning individuel par animateur, ou ZIP de tous les
  plannings individuels ;
- **ICS** : planning individuel importable directement dans Google Calendar,
  Apple Calendar ou Outlook, ou ZIP de tous les plannings.

Endpoints correspondants dans [`api.md`](api.md).
