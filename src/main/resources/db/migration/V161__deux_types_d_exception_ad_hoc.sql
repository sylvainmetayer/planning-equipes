-- Une exception ad hoc n'a plus que trois types : l'incompatibilité, l'affinité
-- et l'arrivée groupée (ADR 0117). L'affectation forcée et l'indisponibilité
-- forcée disparaissent : un verrou animateur + créneau et un jour indisponible
-- de la fiche disent la même chose, avec un geste que l'organisateur connaît déjà.
--
-- Chaque exception retirée est d'abord recopiée dans `contrainte_ad_hoc_avant_4_0`,
-- une archive que l'application ne lit ni n'exporte et que la 5.0 supprimera
-- (retour à la 3.x possible d'ici là). `convertie` dit si elle a donné quelque
-- chose ; ce qui ne se convertit pas reste là, et chaque cas est signalé par un
-- avertissement du journal de migration (RAISE WARNING). Rien n'est perdu sans bruit.
--
--   INDISPONIBILITE_FORCEE sans stand : un jour indisponible de la fiche, pour
--       chaque animateur nommé. Sur un créneau, c'est le JOUR ENTIER (la fiche ne
--       connaît pas le créneau) : la perte est signalée. Sans créneau, tous les
--       jours qui portent un créneau. Avec un stand (« pas sur ce stand ») : rien
--       à convertir, la fiche n'a pas de jour pour cela.
--   AFFECTATION_FORCEE sur un créneau : un verrou animateur + créneau pour chaque
--       animateur nommé qui tient une place dans ce périmètre sur le plan
--       enregistré. Le plan lui-même n'est pas touché : il reste identique. Sans
--       créneau, ou si personne ne tient la place : rien à verrouiller, signalé.
--
-- Les réglages des deux règles (interrupteur, poids) n'ont plus de règle à
-- régler : ils sont supprimés. L'historique des pondérations, lui, est une
-- archive de ce qui a été fait sous le nom d'alors et n'est pas réécrit.

CREATE TABLE contrainte_ad_hoc_avant_4_0 (
    edition_id VARCHAR(64) NOT NULL REFERENCES edition(id) ON DELETE CASCADE,
    id VARCHAR(64) NOT NULL,
    type VARCHAR(32) NOT NULL,
    animateur_ids TEXT NOT NULL,
    creneau_date DATE,
    creneau_heure_debut TIME,
    creneau_heure_fin TIME,
    stand_id VARCHAR(64),
    raison TEXT,
    cree_par VARCHAR(128),
    cree_le TIMESTAMP WITH TIME ZONE,
    convertie BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (edition_id, id)
);

INSERT INTO contrainte_ad_hoc_avant_4_0
    (edition_id, id, type, animateur_ids, creneau_date, creneau_heure_debut, creneau_heure_fin,
     stand_id, raison, cree_par, cree_le)
SELECT a.edition_id, a.id, a.type,
       COALESCE((SELECT string_agg(ca.animateur_id, ',' ORDER BY ca.position)
                   FROM contrainte_animateur ca
                  WHERE ca.edition_id = a.edition_id AND ca.contrainte_id = a.id), ''),
       a.creneau_date, a.creneau_heure_debut, a.creneau_heure_fin,
       a.stand_id, a.raison, a.cree_par, a.cree_le
  FROM contrainte_ad_hoc a
 WHERE a.type IN ('INDISPONIBILITE_FORCEE', 'AFFECTATION_FORCEE');

-- 1. Indisponibilités forcées -> jours indisponibles de la fiche.
INSERT INTO animateur_jour_indispo (edition_id, animateur_id, jour)
SELECT DISTINCT a.edition_id, ca.animateur_id, j.jour
  FROM contrainte_ad_hoc a
  JOIN contrainte_animateur ca ON ca.edition_id = a.edition_id AND ca.contrainte_id = a.id
  JOIN LATERAL (
        SELECT a.creneau_date AS jour WHERE a.creneau_date IS NOT NULL
        UNION
        SELECT DISTINCT c.date_creneau FROM creneau c
         WHERE a.creneau_date IS NULL AND c.edition_id = a.edition_id
       ) j ON TRUE
 WHERE a.type = 'INDISPONIBILITE_FORCEE' AND a.stand_id IS NULL
ON CONFLICT DO NOTHING;

UPDATE contrainte_ad_hoc_avant_4_0
   SET convertie = TRUE
 WHERE type = 'INDISPONIBILITE_FORCEE' AND stand_id IS NULL AND animateur_ids <> '';

-- 2. Affectations forcées -> verrous animateur + créneau sur la place tenue.
INSERT INTO verrouillage_planning (edition_id, id, type, animateur_id, creneau_id,
                                   creneau_date, creneau_heure_debut, creneau_heure_fin, raison, cree_le)
SELECT DISTINCT ON (a.edition_id, p.animateur_id, c.id)
       a.edition_id, gen_random_uuid()::text, 'ANIMATEUR_CRENEAU', p.animateur_id, c.id,
       c.date_creneau, c.heure_debut, c.heure_fin, a.raison, COALESCE(a.cree_le, now())
  FROM contrainte_ad_hoc a
  JOIN contrainte_animateur ca ON ca.edition_id = a.edition_id AND ca.contrainte_id = a.id
  JOIN creneau c ON c.edition_id = a.edition_id
                AND c.date_creneau = a.creneau_date
                AND c.heure_debut = a.creneau_heure_debut
                AND c.heure_fin = a.creneau_heure_fin
  JOIN poste_affectation p ON p.edition_id = a.edition_id AND p.creneau_id = c.id
                          AND p.animateur_id = ca.animateur_id
                          AND (a.stand_id IS NULL OR p.stand_id = a.stand_id)
 WHERE a.type = 'AFFECTATION_FORCEE'
ON CONFLICT DO NOTHING;

UPDATE contrainte_ad_hoc_avant_4_0 r
   SET convertie = TRUE
 WHERE r.type = 'AFFECTATION_FORCEE'
   AND EXISTS (
        SELECT 1
          FROM contrainte_animateur ca
          JOIN creneau c ON c.edition_id = r.edition_id
                        AND c.date_creneau = r.creneau_date
                        AND c.heure_debut = r.creneau_heure_debut
                        AND c.heure_fin = r.creneau_heure_fin
          JOIN poste_affectation p ON p.edition_id = r.edition_id AND p.creneau_id = c.id
                                  AND p.animateur_id = ca.animateur_id
                                  AND (r.stand_id IS NULL OR p.stand_id = r.stand_id)
         WHERE ca.edition_id = r.edition_id AND ca.contrainte_id = r.id);

-- 3. Ce que la conversion perd ou ne fait pas, dit dans le journal de migration.
DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN SELECT edition_id, id, type, stand_id, creneau_date, convertie
               FROM contrainte_ad_hoc_avant_4_0 ORDER BY edition_id, id LOOP
        IF NOT r.convertie THEN
            RAISE WARNING 'Exception % (%, edition %) : type retire en 4.0, rien a convertir (aucune place tenue, aucun jour), gardee dans contrainte_ad_hoc_avant_4_0',
                r.id, r.type, r.edition_id;
        ELSIF r.type = 'INDISPONIBILITE_FORCEE' AND r.creneau_date IS NOT NULL THEN
            RAISE WARNING 'Exception % (edition %) : indisponibilite sur un creneau, convertie en JOUR ENTIER (%) sur la fiche',
                r.id, r.edition_id, r.creneau_date;
        END IF;
    END LOOP;
END $$;

-- 4. Les exceptions retirées quittent la table vivante.
DELETE FROM contrainte_animateur ca
 USING contrainte_ad_hoc a
 WHERE ca.edition_id = a.edition_id AND ca.contrainte_id = a.id
   AND a.type IN ('INDISPONIBILITE_FORCEE', 'AFFECTATION_FORCEE');

DELETE FROM contrainte_ad_hoc WHERE type IN ('INDISPONIBILITE_FORCEE', 'AFFECTATION_FORCEE');

DELETE FROM constraint_toggle WHERE nom IN ('indisponibiliteForcee', 'affectationForcee');
DELETE FROM ponderation_contrainte WHERE nom IN ('indisponibiliteForcee', 'affectationForcee');
