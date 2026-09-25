-- Gel du référentiel : une famille de fiches déclarée « prête » par
-- l'organisateur, que plus aucun chemin d'écriture ne modifie tant que le gel
-- n'est pas levé.
--
-- Distinct du verrouillage du planning (décision 0003) : un verrou fige des
-- SIÈGES d'un plan calculé pour la prochaine résolution, un gel fige les
-- FICHES dont ce plan est calculé — stands, créneaux, typologies et
-- emplacements, compétences des animateurs. Une ligne par famille figée ;
-- l'absence de ligne vaut « modifiable ». Lever le gel supprime la ligne.
--
-- Cloisonnée par édition comme toute table métier (décision 0001) : le gel
-- d'une édition ne dit rien des autres, et une duplication ne le recopie pas
-- — la nouvelle édition repart en préparation.

CREATE TABLE IF NOT EXISTS gel_referentiel (
    edition_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES edition(id) ON DELETE CASCADE,
    famille VARCHAR(40) NOT NULL,
    fige_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (edition_id, famille),
    CONSTRAINT ck_gel_referentiel_famille
        CHECK (famille IN ('STANDS', 'CRENEAUX', 'TYPOLOGIES_EMPLACEMENTS', 'COMPETENCES'))
);

COMMENT ON TABLE gel_referentiel IS
    'A referential family the organiser declared ready: every write of it is refused until lifted';
