-- File d'attente et journal des jobs de résolution, persistés.
--
-- Jusqu'ici `SolverJobService` gardait tout en mémoire : un redémarrage du
-- serveur perdait la file des solves planifiés (et l'opérateur n'avait aucune
-- trace de ce que le solve interrompu était en train de faire). Le résultat
-- d'un solve terminé, lui, était déjà en base — c'est la file, pas le calcul,
-- que cette table sauve.
--
-- Ce qui est stocké est l'**intention**, jamais l'état du solveur Timefold :
-- type, budget, périmètre d'une replanification incrémentale. C'est suffisant
-- parce qu'une tâche en file construit déjà son problème au moment où elle
-- démarre, pas au moment du clic — une file rejouée après redémarrage lit donc
-- le référentiel du redémarrage, exactement comme elle aurait lu celui de son
-- tour de file. La sémantique est inchangée.
--
-- Le résultat (`PlanningDiagnostic`) n'est délibérément pas stocké : il pèse
-- lourd, et ce qu'il décrit est déjà en base (plan persisté, `kpi_historique`,
-- `plan_snapshot`). Un job restauré expose donc `result: null`.
--
-- Clé primaire simple, contrairement au reste du référentiel cloisonné par
-- édition : un id de job est un UUID globalement unique, et l'API l'adresse
-- seul (`/api/jobs/{id}`), sans en-tête d'édition.
--
-- Clé étrangère ON DELETE CASCADE vers `edition`, à l'inverse de
-- `kpi_historique` qui doit survivre à l'édition qu'il décrit : un solve
-- planifié sur une édition supprimée n'a plus rien où écrire, le rejouer au
-- redémarrage serait une faute.

CREATE TABLE IF NOT EXISTS solver_job (
    id VARCHAR(64) PRIMARY KEY,
    -- Ordre d'arrivée : c'est lui qui rejoue la file FIFO dans le bon ordre au
    -- redémarrage. `soumis_le` ne suffirait pas — deux soumissions peuvent
    -- porter le même instant.
    ordre BIGSERIAL NOT NULL,
    edition_id VARCHAR(64) NOT NULL REFERENCES edition(id) ON DELETE CASCADE,
    -- Dénormalisé comme en mémoire : le nom est résolu à la soumission, et
    -- l'IHM le montre partout où elle nomme le job.
    edition_nom VARCHAR(255),
    type VARCHAR(32) NOT NULL,
    statut VARCHAR(16) NOT NULL,
    seconds_limit BIGINT,
    -- Périmètre d'une replanification incrémentale (issue #86), sérialisé tel
    -- quel. NULL pour les autres types.
    perimetre JSONB,
    -- Vrai quand le job sait reconstruire son propre problème (solve depuis le
    -- référentiel, replanification incrémentale). Faux pour un solve ou une
    -- analyse dont le problème est arrivé dans le corps de la requête HTTP :
    -- ce corps n'est pas stocké, donc le job n'est pas rejouable. Ces
    -- lancements-là ne sont jamais mis en file aujourd'hui ; la colonne rend
    -- la garantie explicite plutôt qu'implicite.
    rejouable BOOLEAN NOT NULL DEFAULT FALSE,
    erreur TEXT,
    soumis_le TIMESTAMP WITH TIME ZONE NOT NULL,
    demarre_le TIMESTAMP WITH TIME ZONE,
    termine_le TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_solver_job_ordre ON solver_job(ordre);
