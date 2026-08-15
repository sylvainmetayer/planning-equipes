-- Lets the vacation generator produce several time-staggered variants
-- ("familles") of the same day's relay grid instead of one shared grid used
-- identically by every stand. Each créneau records which variant it belongs
-- to (0 when staggering is off, the default); poste generation assigns each
-- stand to exactly one famille via a deterministic hash of its id, so
-- relay/handover moments spread out across the day instead of every stand
-- cutting over at the same clock minute. See VacationGeneratorService.

ALTER TABLE creneau ADD COLUMN IF NOT EXISTS famille INTEGER NOT NULL DEFAULT 0;

ALTER TABLE parametres_decoupage ADD COLUMN IF NOT EXISTS nombre_familles_decalage INTEGER NOT NULL DEFAULT 1;
ALTER TABLE parametres_decoupage ADD COLUMN IF NOT EXISTS duree_decalage_max_minutes INTEGER NOT NULL DEFAULT 0;
