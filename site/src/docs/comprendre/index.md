---
titre: Comprendre comment c'est fait
pour: "Pour qui développe, intègre ou audite : le modèle, les règles, l'API, la construction."
docs:
  - vue-d-ensemble
  - cloisonnement-par-edition
  - acces-et-comptes
  - taches-de-fond-et-notifications
  - frontend
  - comment-le-planning-est-calcule
  - modele-des-donnees
  - horaires-et-fenetres-des-stands
  - plan-verrous-et-publication
  - parametres-legaux-et-qualite
  - exceptions-ad-hoc
  - pourquoi-ces-regles
  - regles-legales-et-pauses
  - repos-et-jours-d-affilee
  - regles-de-couverture-et-de-confort
  - doser-et-desactiver-les-regles
  - ajouter-une-regle
  - api-conventions
  - api-calcul
  - api-planning-et-publication
  - api-regles-et-diagnostic
  - api-referentiels
  - api-horaires-des-stands
  - api-avertissements-de-saisie
  - api-jour-j-et-animateurs
  - api-faisabilite-et-pauses
  - api-espace-animateur
  - api-integrations
  - mcp-connexion
  - mcp-conventions-des-outils
  - mcp-outils
  - scenario-yaml
  - imports-csv
  - exports-et-archives
  - restaurer-une-edition
  - demarrer
  - tests
  - tests-de-scenarios
  - tests-de-charge
  - regler-le-solveur
  - ci-et-livraison
  - renovate-et-dependances
  - conventions-du-depot
---
# Comprendre comment c'est fait

Planning Équipes est une application Quarkus qui embarque le solveur Timefold, une interface Angular et une base PostgreSQL, le tout dans un seul service. Ces pages montrent comment les pièces tiennent ensemble, pourquoi chaque règle existe et ce qu'un changement ne doit pas casser.

Si vous découvrez le code, lisez dans l'ordre [la vue d'ensemble](vue-d-ensemble.md), [le calcul du planning](comment-le-planning-est-calcule.md) et [le modèle des données](modele-des-donnees.md). Sinon, partez de la question que vous avez.

## Je découvre le projet

- Je veux savoir comment le code est découpé : [La pile et le découpage du code](vue-d-ensemble.md).
- Je veux savoir comment les éditions restent séparées : [Une édition, une partition des données](cloisonnement-par-edition.md).
- Je cherche qui peut faire quoi : [Comptes, rôles et accès](acces-et-comptes.md).
- Je cherche ce qui tourne sans qu'on le lance, et ce qui part par mail ou par webhook : [Tâches de fond et notifications](taches-de-fond-et-notifications.md).
- Je touche à l'interface : [Le frontend Angular](frontend.md).

## Je veux comprendre le calcul

- [Du problème au plan : le solveur et ses deux moteurs](comment-le-planning-est-calcule.md)
- [Le modèle des données et ses invariants](modele-des-donnees.md)
- [Horaires, consignes et fenêtres effectives des stands](horaires-et-fenetres-des-stands.md)
- [Verrous, gel, replanification, échanges et plan publié](plan-verrous-et-publication.md)
- [Vacations, paramètres légaux, repas et qualité](parametres-legaux-et-qualite.md)
- [Les exceptions ad hoc et leurs contradictions](exceptions-ad-hoc.md)

## Je veux savoir pourquoi une règle existe, ou en ajouter une

- [Les règles du solveur et leur catalogue](pourquoi-ces-regles.md)
- [Pauses, repas et durées légales](regles-legales-et-pauses.md)
- [Repos, semaines et jours d'affilée](repos-et-jours-d-affilee.md)
- [Couverture, équité et confort](regles-de-couverture-et-de-confort.md)
- [Régler le poids d'une règle, l'éteindre](doser-et-desactiver-les-regles.md)
- [Ajouter une règle](ajouter-une-regle.md)

## J'appelle l'API ou je branche un assistant

- [Édition, authentification et erreurs](api-conventions.md)
- [Lancer, suivre et arrêter un calcul](api-calcul.md)
- [Consignes, publication et gel du référentiel](api-planning-et-publication.md)
- [Règles, explications et réparations](api-regles-et-diagnostic.md)
- [Les référentiels](api-referentiels.md), [leurs horaires](api-horaires-des-stands.md) et [les avertissements de saisie](api-avertissements-de-saisie.md)
- [Le jour J et le mural](api-jour-j-et-animateurs.md), [la faisabilité et les pauses](api-faisabilite-et-pauses.md), [l'espace des animateurs](api-espace-animateur.md)
- [Webhooks, météo, journal, exports](api-integrations.md)
- [Brancher un assistant IA](mcp-connexion.md), [les règles des outils MCP](mcp-conventions-des-outils.md) et [les outils](mcp-outils.md)

## Je charge ou j'exporte des données

- [Le fichier de scénario YAML](scenario-yaml.md)
- [Les imports CSV](imports-csv.md)
- [PDF, ICS, CSV et archive de fin d'événement](exports-et-archives.md)
- [Repartir d'une édition, restaurer après un import](restaurer-une-edition.md)

## Je contribue

- [Installer l'environnement et lancer](demarrer.md)
- [Les tests](tests.md) et [les scénarios de charge](tests-de-scenarios.md)
- [Tester la charge d'une démo publique](tests-de-charge.md)
- [Régler le solveur](regler-le-solveur.md)
- [Intégration continue et livraison](ci-et-livraison.md), [Renovate et licences](renovate-et-dependances.md)
- [Conventions du dépôt](conventions-du-depot.md)

## Ce qui vit dans le dépôt

Les décisions d'architecture sont une page par choix dans [`docs/decisions/`](repo:docs/decisions/README.md). Le contrat de l'API est [`docs/schema/openapi.json`](repo:docs/schema/openapi.json). Les diagrammes sont des sources PlantUML dans [`docs/diagrammes/`](repo:docs/diagrammes). [La mémoire du projet](repo:docs/memoire-du-projet.md) rassemble les mesures et les pistes abandonnées derrière certains réglages du solveur.

Pour héberger l'application plutôt que la modifier, voyez [Installer chez vous](../installer/index.md) ; pour l'utiliser, [Les règles du jeu](../regles/index.md).
