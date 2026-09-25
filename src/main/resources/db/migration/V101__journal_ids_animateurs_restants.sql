-- What V100 left behind in the journal (ADR 0050).
--
-- V100 rewrote the journal's entite_id for the five renumbered entities and
-- its acteur_id when the actor is an animateur. It missed one case: on the
-- espace routes, a line whose call returned nothing names the animateur the
-- token belongs to as its object — under DISPONIBILITE, ECHANGE, PLANNING… —
-- and a few admin routes aimed at one animateur (sending their planning,
-- their PDF or ICS, a Jour J absence) do the same. On a database whose
-- animateurs had ids typed by hand, those lines kept the old one
-- (`marie.dupont`): the name V100 exists to remove, shown as-is by the
-- Historique screen, and pointing at no fiche.
--
-- V100 dropped its old → new mapping, so an old id cannot be looked up here.
-- What is left is exact where it can be:
--
-- 1. A line whose actor proved to be an animateur: on these routes the object
--    the journal fell back on is the actor itself, and V100 already rewrote
--    the actor. The object becomes the actor.
-- 2. Anything else — an anonymous espace call, an admin action aimed at one
--    animateur — has nothing to rebuild the link from: the value becomes an
--    orphan marker, as V100 does for a row that no longer exists.
--
-- Only a value naming no animateur of its edition, and shaped neither like a
-- UUID (a swap request, a declaration) nor like a number (a timeslot, a
-- snapshot), is touched: every other id the journal holds is left alone. On
-- a database whose animateurs already had the A<n> shape, this finds nothing.

UPDATE journal_action j
SET entite_id = j.acteur_id
WHERE j.acteur = 'ANIMATEUR'
  AND j.acteur_id IS NOT NULL
  AND j.entite NOT IN ('ANIMATEUR', 'STAND', 'TYPOLOGIE', 'EMPLACEMENT', 'AJUSTEMENT', 'EDITION')
  AND j.entite_id IS NOT NULL
  AND j.entite_id <> j.acteur_id
  AND left(j.entite_id, 1) <> '~'
  AND j.entite_id !~ '^[0-9]+$'
  AND j.entite_id !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
  AND NOT EXISTS (SELECT 1 FROM animateur a WHERE a.edition_id = j.edition_id AND a.id = j.entite_id);

UPDATE journal_action j
SET entite_id = '~' || left(md5(j.entite_id), 12)
WHERE (j.acteur = 'ANONYME'
       OR j.action IN ('PLANNING_ENVOYE', 'EXPORT_PDF_ANIMATEUR', 'EXPORT_ICS_ANIMATEUR',
                       'ABSENCE_ENREGISTREE', 'ABSENCE_ANNULEE'))
  AND j.entite NOT IN ('ANIMATEUR', 'STAND', 'TYPOLOGIE', 'EMPLACEMENT', 'AJUSTEMENT', 'EDITION')
  AND j.entite_id IS NOT NULL
  AND left(j.entite_id, 1) <> '~'
  AND j.entite_id !~ '^[0-9]+$'
  AND j.entite_id !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
  AND NOT EXISTS (SELECT 1 FROM animateur a WHERE a.edition_id = j.edition_id AND a.id = j.entite_id);
