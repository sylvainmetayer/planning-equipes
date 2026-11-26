-- La phase de l'édition (ADR 0115) remplace le gel du référentiel par famille
-- (ADR 0052). Quatre phases : PREPARATION (tout est modifiable), CALCUL (le
-- référentiel est figé : stands, créneaux, typologies et emplacements,
-- compétences), PUBLIEE (la structure est figée — stands, créneaux,
-- typologies et emplacements — les fiches des animateurs restent ouvertes),
-- TERMINEE (comme CALCUL). En PUBLIEE, `structure_debloquee` rouvre la
-- structure pour un changement de dernière minute ; la publication suivante
-- la refige.
--
-- La phase se déduit de l'état 3.x, et la correspondance perd la finesse par
-- famille (décision de l'organisation, guide « Passer à la 4.0 ») :
--   * une édition qui a déjà publié passe en PUBLIEE ; sa structure reste
--     débloquée si aucune famille de structure n'était figée, pour que rien
--     ne se ferme à la mise à jour ;
--   * une édition qui n'a pas publié mais dont une famille au moins était
--     figée passe en CALCUL : les quatre familles sont alors figées ;
--   * sinon, PREPARATION.
ALTER TABLE edition
    ADD COLUMN phase VARCHAR(16) NOT NULL DEFAULT 'PREPARATION',
    ADD COLUMN phase_depuis TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    ADD COLUMN structure_debloquee BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT edition_phase_connue CHECK (phase IN ('PREPARATION', 'CALCUL', 'PUBLIEE', 'TERMINEE')),
    ADD CONSTRAINT edition_deblocage_en_publiee CHECK (NOT structure_debloquee OR phase = 'PUBLIEE');

UPDATE edition e
SET phase = 'PUBLIEE',
    phase_depuis = (SELECT max(v.publie_le) FROM version_plan v WHERE v.edition_id = e.id),
    structure_debloquee = NOT EXISTS (
        SELECT 1 FROM gel_referentiel g
        WHERE g.edition_id = e.id AND g.famille IN ('STANDS', 'CRENEAUX', 'TYPOLOGIES_EMPLACEMENTS'))
WHERE EXISTS (SELECT 1 FROM version_plan v WHERE v.edition_id = e.id AND v.publie_le IS NOT NULL);

UPDATE edition e
SET phase = 'CALCUL',
    phase_depuis = (SELECT min(g.fige_le) FROM gel_referentiel g WHERE g.edition_id = e.id)
WHERE e.phase = 'PREPARATION'
  AND EXISTS (SELECT 1 FROM gel_referentiel g WHERE g.edition_id = e.id);

-- `gel_referentiel` n'est plus lue ni écrite : elle reste en place, sous son
-- nom, pour un retour à la 3.x ou une relecture par l'exploitant, hors de
-- l'export SQL, et la 5.0 la supprimera.
COMMENT ON TABLE gel_referentiel IS
    'The 3.x per-family freeze, replaced by edition.phase (ADR 0115): read by nothing, dropped in 5.0';
