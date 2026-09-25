-- Historique des réglages de pondération : qui a changé quel poids, ou quelle
-- activation, quand, et de combien.
--
-- Une table à part plutôt qu'un élargissement de journal_action : le journal ne
-- garde volontairement aucune valeur (minimisation, docs/rgpd.md) et expire au
-- bout de 90 jours, alors qu'un poids de contrainte n'est pas une donnée
-- personnelle et que son historique doit couvrir toute la préparation d'une
-- édition. Rien de nominatif ici : un nom de règle, des entiers, des booléens,
-- une origine typée, une date. La ligne disparaît avec l'édition.
--
-- Une ligne n'est écrite que si la valeur EFFECTIVE change — défaut du
-- déploiement compris — : remettre la même valeur n'écrit rien.
--   poids_avant / poids_apres : poids effectifs, défaut compris ; NULL quand
--                               la ligne ne porte que sur l'activation
--   retour_defaut             : l'édition a rendu la règle au défaut du
--                               déploiement (« 20 → défaut (1) »)
--   actif_avant / actif_apres : état effectif ; NULL quand la ligne ne porte
--                               que sur le poids
--   origine                   : ECRAN, ASSISTANT, SCENARIO ou DUPLICATION —
--                               l'acteur (ADMIN, ASSISTANT, SYSTEME) s'en déduit
--   edition_source            : pour DUPLICATION, l'édition d'où le dosage a
--                               été hérité
CREATE TABLE ponderation_contrainte_historique (
    id BIGSERIAL PRIMARY KEY,
    edition_id VARCHAR(64) NOT NULL REFERENCES edition (id) ON DELETE CASCADE,
    nom VARCHAR(128) NOT NULL,
    poids_avant INTEGER,
    poids_apres INTEGER,
    retour_defaut BOOLEAN NOT NULL DEFAULT FALSE,
    actif_avant BOOLEAN,
    actif_apres BOOLEAN,
    origine VARCHAR(32) NOT NULL,
    edition_source VARCHAR(64),
    cree_le TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ponderation_contrainte_historique_edition_nom
    ON ponderation_contrainte_historique (edition_id, nom, cree_le DESC);

-- Le dosage sous lequel le plan enregistré a été calculé, pris au LANCEMENT de
-- la résolution qui l'a produit : un instantané le recopie dans son KPI, et le
-- Comparateur dit quand deux plans ne sont pas comparables à poids égaux. NULL
-- pour un plan calculé avant cette colonne (« dosage inconnu »).
ALTER TABLE planning_resolution ADD COLUMN dosage JSONB;
