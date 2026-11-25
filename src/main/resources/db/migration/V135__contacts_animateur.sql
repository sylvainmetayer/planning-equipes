-- Le contact d'urgence et le représentant légal d'un animateur : deux noms et deux
-- téléphones, facultatifs, dans une table à part.
--
-- À part, et non en colonnes de `animateur` : l'animateur est un fait du problème
-- du solveur, sérialisé par le scénario YAML, les exports, le MCP, l'espace des
-- collègues. Une donnée qui ne doit sortir dans aucun d'eux ne se range pas dans
-- le type qu'ils lisent tous. La fiche supprimée emporte ses contacts (cascade) :
-- c'est la purge, il n'y en a pas d'autre.
--
-- Aucune ligne n'est créée tant que personne n'a rien saisi.

CREATE TABLE animateur_contact (
    edition_id VARCHAR(64) NOT NULL,
    animateur_id VARCHAR(64) NOT NULL,
    urgence_nom VARCHAR(100),
    urgence_telephone VARCHAR(30),
    representant_nom VARCHAR(100),
    representant_telephone VARCHAR(30),
    modifie_le TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (edition_id, animateur_id),
    FOREIGN KEY (edition_id, animateur_id) REFERENCES animateur (edition_id, id) ON DELETE CASCADE
);
