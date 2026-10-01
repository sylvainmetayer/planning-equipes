-- Alerte météo (ADR 0074) : chaque matin, l'édition qui émet interroge
-- Open-Meteo sur les lieux de ses stands ouverts et, si un seuil est franchi,
-- SUGGÈRE un préréglage de consigne. Rien n'est jamais appliqué.
--
-- Deux tables, et le partage entre elles est le point :
--
-- 1. `parametres_meteo` — ce que l'organisateur règle, une ligne par édition.
--    Exportée et recopiée par la duplication, mais `actif` y est EXCUSÉ et
--    retombe donc à FALSE : une édition copiée n'interroge personne tant que
--    quelqu'un ne l'a pas décidé. Les préréglages suggérés pointent vers ceux
--    de l'édition ; un préréglage supprimé laisse la suggestion vide
--    (ON DELETE SET NULL sur la seule colonne, pas sur edition_id).
--
-- 2. `meteo_etat` — ce que la machine a vu à la dernière interrogation
--    (« lue le… », « injoignable depuis… », « hors prévision »). L'état d'une
--    instance, pas un réglage : ni exporté, ni recopié.
--
-- Aucune donnée personnelle : des seuils, des dates, des lieux.

CREATE TABLE parametres_meteo (
    edition_id         VARCHAR(64) PRIMARY KEY REFERENCES edition (id) ON DELETE CASCADE,
    actif              BOOLEAN     NOT NULL DEFAULT FALSE,
    horizon_jours      INT         NOT NULL DEFAULT 5,
    seuil_temperature  INT         NOT NULL DEFAULT 33,
    seuil_rafales      INT         NOT NULL DEFAULT 60,
    orage              BOOLEAN     NOT NULL DEFAULT TRUE,
    prereglage_chaleur VARCHAR(64),
    prereglage_vent    VARCHAR(64),
    prereglage_orage   VARCHAR(64),
    CONSTRAINT parametres_meteo_horizon CHECK (horizon_jours BETWEEN 1 AND 14),
    CONSTRAINT parametres_meteo_prereglage_chaleur
        FOREIGN KEY (edition_id, prereglage_chaleur) REFERENCES prereglage_consigne (edition_id, id)
        ON DELETE SET NULL (prereglage_chaleur),
    CONSTRAINT parametres_meteo_prereglage_vent
        FOREIGN KEY (edition_id, prereglage_vent) REFERENCES prereglage_consigne (edition_id, id)
        ON DELETE SET NULL (prereglage_vent),
    CONSTRAINT parametres_meteo_prereglage_orage
        FOREIGN KEY (edition_id, prereglage_orage) REFERENCES prereglage_consigne (edition_id, id)
        ON DELETE SET NULL (prereglage_orage)
);

-- La précondition d'écriture des réglages (#362) : deux sessions ouvertes sur
-- l'écran Paramètres ne s'écrasent pas en silence.
ALTER TABLE parametres_meteo ADD COLUMN modifie_le TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE TABLE meteo_etat (
    edition_id         VARCHAR(64) PRIMARY KEY REFERENCES edition (id) ON DELETE CASCADE,
    issue              VARCHAR(20),
    derniere_tentative TIMESTAMPTZ,
    derniere_lecture   TIMESTAMPTZ,
    injoignable_depuis TIMESTAMPTZ,
    erreur             VARCHAR(300),
    CONSTRAINT meteo_etat_issue_connue
        CHECK (issue IS NULL OR issue IN ('READ', 'OUT_OF_FORECAST', 'NO_PLACE', 'UNREACHABLE'))
);
