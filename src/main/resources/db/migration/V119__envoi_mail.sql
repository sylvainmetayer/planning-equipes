-- Le résultat de chaque courriel adressé à un animateur, quel qu'il soit :
-- planning publié ou renvoyé, code d'accès, invitation à déclarer, relances
-- (à la main et de nuit), rappel de la veille, échanges, covoiturage,
-- absences. Ce que la colonne « Accusé de réception » de la page Animateurs lit
-- pour distinguer un échec d'envoi d'un silence, et ce que les relances lisent
-- pour ne pas réécrire à une adresse que le relais a refusée.
--
-- Écrite à un seul endroit, là où chaque courriel part (MailMetrics) : une
-- ligne par envoi, parti ou en échec. Un animateur sans adresse n'en produit
-- pas — rien n'est parti, et la fiche dit déjà qu'elle n'a pas d'adresse.
--
-- Minimisée : l'id de l'animateur, le modèle du courriel (`type`, un nom de
-- la liste fermée des modèles), l'état, la catégorie d'un échec et la date.
-- Ni adresse, ni contenu, ni message du serveur de messagerie, qui cite
-- couramment l'adresse refusée. L'identité se relit sur la fiche.
--
-- Pas de clé étrangère vers la fiche, comme `notification_planifiee` : la
-- ligne cascade avec l'édition, et la purge de nuit retire celles qui ont
-- dépassé JOURNAL_RETENTION.
CREATE TABLE envoi_mail (
    edition_id      VARCHAR(64) NOT NULL REFERENCES edition (id) ON DELETE CASCADE,
    id              BIGSERIAL,
    animateur_id    VARCHAR(64) NOT NULL,
    -- Le modèle du courriel, sans le préfixe « mail/ » : relance-confirmation,
    -- rappel-veille, planning-publie, code-acces…
    type            VARCHAR(64) NOT NULL,
    -- ENVOYE ou ECHEC.
    statut          VARCHAR(16) NOT NULL,
    -- RELAIS_INJOIGNABLE, AUTHENTIFICATION, ADRESSE_REFUSEE, TEMPORAIRE ou
    -- AUTRE, sur un échec seulement.
    categorie_echec VARCHAR(32),
    envoye_le       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (edition_id, id)
);

CREATE INDEX idx_envoi_mail_animateur ON envoi_mail (edition_id, animateur_id, envoye_le DESC);
