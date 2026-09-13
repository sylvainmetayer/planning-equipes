-- Le découpage disparaît. Un évènement connaît ses horaires d'ouverture et
-- projette ses journées types : la grille qu'une édition détient est toujours
-- faite de vacations, jamais d'amplitudes à trancher avant de résoudre. Plus
-- de paramètres de découpage, donc, et plus de mode de lecture à déclarer.
--
-- Une seule de ces valeurs survit, et elle change de maison comme la coupure
-- repas l'a fait en V72 : la durée maximale d'une vacation. Elle bornait ce
-- que le découpage produisait ; elle dit maintenant à partir de quand le
-- contrôle de grille avertit qu'une vacation exige une coupure interne
-- (art. L3121-16). C'est une règle de l'évènement, jugée sur la grille que
-- l'organisateur a, donc elle rejoint les autres règles.
ALTER TABLE parametres_legaux
    ADD COLUMN duree_vacation_max_minutes INTEGER NOT NULL DEFAULT 360;

-- Une édition qui avait réglé son découpage sans jamais toucher ses paramètres
-- légaux n'a pas encore de ligne ici : lui en donner une, pour que son seuil
-- traverse le déménagement.
INSERT INTO parametres_legaux (edition_id)
SELECT d.edition_id
FROM parametres_decoupage d
WHERE NOT EXISTS (SELECT 1 FROM parametres_legaux l WHERE l.edition_id = d.edition_id);

UPDATE parametres_legaux l
SET duree_vacation_max_minutes = d.duree_vacation_max_minutes
FROM parametres_decoupage d
WHERE d.edition_id = l.edition_id;

-- Le reste — durée cible, durée minimale, chevauchement, stratégie de
-- couverture pendant la pause, mode de grille — n'a plus de lecteur. La
-- couverture de pause elle-même reste : ce n'est pas un réglage de découpage
-- mais un attribut du créneau (creneau.couverture_pause), posé à la main ou
-- par une journée type, et l'effectif y est toujours divisé par deux.
DROP TABLE parametres_decoupage;
