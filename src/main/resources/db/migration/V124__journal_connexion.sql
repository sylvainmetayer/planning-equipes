-- Journal des connexions administrateur : chaque connexion réussie, chaque
-- échec et chaque verrouillage du form login (`/j_security_check`), avec son
-- horodatage et l'adresse cliente que le verrou compte.
--
-- Une table de l'instance, sans `edition_id` : se connecter n'a lieu dans
-- aucune édition — le form login passe avant toute requête qui en désigne une —
-- et l'écran la montre quelle que soit l'édition choisie.
--
-- Rien de ce qui a été saisi n'y entre : ni le mot de passe, ni l'identifiant
-- tapé. Un identifiant faux est souvent un mot de passe tapé dans le mauvais
-- champ ; le garder, ce serait conserver en clair la moitié d'un secret.
--
-- L'adresse est une donnée personnelle : la table suit la purge de
-- l'historique des actions (JOURNAL_RETENTION, la nuit), et le dump de la
-- base ne l'emporte pas — une trace d'exploitation de cette instance, qu'un
-- import ne doit ni effacer ni remplacer par celle d'une autre.
--
-- Table en ajout seul : pas de `modifie_le`, une ligne n'est jamais rééditée.
CREATE TABLE IF NOT EXISTS journal_connexion (
    id         BIGSERIAL   PRIMARY KEY,
    survenu_le TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- CONNEXION (réussie), ECHEC (mauvais identifiants) ou VERROUILLAGE
    -- (l'échec qui atteint CONNEXION_MAX_ECHECS et bloque l'adresse).
    evenement  TEXT        NOT NULL CHECK (evenement IN ('CONNEXION', 'ECHEC', 'VERROUILLAGE')),
    adresse    TEXT        NOT NULL
);

-- La lecture de l'écran (les plus récentes d'abord) et la purge par date.
CREATE INDEX IF NOT EXISTS idx_journal_connexion_recent
    ON journal_connexion (survenu_le DESC, id DESC);
