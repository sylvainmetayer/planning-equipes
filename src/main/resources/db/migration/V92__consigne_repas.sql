-- Une consigne peut porter ses propres fenêtres repas (issue #4, suite).
--
-- Le soir d'une canicule, la compensation 18 h-22 h prolonge l'après-midi
-- resté ouvert 18 h-20 h : la coupure repas du soir (19 h-21 h) interdit
-- alors à une même personne d'enchaîner, et il faut deux personnes par
-- siège — ce que la semaine ne tient pas. Or les gens ont mangé pendant la
-- bande fermée. La réponse était de déplacer la fenêtre dans les paramètres
-- légaux de l'édition, à la main, et de penser à la remettre à la levée :
-- un réglage global pour un fait daté, que l'oubli casse dans les deux
-- sens.
--
-- La fenêtre descend donc sur la consigne, datée par construction : elle ne
-- gouverne que ce jour-là, part avec la consigne, et les autres jours ne la
-- voient jamais. Seules les fenêtres repas sont surchargeables — la coupure
-- repas est la règle de l'organisateur, pas le Code du travail ; les
-- plafonds légaux restent hors d'atteinte. Une justification, en termes
-- métier, est exigée dès qu'un champ est renseigné : elle est imprimée à
-- côté de la journée.
--
-- NULL partout = les fenêtres de l'édition s'appliquent.

ALTER TABLE consigne_edition
    ADD COLUMN repas_midi_debut      TIME,
    ADD COLUMN repas_midi_fin        TIME,
    ADD COLUMN repas_soir_debut      TIME,
    ADD COLUMN repas_soir_fin        TIME,
    ADD COLUMN repas_coupure_minutes INTEGER,
    ADD COLUMN repas_justification   TEXT,
    ADD CONSTRAINT consigne_edition_repas_justifie CHECK (
        (repas_midi_debut IS NULL AND repas_midi_fin IS NULL AND repas_soir_debut IS NULL
            AND repas_soir_fin IS NULL AND repas_coupure_minutes IS NULL)
        OR length(btrim(repas_justification)) > 0),
    ADD CONSTRAINT consigne_edition_repas_ordonne CHECK (
        (repas_midi_debut IS NULL OR repas_midi_fin IS NULL OR repas_midi_debut < repas_midi_fin)
        AND (repas_soir_debut IS NULL OR repas_soir_fin IS NULL OR repas_soir_debut < repas_soir_fin)
        AND (repas_coupure_minutes IS NULL OR repas_coupure_minutes > 0));

ALTER TABLE prereglage_consigne
    ADD COLUMN repas_midi_debut      TIME,
    ADD COLUMN repas_midi_fin        TIME,
    ADD COLUMN repas_soir_debut      TIME,
    ADD COLUMN repas_soir_fin        TIME,
    ADD COLUMN repas_coupure_minutes INTEGER,
    ADD COLUMN repas_justification   TEXT,
    ADD CONSTRAINT prereglage_consigne_repas_justifie CHECK (
        (repas_midi_debut IS NULL AND repas_midi_fin IS NULL AND repas_soir_debut IS NULL
            AND repas_soir_fin IS NULL AND repas_coupure_minutes IS NULL)
        OR length(btrim(repas_justification)) > 0),
    ADD CONSTRAINT prereglage_consigne_repas_ordonne CHECK (
        (repas_midi_debut IS NULL OR repas_midi_fin IS NULL OR repas_midi_debut < repas_midi_fin)
        AND (repas_soir_debut IS NULL OR repas_soir_fin IS NULL OR repas_soir_debut < repas_soir_fin)
        AND (repas_coupure_minutes IS NULL OR repas_coupure_minutes > 0));
