-- Consigne d'édition (issue #4) : ce qu'une autorité impose à toute la foire
-- sur une date — une bande horaire fermée pour tous les stands sans exception,
-- un arrêté préfectoral de canicule typiquement — et ce que l'organisateur
-- décide en compensation : des fenêtres où certains stands rouvrent.
--
-- Rien n'est supprimé dans la grille. La consigne est une quatrième couche du
-- résolveur d'horaires, appliquée au-dessus des règles récurrentes et des
-- exceptions datées de chaque stand : les créneaux nominaux gardent leur id,
-- leurs sièges et leurs verrous, et un stand fermé sur une partie d'un créneau
-- n'y engendre que les sièges du reste, aux heures effectives.
--
-- Une ligne par date, si bien que prolonger une alerte est un ajout de lignes,
-- la lever ne touche que les dates à venir, et une date déjà travaillée garde
-- pour toujours la consigne qui l'a réellement gouvernée.

CREATE TABLE consigne_edition (
    edition_id      VARCHAR(64) NOT NULL REFERENCES edition (id) ON DELETE CASCADE,
    date_jour       DATE        NOT NULL,
    -- La bande interdite. `fermeture_fin` NULL se lit « jusqu'à minuit », la
    -- convention des fenêtres datées des stands ; 00:00 → NULL est la journée
    -- entière.
    fermeture_debut TIME        NOT NULL,
    fermeture_fin   TIME,
    motif           TEXT        NOT NULL,
    -- Le préréglage dont la consigne est issue (« Plan canicule »), pour
    -- l'afficher et proposer la même liste à la prolongation ; NULL pour une
    -- consigne saisie librement. Un nom, pas une FK : supprimer le préréglage
    -- ne réécrit pas l'histoire des dates qu'il a gouvernées.
    prereglage_nom  VARCHAR(120),
    cree_le         TIMESTAMPTZ NOT NULL DEFAULT now(),
    modifie_le      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (edition_id, date_jour),
    CONSTRAINT consigne_edition_fermeture_ordonnee
        CHECK (fermeture_fin IS NULL OR fermeture_debut < fermeture_fin),
    CONSTRAINT consigne_edition_motif_renseigne CHECK (length(btrim(motif)) > 0)
);

COMMENT ON TABLE consigne_edition IS
    'One date the organiser closed a band on for every stand, with the compensation chosen';

-- Les fenêtres de compensation par défaut de la journée : celles que reçoit
-- chaque stand coché avant tout ajustement individuel. Plusieurs par jour, le
-- matin et le soir par exemple.
CREATE TABLE consigne_edition_fenetre (
    edition_id  VARCHAR(64) NOT NULL,
    date_jour   DATE        NOT NULL,
    position    INTEGER     NOT NULL,
    heure_debut TIME        NOT NULL,
    heure_fin   TIME,
    PRIMARY KEY (edition_id, date_jour, position),
    FOREIGN KEY (edition_id, date_jour) REFERENCES consigne_edition (edition_id, date_jour) ON DELETE CASCADE,
    CONSTRAINT consigne_edition_fenetre_ordonnee CHECK (heure_fin IS NULL OR heure_debut < heure_fin)
);

-- Les stands qui rouvrent, une ligne par fenêtre et par stand. Absent de la
-- table = le stand garde ses horaires hors de la bande, rien de plus : la
-- compensation est choisie, jamais implicite. `effectif` NULL hérite du plus
-- fort effectif que le stand perd dans la bande, sinon de son minimum.
CREATE TABLE consigne_edition_ouverture (
    edition_id  VARCHAR(64) NOT NULL,
    date_jour   DATE        NOT NULL,
    stand_id    VARCHAR(64) NOT NULL,
    position    INTEGER     NOT NULL,
    heure_debut TIME        NOT NULL,
    heure_fin   TIME,
    effectif    INTEGER,
    PRIMARY KEY (edition_id, date_jour, stand_id, position),
    FOREIGN KEY (edition_id, date_jour) REFERENCES consigne_edition (edition_id, date_jour) ON DELETE CASCADE,
    FOREIGN KEY (edition_id, stand_id) REFERENCES stand (edition_id, id) ON DELETE CASCADE,
    CONSTRAINT consigne_edition_ouverture_ordonnee CHECK (heure_fin IS NULL OR heure_debut < heure_fin),
    CONSTRAINT consigne_edition_ouverture_effectif_positif CHECK (effectif IS NULL OR effectif > 0)
);

-- Les créneaux que la consigne a dû ajouter parce que la grille nominale ne
-- couvrait pas une fenêtre de compensation (20h-22h après une journée qui
-- finit à 20h). C'est la seule écriture de la consigne sur la grille, et elle
-- est additive : à la levée, ces créneaux sont supprimés avec leurs sièges, et
-- la publication suivante annonce leur retrait depuis l'instantané publié.
-- Ils sont marqués ici pour que l'application d'une journée type les ignore
-- au lieu de les compter en écart.
CREATE TABLE consigne_edition_creneau (
    edition_id VARCHAR(64) NOT NULL,
    date_jour  DATE        NOT NULL,
    creneau_id BIGINT      NOT NULL REFERENCES creneau (id) ON DELETE CASCADE,
    PRIMARY KEY (edition_id, date_jour, creneau_id),
    FOREIGN KEY (edition_id, date_jour) REFERENCES consigne_edition (edition_id, date_jour) ON DELETE CASCADE
);

-- Un préréglage nommé — « Plan canicule : 12h-18h fermé, soir 18h-22h » —
-- mémorisé sur l'édition, validé à froid, et copié à la duplication ; les
-- consignes datées, elles, ne le sont pas : elles appartiennent aux jours
-- d'une édition, pas à sa forme.
CREATE TABLE prereglage_consigne (
    edition_id      VARCHAR(64) NOT NULL REFERENCES edition (id) ON DELETE CASCADE,
    id              VARCHAR(64) NOT NULL,
    nom             VARCHAR(120) NOT NULL,
    fermeture_debut TIME        NOT NULL,
    fermeture_fin   TIME,
    motif           TEXT        NOT NULL,
    modifie_le      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (edition_id, id),
    CONSTRAINT prereglage_consigne_fermeture_ordonnee
        CHECK (fermeture_fin IS NULL OR fermeture_debut < fermeture_fin),
    CONSTRAINT prereglage_consigne_motif_renseigne CHECK (length(btrim(motif)) > 0)
);

CREATE UNIQUE INDEX uq_prereglage_consigne_nom ON prereglage_consigne (edition_id, lower(nom));

CREATE TABLE prereglage_consigne_fenetre (
    edition_id    VARCHAR(64) NOT NULL,
    prereglage_id VARCHAR(64) NOT NULL,
    position      INTEGER     NOT NULL,
    heure_debut   TIME        NOT NULL,
    heure_fin     TIME,
    PRIMARY KEY (edition_id, prereglage_id, position),
    FOREIGN KEY (edition_id, prereglage_id) REFERENCES prereglage_consigne (edition_id, id) ON DELETE CASCADE,
    CONSTRAINT prereglage_consigne_fenetre_ordonnee CHECK (heure_fin IS NULL OR heure_debut < heure_fin)
);
