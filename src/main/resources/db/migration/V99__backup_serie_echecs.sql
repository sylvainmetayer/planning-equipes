-- Alerte sur échec de la sauvegarde de nuit : ce que le courriel doit dire et
-- que le seul compte rendu de la dernière tentative ne sait pas.
--
-- `echecs_consecutifs` compte les tentatives ratées depuis la dernière réussie
-- (« 2 nuits consécutives ») et revient à zéro au premier succès — c'est aussi
-- ce qui décide du courriel « sauvegarde rétablie ». `dernier_succes_le` date
-- la dernière sauvegarde réussie, que le courriel d'échec rappelle.
--
-- Persistés et non tenus en mémoire : un redémarrage entre deux nuits en
-- échec ne doit pas remettre la série à zéro, ni faire oublier qu'une
-- rétablie est due.
ALTER TABLE backup_settings
    ADD COLUMN IF NOT EXISTS echecs_consecutifs INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS dernier_succes_le  TIMESTAMPTZ;

-- Reprise de l'existant : une dernière tentative réussie date la dernière
-- réussite ; une dernière tentative en échec ouvre une série d'un.
UPDATE backup_settings
SET dernier_succes_le  = CASE WHEN dernier_succes THEN derniere_tentative END,
    echecs_consecutifs = CASE WHEN dernier_succes IS FALSE THEN 1 ELSE 0 END;
