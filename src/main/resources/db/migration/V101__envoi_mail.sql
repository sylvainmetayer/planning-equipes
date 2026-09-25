-- Le résultat de chaque envoi de courriel à un animateur : parti, sans
-- adresse, ou en échec avec sa catégorie.
--
-- Sans lui, une personne dont le courriel a été refusé par le relais était
-- comptée « silencieuse » sur la page Animateurs, relancée la nuit suivante
-- vers la même adresse invalide, et rien ne suggérait de l'appeler ni de
-- corriger sa fiche.
--
-- C'est un JOURNAL, une ligne par envoi : le dernier envoi d'une personne se
-- lit comme la ligne la plus récente, et l'historique reste pour comprendre
-- un échec qui se répète. Minimisé comme `notification_planifiee` et
-- `journal_action` : l'identifiant de l'animateur, le type d'envoi, le
-- statut, la catégorie d'échec et la date — NI adresse NI contenu. L'adresse
-- se relit sur la fiche au moment où l'on en a besoin (docs/rgpd.md).
--
-- « Parti » veut dire « accepté par le relais », jamais « reçu » : un rejet
-- différé (boîte pleine, adresse inexistante signalée après coup) n'arrive
-- pas jusqu'ici. Et avec MAIL_MOCK rien ne part du tout.
--
-- Cloisonné par édition et supprimé avec elle ; supprimé aussi avec la fiche
-- de l'animateur, dont il ne dit rien d'autre.
CREATE TABLE IF NOT EXISTS envoi_mail (
    edition_id      TEXT        NOT NULL REFERENCES edition (id) ON DELETE CASCADE,
    id              BIGSERIAL   NOT NULL,
    animateur_id    TEXT        NOT NULL,
    type            TEXT        NOT NULL,
    statut          TEXT        NOT NULL CHECK (statut IN ('ENVOYE', 'SANS_EMAIL', 'ECHEC')),
    -- Renseignée pour un échec, et pour un échec seulement.
    categorie_echec TEXT        CHECK ((statut = 'ECHEC') = (categorie_echec IS NOT NULL)),
    envoye_le       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (edition_id, id),
    FOREIGN KEY (edition_id, animateur_id) REFERENCES animateur (edition_id, id) ON DELETE CASCADE
);

-- La seule lecture : le dernier envoi de chaque animateur de l'édition.
CREATE INDEX IF NOT EXISTS idx_envoi_mail_dernier
    ON envoi_mail (edition_id, animateur_id, envoye_le DESC, id DESC);
