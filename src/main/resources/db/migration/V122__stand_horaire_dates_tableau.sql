-- Les dates d'une règle d'horaire « dates précises » deviennent un vrai
-- tableau de dates.
--
-- V37 les rangeait en texte ISO séparé par des virgules dans un VARCHAR(512) :
-- dix caractères par date plus la virgule, la 47ᵉ date dépassait, et
-- l'enregistrement d'un stand ouvert « un jour sur deux » sur un événement
-- long échouait en erreur 500. Élargir la colonne aurait gardé une liste non
-- typée, dont une date invalide ne se voyait qu'à la relecture : `date[]` n'a
-- pas de plafond et c'est la base qui type chaque élément.
--
-- La conversion relit exactement ce que le code lisait (LocalDate.parse sur
-- chaque morceau débarrassé de ses blancs, morceaux vides ignorés, liste vide
-- = NULL) et rien de plus permissif. Le cast `::date` de PostgreSQL accepte
-- bien davantage — « today », « 20260714 », « 14/07/2026 » lu selon le
-- DateStyle du serveur, « infinity » —, d'où la vérification qui le précède :
-- un morceau qui n'est pas une date ISO AAAA-MM-JJ fait ÉCHOUER la migration
-- plutôt que d'être converti en autre chose ou perdu en silence.
--
-- La contrainte borne ensuite ce que la colonne peut recevoir d'ailleurs (un
-- dump rejoué, du SQL à la main) à ce que l'application relit : ni élément
-- NULL, ni ±infinity, ni année hors de 1 à 9999.
--
-- La duplication d'édition recopie la colonne telle quelle, et un dump exporté
-- après cette migration écrit le littéral `'{…}'`. Un dump exporté AVANT ne se
-- rejoue plus — comme aucun dump antérieur à V118 depuis le renommage de
-- `edition.defaut` : un dump se rejoue sur la version qui l'a produit.
--
-- `jours_semaine` reste en texte : sept noms de jour au plus tiennent
-- largement dans ses 80 caractères.

DO $$
DECLARE
    illisible RECORD;
BEGIN
    -- Blancs retirés aux deux bouts comme String#trim, pas au milieu.
    SELECT h.edition_id, h.id, morceau.valeur
    INTO illisible
    FROM stand_horaire h,
         LATERAL (SELECT regexp_replace(brut, '^\s+|\s+$', '', 'g') AS valeur
                  FROM unnest(string_to_array(h.dates, ',')) AS brut) AS morceau
    WHERE morceau.valeur <> ''
      AND morceau.valeur !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'stand_horaire (%, %) : « % » n''est pas une date AAAA-MM-JJ',
            illisible.edition_id, illisible.id, illisible.valeur;
    END IF;
END $$;

ALTER TABLE stand_horaire
    ALTER COLUMN dates TYPE DATE[]
    USING NULLIF(array_remove(string_to_array(regexp_replace(dates, '\s', '', 'g'), ','), ''), '{}')::DATE[];

ALTER TABLE stand_horaire
    ADD CONSTRAINT stand_horaire_dates_lisibles
        CHECK (dates IS NULL
            OR (array_position(dates, NULL) IS NULL
                AND DATE '0001-01-01' <= ALL (dates)
                AND DATE '9999-12-31' >= ALL (dates)));
