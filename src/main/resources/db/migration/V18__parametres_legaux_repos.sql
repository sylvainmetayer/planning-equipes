-- Adds the two new admin-configurable legal-rest parameters consumed by the
-- pauseMinimaleEntreVacations and reposQuotidienMinimalTousAnimateurs hard
-- constraints. Defaults: 30 min between two same-day vacations, 11h (660 min)
-- daily rest between two calendar days (Code du travail art. L3131-1).

ALTER TABLE parametres_legaux
    ADD COLUMN IF NOT EXISTS pause_minimale_entre_vacations_minutes INTEGER NOT NULL DEFAULT 30,
    ADD COLUMN IF NOT EXISTS repos_quotidien_minimal_minutes INTEGER NOT NULL DEFAULT 660;
