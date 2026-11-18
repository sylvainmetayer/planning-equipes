-- Calcul déporté (ADR 0086). Deux tables d'instance, sans edition_id : une
-- machine de calcul sert l'instance entière, et un redémarrage de
-- l'application ne doit oublier ni une machine louée qui tourne, ni un calcul
-- en cours sur elle.

-- Une ligne par machine louée, de sa création à son arrêt. Celle dont
-- arretee_le est nul est la machine en vie ; les autres sont l'historique que
-- lit le compteur d'heures du mois. Le secret du worker n'est pas stocké : il
-- se recalcule depuis le secret du crochet et l'aléa de la ligne.
CREATE TABLE machine_calcul (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version          TEXT        NOT NULL,
    alea             TEXT        NOT NULL,
    url              TEXT,
    empreinte        TEXT,
    demarree_le      TIMESTAMPTZ NOT NULL,
    prete_le         TIMESTAMPTZ,
    derniere_activite TIMESTAMPTZ NOT NULL,
    arretee_le       TIMESTAMPTZ
);

-- Au plus une machine en vie.
CREATE UNIQUE INDEX machine_calcul_en_vie ON machine_calcul ((arretee_le IS NULL)) WHERE arretee_le IS NULL;

-- Le calcul qu'une tâche a confié à un worker : de quoi le retrouver après un
-- redémarrage, et la table qui rend leur identifiant aux animateurs partis
-- sous un pseudonyme. Supprimée dès que la tâche se termine.
CREATE TABLE resolution_distante (
    job_id       TEXT PRIMARY KEY,
    solve_id     TEXT        NOT NULL,
    identifiants TEXT        NOT NULL,
    cree_le      TIMESTAMPTZ NOT NULL
);
