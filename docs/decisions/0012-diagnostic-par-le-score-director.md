# 0012 — Diagnostiquer par le score director, sans mode dégradé

- **Statut** : accepté — remplace [0002](0002-diagnostic-decouple-du-solveur.md)
- **Date** : août 2026
- **Portée** : service

## Contexte

La décision [0002](0002-diagnostic-decouple-du-solveur.md) pose le problème et
reste valable : le diagnostic de contraintes repose entièrement sur
`SolutionManager.analyze()`, réservé à l'édition Enterprise de Timefold à partir
de la 2.x, et la dépendance s'est élargie bien au-delà de l'écran qui l'avait
introduite.

Elle en tirait une conclusion que la présente décision révise : que l'analyse
devait exister en deux qualités, une détaillée et une dégradée, avec un
indicateur de mode pour avertir l'utilisateur de la perte de finesse.

Ce que l'examen de la bibliothèque a montré ensuite : **seule la façade est
verrouillée**. Le verrou porte sur trois méthodes de `SolutionManager` —
`analyze()`, `diff()`, `recommendAssignment()` — et sur rien du chemin qui les
implémente. Le score director, la session de contraintes et le calcul des
correspondances, y compris les justifications qui en sont la partie coûteuse,
sont du code ordinaire de l'édition Community. Le contournement que Timefold
documente lui-même pour cette fonctionnalité tient en une phrase : ne pas
utiliser la méthode `analyze()`.

Or `analyze()` n'est, à ce niveau, rien d'autre qu'un calcul de score suivi
d'un parcours de la table des correspondances par contrainte. Refaire ce
parcours n'est pas reconstruire un analyseur.

## Options envisagées

| Option | Ce qu'elle coûte |
| --- | --- |
| Deux implémentations, détaillée et dégradée (décision 0002) | Matrice de tests doublée, contrat d'API à deux visages, IHM devant signaler une perte de finesse — et surtout extinction réelle de l'explication d'affectation, des simulations et de l'attribution des contraintes ad hoc, faute de justifications |
| Rendre l'analyse tolérante à l'absence de licence | Vingt lignes, les solves aboutissent, mais les écrans de diagnostic restent vides |
| Une implémentation unique sur le score director | Dépendance à `ai.timefold.solver.core.impl`, hors semver |

## Décision

Une **seule** analyse, produite par le score director du solveur, exposée
derrière `ConstraintDiagnosticService` sous un type que le projet possède.
Aucun mode dégradé, aucune détection au démarrage, aucun indicateur de mode :
il n'y a qu'une qualité d'analyse, donc rien à signaler.

L'implémentation basée sur `analyze()` est **conservée**, non comme secours mais
comme **oracle** : un test de contrat exécute les deux côte à côte sur un
planning de référence et échoue si elles divergent, sur le score, les
contraintes, le nombre de correspondances ou les faits qui les justifient. Une
propriété de configuration sélectionne l'une ou l'autre, et ce point de bascule
est le seul endroit du code qui sait qu'il en existe deux.

## Conséquences

- La feuille de route des écrans cesse de dépendre du choix d'édition du
  solveur, ce qui était le but de 0002. Une migration vers la 2.x n'a plus de
  verrou à lever sur le chemin par défaut.
- Le projet s'appuie sur des paquets `impl` sans promesse de compatibilité. Ce
  pari est déjà pris ailleurs — deux filtres de mouvement du solveur en
  dépendent — mais il est ici assumé explicitement : tout le contact tient dans
  une classe, et le test de contrat est ce qui rend la dépendance surveillable
  plutôt que silencieuse. Une montée de version de Timefold doit faire tourner
  ce test.
- Le passage à la 2.x reste borné, mais pas trivial. Vérifié contre 2.5.0 :
  `ConstraintMatchTotal` et `ConstraintMatch` se déplacent d'un paquet `api`
  vers un paquet `impl` ; `ConstraintRef` perd `constraintName()` au profit
  d'`id()`, et **il faudra vérifier ce que cet identifiant contient** — ces noms
  servent de clés vers le catalogue des contraintes, donc un identifiant
  qualifié casserait toutes les recherches en silence ; le type de score change
  de paquet et passe en `long`, ce qui dépasse cette classe. Le type de clé de
  la table des correspondances change aussi, mais le code l'évite déjà en n'en
  lisant que les valeurs.
- L'implémentation conservée cessera de fonctionner en 2.x sans licence. C'est
  attendu : c'est la propriété qu'elle documente, et le test de contrat devra
  alors être exécuté sur la 1.x ou avec une licence, ou retiré en connaissance
  de cause.
