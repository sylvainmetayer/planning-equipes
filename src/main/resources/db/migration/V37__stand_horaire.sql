-- Horaires récurrents de stand : une règle « ouvert 10h-12h puis 14h jusqu'à la
-- fermeture, tous les jours » au lieu d'une ligne datée par jour de festival.
-- Voir HoraireStand (invariant des trois états, arbitrage exception > règle) et
-- docs/domaine.md.
--
-- Migration purement additive, sans reprise de données : les lignes
-- stand_indisponibilite / stand_ouverture existantes gardent exactement leur
-- sens — ce sont désormais les *exceptions datées*, la couche qui prime sur les
-- règles. Une base d'avant cette migration se comporte donc à l'identique, et
-- l'action « Compacter les horaires » (ReferenceDataService#compacterHoraires)
-- est ce qui, à la demande, en dérive les règles équivalentes.
--
-- `jours_semaine` et `dates` sont stockés en texte séparé par des virgules
-- plutôt que normalisés dans une table fille : ce sont des valeurs feuilles,
-- jamais un prédicat SQL (la couverture d'un jour est calculée en Java par
-- HoraireStand#couvre), et les garder scalaires évite une troisième table pour
-- rien tout en laissant le dump SQL générique (DatabaseDumpService fait un
-- SELECT * et sait déjà écrire des littéraux texte).

CREATE TABLE IF NOT EXISTS stand_horaire (
    edition_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES edition(id) ON DELETE CASCADE,
    id BIGSERIAL,
    stand_id VARCHAR(64) NOT NULL,
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('OUVERTURE', 'FERMETURE')),
    type_jours VARCHAR(16) NOT NULL DEFAULT 'TOUS'
        CHECK (type_jours IN ('TOUS', 'JOURS_SEMAINE', 'PLAGE', 'DATES')),
    -- Noms java.time.DayOfWeek séparés par des virgules, ex. 'SATURDAY,SUNDAY'.
    jours_semaine VARCHAR(80),
    date_debut DATE,
    date_fin DATE,
    -- Dates ISO séparées par des virgules, ex. '2026-07-14,2026-07-19'.
    dates VARCHAR(512),
    motif VARCHAR(255),
    PRIMARY KEY (edition_id, id),
    CONSTRAINT stand_horaire_stand_fkey
        FOREIGN KEY (edition_id, stand_id) REFERENCES stand (edition_id, id) ON DELETE CASCADE,
    CONSTRAINT stand_horaire_plage_bornee
        CHECK (type_jours <> 'PLAGE' OR (date_debut IS NOT NULL AND date_fin IS NOT NULL AND date_fin >= date_debut))
);

CREATE INDEX IF NOT EXISTS idx_stand_horaire_stand ON stand_horaire(edition_id, stand_id);

-- Les fenêtres d'une règle, elles, sont bien une table fille : leur nombre est
-- variable et c'est la donnée centrale de la règle (la coupure méridienne d'un
-- stand = deux fenêtres pour une seule règle).
--
-- heure_fin NULL = « jusqu'à la fermeture » : la fenêtre court jusqu'à la fin du
-- créneau évalué. C'est ce qui remplace le contournement `23:59` qu'imposait une
-- heure de fin concrète les jours fermant à minuit (une fenêtre ne peut pas
-- chevaucher minuit) et ce qui permet à une même règle de couvrir un jour
-- fermant à 20h et un jour fermant à minuit.
CREATE TABLE IF NOT EXISTS stand_horaire_fenetre (
    edition_id VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES edition(id) ON DELETE CASCADE,
    id BIGSERIAL,
    horaire_id BIGINT NOT NULL,
    position INT NOT NULL DEFAULT 0,
    heure_debut TIME NOT NULL,
    heure_fin TIME,
    PRIMARY KEY (edition_id, id),
    CONSTRAINT stand_horaire_fenetre_horaire_fkey
        FOREIGN KEY (edition_id, horaire_id) REFERENCES stand_horaire (edition_id, id) ON DELETE CASCADE,
    CHECK (heure_fin IS NULL OR heure_fin > heure_debut)
);

CREATE INDEX IF NOT EXISTS idx_stand_horaire_fenetre_horaire
    ON stand_horaire_fenetre(edition_id, horaire_id);

/* --------- « Jusqu'à la fermeture » sur les exceptions datées aussi --------- */

-- Même notion, même intérêt : une fermeture « à partir de 14h » n'a pas à
-- inventer une heure de fin, et le jour fermant à minuit n'a plus besoin de
-- `23:59`. Toute ligne existante satisfait déjà la contrainte réécrite, la
-- migration ne peut donc pas échouer sur l'existant.
--
-- Les CHECK anonymes de V21/V23 sont nommés `<table>_check` par PostgreSQL ; le
-- DROP est en IF EXISTS parce que ce nom est une convention et non un contrat,
-- et son échec serait de toute façon sans conséquence fonctionnelle : un CHECK
-- qui s'évalue à NULL passe, donc `heure_fin > heure_debut` n'aurait jamais
-- rejeté un heure_fin NULL. On le remplace pour que la règle réelle soit lisible
-- dans le schéma, pas pour débloquer l'écriture.

ALTER TABLE stand_indisponibilite ALTER COLUMN heure_fin DROP NOT NULL;
ALTER TABLE stand_indisponibilite DROP CONSTRAINT IF EXISTS stand_indisponibilite_check;
ALTER TABLE stand_indisponibilite
    ADD CONSTRAINT stand_indisponibilite_fenetre_valide
        CHECK (heure_fin IS NULL OR heure_fin > heure_debut);

ALTER TABLE stand_ouverture ALTER COLUMN heure_fin DROP NOT NULL;
ALTER TABLE stand_ouverture DROP CONSTRAINT IF EXISTS stand_ouverture_check;
ALTER TABLE stand_ouverture
    ADD CONSTRAINT stand_ouverture_fenetre_valide
        CHECK (heure_fin IS NULL OR heure_fin > heure_debut);
