-- La couverture de pause (le « relais repas » qui divisait les sièges par deux)
-- est retirée du modèle : aucune règle du solveur ne la lisait, elle ne
-- servait qu'à la génération des sièges. Une vacation marquée en perd son
-- marquage et se pourvoit désormais à l'effectif plein du stand ; la fenêtre
-- repas et les pauses restent portées par les paramètres légaux.
--
-- Le nombre de vacations concernées est écrit au journal de migration, pour
-- qu'un opérateur sache ce que cette base a perdu (zéro sur les éditions
-- livrées).

DO $$
DECLARE
    n_creneaux BIGINT;
    n_gabarits BIGINT;
BEGIN
    SELECT count(*) INTO n_creneaux FROM creneau WHERE couverture_pause;
    SELECT count(*) INTO n_gabarits FROM gabarit_vacation WHERE couverture_pause;
    IF n_creneaux + n_gabarits > 0 THEN
        RAISE WARNING 'V170 : % créneau(x) et % vacation(s) de gabarit portaient une couverture de pause, retirée',
            n_creneaux, n_gabarits;
    END IF;
END $$;

ALTER TABLE creneau DROP COLUMN couverture_pause;
ALTER TABLE gabarit_vacation DROP COLUMN couverture_pause;
