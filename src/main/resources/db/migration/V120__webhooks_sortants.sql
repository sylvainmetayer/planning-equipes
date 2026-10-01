-- Webhooks sortants (ADR 0074) : ce que l'application annonce à un outil
-- extérieur — n8n, Slack, Discord, Matrix, Telegram — en plus du courriel.
--
-- Configuration d'INSTANCE, pas d'édition : aucune colonne edition_id. Le
-- payload porte l'édition dont il parle, et seule une édition autorisée à émettre
-- vers l'extérieur en produit (OutboundEditionPolicy) ; rien n'est donc à
-- reconfigurer quand une autre édition prend le relais. Pour la même raison,
-- la duplication d'une édition n'a rien à recopier ici.
--
-- Les deux tables restent hors de l'export SQL (DatabaseDumpService) : des
-- secrets et l'état d'une machine, pas le jeu de données. La sauvegarde
-- nocturne, elle, prend tout le cluster : c'est pourquoi le secret est chiffré
-- (AES-GCM, clé WEBHOOKS_SECRET_KEY) avant d'arriver ici — un dump n'en
-- contient que le chiffré.

CREATE TABLE webhook (
    id              VARCHAR(36)  PRIMARY KEY,
    nom             VARCHAR(120) NOT NULL,
    format          VARCHAR(20)  NOT NULL,
    -- L'adresse en clair, pour le format générique seulement : pour Slack,
    -- Discord et Matrix l'adresse EST le secret, et Telegram a une adresse fixe.
    url             TEXT,
    -- Chiffré : le secret HMAC (générique), l'adresse entière (Slack, Discord,
    -- Matrix) ou le jeton du bot (Telegram).
    secret_chiffre  TEXT         NOT NULL,
    chat_id         VARCHAR(100),
    evenements      TEXT[]       NOT NULL DEFAULT '{}',
    actif           BOOLEAN      NOT NULL DEFAULT TRUE,
    cree_le         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    modifie_le      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT webhook_format_connu
        CHECK (format IN ('GENERIC', 'SLACK', 'DISCORD', 'MATRIX', 'TELEGRAM')),
    CONSTRAINT webhook_nom_renseigne CHECK (length(btrim(nom)) > 0)
);

-- Une ligne par événement et par webhook abonné. Le corps est rendu au moment
-- de l'envoi depuis `payload` (l'enveloppe neutre) : un secret régénéré signe
-- donc aussi ce qui attendait. Jamais la réponse du récepteur : son code et sa
-- durée, pas son corps. Purgée au-delà de 30 jours par le balayage de nuit.
-- JSON et non JSONB : l'ordre des clés est gardé tel qu'écrit, celui que le
-- récepteur lira.
CREATE TABLE webhook_livraison (
    id                    UUID         PRIMARY KEY,
    webhook_id            VARCHAR(36)  NOT NULL REFERENCES webhook (id) ON DELETE CASCADE,
    evenement             VARCHAR(60)  NOT NULL,
    payload               JSON         NOT NULL,
    tentative             INT          NOT NULL DEFAULT 0,
    statut                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    code_http             INT,
    duree_ms              BIGINT,
    erreur                VARCHAR(500),
    prochain_essai        TIMESTAMPTZ,
    -- Le bail d'un envoi en cours : l'essai immédiat et le balayage d'une
    -- minute ne prennent jamais la même ligne, et un bail expiré (un
    -- redémarrage au milieu d'un envoi) la rend au balayage suivant.
    en_cours_depuis       TIMESTAMPTZ,
    derniere_tentative_le TIMESTAMPTZ,
    cree_le               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT webhook_livraison_statut_connu
        CHECK (statut IN ('PENDING', 'DELIVERED', 'FAILED', 'ABANDONED'))
);

CREATE INDEX idx_webhook_livraison_dues ON webhook_livraison (statut, prochain_essai);
CREATE INDEX idx_webhook_livraison_journal ON webhook_livraison (webhook_id, cree_le DESC);
