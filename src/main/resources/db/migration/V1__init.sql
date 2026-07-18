CREATE TABLE IF NOT EXISTS animateur (
    id VARCHAR(64) PRIMARY KEY,
    prenom VARCHAR(128) NOT NULL,
    nom VARCHAR(128) NOT NULL,
    date_naissance DATE NOT NULL,
    statut VARCHAR(32) NOT NULL
);

CREATE TABLE IF NOT EXISTS stand (
    id VARCHAR(64) PRIMARY KEY,
    nom VARCHAR(255) NOT NULL,
    effectif_min INTEGER NOT NULL,
    effectif_max INTEGER NOT NULL,
    reserve_majeurs BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS creneau (
    id VARCHAR(64) PRIMARY KEY,
    jour INTEGER NOT NULL,
    date_creneau DATE NOT NULL,
    heure_debut TIME NOT NULL,
    heure_fin TIME NOT NULL
);

CREATE TABLE IF NOT EXISTS poste_affectation (
    id VARCHAR(64) PRIMARY KEY,
    stand_id VARCHAR(64) NOT NULL REFERENCES stand(id),
    creneau_id VARCHAR(64) NOT NULL REFERENCES creneau(id),
    animateur_id VARCHAR(64) REFERENCES animateur(id)
);
