-- Détection des écritures concurrentes sur les quatre tables de paramètres de
-- l'édition (légal, organisation, notifications, calcul), comme le font déjà
-- les référentiels (V67) : une lecture périmée est refusée (409) au lieu
-- d'écraser en silence ce qu'une autre session a enregistré.
--
-- Les lignes existantes prennent l'instant de la migration. Un client qui
-- n'envoie pas `modifieLe` (ancien alias, import, outil MCP sans l'argument)
-- n'est pas contrôlé.
ALTER TABLE parametres_legaux ADD COLUMN modifie_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
ALTER TABLE parametres_qualite ADD COLUMN modifie_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
ALTER TABLE parametres_solveur ADD COLUMN modifie_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
ALTER TABLE parametres_notifications ADD COLUMN modifie_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
