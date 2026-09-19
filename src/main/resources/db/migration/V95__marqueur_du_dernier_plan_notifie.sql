-- Exclure quelqu'un d'une publication sans le perdre de vue (issue #503).
--
-- Jusqu'ici, « ce qu'on a annoncé » était une seule référence pour tout le
-- monde : le dernier instantané publié. Différer le message d'une personne —
-- « on ne prévient pas Untel ce soir, on l'appelle d'abord » — était donc
-- impossible à tenir : la publication suivante comparait son planning à un
-- instantané qu'elle n'avait jamais reçu, et son écart disparaissait sans que
-- personne ne lui ait rien dit.
--
-- La référence devient donc **par personne** : `plan_notifie_id` porte
-- l'instantané dont le contenu a réellement été annoncé à cet animateur. Une
-- publication le déplace pour tout le monde sauf pour les exclus, qui restent
-- ainsi « à prévenir » au tour suivant, avec l'écart cumulé depuis leur
-- dernier message.
--
-- NULL = jamais prévenu, ce qui est exactement une première diffusion. La
-- reprise lit la trace de publication déjà écrite (`publication_destinataire`)
-- plutôt que de repartir de zéro : sans elle, la première publication après
-- cette migration réécrirait aux cent cinquante personnes comme si aucune ne
-- savait rien.
--
-- Pas de clé étrangère, pour la raison de la V94 : un instantané supprimé
-- laisse une référence qui ne désigne plus rien, et la lecture retombe alors
-- sur « rien à comparer » plutôt que d'inventer un plan.
ALTER TABLE animateur ADD COLUMN plan_notifie_id BIGINT;

COMMENT ON COLUMN animateur.plan_notifie_id IS
    'Instantané dont le contenu a été annoncé à cette personne ; NULL tant qu''elle n''a jamais été prévenue';

UPDATE animateur a
SET plan_notifie_id = (
    SELECT max(d.snapshot_id)
    FROM publication_destinataire d
    WHERE d.edition_id = a.edition_id
      AND d.animateur_id = a.id
);
