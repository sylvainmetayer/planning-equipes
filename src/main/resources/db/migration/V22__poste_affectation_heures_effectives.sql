-- A poste generated for one open segment of a partially-closed stand (see
-- IndisponibiliteStand / issue #60) carries an effective time window narrower
-- than its créneau. Without these columns, PlanningPersistenceService dropped
-- that window on every persist/reload round-trip, silently reverting the
-- poste to the créneau's full hours everywhere downstream (exports, calendar
-- and staffing pages, and any constraint re-analysis run against the
-- persisted planning instead of the freshly-solved one in memory).
ALTER TABLE poste_affectation
    ADD COLUMN IF NOT EXISTS heure_debut_effective TIME,
    ADD COLUMN IF NOT EXISTS heure_fin_effective TIME;
