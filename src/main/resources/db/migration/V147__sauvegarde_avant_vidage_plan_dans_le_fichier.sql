-- La copie avant vidage devient un seul export de scénario dont la dernière
-- section, `plan:`, porte les sièges et les verrous (ADR 0112) : la colonne
-- `plan` n'est plus écrite. Les copies déjà rangées gardent la leur, et restent
-- restaurables : l'application la relit comme cette section. Aucune ligne n'est
-- réécrite ni supprimée.

ALTER TABLE sauvegarde_avant_vidage ALTER COLUMN plan DROP NOT NULL;
