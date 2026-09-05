-- Declares how the edition's créneaux are to be read: daily AMPLITUDES still
-- to be sliced into vacations by the découpage, or final VACATIONS solved as
-- they are. The two are indistinguishable from the data alone, and the
-- distinction changes every verdict of the grid validation (an overlap is a
-- mistake between amplitudes and the normal shape of staggered vacations), so
-- the mode is declared once, on the Créneaux page, rather than asked on every
-- call. Default AMPLITUDES: what the screen presumed silently until now.

ALTER TABLE parametres_decoupage
    ADD COLUMN IF NOT EXISTS mode_grille VARCHAR(16) NOT NULL DEFAULT 'AMPLITUDES';

ALTER TABLE parametres_decoupage
    ADD CONSTRAINT parametres_decoupage_mode_grille_check
    CHECK (mode_grille IN ('AMPLITUDES', 'VACATIONS'));

COMMENT ON COLUMN parametres_decoupage.mode_grille IS
    'How the edition''s créneaux read: AMPLITUDES to slice, or final VACATIONS';
