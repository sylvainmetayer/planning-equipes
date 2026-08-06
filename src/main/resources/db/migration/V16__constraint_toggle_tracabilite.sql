-- Traçabilité de la désactivation d'une contrainte (constat C2 / S4 de
-- l'audit de conformité RH).
--
-- Jusqu'ici, `constraint_toggle` ne portait que le nom de la contrainte
-- désactivée : impossible de savoir qui avait désactivé une règle légale
-- (par exemple `travailDeNuitInterditPourMineur`), quand, ni pourquoi — alors
-- que `contrainte_ad_hoc`, bien moins lourde de conséquences, porte déjà
-- `raison`, `cree_par` et `cree_le`.
--
-- Limite connue et assumée : l'application n'a aucune authentification.
-- `modifie_par_utilisateur_id` reçoit ce que l'IHM envoie (aujourd'hui la
-- constante « ui », exactement comme `contrainte_ad_hoc.cree_par`) : ce n'est
-- donc pas une preuve d'imputabilité. `motif` et `modifie_le`, eux, sont des
-- informations réelles.

ALTER TABLE constraint_toggle
    ADD COLUMN IF NOT EXISTS motif TEXT,
    ADD COLUMN IF NOT EXISTS modifie_par_utilisateur_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS modifie_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
