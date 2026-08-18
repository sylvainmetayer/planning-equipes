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
| `POST` | `/api/planning/reset` | Vide l'**édition courante** (stands, créneaux, animateurs, affectations, contraintes) sans charger de scénario ; ses grilles de créneaux sont réinitialisées à la seule grille `DEFAUT` active. Les autres éditions ne sont pas touchées (bouton « Reset BDD ») |
| `GET` | `/api/planning/persisted` | Planning persisté en base, lecture seule (utilisé par les vues calendrier, qui ne déclenchent jamais de résolution) |
| `GET` | `/api/planning/persisted/count` | Nombre d'affectations persistées |
| `GET` | `/api/planning/persisted/resolution` | Groupe de créneaux et date de la dernière résolution persistée (`solved: false` si aucune résolution n'a encore eu lieu), plus `derniereModificationDonnees` : date de la dernière modification d'une donnée de référence (`null` si aucune depuis le démarrage du serveur) |

## Instantanés de plan

Un seul plan est persisté à la fois par édition (`poste_affectation` est
réécrite en entier à chaque solve). Les instantanés sont la seule persistance
capable d'en garder plusieurs : ils mettent un plan de côté avant qu'il ne soit
écrasé.

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/planning/snapshots` | Liste les instantanés de l'édition courante, du plus récent au plus ancien (métadonnées seules, sans le contenu) |
| `GET` | `/api/planning/snapshots/{id}` | Un instantané avec ses affectations |
| `POST` | `/api/planning/snapshots` | Enregistre le plan actuellement persisté. Corps : `{ "libelle": "…" }`. `409` s'il n'y a aucun plan à enregistrer |
| `POST` | `/api/planning/snapshots/{id}/restore` | Réécrit `poste_affectation` et `planning_resolution` depuis l'instantané. `409` **sans rien écrire** si des références ont disparu, avec la liste `referencesManquantes` (`stand:…`, `creneau:…`, `animateur:…`) |
| `DELETE` | `/api/planning/snapshots/{id}` | Supprime un instantané |

Un instantané est pris **automatiquement avant chaque solve** (`automatique:
true`, libellé « Avant solve du … ») : c'est le vrai filet anti-écrasement,
celui qui protège l'utilisateur qui n'a pas pensé à enregistrer. Ces
instantanés-là sont purgés au-delà des N derniers
(`planning.snapshots.automatiques-conservees`, 5 par défaut) ; ceux créés à la
main ne le sont jamais.

Le contenu est stocké **dénormalisé** en JSONB, jamais comme une copie de
lignes `poste_affectation` : ces lignes sont liées aux `creneau` par clé
étrangère, donc une copie mourrait avec les créneaux du groupe abandonné —
exactement le cas d'usage visé. En contrepartie, restaurer est une
ré-résolution contre le référentiel du moment, et peut légitimement échouer.

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
{ "actif": false }
```

Une désactivation insère une ligne dans `constraint_toggle`, une réactivation
la supprime : c'est tout ce que la table porte (voir
[`contraintes.md`](contraintes.md)).

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

## Besoin minimum en animateurs

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/staffing` | Nombre minimal d'animateurs qu'exigent les stands et créneaux actuels, **sans lancer le solveur** (écran « Besoin en animateurs ») |

Le calcul part des **sièges** qu'une résolution aurait à pourvoir : le problème
est construit exactement comme pour `POST /api/solve/async/reference-data`
(horaires récurrents résolus, familles de relais, effectif réduit pendant les
pauses), si bien que le compte ne peut pas diverger du réel. Trois bornes sont
calculées, la plus grande est retenue dans `minimumTotal` :

| Champ | Borne |
| --- | --- |
| `picSimultane` | sièges ouverts au même instant : personne n'en tient deux à la fois |
| `picAvecPause` | même pic, chaque vacation prolongée de `pauseMinimaleEntreVacationsMinutes` — c'est le nombre **exact** d'animateurs distincts qu'exige la journée la plus chargée |
| `chargeTotal` | heures-personne totales divisées par le plafond hebdomadaire légal × nombre de semaines ISO |

`parJour` détaille chaque journée (`standsOuverts`, `sieges`, `heures`,
`picSimultane`, `picAvecPause`) et `jourCritique` pointe celle qui fixe
`picAvecPause`. Les trois bornes restent **optimistes** : elles ignorent les
compétences et les indisponibilités individuelles. À traiter comme un plancher
de recrutement à dépasser, jamais comme une cible.

```json
{
  "minimumTotal": 122,
  "minimumMajeurs": 87,
  "minimumMineurs": 35,
  "picSimultane": 105,
  "picAvecPause": 122,
  "chargeTotal": 67,
  "borneRetenue": "PIC_AVEC_PAUSE",
  "nombreSemaines": 3,
  "totalDemandeHeures": 9583.75,
  "jourCritique": { "date": "2026-07-10", "jour": 5, "picAvecPause": 122 }
}
```

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

## Éditions

Tout le référentiel est cloisonné par **édition** — une édition complète du
festival, « Année 2025 », « Année 2026 » — avec ses propres stands, animateurs,
typologies, emplacements, grilles de créneaux, paramètres et planning résolu.
Rien ne circule de l'une à l'autre. Voir [`editions.md`](editions.md).

**Le client désigne l'édition qu'il consulte à chaque requête**, via l'en-tête
`X-Edition-Id` :

- absent, ou nommant une édition inconnue → le serveur retombe silencieusement
  sur l'édition marquée `defaut`, jamais une erreur : un onglet resté ouvert
  sur une édition supprimée entre-temps continue de fonctionner ;
- ce n'est donc pas un état global : deux onglets peuvent travailler sur deux
  éditions différentes en même temps.

L'en-tête vaut pour **tous** les endpoints de ce document, à la seule exception
du dump SQL (`/api/database/*`), qui reste une sauvegarde de l'instance entière.

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/editions` | Liste des éditions |
| `GET` | `/api/editions/courant` | Édition à laquelle *cette* requête a réellement été résolue — la façon dont un client découvre que son `X-Edition-Id` a été ignoré |
| `POST` | `/api/editions` | Crée une édition vide (`{ "id", "nom" }`) ; **400** si l'identifiant est déjà pris |
| `PUT` | `/api/editions/{id}` | Renomme l'édition |
| `POST` | `/api/editions/{id}/dupliquer` | Crée une nouvelle édition (`{ "id", "nom" }`) et y recopie **tout** le référentiel de `{id}` — les résultats de solveur (`poste_affectation`, `planning_resolution`) sont exclus : « 2026 = 2025 moins les affectations » |
| `PUT` | `/api/editions/{id}/defaut` | Désigne l'édition de repli pour les appelants sans en-tête |
| `DELETE` | `/api/editions/{id}` | Supprime l'édition **et tout son référentiel** ; **400** s'il s'agit de l'édition par défaut, de l'édition courante, ou de la dernière restante |

Le verrou « un solveur à la fois » reste global à l'instance, toutes éditions
confondues (voir *Résolution asynchrone*) ; un job écrit son résultat dans
l'édition pour laquelle il a été lancé, même si le client bascule ensuite.

## Référentiels (CRUD)

Même schéma pour chaque référentiel : `GET` (liste), `POST` (création),
`PUT /{id}` (mise à jour), `DELETE /{id}` (suppression). Tout est lu et écrit
dans l'édition désignée par `X-Edition-Id` (voir ci-dessus) : deux éditions
peuvent porter les mêmes identifiants métier sans se marcher dessus.

| Ressource | Chemin |
| --- | --- |
| Stands | `/api/stands` |
| Créneaux | `/api/creneaux` |
| Groupes de créneaux | `/api/groupes-creneaux` |
| Animateurs | `/api/animateurs` |
| Typologies de jeux | `/api/typologies` |

`PUT /api/groupes-creneaux/{id}/actif` active cette grille de créneaux pour le
prochain solve et désactive toutes les autres **de l'édition courante** (une
seule grille active à la fois, par édition : activer une grille dans l'édition
2026 ne touche pas à celle de 2025).

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

### Horaires d'un stand

Un stand porte son planning d'ouverture sur deux niveaux, et `GET /api/stands`
les rend tels quels — sans expansion, c'est la vue que l'IHM édite :

- `horaires` : les **règles récurrentes**, `{ mode, jours, joursSemaine,
  dateDebut, dateFin, dates, fenetres, motif }`. `mode` vaut `OUVERTURE` ou
  `FERMETURE`, `jours` vaut `TOUS` (défaut), `JOURS_SEMAINE`, `PLAGE` ou
  `DATES`, et seuls les champs que ce sélecteur utilise sont lus ;
- `indisponibilites` / `ouvertures` : les **exceptions datées**, qui priment sur
  les règles pour le seul jour qu'elles nomment.

Dans les deux cas, `heureFin` est **nullable** et vaut alors « jusqu'à la
fermeture » : la fenêtre court jusqu'à la fin du créneau évalué. Voir
[`domaine.md`](domaine.md#horaires-récurrents) pour l'arbitrage complet.

`POST`/`PUT /api/stands` répondent **400** si une règle est incohérente (aucune
fenêtre, heure de fin antérieure à l'heure de début, sélecteur sans les données
qu'il exige) ou si deux règles de même portée, portant sur des jours qui se
croisent, se contredisent (l'une ouverture, l'autre fermeture) — il n'y aurait
pas de gagnant non arbitraire.

| Méthode | Chemin | Description |
| --- | --- | --- |
| `POST` | `/api/stands/compactage-horaires?appliquer=false` | Réécrit les fenêtres datées répétées en règles équivalentes. `appliquer=false` (défaut) est un **essai à blanc** : rien n'est écrit, le rapport décrit ce qui *serait* fait |
| `POST` | `/api/stands/compactage-horaires?appliquer=true` | Idem, et persiste les stands compactés |

Le rapport porte `{ applique, standsCompactes, fenetresAvant, fenetresApres,
stands[] }`, une ligne par stand : `{ standId, fenetresAvant, reglesApres,
exceptionsApres, ecartMinutes, compacte, raison }`. Un stand n'est réécrit que si
les règles proposées reproduisent ses propres segments ouverts ; sinon `compacte`
est `false` et `raison` dit pourquoi.

`ecartMinutes` compte les minutes d'ouverture en désaccord (au pire sur un
créneau) : `0` dans le cas général, `1` quand un ancien `23:59` est devenu la
fermeture réelle du jour. Ce cas a une conséquence visible : un stand absent
toute la journée ne génère plus le poste d'une minute que ce `23:59` laissait
derrière lui. Voir
[`domaine.md`](domaine.md#horaires-récurrents).

### Visualisation des ouvertures

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/ouvertures-stands` | Grille stand × jour des ouvertures réellement en vigueur, plus les anomalies à relire (écran « Ouvertures des stands ») |

Lecture seule, aucune résolution déclenchée — le pendant, pour les horaires, de
ce que `GET /api/feasibility` est pour la capacité en animateurs. La grille est
construite **à partir des postes que le solveur recevrait** (les stands sont
résolus, les créneaux ceux du groupe actif) : l'écran valide donc la donnée
réelle, pas une seconde interprétation de la même saisie.

```
{ jours: [{ date, jour, heureDebut, heureFin, minutes, nombreCreneaux }],
  stands: [{ standId, nom, effectifMin, minutesOuvertes, postes,
             jours: [{ date, etat, source, fenetres, minutesOuvertes,
                       minutesAmplitude, postes }] }],
  standsJamaisOuverts, postesTotal,
  anomalies: [{ type, standId, standNom, date, message }] }
```

- `etat` vaut `OUVERT_TOTAL`, `OUVERT_PARTIEL` ou `FERME` — la part de
  l'amplitude du jour que le stand couvre ;
- `source` vaut `DEFAUT`, `REGLE` ou `EXCEPTION` : quelle couche a décidé de ce
  jour-là, ce qui permet de remonter à la saisie fautive ;
- `fenetres` sont les plages ouvertes en heures réelles, fusionnées et bornées
  aux créneaux (les vacations d'un même jour se chevauchent par construction :
  une journée continue doit se lire comme une seule plage) ;
- `anomalies` porte trois types : `STAND_JAMAIS_OUVERT` (aucun poste sur tout le
  groupe), `FENETRE_SANS_EFFET` (une fenêtre saisie qui ne recoupe aucun créneau
  de son jour) et `SEGMENT_TROP_COURT` (une plage ouverte trop courte pour être
  un vrai créneau de travail — la signature du contournement `23:59`). Aucune ne
  bloque une résolution.

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
à exporter est envoyé dans le corps de la requête, sauf pour l'export global qui
lit le planning persisté lui-même (voir ci-dessous).

| Méthode | Chemin | Description |
| --- | --- | --- |
| `GET` | `/api/planning/export/pdf/global` | **Un seul PDF** reprenant toutes les affectations, pour l'organisateur : section « par journée » puis section « par stand » (bouton « Exporter le planning global » de la page Solveur) |
| `POST` | `/api/planning/export/bundle/all` | ZIP contenant un PDF **et** un ICS par animateur (utilisé par l'IHM) |
| `POST` | `/api/planning/export/pdf/all` | ZIP contenant un PDF par animateur |
| `POST` | `/api/planning/export/pdf/animateur/{animateurId}` | PDF du planning individuel |
| `POST` | `/api/planning/export/ics/all` | ZIP contenant un ICS par animateur |
| `POST` | `/api/planning/export/ics/animateur/{animateurId}` | ICS du planning individuel |

L'export global est le seul en `GET` : un planning de la taille du festival pèse
plusieurs mégaoctets en JSON, que l'appelant n'a pas à téléverser pour récupérer
un document. Il lit la même source que les calendriers
(`GET /api/planning/persisted`). Chaque ligne du document vaut pour un stand ×
vacation : elle porte les animateurs affectés, l'effectif `pourvus/sièges`, et
signale en rouge les sièges non pourvus.

## Heures planifiées

Calcule, pour chaque animateur, le nombre d'heures planifiées par semaine
calendaire ISO (`AAAA-Wss`) et le total. Le planning à analyser est envoyé
dans le corps de la requête, comme pour les exports.

| Méthode | Chemin | Description |
| --- | --- | --- |
| `POST` | `/api/planning/hours` | Rapport JSON (semaines + heures par animateur) |
| `POST` | `/api/planning/hours/export` | Le même rapport, au format CSV |
