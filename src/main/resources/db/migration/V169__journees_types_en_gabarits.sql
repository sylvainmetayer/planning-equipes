-- Les journées types deviennent les gabarits du générateur de créneaux unique
-- (ADR 0126). Même forme — un nom, des vacations, des dates affectées — donc
-- les tables sont renommées, et leurs lignes, leurs identifiants et leurs dates
-- restent tels quels : une édition retrouve ses gabarits, son calendrier et
-- ses dates en écart à l'identique.
--
-- Ce qui ne se convertit pas, c'est un gabarit que le générateur refuserait
-- d'appliquer : sans aucune vacation, avec une vacation de durée nulle, ou
-- deux fois la même vacation. Le service les refusait déjà à l'écriture, mais
-- rien ne les interdisait en base. Ils partent, avec leurs dates, dans
-- `journee_type_avant_4_0`, lisibles tels quels, jusqu'en 5.0 ; leurs dates ne
-- sont plus gouvernées, et leurs créneaux restent.

ALTER TABLE journee_type RENAME TO gabarit;
ALTER TABLE journee_type_vacation RENAME TO gabarit_vacation;
ALTER TABLE journee_type_date RENAME TO gabarit_date;
ALTER TABLE gabarit_vacation RENAME COLUMN journee_type_id TO gabarit_id;
ALTER TABLE gabarit_date RENAME COLUMN journee_type_id TO gabarit_id;
ALTER INDEX uq_journee_type_nom RENAME TO uq_gabarit_nom;
ALTER INDEX idx_journee_type_vacation_journee RENAME TO idx_gabarit_vacation_gabarit;
ALTER INDEX idx_journee_type_date_journee_type RENAME TO idx_gabarit_date_gabarit;

COMMENT ON TABLE gabarit IS
    'A template of the timeslot generator: the vacations of one kind of day, applied to dates (ADR 0126)';
COMMENT ON TABLE gabarit_date IS
    'Which template governs which date; a date not listed is governed by nothing';

CREATE TABLE journee_type_avant_4_0 (
    edition_id VARCHAR(64) NOT NULL REFERENCES edition(id) ON DELETE CASCADE,
    id BIGINT NOT NULL,
    nom VARCHAR(80) NOT NULL,
    vacations TEXT NOT NULL,
    dates TEXT NOT NULL,
    motif TEXT NOT NULL,
    PRIMARY KEY (edition_id, id)
);

COMMENT ON TABLE journee_type_avant_4_0 IS
    'Day templates of 3.x the generator cannot apply (V169), kept with their dates until 5.0';

INSERT INTO journee_type_avant_4_0 (edition_id, id, nom, vacations, dates, motif)
SELECT g.edition_id,
       g.id,
       g.nom,
       coalesce((SELECT string_agg(to_char(v.heure_debut, 'HH24:MI') || '-' || to_char(v.heure_fin, 'HH24:MI')
                                       || CASE WHEN v.couverture_pause THEN ' R' ELSE '' END,
                                   ', ' ORDER BY v.position, v.id)
                 FROM gabarit_vacation v
                 WHERE v.edition_id = g.edition_id AND v.gabarit_id = g.id), ''),
       coalesce((SELECT string_agg(d.date_jour::text, ', ' ORDER BY d.date_jour)
                 FROM gabarit_date d
                 WHERE d.edition_id = g.edition_id AND d.gabarit_id = g.id), ''),
       CASE
           WHEN NOT EXISTS (SELECT 1 FROM gabarit_vacation v
                            WHERE v.edition_id = g.edition_id AND v.gabarit_id = g.id)
               THEN 'aucune vacation'
           WHEN EXISTS (SELECT 1 FROM gabarit_vacation v
                        WHERE v.edition_id = g.edition_id AND v.gabarit_id = g.id
                          AND v.heure_debut = v.heure_fin)
               THEN 'vacation de durée nulle'
           ELSE 'vacation en double'
       END
FROM gabarit g
WHERE NOT EXISTS (SELECT 1 FROM gabarit_vacation v
                  WHERE v.edition_id = g.edition_id AND v.gabarit_id = g.id)
   OR EXISTS (SELECT 1 FROM gabarit_vacation v
              WHERE v.edition_id = g.edition_id AND v.gabarit_id = g.id
                AND v.heure_debut = v.heure_fin)
   OR EXISTS (SELECT 1 FROM gabarit_vacation v
              WHERE v.edition_id = g.edition_id AND v.gabarit_id = g.id
              GROUP BY v.heure_debut, v.heure_fin
              HAVING count(*) > 1);

-- Leurs vacations et leurs dates partent avec eux (cascade).
DELETE FROM gabarit g
USING journee_type_avant_4_0 a
WHERE a.edition_id = g.edition_id AND a.id = g.id;
