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

-- Existing editions: the default above would declare an already-sliced grid as
-- amplitudes, which offers the destructive « generate the slicing » button on a
-- grid that already is vacations, and reads every relay overlap as a mistake.
-- Only the data can prove VACATIONS — a stagger family or a break-covering
-- créneau is produced by the découpage and by nothing else — so only that
-- proof is used; a hand-written grid of real vacations stays AMPLITUDES and is
-- declared by its organiser, which is what the Créneaux page is for.
UPDATE parametres_decoupage p
SET mode_grille = 'VACATIONS'
WHERE EXISTS (
    SELECT 1 FROM creneau c
    WHERE c.edition_id = p.edition_id
      AND (c.famille > 0 OR c.couverture_pause = TRUE)
);
