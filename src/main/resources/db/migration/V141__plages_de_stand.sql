-- Les horaires d'un stand deviennent une liste de plages datées (ADR 0104).
--
-- Une plage est une ouverture ou une fermeture datée : c'est exactement ce que
-- portent déjà stand_ouverture et stand_indisponibilite, qui restent donc le
-- stockage (le modèle applicatif les lit en une seule liste, `Stand.plages`).
-- Ce qui disparaît, ce sont les règles récurrentes (stand_horaire et ses
-- fenêtres), converties ici en plages sur les jours de l'événement — du premier
-- au dernier créneau de l'édition, plus le lendemain d'un créneau qui passe
-- minuit — avec la lecture exacte que l'application en avait à chaque lecture
-- (HorairesRecurrents) :
--   * le jour d'un stand qui porte déjà une plage datée ne change pas : une
--     exception primait sur les règles ;
--   * sinon les règles qui couvrent le jour et ont la portée la plus précise
--     (TOUS < JOURS_SEMAINE < PLAGE < DATES) gagnent, leurs fenêtres se
--     réunissent ; deux règles de même portée de modes opposés donnent
--     l'OUVERTURE ;
--   * sinon, un stand dont les règles déclarent des ouvertures ne s'ouvre pas ce
--     jour-là : fermeture de minuit à la fermeture ;
--   * sinon rien : ouvert toute la journée.
-- Un jour sans créneau n'a pas de plage : c'était le seul cas où la règle disait
-- quelque chose d'une date que l'application ne lisait jamais.
--
-- Une édition SANS créneau n'a aucun jour où poser ses règles. Elles ne sont ni
-- converties ni perdues : les deux tables de règles sont renommées, pas
-- supprimées (leur suppression est pour la 5.0), et un avertissement nomme
-- chaque édition concernée. Le guide de passage à la 4.0 demande en outre un
-- export YAML de chaque édition avant la montée : l'export 3.x relu en 4.0
-- reconvertit ses règles dès que l'édition a des créneaux.

CREATE TEMP TABLE jour_evenement ON COMMIT DROP AS
SELECT DISTINCT c.edition_id, d.jour
FROM creneau c
CROSS JOIN LATERAL (
    SELECT c.date_creneau AS jour
    UNION ALL
    SELECT c.date_creneau + 1 WHERE c.heure_fin <= c.heure_debut
) d;
ANALYZE jour_evenement;

-- Une ligne par fenêtre de règle ; une règle sans fenêtre ne dit rien (ni couvre,
-- ni ne déclare d'ouverture), comme dans le résolveur de la 3.x.
CREATE TEMP TABLE regle_fenetre ON COMMIT DROP AS
SELECT h.edition_id, h.stand_id, h.id AS horaire_id, h.mode,
       CASE h.type_jours WHEN 'TOUS' THEN 0 WHEN 'JOURS_SEMAINE' THEN 1 WHEN 'PLAGE' THEN 2 ELSE 3 END AS specificite,
       h.type_jours, h.jours_semaine, h.date_debut, h.date_fin, h.dates, h.motif,
       f.heure_debut, f.heure_fin, f.effectif
FROM stand_horaire h
JOIN stand_horaire_fenetre f ON f.edition_id = h.edition_id AND f.horaire_id = h.id;
ANALYZE regle_fenetre;

DO $$
DECLARE
    edition RECORD;
BEGIN
    FOR edition IN
        SELECT DISTINCT r.edition_id
        FROM regle_fenetre r
        WHERE NOT EXISTS (SELECT 1 FROM jour_evenement j WHERE j.edition_id = r.edition_id)
        ORDER BY 1
    LOOP
        RAISE WARNING 'Édition % : ses horaires récurrents de stand ne sont pas convertis en plages, elle n''a aucun créneau. Ils sont conservés dans stand_horaire_avant_4_0 et stand_horaire_fenetre_avant_4_0.', edition.edition_id;
    END LOOP;
END $$;

-- Les jours à résoudre : ceux de l'événement où le stand n'a aucune plage datée.
CREATE TEMP TABLE stand_jour ON COMMIT DROP AS
SELECT s.edition_id, s.stand_id, j.jour
FROM (SELECT DISTINCT edition_id, stand_id FROM regle_fenetre) s
JOIN jour_evenement j ON j.edition_id = s.edition_id
WHERE NOT EXISTS (SELECT 1 FROM stand_ouverture o
                  WHERE o.edition_id = s.edition_id AND o.stand_id = s.stand_id AND o.date_ouverture = j.jour)
  AND NOT EXISTS (SELECT 1 FROM stand_indisponibilite i
                  WHERE i.edition_id = s.edition_id AND i.stand_id = s.stand_id AND i.date_indisponibilite = j.jour);
ANALYZE stand_jour;

CREATE TEMP TABLE couvrante ON COMMIT DROP AS
SELECT sj.jour, r.*
FROM stand_jour sj
JOIN regle_fenetre r ON r.edition_id = sj.edition_id AND r.stand_id = sj.stand_id
WHERE CASE r.type_jours
          WHEN 'TOUS' THEN TRUE
          WHEN 'JOURS_SEMAINE' THEN
              (ARRAY['MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY'])
                  [EXTRACT(ISODOW FROM sj.jour)::int]
              = ANY (string_to_array(regexp_replace(r.jours_semaine, '\s', '', 'g'), ','))
          WHEN 'PLAGE' THEN (r.date_debut IS NULL OR sj.jour >= r.date_debut)
                        AND (r.date_fin IS NULL OR sj.jour <= r.date_fin)
          ELSE sj.jour = ANY (r.dates)
      END;
ANALYZE couvrante;

-- La portée la plus précise de chaque jour (gagnante), puis son mode : OUVERTURE dès qu'une règle
-- gagnante l'est (retenue). Chaque étape est une table : les temporaires n'ont pas de statistiques, on
-- les analyse, sinon le plan d'une grosse édition devient quadratique.
CREATE TEMP TABLE gagnante ON COMMIT DROP AS
SELECT c.*
FROM couvrante c
JOIN (SELECT edition_id, stand_id, jour, max(specificite) AS specificite
      FROM couvrante GROUP BY edition_id, stand_id, jour) m
  USING (edition_id, stand_id, jour, specificite);
ANALYZE gagnante;

CREATE TEMP TABLE retenue ON COMMIT DROP AS
SELECT g.*
FROM gagnante g
JOIN (SELECT edition_id, stand_id, jour,
             CASE WHEN bool_or(mode = 'OUVERTURE') THEN 'OUVERTURE' ELSE 'FERMETURE' END AS mode
      FROM gagnante GROUP BY edition_id, stand_id, jour) m
  USING (edition_id, stand_id, jour, mode);
ANALYZE retenue;

-- Le motif est le premier que porte une règle retenue, dans l'ordre où le stand les liste.
CREATE TEMP TABLE motif_jour ON COMMIT DROP AS
SELECT edition_id, stand_id, jour,
       (array_agg(motif ORDER BY horaire_id) FILTER (WHERE motif IS NOT NULL))[1] AS motif
FROM (SELECT DISTINCT edition_id, stand_id, jour, horaire_id, motif FROM retenue) r
GROUP BY edition_id, stand_id, jour;
ANALYZE motif_jour;

CREATE TEMP TABLE jour_resolu ON COMMIT DROP AS
SELECT DISTINCT r.edition_id, r.stand_id, r.jour, r.mode, r.heure_debut, r.heure_fin, r.effectif, m.motif
FROM retenue r
JOIN motif_jour m USING (edition_id, stand_id, jour);
ANALYZE jour_resolu;

INSERT INTO stand_ouverture (edition_id, stand_id, date_ouverture, heure_debut, heure_fin, motif, effectif)
SELECT DISTINCT edition_id, stand_id, jour, heure_debut, heure_fin, motif, effectif
FROM jour_resolu
WHERE mode = 'OUVERTURE';

INSERT INTO stand_indisponibilite (edition_id, stand_id, date_indisponibilite, heure_debut, heure_fin, motif)
SELECT DISTINCT edition_id, stand_id, jour, heure_debut, heure_fin, motif
FROM jour_resolu
WHERE mode = 'FERMETURE';

-- Un jour qu'aucune règle ne nomme, pour un stand dont les règles déclarent des ouvertures : fermé.
INSERT INTO stand_indisponibilite (edition_id, stand_id, date_indisponibilite, heure_debut, heure_fin, motif)
SELECT sj.edition_id, sj.stand_id, sj.jour, TIME '00:00', NULL, 'Jour non déclaré par le stand'
FROM stand_jour sj
WHERE EXISTS (SELECT 1 FROM regle_fenetre r
              WHERE r.edition_id = sj.edition_id AND r.stand_id = sj.stand_id AND r.mode = 'OUVERTURE')
  AND NOT EXISTS (SELECT 1 FROM jour_resolu d
                  WHERE d.edition_id = sj.edition_id AND d.stand_id = sj.stand_id AND d.jour = sj.jour);

-- Archive, jusqu'à la 5.0 : l'application ne les lit plus.
ALTER TABLE stand_horaire_fenetre RENAME TO stand_horaire_fenetre_avant_4_0;
ALTER TABLE stand_horaire RENAME TO stand_horaire_avant_4_0;
