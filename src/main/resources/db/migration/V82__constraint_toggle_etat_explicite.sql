-- Une ligne de `constraint_toggle` portait jusqu'ici une seule information :
-- « cette contrainte est désactivée ». L'absence de ligne valait « active »,
-- pour toutes les contraintes, sans exception — il n'existait donc aucun moyen
-- de livrer une règle éteinte par défaut (issue #595).
--
-- La ligne porte désormais l'ÉTAT EXPLICITE de la contrainte, et l'absence de
-- ligne vaut « ce que dit le catalogue », c'est-à-dire actif pour tout ce qui
-- n'est pas listé dans ConstraintCatalog.DESACTIVEES_PAR_DEFAUT. Une règle
-- éteinte par défaut s'allume donc par une ligne `actif = TRUE`, symétrique de
-- la ligne `actif = FALSE` qui éteint les autres.
--
-- Les lignes déjà écrites signifiaient toutes « désactivée » : elles prennent
-- `actif = FALSE`, ce que fait le DEFAULT ci-dessous. Il est retiré ensuite,
-- pour qu'aucune écriture future ne puisse omettre l'état sans le dire.
--
-- Aucun amorçage n'est nécessaire pour `mineurNecessiteEncadrementMajeur` :
-- désormais éteinte au catalogue, elle l'est pour toute édition qui n'a jamais
-- rien demandé — y compris celles qui existent déjà.

ALTER TABLE constraint_toggle
    ADD COLUMN IF NOT EXISTS actif BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE constraint_toggle
    ALTER COLUMN actif DROP DEFAULT;
