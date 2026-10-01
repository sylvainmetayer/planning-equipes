-- Réalisé vs planifié : la mesure de fin d'événement, une ligne par édition et
-- par typologie, plus une ligne pour l'événement entier (typologie_id vide).
--
-- Écrite une seule fois par le job de nuit (RealisedFreezeJob), la première
-- nuit après le dernier créneau de l'édition : une édition qui a ses lignes
-- est sautée, et la clé (edition_id, typologie_id) porte l'ON CONFLICT DO
-- NOTHING qui empêche deux passages d'écrire deux fois. Refiger une édition
-- est une suppression de ses lignes à la main (docs/exploitation.md).
--
-- Volontairement SANS clé étrangère vers `edition` ni vers `typologie`, et
-- avec les libellés dénormalisés, comme `kpi_historique` (V48) : la mesure
-- doit survivre à la suppression de l'édition qu'elle décrit — c'est
-- l'édition suivante qui la lit, sur sa page Versions et sur l'onglet Besoin.
-- Les bornes de l'événement sont recopiées pour la même raison : une édition
-- ne stocke pas ses dates (elles se déduisent de ses créneaux), et une fois
-- l'édition supprimée il n'y a plus de créneau pour les déduire. Elles disent
-- aussi laquelle est « l'édition précédente » : celle dont l'événement s'est
-- terminé le plus tard avant le premier jour de l'édition qui lit.
--
-- Aucune donnée nominative : des comptes et des minutes, jamais une personne.
-- Les taux se calculent à la lecture.

CREATE TABLE IF NOT EXISTS kpi_realise (
    edition_id        VARCHAR(64)  NOT NULL,
    typologie_id      VARCHAR(64)  NOT NULL,
    edition_nom       VARCHAR(255),
    typologie_nom     VARCHAR(255),
    premier_jour      DATE         NOT NULL,
    dernier_jour      DATE         NOT NULL,
    jours_comptes     INT          NOT NULL,
    sieges_publies    INT          NOT NULL,
    sieges_tenus      INT          NOT NULL,
    absences          INT          NOT NULL,
    remplacements     INT          NOT NULL,
    sieges_vides      INT          NOT NULL,
    sieges_retires    INT          NOT NULL,
    sieges_ajoutes    INT          NOT NULL,
    minutes_publiees  INT          NOT NULL,
    minutes_realisees INT          NOT NULL,
    minutes_perdues   INT          NOT NULL,
    fige_le           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (edition_id, typologie_id)
);

CREATE INDEX IF NOT EXISTS idx_kpi_realise_dernier_jour
    ON kpi_realise(dernier_jour DESC);
