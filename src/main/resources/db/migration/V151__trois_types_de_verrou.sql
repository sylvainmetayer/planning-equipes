-- Un verrou n'a plus que trois cibles : un animateur sur un créneau, un stand
-- sur un jour, un jour (ADR 0109). ANIMATEUR, STAND et CRENEAU disparaissent.
--
-- Chacun est converti en ce qu'il figeait réellement sur le plan enregistré :
-- les places que le plan donne. Le plan enregistré ne change donc pas, et la
-- résolution suivante repart des mêmes places figées.
--   ANIMATEUR -> un verrou animateur+créneau par créneau où il tient une place ;
--   STAND     -> un verrou stand+jour par jour où le stand a des places ;
--   CRENEAU   -> un verrou animateur+créneau par animateur qui y tient une place.
-- Ce qui ne passe pas : l'interdiction de donner à l'animateur une place sur un
-- créneau où il n'en tenait pas (ANIMATEUR), et le gel des places vides, que
-- l'ancien verrou ne figeait pas non plus. Un verrou dont le plan n'a aucune
-- place est retiré : il ne figeait rien.
-- ON CONFLICT DO NOTHING : une cible déjà verrouillée n'est pas posée deux fois.

ALTER TABLE verrouillage_planning DROP CONSTRAINT verrouillage_planning_cible_coherente;

INSERT INTO verrouillage_planning (edition_id, id, type, animateur_id, creneau_id,
                                   creneau_date, creneau_heure_debut, creneau_heure_fin, raison, cree_le)
SELECT DISTINCT ON (v.edition_id, v.id, c.id)
       v.edition_id, gen_random_uuid()::text, 'ANIMATEUR_CRENEAU', v.animateur_id, c.id,
       c.date_creneau, c.heure_debut, c.heure_fin, v.raison, v.cree_le
  FROM verrouillage_planning v
  JOIN poste_affectation p ON p.edition_id = v.edition_id AND p.animateur_id = v.animateur_id
  JOIN creneau c ON c.edition_id = p.edition_id AND c.id = p.creneau_id
 WHERE v.type = 'ANIMATEUR'
ON CONFLICT DO NOTHING;

INSERT INTO verrouillage_planning (edition_id, id, type, stand_id, jour, raison, cree_le)
SELECT DISTINCT ON (v.edition_id, v.id, c.date_creneau)
       v.edition_id, gen_random_uuid()::text, 'STAND_JOUR', v.stand_id, c.date_creneau, v.raison, v.cree_le
  FROM verrouillage_planning v
  JOIN poste_affectation p ON p.edition_id = v.edition_id AND p.stand_id = v.stand_id
  JOIN creneau c ON c.edition_id = p.edition_id AND c.id = p.creneau_id
 WHERE v.type = 'STAND'
ON CONFLICT DO NOTHING;

INSERT INTO verrouillage_planning (edition_id, id, type, animateur_id, creneau_id,
                                   creneau_date, creneau_heure_debut, creneau_heure_fin, raison, cree_le)
SELECT DISTINCT ON (v.edition_id, v.id, p.animateur_id)
       v.edition_id, gen_random_uuid()::text, 'ANIMATEUR_CRENEAU', p.animateur_id, c.id,
       c.date_creneau, c.heure_debut, c.heure_fin, v.raison, v.cree_le
  FROM verrouillage_planning v
  JOIN creneau c ON c.edition_id = v.edition_id
                AND c.date_creneau = v.creneau_date
                AND c.heure_debut = v.creneau_heure_debut
                AND c.heure_fin = v.creneau_heure_fin
  JOIN poste_affectation p ON p.edition_id = c.edition_id AND p.creneau_id = c.id
 WHERE v.type = 'CRENEAU' AND p.animateur_id IS NOT NULL
ON CONFLICT DO NOTHING;

DELETE FROM verrouillage_planning WHERE type IN ('ANIMATEUR', 'STAND', 'CRENEAU');

ALTER TABLE verrouillage_planning ADD CONSTRAINT verrouillage_planning_cible_coherente CHECK (
    (type = 'ANIMATEUR_CRENEAU' AND animateur_id IS NOT NULL AND creneau_date IS NOT NULL
        AND creneau_heure_debut IS NOT NULL AND creneau_heure_fin IS NOT NULL
        AND stand_id IS NULL AND jour IS NULL)
    OR (type = 'STAND_JOUR' AND stand_id IS NOT NULL AND jour IS NOT NULL AND animateur_id IS NULL
        AND creneau_date IS NULL)
    OR (type = 'JOUR' AND jour IS NOT NULL AND animateur_id IS NULL
        AND stand_id IS NULL AND creneau_date IS NULL)
);
