-- Declares whether the legal break (20 min once 6 h of work are reached for an
-- adult, 30 min at 4 h 30 for a minor) is taken on the post, by relay between
-- colleagues, rather than as a gap between two vacations. When true, the
-- continuous-work constraints treat the break as organised inside the
-- vacation and the daily caps deduct it from the amplitude. Default false:
-- the application never presumes an organisational fact it does not hold.

ALTER TABLE parametres_legaux
    ADD COLUMN IF NOT EXISTS pause_sur_poste BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN parametres_legaux.pause_sur_poste IS
    'Legal break taken on the post by relay (L3121-16 / L3162-3) instead of as a gap between vacations';
