# API REST

Toutes les ressources sont exposées par le service Quarkus sous `/api`, au format
JSON sauf mention contraire.

## Configuration

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/config` | Configuration d'observabilité lue par le frontend au démarrage (DSN Sentry/Bugsink, clé PostHog) — voir [`observabilite.md`](observabilite.md) |
| `POST` | `/api/debug/test-exception` | Lève systématiquement une exception de test, pour vérifier le suivi d'erreurs (bouton « Exception back » de l'onglet Débogage) — voir [`observabilite.md`](observabilite.md) |

## Planning

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/planning/sample` | Jeu d'exemple construit depuis `scenario.yml` (non résolu) |
| `POST` | `/api/solve` | Résout un `PlanningFestival` envoyé en JSON (synchrone) |
| `POST` | `/api/solve/analyze` | Analyse un planning : score et contraintes violées |
| `POST` | `/api/planning/reset` | Recharge le scénario d'exemple en base **sans** résolution (bouton « Reset BDD ») |
| `GET` | `/api/planning/persisted` | Planning persisté en base, lecture seule (utilisé par les vues calendrier, qui ne déclenchent jamais de résolution) |
| `GET` | `/api/planning/persisted/count` | Nombre d'affectations persistées |
| `GET` | `/api/planning/persisted/resolution` | Groupe de créneaux et date de la dernière résolution persistée (`solved: false` si aucune résolution n'a encore eu lieu), plus `derniereModificationDonnees` : date de la dernière modification d'une donnée de référence (`null` si aucune depuis le démarrage du serveur) |

## Résolution asynchrone

La résolution complète dure plusieurs minutes : l'IHM lance un job, reste
navigable et notifie à la fin.

Le verrou « un solveur à la fois » est **porté par le serveur**, pas par le
navigateur : un seul job de résolution ou d'analyse peut tourner à la fois pour
toute l'application. Toute autre session (autre navigateur, navigation privée,
autre onglet) voit le même job actif et le même temps écoulé via
`GET /api/jobs/active`, et se voit refuser un second lancement en `409 Conflict`
(le corps de la réponse contient le job en cours).

| Méthode | Chemin | Description |
| --- | --- | --- |
| `POST` | `/api/solve/async?seconds={n}` | Démarre une résolution en tâche de fond (`202`, ou `409` si le solveur est occupé) |
| `POST` | `/api/solve/analyze/async?seconds={n}` | Démarre une analyse en tâche de fond (`202`, ou `409` si le solveur est occupé) |
| `GET` | `/api/jobs` | Liste des jobs |
| `GET` | `/api/jobs/active` | Job en cours (`200`) ou solveur libre (`204`) |
| `GET` | `/api/jobs/{id}` | État et résultat d'un job |
| `DELETE` | `/api/jobs/{id}` | Supprime un job terminé (`409` si le job tourne encore) |

Chaque job expose `elapsedSeconds`, calculé côté serveur : le temps écoulé
affiché est identique quel que soit le client, son horloge ou son heure de
connexion.

## Contraintes

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/constraints` | Catalogue métier des contraintes + résultat de la dernière analyse |
| `PUT` | `/api/constraints/{name}` | Active/désactive une contrainte pour le prochain solve |

Corps du `PUT /api/constraints/{name}` :

```json
{ "actif": false, "motif": "…", "modifieParUtilisateurId": "ui" }
```

`motif` et `modifieParUtilisateurId` ne sont enregistrés que lors d'une
**désactivation** (colonnes `motif`, `modifie_par_utilisateur_id`,
`modifie_le` de `constraint_toggle`) ; ils sont ignorés à la réactivation, qui
supprime la ligne. Tous deux sont facultatifs — un client plus ancien continue
de fonctionner. L'IHM les renseigne systématiquement pour les contraintes de
catégorie « Légal », après un avertissement explicite : voir
[`contraintes.md`](contraintes.md).

## Faisabilité

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/feasibility` | Diagnostic d'infaisabilité calculé sur les données de référence actuelles, **sans lancer le solveur** |

Le diagnostic est un simple calcul de capacité (aucune résolution, réponse
immédiate) : l'écran de préparation des données peut donc l'afficher avant même
de lancer une résolution de plusieurs minutes. Les créneaux pris en compte sont
ceux du **groupe actif**, comme pour une résolution.

```json
{
  "feasible": false,
  "manqueAnimateurs": 2,
  "causes": [
    {
      "type": "STAND_SANS_ANIMATEUR_COMPETENT",
      "severite": "CRITIQUE",
      "message": "Aucun animateur ne possède la compétence requise pour le stand « Tir à l'arc » : il ne peut être tenu sur aucun créneau.",
      "creneauId": null,
      "date": null,
      "heureDebut": null,
      "heureFin": null,
      "standIds": ["STAND-TIR"],
      "demande": -1,
      "capacite": -1,
      "manque": -1
    }
  ],
  "totalCauses": 7,
  "message": "Ce planning n'est pas réalisable avec les animateurs actuels : …"
}
```

| Champ | Description |
| --- | --- |
| `feasible` | `true` si aucune cause bloquante n'a été détectée |
| `manqueAnimateurs` | Manque le plus élevé constaté sur un créneau (`0` si aucun) |
| `causes` | Causes classées de la plus bloquante à la moins bloquante, **plafonnées aux 10 premières** |
| `totalCauses` | Nombre total de causes **avant** plafonnement (permet d'afficher « +N autres ») |
| `message` | Phrase de synthèse prête à afficher |

Deux types de causes (`type`) :

| Type | Sévérité | Signification |
| --- | --- | --- |
| `STAND_SANS_ANIMATEUR_COMPETENT` | toujours `CRITIQUE` | Aucun animateur du référentiel n'a la compétence du stand : il ne peut être tenu aucun jour. `creneauId`/`date`/`heureDebut`/`heureFin` sont `null` et le triplet `demande`/`capacite`/`manque` vaut `-1` (non pertinent) |
| `CRENEAU_SOUS_EFFECTIF` | `CRITIQUE` si `manque >= demande`, sinon `ELEVE` | Les animateurs compétents et disponibles ce jour-là ne suffisent pas à couvrir les postes ouverts sur le créneau |

Le tri place les causes `CRITIQUE` avant les `ELEVE`, les stands sans animateur
compétent avant les créneaux sous-effectif, puis les manques décroissants.

Le même rapport est également renvoyé, après résolution, dans le champ
`faisabilite` de `GET /api/constraints` : celui-ci reflète les données de la
dernière analyse, alors que `GET /api/feasibility` reflète toujours le
référentiel courant. Comme il s'agit d'une estimation **optimiste** (elle ignore
quel stand précis chaque animateur pourrait tenir), `feasible: true` ne garantit
pas un score dur nul après résolution — voir [`domaine.md`](domaine.md).

## Paramètres légaux

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/parametres-legaux` | Plafonds hebdomadaires de temps de travail |
| `PUT` | `/api/parametres-legaux` | Met à jour ces plafonds |

```json
{ "dureeHebdomadaireMaxMinutes": 2880, "dureeHebdomadaireMaxMineurMinutes": 2100 }
```

Le `PUT` répond **400** avec `{ "message": "…" }` si une valeur dépasse son
plafond d'ordre public : 48 h pour les majeurs (Code du travail art. L3121-20),
35 h pour les mineurs (art. L3162-1). Une valeur inférieure, plus protectrice,
est acceptée.

## Paramètres du solveur

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/parametres-solveur` | Durée de résolution par défaut (onglet Données) |
| `PUT` | `/api/parametres-solveur` | Met à jour cette durée |

```json
{ "dureeResolutionSecondes": 180 }
```

Persistée côté serveur (et non en `localStorage`) : la même valeur est lue et
modifiée depuis n'importe quel navigateur. Le `PUT` répond **400** avec
`{ "message": "…" }` si la valeur n'est pas strictement positive.

## Référentiels (CRUD)

Même schéma pour chaque référentiel : `GET` (liste), `POST` (création),
`PUT /{id}` (mise à jour), `DELETE /{id}` (suppression).

| Ressource | Chemin |
| --- | --- |
| Stands | `/api/stands` |
| Créneaux | `/api/creneaux` |
| Groupes de créneaux | `/api/groupes-creneaux` |
| Animateurs | `/api/animateurs` |
| Typologies de jeux | `/api/typologies` |

`PUT /api/groupes-creneaux/{id}/actif` active ce groupe de créneaux pour le
prochain solve et désactive tous les autres (un seul groupe actif à la
fois).

Contraintes ad hoc (pas de mise à jour, on supprime et on recrée) :

| Méthode | Chemin |
| --- | --- |
| `GET` | `/api/contraintes-ad-hoc` |
| `POST` | `/api/contraintes-ad-hoc` |
| `DELETE` | `/api/contraintes-ad-hoc/{id}` |

Import global du référentiel depuis un `PlanningFestival` :
`POST /api/reference-data/import`.

`POST /api/reference-data/import-scenario?name={fichier}` charge un scénario
du dossier `scenarios/` côté serveur et importe son référentiel. Si le
fichier définit `parametresLegaux:`, `parametresDecoupage:` et/ou
`parametresSolveur:` (sections optionnelles, voir
[`domaine.md`](domaine.md#découpage-automatique-en-vacations)), ces réglages
sont aussi appliqués — `parametresSolveur.dureeResolutionSecondes` reconfigure
la durée de résolution (onglet Données) ; absents, les réglages actuellement
en base sont laissés tels quels.

## Découpage automatique en vacations

Découpe les amplitudes d'un groupe de créneaux source en vacations plus
courtes et chevauchantes (voir [`domaine.md`](domaine.md#découpage-automatique-en-vacations)).

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/decoupage/preview?groupeSourceId={id}` | Prévisualise les vacations générées, sans rien persister |
| `POST` | `/api/decoupage/generer` | Matérialise les vacations dans un groupe cible (créé si besoin) : `{ "groupeSourceId", "groupeCibleId", "nomGroupeCible", "activerGroupeCible" }` |
| `GET` | `/api/parametres-decoupage` | Paramètres de découpage courants |
| `PUT` | `/api/parametres-decoupage` | Met à jour les paramètres de découpage |

## Import / export de données

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/database/export` | Dump SQL autonome de toutes les tables métier |
| `POST` | `/api/database/import` | Rejoue un dump SQL dans une transaction unique |
| `POST` | `/api/import/csv/{animateurs\|stands\|creneaux}` | Import CSV d'un référentiel |

Détail des formats : [`import-export.md`](import-export.md).

## Exports planning

Génération **côté serveur** (OpenPDF pour le PDF, texte pour l'ICS). Le planning
à exporter est envoyé dans le corps de la requête.

| Méthode | Chemin | Description |
| --- | --- | --- |
| `POST` | `/api/planning/export/bundle/all` | ZIP contenant un PDF **et** un ICS par animateur (utilisé par l'IHM) |
| `POST` | `/api/planning/export/pdf/all` | ZIP contenant un PDF par animateur |
| `POST` | `/api/planning/export/pdf/animateur/{animateurId}` | PDF du planning individuel |
| `POST` | `/api/planning/export/ics/all` | ZIP contenant un ICS par animateur |
| `POST` | `/api/planning/export/ics/animateur/{animateurId}` | ICS du planning individuel |

## Heures planifiées

Calcule, pour chaque animateur, le nombre d'heures planifiées par semaine
calendaire ISO (`AAAA-Wss`) et le total. Le planning à analyser est envoyé
dans le corps de la requête, comme pour les exports.

| Méthode | Chemin | Description |
| --- | --- | --- |
| `POST` | `/api/planning/hours` | Rapport JSON (semaines + heures par animateur) |
| `POST` | `/api/planning/hours/export` | Le même rapport, au format CSV |
