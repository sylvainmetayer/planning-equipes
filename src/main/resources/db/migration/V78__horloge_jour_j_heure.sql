-- Development-only frozen time of day, next to the frozen date of V58.
--
-- NULL keeps the real wall clock, which is what the date mock alone did: a
-- frozen day still moving hour by hour. Set, the moment is fixed — the same
-- 14:00 on every reload, which is how a seat « en cours » is checked on
-- purpose rather than by waiting for it.
--
-- A time without a date would freeze « 14:00 today » against the machine's
-- date, a moment that slides by one day at midnight: refused here as well as
-- by the write path.
ALTER TABLE horloge_jour_j ADD COLUMN IF NOT EXISTS heure_du_jour TIME;

ALTER TABLE horloge_jour_j
    ADD CONSTRAINT horloge_jour_j_heure_avec_date CHECK (heure_du_jour IS NULL OR date_du_jour IS NOT NULL);
