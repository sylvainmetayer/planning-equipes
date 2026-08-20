-- Validation par le collègue : une demande soumise attend d'abord l'accord de
-- la cible (EN_ATTENTE_CIBLE) avant d'entrer dans la file admin (PROPOSEE) —
-- l'administrateur n'a plus à demander à chacun s'il est d'accord. Les
-- demandes PROPOSEE existantes restent en file admin telles quelles.
ALTER TABLE demande_echange
    ADD COLUMN cible_decide_le timestamp;

ALTER TABLE demande_echange
    DROP CONSTRAINT demande_echange_statut_connu,
    ADD CONSTRAINT demande_echange_statut_connu CHECK (
        statut IN ('EN_ATTENTE_CIBLE', 'PROPOSEE', 'ACCEPTEE', 'REFUSEE', 'REFUSEE_CIBLE', 'ANNULEE'));
