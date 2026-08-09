-- Physical-effort tier of a stand (issue #93 / #79): lets the solver identify
-- physically exhausting stands (e.g. "Homme-jeu") and prioritise rest — or an
-- easier stand — right after one, and factor them into the fairness
-- balancing of "pénible" slots across animateurs. Only two levels for now,
-- mirroring the existing boolean-flag pattern (premium) rather than a
-- three-way scale.

ALTER TABLE stand ADD COLUMN niveau_effort VARCHAR(16) NOT NULL DEFAULT 'NORMAL';
