-- La consigne d'édition s'écrit en plages datées sur les stands (ADR 0125).
--
-- Jusqu'ici, une consigne était une quatrième couche que la résolution posait à
-- chaque lecture au-dessus des plages (ConsigneResolver, ADR 0043). Elle devient
-- une écriture : des plages sur chaque stand de sa date, marquées `consigne`,
-- que lever la consigne retire exactement.
--
-- Pour chaque consigne déjà posée et chaque stand de son édition, cette
-- migration écrit ce que l'application écrit en posant une consigne
-- (ConsignePlages), avec la même lecture que la couche faisait :
--   1. la bande ferme le stand : une fermeture sur la bande ;
--   2. les créneaux que la consigne a ajoutés n'appartiennent aux horaires de
--      personne : une fermeture sur chacun ;
--   3. une ouverture de consigne est une extension : une ouverture par fenêtre,
--      hors de la bande, à l'effectif saisi, sinon au plus fort effectif que la
--      bande retire au stand (vide quand c'est son minimum, qui revient au même).
-- L'application lit ces plages au-dessus de celles du stand : leurs fermetures
-- retirent leur étendue des heures propres du stand, jamais des ouvertures de la
-- consigne, qui s'y ajoutent. Le
-- résultat jour par jour est celui que la couche calculait.

ALTER TABLE stand_ouverture ADD COLUMN consigne BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE stand_indisponibilite ADD COLUMN consigne BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE stand_ouverture ADD CONSTRAINT stand_ouverture_une_origine CHECK (NOT (depliee AND consigne));
ALTER TABLE stand_indisponibilite ADD CONSTRAINT stand_indisponibilite_une_origine CHECK (NOT (depliee AND consigne));

-- Chaque stand de chaque date sous consigne, lu sur ses propres plages avant toute écriture.
CREATE TEMP TABLE consigne_stand ON COMMIT DROP AS
SELECT c.edition_id, c.date_jour, s.id AS stand_id, s.effectif_min, c.fermeture_debut, c.fermeture_fin,
       (SELECT max(COALESCE(o.effectif, s.effectif_min)) FROM stand_ouverture o
        WHERE o.edition_id = c.edition_id AND o.stand_id = s.id AND o.date_ouverture = c.date_jour
          AND o.heure_debut < COALESCE(c.fermeture_fin, TIME '24:00')
          AND c.fermeture_debut < COALESCE(o.heure_fin, TIME '24:00'))
           AS herite
FROM consigne_edition c
JOIN stand s ON s.edition_id = c.edition_id;

-- 1. La bande.
INSERT INTO stand_indisponibilite (edition_id, stand_id, date_indisponibilite, heure_debut, heure_fin, motif, consigne)
SELECT edition_id, stand_id, date_jour, fermeture_debut, fermeture_fin, 'Consigne d''édition', TRUE
FROM consigne_stand;

-- 2. Les créneaux que la consigne a ajoutés, sur sa date (jusqu'à minuit pour un créneau qui le passe).
INSERT INTO stand_indisponibilite (edition_id, stand_id, date_indisponibilite, heure_debut, heure_fin, motif, consigne)
SELECT cs.edition_id, cs.stand_id, cs.date_jour, k.heure_debut,
       CASE WHEN k.heure_fin <= k.heure_debut THEN NULL ELSE k.heure_fin END, 'Consigne d''édition', TRUE
FROM consigne_stand cs
JOIN consigne_edition_creneau ce ON ce.edition_id = cs.edition_id AND ce.date_jour = cs.date_jour
JOIN creneau k ON k.edition_id = ce.edition_id AND k.id = ce.creneau_id AND k.date_creneau = cs.date_jour
ORDER BY cs.edition_id, cs.stand_id, cs.date_jour, k.heure_debut;

-- 3. Les fenêtres, hors de la bande : ce qui en reste avant elle, puis après elle.
CREATE TEMP TABLE fenetre_consigne ON COMMIT DROP AS
SELECT o.edition_id, o.date_jour, o.stand_id, o.position, o.heure_debut,
       COALESCE(o.heure_fin, TIME '24:00') AS heure_fin, cs.fermeture_debut,
       COALESCE(cs.fermeture_fin, TIME '24:00') AS fermeture_fin,
       COALESCE(o.effectif, NULLIF(cs.herite, cs.effectif_min)) AS effectif
FROM consigne_edition_ouverture o
JOIN consigne_stand cs ON cs.edition_id = o.edition_id AND cs.date_jour = o.date_jour AND cs.stand_id = o.stand_id;

INSERT INTO stand_ouverture (edition_id, stand_id, date_ouverture, heure_debut, heure_fin, motif, effectif, consigne)
SELECT edition_id, stand_id, date_jour, debut, NULLIF(fin, TIME '24:00'), 'Consigne d''édition', effectif, TRUE
FROM (SELECT edition_id, date_jour, stand_id, position, 0 AS morceau, effectif,
             heure_debut AS debut, LEAST(heure_fin, fermeture_debut) AS fin
      FROM fenetre_consigne WHERE heure_debut < fermeture_debut
      UNION ALL
      SELECT edition_id, date_jour, stand_id, position, 1, effectif,
             GREATEST(heure_debut, fermeture_fin), heure_fin
      FROM fenetre_consigne WHERE heure_fin > fermeture_fin) morceaux
ORDER BY edition_id, date_jour, stand_id, position, morceau;
