# 0002 — Découpler le diagnostic de contraintes de l'édition du solveur

- **Statut** : remplacé par [0012](0012-diagnostic-par-le-score-director.md) —
  le découplage a été réalisé, mais avec une seule implémentation au lieu des
  deux modes retenus ici
- **Date** : août 2026
- **Portée** : service, API, frontend

## Contexte

Le diagnostic — « quelles contraintes sont violées, combien de fois, par quels
postes » — repose entièrement sur `SolutionManager.analyze()`. Cette méthode est
disponible en édition Community de Timefold 1.x, mais **devient réservée à
l'édition Enterprise en 2.x**.

Entre-temps, la dépendance s'est élargie bien au-delà de l'écran qui l'avait
introduite : `PlanningService.diagnostiquer()`, `ConstraintAnalysisStore`, les
ressources REST, les jobs d'analyse asynchrones, les outils MCP, et côté IHM les
pages *Contraintes*, *Problèmes*, *Heatmap*, l'explication d'affectation et la
simulation d'échange. Chaque nouvel écran de diagnostic augmente donc le coût
d'une migration déjà bloquée.

## Options envisagées

| Option | Ce qu'elle coûte |
| --- | --- |
| Rester en 1.x indéfiniment | Gèle une branche qui cessera d'être maintenue |
| Migrer en 2.x et abandonner les écrans de diagnostic | Retire ce qui rend le résultat du solveur compréhensible |
| Acquérir une licence Enterprise | Reporte la décision sur le modèle économique, et la rend récurrente |
| Interposer une abstraction à deux implémentations | Travail de construction, mais découple la feuille de route de la question |

## Décision

Interposer une interface au-dessus de l'analyse, avec deux implémentations : une
**détaillée**, qui reste celle d'aujourd'hui, et une **dégradée**, qui recalcule
les violations en réexécutant les contraintes.

Deux propriétés font tout l'intérêt de l'abstraction, et doivent être tenues :
le **point de bascule est unique et explicite** — un seul endroit décide quelle
implémentation est disponible, le reste du code l'ignore ; et le **format de
sortie est identique dans les deux modes**, assorti d'un indicateur de mode pour
que l'IHM puisse signaler la perte de finesse plutôt que de mentir par omission.

## Conséquences

- La feuille de route des écrans cesse de dépendre du choix d'édition du
  solveur. C'est le but : chaque écran ajouté ne renchérit plus la migration.
- Le mode dégradé perd la justification par correspondance — on sait *combien*
  de violations, pas *lesquelles* précisément. L'explication d'affectation et la
  simulation d'échange y perdent en précision, il faut le dire à l'utilisateur.
- Rien n'existe aujourd'hui : c'est une construction complète, pas un
  refactoring.
