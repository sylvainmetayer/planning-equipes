-- Le contact de l'organisation, affiché dans l'espace animateur : un
-- téléphone et une adresse e-mail, par édition (Paramètres › Édition).
--
-- Une ligne par édition, absente tant que rien n'a été saisi — même convention
-- que `parametres_notifications` : l'absence vaut « aucun contact affiché ».
-- Les deux colonnes sont facultatives : une organisation peut ne publier que
-- l'une des deux. Rien de nominatif n'est exigé ; c'est ce que l'organisation
-- choisit de donner à ses propres animateurs. La ligne disparaît avec
-- l'édition et la suit à la duplication.
CREATE TABLE contact_organisation (
    edition_id VARCHAR(64) PRIMARY KEY REFERENCES edition (id) ON DELETE CASCADE,
    telephone VARCHAR(40),
    email VARCHAR(254)
);
