-- « Je ne pourrai pas être là » : l'empêchement qu'un animateur signale depuis
-- son espace, sur une journée ou sur un poste, pendant toute l'édition — la
-- foire et la collecte fermées comprises, puisque c'est le cas principal.
--
-- Une information, pas une écriture du référentiel : rien n'est modifié au
-- planning tant que l'organisation n'a pas constaté l'absence (« Marquer
-- absent et remplacer », statut TRAITE) ou classé le signalement (CLASSE).
-- L'animateur annule le sien tant qu'il n'est pas traité (ANNULE).
--
-- Le motif est une liste fermée et facultative — PERSONNEL, TRANSPORT, AUTRE —,
-- jamais un texte libre : un champ libre est l'endroit où l'on écrit une raison
-- de santé que personne n'a demandée (docs/rgpd.md).
--
-- Le poste est désigné par sa clé naturelle, créneau et stand, jamais par
-- l'identifiant du siège : une résolution renumérote les sièges sans déplacer
-- personne. Le signalement suit la fiche, le créneau et le stand qu'il nomme
-- (suppression en cascade), et disparaît avec l'édition.
CREATE TABLE signalement_absence (
    edition_id   VARCHAR(64) NOT NULL REFERENCES edition (id) ON DELETE CASCADE,
    id           BIGSERIAL,
    animateur_id VARCHAR(64) NOT NULL,
    -- JOUR ou POSTE.
    portee       VARCHAR(16) NOT NULL,
    jour         DATE        NOT NULL,
    creneau_id   BIGINT,
    stand_id     VARCHAR(64),
    motif        VARCHAR(16),
    statut       VARCHAR(16) NOT NULL DEFAULT 'SIGNALE',
    signale_le   TIMESTAMPTZ NOT NULL DEFAULT now(),
    traite_le    TIMESTAMPTZ,
    PRIMARY KEY (edition_id, id),
    FOREIGN KEY (edition_id, animateur_id) REFERENCES animateur (edition_id, id) ON DELETE CASCADE,
    FOREIGN KEY (creneau_id) REFERENCES creneau (id) ON DELETE CASCADE,
    FOREIGN KEY (edition_id, stand_id) REFERENCES stand (edition_id, id) ON DELETE CASCADE,
    CONSTRAINT signalement_absence_portee_connue CHECK (portee IN ('JOUR', 'POSTE')),
    CONSTRAINT signalement_absence_motif_connu CHECK (motif IS NULL OR motif IN ('PERSONNEL', 'TRANSPORT', 'AUTRE')),
    CONSTRAINT signalement_absence_statut_connu CHECK (statut IN ('SIGNALE', 'TRAITE', 'CLASSE', 'ANNULE')),
    CONSTRAINT signalement_absence_poste_complet CHECK (
        (portee = 'JOUR' AND creneau_id IS NULL AND stand_id IS NULL)
        OR (portee = 'POSTE' AND creneau_id IS NOT NULL AND stand_id IS NOT NULL)
    )
);

-- Un seul signalement ouvert par personne et par objet — la journée, ou le
-- poste : signaler deux fois la même chose laisse une ligne, tenue par la base.
CREATE UNIQUE INDEX idx_signalement_absence_ouvert
    ON signalement_absence (edition_id, animateur_id, jour, COALESCE(creneau_id, -1), COALESCE(stand_id, ''))
    WHERE statut = 'SIGNALE';

CREATE INDEX idx_signalement_absence_statut ON signalement_absence (edition_id, statut, jour);
