-- Toute clé étrangère porte un index côté référençant (ADR 0083).
--
-- PostgreSQL indexe gratuitement le côté référencé d'une clé, jamais le côté
-- référençant : sans index, supprimer une ligne parent parcourt la table
-- enfant entière, une fois par ligne supprimée. Les index sont posés dans
-- l'ordre des colonnes de la clé ; un index partiel `col IS NOT NULL` suffit
-- aux colonnes presque toujours vides. Un test lit pg_constraint et pg_index
-- du schéma migré et refuse toute clé laissée sans index.

-- Les sièges ------------------------------------------------------------------
CREATE INDEX idx_poste_affectation_creneau ON poste_affectation (edition_id, creneau_id);
CREATE INDEX idx_poste_affectation_animateur ON poste_affectation (edition_id, animateur_id);
CREATE INDEX idx_poste_affectation_stand_creneau ON poste_affectation (edition_id, stand_id, creneau_id);
CREATE INDEX idx_poste_affectation_suite_de ON poste_affectation (edition_id, suite_de) WHERE suite_de IS NOT NULL;

-- Le journal : les lectures trient sur (survenu_le DESC, id DESC) ---------------
DROP INDEX idx_journal_action_recent;
CREATE INDEX idx_journal_action_recent ON journal_action (edition_id, survenu_le DESC, id DESC);

-- Trois index refaits : (stand_id) seul ne servait ni la clé vers edition ni les lectures par édition ;
-- celui de consigne_edition_creneau, sur (creneau_id), ne sert plus la clé devenue composite
DROP INDEX idx_stand_ouverture_stand_id;
CREATE INDEX idx_stand_ouverture_stand_id ON stand_ouverture (edition_id, stand_id);
DROP INDEX idx_stand_indisponibilite_stand_id;
CREATE INDEX idx_stand_indisponibilite_stand_id ON stand_indisponibilite (edition_id, stand_id);

-- Le référentiel -----------------------------------------------------------------
CREATE INDEX idx_stand_emplacement ON stand (edition_id, emplacement_id);
CREATE INDEX idx_stand_typologie_typologie ON stand_typologie (edition_id, typologie);
CREATE INDEX idx_animateur_competence_typologie ON animateur_competence (edition_id, typologie);
CREATE INDEX idx_animateur_souhait_typologie ON animateur_souhait (edition_id, typologie);
CREATE INDEX idx_animateur_plan_notifie ON animateur (edition_id, plan_notifie_id) WHERE plan_notifie_id IS NOT NULL;
CREATE INDEX idx_creneau_stand_ouvert_stand ON creneau_stand_ouvert (edition_id, stand_id);
DROP INDEX idx_consigne_edition_creneau_creneau;
CREATE INDEX idx_consigne_edition_creneau_creneau ON consigne_edition_creneau (edition_id, creneau_id);
CREATE INDEX idx_journee_type_date_journee_type ON journee_type_date (edition_id, journee_type_id);
CREATE INDEX idx_lien_affichage_mural_emplacement_emplacement
    ON lien_affichage_mural_emplacement (edition_id, emplacement_id);
CREATE INDEX idx_parametres_meteo_chaleur ON parametres_meteo (edition_id, prereglage_chaleur);
CREATE INDEX idx_parametres_meteo_vent ON parametres_meteo (edition_id, prereglage_vent);
CREATE INDEX idx_parametres_meteo_orage ON parametres_meteo (edition_id, prereglage_orage);

-- Les exceptions et les verrous -----------------------------------------------------
CREATE INDEX idx_contrainte_ad_hoc_creneau ON contrainte_ad_hoc (edition_id, creneau_id);
CREATE INDEX idx_contrainte_ad_hoc_stand ON contrainte_ad_hoc (edition_id, stand_id);
CREATE INDEX idx_contrainte_animateur_animateur ON contrainte_animateur (edition_id, animateur_id);
CREATE INDEX idx_verrouillage_planning_creneau ON verrouillage_planning (edition_id, creneau_id);
CREATE INDEX idx_verrouillage_planning_animateur ON verrouillage_planning (edition_id, animateur_id);
CREATE INDEX idx_verrouillage_planning_stand ON verrouillage_planning (edition_id, stand_id);

-- Les échanges ---------------------------------------------------------------------
CREATE INDEX idx_demande_echange_creneau ON demande_echange (edition_id, creneau_id);
CREATE INDEX idx_demande_echange_creneau_cible ON demande_echange (edition_id, creneau_cible_id)
    WHERE creneau_cible_id IS NOT NULL;
CREATE INDEX idx_demande_echange_stand ON demande_echange (edition_id, stand_id);
CREATE INDEX idx_demande_echange_stand_cible ON demande_echange (edition_id, stand_cible_id)
    WHERE stand_cible_id IS NOT NULL;
CREATE INDEX idx_demande_echange_cible ON demande_echange (edition_id, cible_id);
CREATE INDEX idx_demande_echange_demandeur ON demande_echange (edition_id, demandeur_id);

-- Ce que disent les animateurs : un index plein à côté de l'unique partiel, qui ne sert que sa règle
CREATE INDEX idx_declaration_disponibilite_animateur ON declaration_disponibilite (edition_id, animateur_id);
CREATE INDEX idx_declaration_coequipier_animateur ON declaration_coequipier (edition_id, animateur_id);
CREATE INDEX idx_declaration_coequipier_contrainte ON declaration_coequipier (edition_id, contrainte_id)
    WHERE contrainte_id IS NOT NULL;
CREATE INDEX idx_signalement_absence_creneau ON signalement_absence (edition_id, creneau_id);
CREATE INDEX idx_signalement_absence_animateur ON signalement_absence (edition_id, animateur_id);
CREATE INDEX idx_signalement_absence_stand ON signalement_absence (edition_id, stand_id);

-- Publication et résolution ---------------------------------------------------------------
CREATE INDEX idx_envoi_planning_snapshot ON envoi_planning (edition_id, snapshot_id);
CREATE INDEX idx_planning_resolution_snapshot ON planning_resolution (edition_id, snapshot_avant_solve_id)
    WHERE snapshot_avant_solve_id IS NOT NULL;
CREATE INDEX idx_solver_job_edition ON solver_job (edition_id);
