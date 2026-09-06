-- Where a queued full solve was asked to start from (issue #174) : AUTO
-- (repart du plan enregistré s'il existe), PLAN_COURANT ou AUCUN. Persisté
-- avec l'intention du job, comme `perimetre`, pour qu'une file rejouée après
-- redémarrage démarre comme on le lui avait demandé. NULL pour une
-- replanification incrémentale, qui n'a pas ce choix.
ALTER TABLE solver_job ADD COLUMN reamorcage VARCHAR(16);
