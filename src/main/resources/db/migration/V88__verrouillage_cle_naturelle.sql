-- Les verrous et les contraintes ad hoc qui visent un créneau le nomment
-- désormais par sa clé naturelle — jour, heure de début, heure de fin — et non
-- plus par le seul id (issue #577).
--
-- `verrouillage_planning.creneau_id` était déclaré ON DELETE CASCADE : toute
-- suppression de créneau — régénération de la grille, suppression sur une
-- plage, journée type qui ne nomme pas la vacation — emportait le verrou sans
-- bruit. Or appliquer un échange validé pose deux verrous sur le créneau que
-- chacun reçoit, précisément « pour que la régénération suivante ne défasse
-- pas l'échange » : la promesse retombait en silence, et la résolution
-- suivante était libre de la défaire.
--
-- `contrainte_ad_hoc.creneau_id` était ON DELETE SET NULL, ce qui est pire
-- encore dans un cas : la contrainte survit mais perd sa portée, donc une
-- règle écrite pour un créneau se met à valoir pour toute l'édition.
--
-- La clé naturelle est celle que le contrôle de doublon, l'application
-- différentielle des journées types et PublicationDiffService.Vacation.cle()
-- utilisent déjà. L'id reste stocké : c'est un cache, remis à jour à la
-- lecture par une jointure sur la clé naturelle, et mis à NULL — non plus en
-- cascade — quand le créneau disparaît.

/* --------- Verrouillages --------- */

ALTER TABLE verrouillage_planning
    ADD COLUMN creneau_date DATE,
    ADD COLUMN creneau_heure_debut TIME,
    ADD COLUMN creneau_heure_fin TIME;

UPDATE verrouillage_planning v
   SET creneau_date = c.date_creneau,
       creneau_heure_debut = c.heure_debut,
       creneau_heure_fin = c.heure_fin
  FROM creneau c
 WHERE c.id = v.creneau_id;

ALTER TABLE verrouillage_planning DROP CONSTRAINT verrouillage_planning_creneau_id_fkey;
ALTER TABLE verrouillage_planning
    ADD CONSTRAINT verrouillage_planning_creneau_fkey
    FOREIGN KEY (creneau_id) REFERENCES creneau(id) ON DELETE SET NULL;

-- La cible d'un verrou de créneau est sa clé naturelle ; `creneau_id` devient
-- facultatif, puisqu'il vaut NULL entre la suppression du créneau et sa
-- recréation.
ALTER TABLE verrouillage_planning DROP CONSTRAINT verrouillage_planning_cible_coherente;
ALTER TABLE verrouillage_planning ADD CONSTRAINT verrouillage_planning_cible_coherente CHECK (
    (type = 'ANIMATEUR' AND animateur_id IS NOT NULL AND stand_id IS NULL
        AND creneau_date IS NULL AND jour IS NULL)
    OR (type = 'STAND' AND stand_id IS NOT NULL AND animateur_id IS NULL
        AND creneau_date IS NULL AND jour IS NULL)
    OR (type = 'CRENEAU' AND creneau_date IS NOT NULL AND creneau_heure_debut IS NOT NULL
        AND creneau_heure_fin IS NOT NULL AND animateur_id IS NULL
        AND stand_id IS NULL AND jour IS NULL)
    OR (type = 'JOUR' AND jour IS NOT NULL AND animateur_id IS NULL
        AND stand_id IS NULL AND creneau_date IS NULL)
    OR (type = 'ANIMATEUR_CRENEAU' AND animateur_id IS NOT NULL AND creneau_date IS NOT NULL
        AND creneau_heure_debut IS NOT NULL AND creneau_heure_fin IS NOT NULL
        AND stand_id IS NULL AND jour IS NULL)
);

-- « Une cible verrouillée une seule fois par édition » se compte sur la clé
-- naturelle : sinon un verrou reposé après recréation du créneau ferait doublon
-- avec celui qui l'attendait.
DROP INDEX idx_verrouillage_cible_edition;
CREATE UNIQUE INDEX idx_verrouillage_cible_edition ON verrouillage_planning (
    edition_id, type,
    COALESCE(animateur_id, ''), COALESCE(stand_id, ''),
    COALESCE(creneau_date, DATE '0001-01-01'),
    COALESCE(creneau_heure_debut, TIME '00:00'),
    COALESCE(creneau_heure_fin, TIME '00:00'),
    COALESCE(jour, DATE '0001-01-01')
);

/* --------- Contraintes ad hoc --------- */

ALTER TABLE contrainte_ad_hoc
    ADD COLUMN creneau_date DATE,
    ADD COLUMN creneau_heure_debut TIME,
    ADD COLUMN creneau_heure_fin TIME;

UPDATE contrainte_ad_hoc a
   SET creneau_date = c.date_creneau,
       creneau_heure_debut = c.heure_debut,
       creneau_heure_fin = c.heure_fin
  FROM creneau c
 WHERE c.id = a.creneau_id;
