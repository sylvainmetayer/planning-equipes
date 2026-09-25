-- Affichage mural : l'écran de la salle de contrôle, ouvert par un lien dédié
-- plutôt que par une session admin laissée sur une TV sans surveillance
-- (ADR 0053).
--
-- Un lien = un jeton qui ne donne qu'une lecture — la vue murale d'une
-- édition — et qui se révoque. Le jeton n'est stocké que haché (SHA-256), comme
-- les sessions de l'espace animateur : une copie de la base ne rouvre aucun
-- écran, et le lien n'est montré qu'une fois, à sa création.
--
-- `noms_complets` affiche le nom en entier au lieu du prénom et de l'initiale —
-- une minimisation par défaut, sur un écran que voit toute personne présente
-- dans la pièce.
--
-- Rattaché à une édition, et supprimé avec elle.
CREATE TABLE lien_affichage_mural (
    edition_id       VARCHAR(64) NOT NULL REFERENCES edition (id) ON DELETE CASCADE,
    id               BIGSERIAL,
    token_hash       VARCHAR(64) NOT NULL,
    libelle          TEXT        NOT NULL,
    noms_complets    BOOLEAN     NOT NULL DEFAULT FALSE,
    -- Vrai quand le lien a été créé pour quelques emplacements (une TV par
    -- zone) : l'écran ne montre alors que ceux de la table ci-dessous, et plus
    -- rien s'ils ont tous été supprimés — jamais, en silence, toute l'édition.
    restreint        BOOLEAN     NOT NULL DEFAULT FALSE,
    cree_le          TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoque_le       TIMESTAMPTZ,
    dernier_acces_le TIMESTAMPTZ,
    PRIMARY KEY (edition_id, id)
);

-- Résolution globale : le jeton arrive sur une URL publique, sans en-tête
-- d'édition à croire, et désigne son édition à lui seul.
CREATE UNIQUE INDEX idx_lien_affichage_mural_token ON lien_affichage_mural (token_hash);

-- Les emplacements d'un lien restreint, sous clé étrangère : un emplacement
-- supprimé sort du filtre, et une renumérotation des identifiants les suit.
CREATE TABLE lien_affichage_mural_emplacement (
    edition_id     VARCHAR(64) NOT NULL,
    lien_id        BIGINT      NOT NULL,
    emplacement_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (edition_id, lien_id, emplacement_id),
    FOREIGN KEY (edition_id, lien_id) REFERENCES lien_affichage_mural (edition_id, id) ON DELETE CASCADE,
    FOREIGN KEY (edition_id, emplacement_id) REFERENCES emplacement (edition_id, id) ON DELETE CASCADE
);
