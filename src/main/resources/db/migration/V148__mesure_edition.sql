-- Les mesures d'une édition n'ont plus qu'une table : `mesure_edition` reprend
-- l'historique des indicateurs (`kpi_historique`, V48, une ligne par résolution
-- terminée) et le réalisé figé (`kpi_realise`, V116, une ligne par édition et
-- par typologie) sous une colonne `nature`.
--
-- Comme les deux tables qu'elle remplace, elle est volontairement SANS clé
-- étrangère : la mesure survit à l'édition qu'elle décrit, c'est l'édition
-- suivante qui la lit. Les libellés sont recopiés pour la même raison, et les
-- bornes de l'événement aussi (une édition ne stocke pas ses dates).
--
-- « Écrit une seule fois » reste vrai du réalisé : l'index unique partiel porte
-- l'ON CONFLICT DO NOTHING, et refiger une édition reste un DELETE de ses
-- lignes `REALISE` par l'exploitant.
--
-- Rien n'est perdu : les lignes sont recopiées (les ids de l'historique sont
-- conservés, ce sont ceux que les clients connaissent), puis les anciennes
-- tables passent en `*_avant_4_0`, que l'application ne lit ni n'exporte et que
-- la 5.0 supprimera (retour à la 3.x ou relecture par l'exploitant d'ici là).

CREATE TABLE mesure_edition (
    id                BIGSERIAL PRIMARY KEY,
    nature            VARCHAR(16) NOT NULL CHECK (nature IN ('INDICATEURS', 'REALISE')),
    edition_id        VARCHAR(64) NOT NULL,
    edition_nom       VARCHAR(255),
    -- INDICATEURS : les chiffres d'une résolution (jamais une personne).
    kpi               JSONB,
    -- REALISE : « » pour la ligne de l'événement entier, l'id de la typologie sinon.
    typologie_id      VARCHAR(64),
    typologie_nom     VARCHAR(255),
    premier_jour      DATE,
    dernier_jour      DATE,
    jours_comptes     INT,
    sieges_publies    INT,
    sieges_tenus      INT,
    absences          INT,
    remplacements     INT,
    sieges_vides      INT,
    sieges_retires    INT,
    sieges_ajoutes    INT,
    minutes_publiees  INT,
    minutes_realisees INT,
    minutes_perdues   INT,
    -- La date de la résolution, ou celle où le réalisé a été figé.
    cree_le           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CHECK ((nature = 'INDICATEURS' AND kpi IS NOT NULL)
        OR (nature = 'REALISE' AND typologie_id IS NOT NULL AND premier_jour IS NOT NULL
            AND dernier_jour IS NOT NULL AND jours_comptes IS NOT NULL AND sieges_publies IS NOT NULL
            AND sieges_tenus IS NOT NULL AND absences IS NOT NULL AND remplacements IS NOT NULL
            AND sieges_vides IS NOT NULL AND sieges_retires IS NOT NULL AND sieges_ajoutes IS NOT NULL
            AND minutes_publiees IS NOT NULL AND minutes_realisees IS NOT NULL
            AND minutes_perdues IS NOT NULL))
);

CREATE INDEX idx_mesure_edition_indicateurs
    ON mesure_edition(edition_id, cree_le DESC) WHERE nature = 'INDICATEURS';
CREATE UNIQUE INDEX uq_mesure_edition_realise
    ON mesure_edition(edition_id, typologie_id) WHERE nature = 'REALISE';
CREATE INDEX idx_mesure_edition_realise_dernier_jour
    ON mesure_edition(dernier_jour DESC) WHERE nature = 'REALISE';

INSERT INTO mesure_edition (id, nature, edition_id, edition_nom, kpi, cree_le)
SELECT id, 'INDICATEURS', edition_id, edition_nom, kpi, cree_le
FROM kpi_historique;

-- La séquence repart après les ids recopiés : les lignes du réalisé en reçoivent de nouveaux.
SELECT setval(pg_get_serial_sequence('mesure_edition', 'id'),
              greatest(1, coalesce((SELECT max(id) FROM mesure_edition), 0)),
              (SELECT count(*) > 0 FROM mesure_edition));

INSERT INTO mesure_edition (nature, edition_id, edition_nom, typologie_id, typologie_nom, premier_jour,
                            dernier_jour, jours_comptes, sieges_publies, sieges_tenus, absences, remplacements,
                            sieges_vides, sieges_retires, sieges_ajoutes, minutes_publiees, minutes_realisees,
                            minutes_perdues, cree_le)
SELECT 'REALISE', edition_id, edition_nom, typologie_id, typologie_nom, premier_jour, dernier_jour, jours_comptes,
       sieges_publies, sieges_tenus, absences, remplacements, sieges_vides, sieges_retires, sieges_ajoutes,
       minutes_publiees, minutes_realisees, minutes_perdues, fige_le
FROM kpi_realise
ORDER BY fige_le, edition_id, typologie_id;

ALTER TABLE kpi_historique RENAME TO kpi_historique_avant_4_0;
ALTER TABLE kpi_realise RENAME TO kpi_realise_avant_4_0;
