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
-- La conversion relit exactement ce que le code lisait (espaces retirés,
-- morceaux vides ignorés, liste vide = NULL) et rien de plus permissif : une
-- valeur qui n'est pas une date fait ÉCHOUER la migration plutôt que d'être
-- perdue en silence. Le dump SQL et la duplication d'édition n'ont rien à
-- changer : le premier écrit le littéral `'{…}'`, la seconde recopie la
-- colonne telle quelle.
--
-- `jours_semaine` reste en texte : sept noms de jour au plus tiennent
-- largement dans ses 80 caractères.

ALTER TABLE stand_horaire
    ALTER COLUMN dates TYPE DATE[]
    USING NULLIF(array_remove(string_to_array(regexp_replace(dates, '\s', '', 'g'), ','), ''), '{}')::DATE[];
