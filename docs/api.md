# API REST

Toutes les ressources sont exposées par le service Quarkus sous `/api`, au format
JSON sauf mention contraire.

## Documentation OpenAPI

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/q/openapi` | Spécification OpenAPI générée automatiquement par Quarkus (YAML par défaut) |
| `GET` | `/q/swagger-ui` | Interface Swagger UI pour explorer et tester les endpoints |

Le service expose aussi un serveur MCP (`/mcp`), pour piloter l'application en
langage naturel depuis un assistant IA : voir [`mcp.md`](mcp.md).

## Configuration

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/config` | Configuration d'observabilité lue par le frontend au démarrage (DSN Sentry/Bugsink, clé PostHog, token Cloudflare Web Analytics) — voir [`observabilite.md`](observabilite.md) |
| `POST` | `/api/debug/test-exception` | Lève systématiquement une exception de test, pour vérifier le suivi d'erreurs (bouton « Exception back » de l'onglet Débogage) — voir [`observabilite.md`](observabilite.md) |

## Planning

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/planning/sample` | Jeu d'exemple construit depuis `scenario.yml` (non résolu) |
| `GET` | `/api/planning/volumetrie` | Volumétrie réelle du prochain solve : nombre d'animateurs (value count Timefold), de postes à pourvoir (entity count Timefold, un par siège requis et non par stand) et de contraintes ad hoc actives ; tout à 0 si aucune donnée de référence n'est chargée |
| `POST` | `/api/solve` | Résout un `PlanningFestival` envoyé en JSON (synchrone) |
| `POST` | `/api/solve/analyze` | Analyse un planning : score et contraintes violées |
| `POST` | `/api/planning/reset` | Vide le **groupe courant** (stands, créneaux, animateurs, affectations, contraintes) sans charger de scénario ; ses grilles de créneaux sont réinitialisées à la seule grille `DEFAUT` active. Les autres groupes ne sont pas touchés (bouton « Reset BDD ») |
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

## Explicabilité par affectation

Explicabilité individuelle (« Pourquoi lui ? »), complémentaire du diagnostic
global de `/api/solve/analyze` : au lieu du score global, ces routes ciblent
un seul `PosteAffectation` du planning envoyé en corps de requête (déjà
résolu — jamais re-résolu ici, contrairement à `/api/solve/analyze`).

| Méthode | Chemin | Description |
| --- | --- | --- |
| `POST` | `/api/postes/{posteId}/explication` | Contraintes violées / respectées pour ce poste, avec le score global |
| `POST` | `/api/postes/{posteId}/simulation-swap?animateurId={id}` | Simule le remplacement de l'occupant actuel du poste par `animateurId` : score avant/après, delta, et contraintes violées avant/après pour ce même poste |

Les deux renvoient `404` avec `{ "message": "…" }` si `posteId` ou
`animateurId` ne figure pas dans le planning envoyé. « Respectée » signifie
seulement qu'aucune violation n'a été trouvée pour ce poste précis, pas que la
contrainte s'applique nécessairement à lui — l'IHM ne doit pas la présenter
comme un satisfecit positif. `simulation-swap` ne persiste rien : c'est une
simulation en mémoire, à usage d'aide à la décision uniquement.

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
      "type": "CRENEAU_SOUS_EFFECTIF",
      "severite": "ELEVE",
      "message": "Le 2026-07-18 12:30-15:30, il manque 2 animateurs pour couvrir Tir à l'arc.",
      "creneauId": 42,
      "date": "2026-07-18",
      "heureDebut": "12:30",
      "heureFin": "15:30",
      "standIds": ["STAND-TIR"],
      "demande": 6,
      "capacite": 4,
      "manque": 2
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

Un seul type de cause (`type`) existe :

| Type | Sévérité | Signification |
| --- | --- | --- |
| `CRENEAU_SOUS_EFFECTIF` | `CRITIQUE` si `manque >= demande`, sinon `ELEVE` | Les animateurs disponibles ce jour-là ne suffisent pas à couvrir les postes ouverts sur le créneau |

La compétence (appréciation de l'administrateur) n'entre **pas** dans ce calcul
de capacité : depuis sa bascule en contrainte medium (`appreciationIncompatible`,
voir [`contraintes.md`](contraintes.md)), n'importe quel animateur disponible
peut littéralement être affecté à n'importe quel stand — un écart d'appréciation
est signalé après résolution (score medium, badge calendrier), jamais comme une
cause bloquante avant résolution.

Le tri place les causes `CRITIQUE` avant les `ELEVE`, puis les manques
décroissants.

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

## Groupes (éditions)

Tout le référentiel est cloisonné par **groupe** — une édition complète du
festival, « Année 2025 », « Année 2026 » — avec ses propres stands, animateurs,
typologies, emplacements, grilles de créneaux, paramètres et planning résolu.
Rien ne circule de l'un à l'autre. Voir [`groupes.md`](groupes.md).

**Le client désigne le groupe qu'il consulte à chaque requête**, via l'en-tête
`X-Groupe-Id` :

- absent, ou nommant un groupe inconnu → le serveur retombe silencieusement sur
  le groupe marqué `defaut`, jamais une erreur : un onglet resté ouvert sur un
  groupe supprimé entre-temps continue de fonctionner ;
- ce n'est donc pas un état global : deux onglets peuvent travailler sur deux
  éditions différentes en même temps.

L'en-tête vaut pour **tous** les endpoints de ce document, à la seule exception
du dump SQL (`/api/database/*`), qui reste une sauvegarde de l'instance entière.

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/groupes` | Liste des groupes |
| `GET` | `/api/groupes/courant` | Groupe auquel *cette* requête a réellement été résolue — la façon dont un client découvre que son `X-Groupe-Id` a été ignoré |
| `POST` | `/api/groupes` | Crée un groupe vide (`{ "id", "nom" }`) ; **400** si l'identifiant est déjà pris |
| `PUT` | `/api/groupes/{id}` | Renomme le groupe |
| `POST` | `/api/groupes/{id}/dupliquer` | Crée un nouveau groupe (`{ "id", "nom" }`) et y recopie **tout** le référentiel de `{id}` — les résultats de solveur (`poste_affectation`, `planning_resolution`) sont exclus : « 2026 = 2025 moins les affectations » |
| `PUT` | `/api/groupes/{id}/defaut` | Désigne le groupe de repli pour les appelants sans en-tête |
| `DELETE` | `/api/groupes/{id}` | Supprime le groupe **et tout son référentiel** ; **400** s'il s'agit du groupe par défaut, du groupe courant, ou du dernier restant |

Le verrou « un solveur à la fois » reste global à l'instance, tous groupes
confondus (voir *Résolution asynchrone*) ; un job écrit son résultat dans le
groupe pour lequel il a été lancé, même si le client bascule ensuite.

## Référentiels (CRUD)

Même schéma pour chaque référentiel : `GET` (liste), `POST` (création),
`PUT /{id}` (mise à jour), `DELETE /{id}` (suppression). Tout est lu et écrit
dans le groupe désigné par `X-Groupe-Id` (voir ci-dessus) : deux groupes peuvent
porter les mêmes identifiants métier sans se marcher dessus.

| Ressource | Chemin |
| --- | --- |
| Stands | `/api/stands` |
| Créneaux | `/api/creneaux` |
| Groupes de créneaux | `/api/groupes-creneaux` |
| Animateurs | `/api/animateurs` |
| Typologies de jeux | `/api/typologies` |

`PUT /api/groupes-creneaux/{id}/actif` active cette grille de créneaux pour le
prochain solve et désactive toutes les autres **du groupe courant** (une seule
grille active à la fois, par groupe : activer une grille dans l'édition 2026 ne
touche pas à celle de 2025).

Les typologies de jeux sont un référentiel comme les autres, pas un enum figé
côté serveur : `stands.typologiesProposees` et
`animateurs.competences` référencent des `id` de `/api/typologies` (contrainte
`FOREIGN KEY` en base). `POST`/`PUT /api/stands`|`/api/animateurs` répondent
**400** si un `id` de typologie référencé n'existe pas encore, et
`DELETE /api/typologies/{id}` répond **400** si la typologie est encore
utilisée par au moins un stand ou animateur.

Un item de `/api/typologies` porte `{ id, label, ninja }`. `ninja` désigne la
typologie des profils polyvalents : `POST`/`PUT` avec `ninja: true` retire
automatiquement le drapeau de la typologie qui le portait (au plus une à la
fois) — voir [`contraintes.md`](contraintes.md#typologie-ninja-et-buffer-de-polyvalents).

Contraintes ad hoc (pas de mise à jour, on supprime et on recrée) :

| Méthode | Chemin |
| --- | --- |
| `GET` | `/api/contraintes-ad-hoc` |
| `POST` | `/api/contraintes-ad-hoc` |
| `DELETE` | `/api/contraintes-ad-hoc/{id}` |

### Verrouillages du planning

Parties du planning validées par l'utilisateur et que le solveur ne doit plus
modifier (voir [`domaine.md`](domaine.md#verrouillage-partiel-du-planning)).
Comme les contraintes ad hoc, un verrou est un état : on ne le met pas à jour,
on le supprime et on le recrée.

| Méthode | Chemin |
| --- | --- |
| `GET` | `/api/verrouillages` |
| `POST` | `/api/verrouillages` |
| `DELETE` | `/api/verrouillages/{id}` |

`GET` renvoie les verrous de **tous** les groupes de créneaux, du plus récent
au plus ancien ; seuls ceux du groupe actif sont appliqués par la résolution.

Corps du `POST` : `type` (`ANIMATEUR`, `STAND`, `JOUR` ou `CRENEAU`) et la
**seule** cible correspondante — `animateurId`, `standId`, `jour` (date ISO) ou
`creneauId`. `raison` est optionnelle, `groupeCreneauId` vaut par défaut le
groupe actif et `id` un UUID généré. Une cible déjà verrouillée sur le même
groupe renvoie `200` sans créer de doublon ; un type sans cible correspondante,
ou une cible inconnue, renvoie `400 {"message": "..."}`.

```bash
curl -X POST http://localhost:8080/api/verrouillages \
  -H 'Content-Type: application/json' \
  -d '{"type":"JOUR","jour":"2026-07-12","raison":"Journée validée avec les responsables"}'
```

Import global du référentiel depuis un `PlanningFestival` :
`POST /api/reference-data/import`.

`POST /api/reference-data/import-scenario?name={fichier}` charge un scénario
du dossier `scenarios/` côté serveur et importe son référentiel. Si le
fichier définit `parametresLegaux:`, `parametresDecoupage:` et/ou
`parametresSolveur:` (sections optionnelles, voir
[`domaine.md`](domaine.md#découpage-automatique-en-vacations)), ces réglages
sont aussi appliqués — `parametresSolveur.dureeResolutionSecondes` reconfigure
la durée de résolution (onglet Données) ; absents, les réglages actuellement
en base sont laissés tels quels. Une section `typologies: [{ id, label, ninja? }, …]`
optionnelle fixe le libellé du référentiel `typologie` (voir
[`import-export.md`](import-export.md#chargement-de-scénario)) pour les ids
que le fichier utilise, au lieu de laisser l'import leur donner un libellé
identique à leur id.

`POST /api/reference-data/import-scenario-fichier` fait la même chose pour un
scénario envoyé en corps de requête (bouton « Importer un fichier » de
l'onglet Données), plutôt qu'un nom de fichier du dossier `scenarios/` —
même format YAML, typiquement celui produit par « Exporter les données
actuelles en scénario ». Un fichier invalide (YAML mal formé, section
manquante) renvoie `400` avec `{"message": "…"}` décrivant l'erreur, sans
rien importer.

`POST /api/reference-data/valider-scenario-fichier` valide la **structure**
d'un fichier YAML (types, sections/champs requis, plages de valeurs — voir
[`docs/schema/scenario-schema.json`](schema/scenario-schema.json) et
`ScenarioValidator`) sans rien importer ni persister — c'est l'endpoint de
l'outil « Validateur YAML ». Toujours **200**, y compris pour un fichier
vide ou un YAML mal formé : `{"valide": bool, "erreurs": ["…", …]}`, une
liste vide signifiant que le fichier est valide. Ne remplace pas les
vérifications de références croisées (`standId`/`creneauId` d'un poste)
qu'effectue `import-scenario-fichier` sur un import réel.

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
