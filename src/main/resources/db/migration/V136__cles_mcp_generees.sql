-- Clés d'API MCP générées depuis l'écran MCP (ADR 0097). Instance entière, hors
-- édition. Jamais la clé : son empreinte SHA-256 seulement, la clé n'est montrée
-- qu'une fois, à la génération.
--
-- Une ligne GENEREE est valable tant que expire_le est NULL (la clé en cours)
-- ou futur (une ancienne, en recouvrement). Une ligne CONFIG est le repère de
-- retrait de la clé de configuration (PLANNING_MCP_API_KEY), posé à la première
-- génération : elle ne vaut plus qu'à l'intérieur de son recouvrement.
CREATE TABLE mcp_cle (
    id        TEXT PRIMARY KEY,
    origine   TEXT NOT NULL CHECK (origine IN ('GENEREE', 'CONFIG')),
    empreinte TEXT NOT NULL UNIQUE,
    cree_le   TIMESTAMPTZ NOT NULL,
    expire_le TIMESTAMPTZ
);
