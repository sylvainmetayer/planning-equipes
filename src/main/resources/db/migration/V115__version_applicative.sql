-- Les versions de l'application qui ont ouvert cette base, dans l'ordre : une
-- ligne chaque fois qu'un démarrage réussi est celui d'une autre version que
-- la précédente, avec la dernière migration appliquée à ce moment.
--
-- De la traçabilité, rien de plus : la seule source de vérité du schéma reste
-- flyway_schema_history. Ce que cette table ajoute, c'est le nom de la version
-- qui a migré la base — « migrée par la 1.3.0, puis ouverte par la 1.2.4 » —,
-- que le refus de démarrer sur une base en avance cite, et que la page
-- Débogage affiche.
--
-- Globale, sans edition_id : elle décrit la base, pas une édition. Hors du dump
-- SQL de l'écran Paramètres pour la même raison (DatabaseDumpService).
CREATE TABLE version_applicative (
    id                 BIGSERIAL PRIMARY KEY,
    version            VARCHAR(64)              NOT NULL,
    premier_demarrage  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    derniere_migration VARCHAR(64)
);
