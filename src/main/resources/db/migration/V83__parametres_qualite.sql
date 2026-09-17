-- Les seuils de « Qualité d'organisation » étaient de la configuration de
-- déploiement (`planning.contraintes.*`), lue au démarrage et identique pour
-- toutes les éditions. Un organisateur pour qui trois typologies par animateur
-- sont normales n'avait aucun moyen de le dire : il ne pouvait que doser le
-- poids de la règle jusqu'à ce qu'elle cesse de compter (issue #591).
--
-- Ils rejoignent donc `parametres_legaux` et `parametres_solveur` : une ligne
-- par édition, absente tant que personne n'a ouvert l'écran. Ce que la
-- configuration porte devient le DÉFAUT — les valeurs d'une édition qui n'a
-- jamais rien réglé — et non plus la seule valeur possible.
--
-- Les heures sont nullables : une heure laissée vide est ce qui neutralise
-- `eviterFermeturePuisOuverture` sans toucher au catalogue (ADR 0031), et
-- minuit n'est pas « absente ».

CREATE TABLE IF NOT EXISTS parametres_qualite (
    edition_id VARCHAR(64) PRIMARY KEY DEFAULT 'DEFAUT' REFERENCES edition(id) ON DELETE CASCADE,
    max_emplacements_distincts_par_jour INTEGER NOT NULL,
    heure_service_tardif TIME,
    heure_service_matinal TIME,
    repos_souhaite_apres_service_tardif_minutes INTEGER NOT NULL,
    typologies_distinctes_max INTEGER NOT NULL
);
