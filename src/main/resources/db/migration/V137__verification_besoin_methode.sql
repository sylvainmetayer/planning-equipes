-- La vérification du besoin répond désormais par défaut par la borne exacte
-- (flot maximal), sans résolution ; le calcul par équipe fictive devient une
-- option. Chaque ligne dit laquelle des deux l'a produite, et la borne qu'elle
-- a comparée à l'effectif.
--
-- Les lignes existantes sont toutes des calculs : le défaut le dit.
ALTER TABLE verification_besoin
    ADD COLUMN IF NOT EXISTS methode TEXT NOT NULL DEFAULT 'CALCUL';

-- Le minimum exact au moment de la vérification ; absent pour un calcul antérieur.
ALTER TABLE verification_besoin
    ADD COLUMN IF NOT EXISTS minimum INTEGER;
