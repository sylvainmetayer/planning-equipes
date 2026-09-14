-- Fraîcheur des instantanés (issue #170) : quand le référentiel d'une édition
-- a été modifié pour la dernière fois, persisté.
--
-- `ReferenceDataChangeTracker` le tenait en mémoire seulement, et c'était
-- assumé : il n'alimentait qu'un bandeau, où un indice muet après redémarrage
-- ne coûte rien. Devant le bouton « Restaurer » d'un instantané, la même
-- amnésie change de nature — tout instantané repasserait « à jour », et un
-- plan calculé avant la suppression d'un animateur se remettrait en place sans
-- un mot. Un « à jour » faux y est pire que pas de badge du tout, donc la date
-- descend en base.
--
-- Nullable, et un NULL veut dire « aucune mutation connue » : une édition dont
-- le référentiel est vide ne périme rien.
ALTER TABLE edition ADD COLUMN reference_modifie_le TIMESTAMP WITH TIME ZONE;

-- Reprise de l'existant : les six référentiels portent `modifie_le` depuis V67,
-- et leur maximum par édition est exactement la date cherchée. Sans cette
-- reprise, toute édition antérieure au déploiement afficherait « à jour »
-- jusqu'à sa prochaine écriture — le faux positif silencieux qu'on vient
-- d'écarter. Limite assumée : une suppression de fiche n'a laissé aucune
-- ligne, donc son horodatage est perdu ; la date reprise est celle de la
-- dernière écriture *survivante*.
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
    ) AS maxima
);
