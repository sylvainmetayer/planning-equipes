-- Retrait de la traçabilité de désactivation d'une contrainte (colonnes
-- ajoutées par V16).
--
-- La confirmation « Désactiver une règle légale ? », seul point d'entrée qui
-- alimentait ces colonnes, n'a jamais servi : personne ne saisit de motif, et
-- `modifie_par_utilisateur_id` ne pouvait de toute façon valoir que la
-- constante « ui » ou « mcp » faute d'authentification — donc ni imputable, ni
-- exploitable. Le dialogue, les colonnes et le code qui les portait sont
-- supprimés ensemble ; `constraint_toggle` redevient ce qu'elle était en V8 :
-- la seule liste des contraintes désactivées.

ALTER TABLE constraint_toggle
    DROP COLUMN IF EXISTS motif,
    DROP COLUMN IF EXISTS modifie_par_utilisateur_id,
    DROP COLUMN IF EXISTS modifie_le;
