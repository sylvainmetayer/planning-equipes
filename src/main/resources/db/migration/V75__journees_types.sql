-- Journées types nommées : « Jour normal », « Nocturne », « Montage » — chacune
-- une ligne de vacations fixes, appliquée à des dates. C'est ce qui remplace la
-- saisie de la grille vacation par vacation pour une édition qui tape ses
-- horaires à la main (décision du 2026-09-12, ADR 0032).
--
-- La journée type est un GÉNÉRATEUR persisté, pas la vérité : les créneaux
-- restent ce que lit tout l'aval (postes, verrous, bornes de l'édition via
-- JoursEvenement). Appliquer un calendrier matérialise les créneaux par
-- différence sur (date, début, fin) — un créneau identique garde son id et ses
-- sièges — et une date sans journée type n'est jamais touchée.
--
-- Les dates du calendrier sont le premier objet qui porte des dates
-- indépendamment des créneaux : c'est là que l'organisateur pose les dates de
-- l'édition avant d'avoir un seul créneau.

CREATE TABLE IF NOT EXISTS journee_type (
    edition_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES edition(id) ON DELETE CASCADE,
    id BIGSERIAL,
    nom VARCHAR(80) NOT NULL,
    modifie_le TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (edition_id, id)
);

-- Un nom par édition, à la casse près : « Nocturne » et « nocturne » seraient
-- deux journées types que rien ne distingue à l'écran.
CREATE UNIQUE INDEX IF NOT EXISTS uq_journee_type_nom ON journee_type (edition_id, lower(nom));

COMMENT ON TABLE journee_type IS
    'A named day template: the vacations of one kind of day, applied to dates (ADR 0032)';

-- Les vacations d'une journée type, dans l'ordre de saisie. `couverture_pause`
-- a le même sens que sur le créneau : un relais repas, sièges divisés par deux.
CREATE TABLE IF NOT EXISTS journee_type_vacation (
    edition_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES edition(id) ON DELETE CASCADE,
    id BIGSERIAL,
    journee_type_id BIGINT NOT NULL,
    position INT NOT NULL DEFAULT 0,
    heure_debut TIME NOT NULL,
    heure_fin TIME NOT NULL,
    couverture_pause BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (edition_id, id),
    CONSTRAINT journee_type_vacation_journee_fkey
        FOREIGN KEY (edition_id, journee_type_id) REFERENCES journee_type (edition_id, id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_journee_type_vacation_journee
    ON journee_type_vacation (edition_id, journee_type_id);

-- Le calendrier : chaque date gouvernée par une journée type, une seule. Une
-- date absente d'ici n'est gouvernée par rien, et ses créneaux sont laissés
-- tels quels par l'application.
CREATE TABLE IF NOT EXISTS journee_type_date (
    edition_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES edition(id) ON DELETE CASCADE,
    date_jour DATE NOT NULL,
    journee_type_id BIGINT NOT NULL,
    PRIMARY KEY (edition_id, date_jour),
    CONSTRAINT journee_type_date_journee_fkey
        FOREIGN KEY (edition_id, journee_type_id) REFERENCES journee_type (edition_id, id) ON DELETE CASCADE
);

COMMENT ON TABLE journee_type_date IS
    'Which day template governs which date; a date not listed is governed by nothing';
