---
ordre: 40
---

### Le lanceur de machine à la demande est retiré

- **Avant (3.x)** : `SOLVER_WORKER_HOOK_URL` et `SOLVER_WORKER_HOOK_SECRET` branchaient un lanceur qui créait une machine louée le temps d'un calcul, avec `SOLVER_WORKER_INSTANCE`, `SOLVER_WORKER_START_TIMEOUT` et `SOLVER_WORKER_IDLE_TIMEOUT`. L'écran Solveur montrait l'état de cette machine, avec « Préparer » et « Arrêter », et le dépôt livrait `deploy/launcher/`.
- **Après (4.0)** : l'application ne démarre ni n'arrête plus aucune machine. Les cinq variables ci-dessus n'ont plus aucun effet : si l'une est renseignée, le démarrage le dit dans un avertissement et continue ; l'avertissement disparaît en 5.0. Le **worker permanent** (`SOLVER_WORKER_URL`, `SOLVER_WORKER_SECRET`, `SOLVER_WORKER_TLS_FINGERPRINT`, `SOLVER_WORKER_DEFAULT`) ne change pas, ni la règle qu'un échec distant fait échouer la tâche. Le choix « Sur place » ou « Sur la machine de calcul » reste sur l'écran Solveur.
- **Ce que vous faites** : si vous utilisiez le lanceur, louez et allumez la machine par les outils de votre hébergeur, lancez-y le worker ([la procédure](calcul-sur-une-autre-machine.md)) et renseignez `SOLVER_WORKER_URL` et `SOLVER_WORKER_SECRET`. Sans cela, vos calculs tournent sur place. Retirez ensuite les cinq variables de votre environnement.
