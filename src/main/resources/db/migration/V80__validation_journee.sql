-- « Relu et accepté » : l'état de relecture d'une journée, distinct du verrou.
--
-- Le verrouillage est un MÉCANISME pour le solveur (décision 0003) : il fige,
-- il ne dit pas que quelqu'un a relu. Entre le premier planning faisable et la
-- publication il y a une semaine de relecture que rien n'outillait — un
-- organisateur qui relit douze jours n'avait aucun moyen de marquer où il en
-- était, ni de voir ce qui avait bougé depuis. C'est ce que cette table porte,
-- et elle ne porte que cela : poser une validation ne fige rien, l'écran
-- propose le verrou à côté sans jamais l'imposer.
--
-- L'axe principal est la JOURNÉE ; `stand_id` est facultatif et permet de
-- relire stand par stand quand la relecture est déléguée aux responsables de
-- stand. Une ligne sans stand vaut pour la journée entière, et les deux
-- coexistent : la progression affichée compte les journées entières.
--
-- Pas de FK vers `stand` : comme `verrouillage_planning` (V34), la cible est
-- vérifiée par le service, qui rend un message lisible là où la contrainte SQL
-- rendrait une erreur brute.
--
-- `valide_par` est l'identité du compte admin unique tant qu'il n'y a pas de
-- table d'utilisateurs ; la colonne existe pour que la livraison des comptes
-- nommés n'ait pas à migrer les lignes déjà écrites.

CREATE TABLE IF NOT EXISTS validation_journee (
    edition_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES edition(id) ON DELETE CASCADE,
    id VARCHAR(64) NOT NULL,
    jour DATE NOT NULL,
    stand_id VARCHAR(64),
    valide_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    valide_par VARCHAR(120),
    commentaire TEXT,
    PRIMARY KEY (edition_id, id)
);

COMMENT ON TABLE validation_journee IS
    'A day (optionally one stand of it) marked reviewed and accepted; distinct from a lock';

-- Une journée — ou un stand d'une journée — n'est validée qu'une fois :
-- revalider remplace la ligne au lieu d'en empiler une seconde.
CREATE UNIQUE INDEX IF NOT EXISTS uq_validation_journee_cible
    ON validation_journee (edition_id, jour, COALESCE(stand_id, ''));

CREATE INDEX IF NOT EXISTS idx_validation_journee_jour
    ON validation_journee (edition_id, jour);
