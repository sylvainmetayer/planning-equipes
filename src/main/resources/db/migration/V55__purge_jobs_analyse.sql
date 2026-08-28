-- Suppression du type de job ANALYZE.
--
-- Une « analyse » était une résolution complète dont le résultat n'était jamais
-- persisté : elle coûtait le même budget qu'un solve pour décrire un planning
-- que plus aucun écran ni aucun outil n'aurait montré. Le diagnostic se dérive
-- désormais du plan persisté, sans solveur (`POST /api/constraints/diagnostic`,
-- outil MCP `diagnostiquer_plan`).
--
-- Les lignes restantes doivent partir : `SolverJobRepository` relit toute la
-- table au démarrage et fait `JobType.valueOf(type)`. Une seule ligne ANALYZE
-- oubliée ferait échouer la restauration de la file entière.
--
-- Le journal perd donc l'historique de ces analyses. C'est assumé : il ne
-- porte que l'intention d'un job, jamais son résultat, et le plan qu'aucune
-- d'elles n'a écrit n'existe nulle part.

DELETE FROM solver_job WHERE type = 'ANALYZE';
