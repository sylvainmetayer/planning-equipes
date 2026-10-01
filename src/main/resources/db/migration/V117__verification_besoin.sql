-- Les vérifications du besoin en animateurs : chaque solve d'une équipe
-- fictive lancé depuis l'écran « Besoin en animateurs », avec son résultat,
-- pour que l'organisateur retrouve ses essais dans l'historique — « ça casse
-- à 140, ça passe à 160 ».
--
-- Rien de nominatif : l'équipe est inventée, la ligne ne porte que des
-- effectifs, des durées et des noms de règles. L'historique (journal_action)
-- n'en garde que l'identifiant, comme il le fait d'un stand ; le résultat est
-- joint à la lecture depuis cette table.
--
-- Une ligne naît EN_COURS au lancement et est complétée une seule fois, à la
-- fin du solve (TERMINEE ou ECHEC) : pas de `modifie_le`, personne d'autre ne
-- l'écrit. Une ligne restée EN_COURS après un redémarrage est un solve
-- interrompu, et l'écran la lit comme telle.
--
-- Cloisonnée par édition et en cascade sur elle. Comme `journal_action`, elle
-- n'est ni recopiée par la duplication d'une édition — une vérification porte
-- sur la grille qui l'a vue naître — ni emportée par le dump de la base.
CREATE TABLE IF NOT EXISTS verification_besoin (
    edition_id         TEXT        NOT NULL REFERENCES edition (id) ON DELETE CASCADE,
    id                 BIGSERIAL,
    lancee_le          TIMESTAMPTZ NOT NULL,
    terminee_le        TIMESTAMPTZ,
    -- EN_COURS, TERMINEE ou ECHEC.
    etat               TEXT        NOT NULL,
    majeurs            INTEGER     NOT NULL,
    mineurs            INTEGER     NOT NULL,
    sieges             INTEGER     NOT NULL,
    plafond_secondes   BIGINT      NOT NULL,
    duree_secondes     BIGINT,
    realisable         BOOLEAN,
    sieges_non_pourvus INTEGER,
    score_dur          BIGINT,
    -- Noms des règles dures encore enfreintes, du catalogue, séparés par une virgule.
    regles_en_defaut   TEXT,
    erreur             TEXT,
    PRIMARY KEY (edition_id, id)
);

CREATE INDEX IF NOT EXISTS idx_verification_besoin_recent
    ON verification_besoin (edition_id, lancee_le DESC);
