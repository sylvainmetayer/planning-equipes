-- `reposQuotidienMineur` a été supprimée au profit de `reposQuotidienMinimal`
-- (repos quotidien réel, gradué par tranche d'âge : 11 h majeur — art. L3131-1,
-- 12 h mineur et 14 h avant 16 ans — art. L3164-1). L'ancienne règle ne se
-- déclenchait qu'après un créneau de nuit tenu par un mineur, situation que
-- `travailDeNuitInterditPourMineur` interdit déjà : elle valait zéro dans tout
-- planning valide.
--
-- Même logique que V14 : une désactivation enregistrée sous l'ancien nom est
-- reportée sur le nouveau plutôt que perdue silencieusement.

INSERT INTO constraint_toggle (nom)
SELECT 'reposQuotidienMinimal'
WHERE EXISTS (SELECT 1 FROM constraint_toggle WHERE nom = 'reposQuotidienMineur')
ON CONFLICT (nom) DO NOTHING;

DELETE FROM constraint_toggle WHERE nom = 'reposQuotidienMineur';
