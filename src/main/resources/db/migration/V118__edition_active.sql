-- Une seule édition active à la fois : elle seule publie, envoie des courriels
-- et ouvre l'espace animateur, le flux ICS et l'affichage mural.
--
-- « Édition par défaut » et « édition active » ne font plus qu'un : la colonne
-- `defaut` devient `active`, et son index unique partiel garantit qu'au plus
-- une ligne la porte. Aucune ligne active est un état valide (entre deux
-- événements, tout est alors fermé vers l'extérieur).
--
-- L'armement des notifications de nuit (`parametres_notifications.actives`)
-- disparaît : l'édition active est de fait armée, les autres ne le sont jamais.
-- Pour ne pas réveiller une édition que personne n'avait armée — ni faire taire
-- celle qui l'était —, l'édition active est choisie ainsi :
--   1. exactement une édition armée : c'est elle ;
--   2. sinon, l'édition par défaut ;
--   3. sinon (aucune ligne marquée), la plus ancienne, comme le repli
--      d'avant cette migration.
-- Le choix est annoncé par un NOTICE, que le journal de démarrage recopie.

DO $$
DECLARE
    armees INTEGER;
    cible  TEXT;
    motif  TEXT;
BEGIN
    SELECT count(*) INTO armees FROM parametres_notifications WHERE actives;
    IF armees = 1 THEN
        SELECT edition_id INTO cible FROM parametres_notifications WHERE actives;
        motif := 'seule édition armée';
    ELSE
        SELECT id INTO cible FROM edition WHERE defaut;
        motif := format('édition par défaut (%s édition(s) armée(s))', armees);
        IF cible IS NULL THEN
            SELECT id INTO cible FROM edition ORDER BY cree_le, id LIMIT 1;
            motif := 'plus ancienne édition, aucune n''étant marquée par défaut';
        END IF;
    END IF;
    UPDATE edition SET defaut = FALSE WHERE defaut AND id IS DISTINCT FROM cible;
    UPDATE edition SET defaut = TRUE WHERE id = cible;
    RAISE NOTICE 'Édition active : % (%)', coalesce(cible, 'aucune'), motif;
END
$$;

DROP INDEX idx_edition_defaut_unique;
ALTER TABLE edition RENAME COLUMN defaut TO active;
CREATE UNIQUE INDEX idx_edition_active_unique ON edition (active) WHERE active;

ALTER TABLE parametres_notifications DROP COLUMN actives;

-- L'historique garde ce qui a été fait sous l'ancien nom : désigner l'édition
-- par défaut, c'est désormais l'activer.
UPDATE journal_action SET action = 'EDITION_ACTIVEE' WHERE action = 'EDITION_PAR_DEFAUT';
