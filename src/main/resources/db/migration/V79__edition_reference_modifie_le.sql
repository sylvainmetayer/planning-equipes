-- Fraîcheur des instantanés (issue #170) : quand le référentiel d'une édition
-- a été modifié pour la dernière fois, persisté.
--
-- `ReferenceDataChangeTracker` le tenait en mémoire seulement, et c'était
-- assumé : il n'alimentait qu'un bandeau, où un indice muet après redémarrage
-- ne coûte rien. Devant le bouton « Restaurer » d'un instantané, la même
-- amnésie change de nature — tout instantané repasserait « à jour », et un
-- plan calculé avant la suppression d'un animateur se remettrait en place sans
-- un mot. Un « à jour » faux y est pire que pas de badge du tout, donc la date
-- descend en base. Voir ADR 0038.
--
-- Nullable, et un NULL veut dire « aucune mutation connue » : une édition dont
-- le référentiel est vide ne périme rien.
ALTER TABLE edition ADD COLUMN reference_modifie_le TIMESTAMP WITH TIME ZONE;

-- Reprise de l'existant. Sans elle, toute édition antérieure au déploiement
-- afficherait « à jour » jusqu'à sa prochaine écriture — le faux positif
-- silencieux qu'on vient d'écarter.
--
-- Deux sources, parce que les écritures que `markModified()` couvre ne sont pas
-- toutes datables :
--
--   1. Les six référentiels portent `modifie_le` depuis V67, et
--      `verrouillage_planning` porte `cree_le` : leur maximum par édition est
--      exactement la date cherchée.
--   2. `constraint_toggle` et `ponderation_contrainte` ne portent aucune date —
--      la seule présence d'une ligne prouve une écriture délibérée dont on ne
--      sait pas dire quand. Pour ces éditions-là on prend l'instant de la
--      migration : leurs instantanés antérieurs au déploiement passent
--      « périmés », ce qui est le sens prudent. Les éditions qui n'ont jamais
--      touché à une bascule ni à un poids — l'immense majorité — gardent leur
--      date exacte.
--
-- Deux limites assumées, toutes deux du même genre : une écriture qui n'a
-- laissé aucune trace datable.
--   * Une fiche supprimée n'a laissé aucune ligne, donc la date reprise est
--     celle de la dernière écriture *survivante*.
--   * `parametres_legaux` et `parametres_solveur` portent une ligne par édition
--     créée d'office : sa présence ne prouve rien, et la comparer aux valeurs
--     par défaut casserait au premier changement de défaut.
-- L'une comme l'autre ne concernent que les éditions existant au déploiement,
-- et seulement jusqu'à leur écriture suivante.
UPDATE edition e
SET reference_modifie_le = (
    SELECT max(dernier)
    FROM (
        SELECT max(modifie_le) AS dernier FROM stand WHERE edition_id = e.id
        UNION ALL SELECT max(modifie_le) FROM animateur WHERE edition_id = e.id
        UNION ALL SELECT max(modifie_le) FROM creneau WHERE edition_id = e.id
        UNION ALL SELECT max(modifie_le) FROM typologie WHERE edition_id = e.id
        UNION ALL SELECT max(modifie_le) FROM emplacement WHERE edition_id = e.id
        UNION ALL SELECT max(modifie_le) FROM contrainte_ad_hoc WHERE edition_id = e.id
        UNION ALL SELECT max(cree_le) FROM verrouillage_planning WHERE edition_id = e.id
        UNION ALL SELECT CASE
            WHEN EXISTS (SELECT 1 FROM constraint_toggle WHERE edition_id = e.id)
              OR EXISTS (SELECT 1 FROM ponderation_contrainte WHERE edition_id = e.id)
            THEN now()
        END
    ) AS maxima
);
