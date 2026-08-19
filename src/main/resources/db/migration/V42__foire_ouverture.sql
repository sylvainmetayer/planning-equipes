-- Ouverture/fermeture de la foire au planning (suite de l'issue #165) :
-- l'admin décide quand les animateurs peuvent proposer des échanges. Foire
-- fermée, l'espace animateur reste consultable (planning, historique des
-- demandes) mais aucune demande ne peut plus être soumise ni annulée — le
-- refus est appliqué côté serveur, pas seulement masqué dans l'interface.
--
-- Une ligne par édition, absente tant que l'admin n'a rien décidé : la foire
-- est alors OUVERTE, le comportement d'origine.

CREATE TABLE parametres_echange (
    edition_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES edition(id) ON DELETE CASCADE,
    foire_ouverte BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (edition_id)
);
