-- « Exclure à la publication » disparaît : une publication prévient désormais
-- toutes les personnes concernées. Seul « prévenir seulement quelques
-- personnes » (les deux personnes d'un échange accepté, un remplacement) reste,
-- et diffère les autres de la même façon.
--
-- Les exclusions en cours sont ignorées : la 3.x laissait déjà la personne
-- différée à prévenir (son repère de plan ne bouge pas), la prochaine
-- publication l'écrit donc à tout le monde, elle comprise. Rien n'est perdu
-- pour autant : chaque personne dont le dernier envoi de publication est un
-- report (`EXCLU`) est d'abord recopiée dans
-- `exclusion_publication_avant_4_0`, une archive que l'application ne lit ni
-- n'exporte et que la 5.0 supprimera (retour à la 3.x possible d'ici là). La
-- 3.x ne distinguait pas un report demandé par exclusion d'un report par
-- « prévenir seulement quelques personnes » : l'archive les garde tous.
--
-- L'archive ne porte qu'un identifiant, comme `envoi_planning` : elle part
-- avec l'édition (purge annuelle) ou avec la fiche de la personne.
CREATE TABLE exclusion_publication_avant_4_0 (
    edition_id VARCHAR(64) NOT NULL REFERENCES edition(id) ON DELETE CASCADE,
    animateur_id VARCHAR(64) NOT NULL,
    snapshot_id BIGINT,
    differe_le TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (edition_id, animateur_id),
    CONSTRAINT exclusion_publication_avant_4_0_animateur_fkey
        FOREIGN KEY (edition_id, animateur_id) REFERENCES animateur (edition_id, id) ON DELETE CASCADE
);

INSERT INTO exclusion_publication_avant_4_0 (edition_id, animateur_id, snapshot_id, differe_le)
SELECT dernier.edition_id, dernier.animateur_id, dernier.snapshot_id, dernier.envoye_le
FROM (
    SELECT DISTINCT ON (edition_id, animateur_id)
           edition_id, animateur_id, snapshot_id, statut, envoye_le
    FROM envoi_planning
    WHERE nature = 'PUBLICATION'
    ORDER BY edition_id, animateur_id, envoye_le DESC, id DESC
) dernier
JOIN animateur a ON a.edition_id = dernier.edition_id AND a.id = dernier.animateur_id
WHERE dernier.statut = 'EXCLU';

-- Une alerte par personne, à l'accueil : l'identifiant seul, jamais le nom
-- (il est joint à la lecture, depuis la fiche). `cle` est l'identifiant, donc
-- rejouer la migration n'en écrirait pas deux.
INSERT INTO notification_planifiee (edition_id, type, cle, declenche_le, libelle, severite, animateur_id)
SELECT edition_id, 'EXCLUSION_PUBLICATION_RETIREE', animateur_id, now(),
       'Exclusion de publication retirée en 4.0 : cette personne était différée, elle recevra son planning à la prochaine publication.',
       'WARNING', animateur_id
FROM exclusion_publication_avant_4_0
ON CONFLICT DO NOTHING;
