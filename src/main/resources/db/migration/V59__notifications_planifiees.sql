-- Les envois de nuit : rappel de la veille (#298), relance des non-confirmés
-- (#299) et alerte sur les demandes d'échange qui dorment (#300).
--
-- Deux tables, et le partage entre elles est le point de conception : l'une dit
-- CE QU'ON A LE DROIT D'ENVOYER, l'autre CE QU'ON A DÉJÀ ENVOYÉ.
--
-- 1. `parametres_notifications` — un réglage par édition, comme les trois
--    autres tables de paramètres.
--
--    `actives` est à FALSE par défaut, et ce défaut est le garde-fou entier de
--    la fonctionnalité. `edition` ne porte ni dates, ni drapeau « en cours » :
--    un job n'a donc AUCUN moyen de deviner à qui il est légitime d'écrire, et
--    sans ce booléen la première nuit après le déploiement enverrait un rappel
--    « vous êtes de service demain » aux bénévoles de l'édition de l'an
--    dernier. L'armement est un geste explicite, pris édition par édition, sur
--    l'écran Paramètres.
--
--    Conséquence assumée : la duplication d'une édition ne recopie PAS cette
--    table (voir `EditionRepository.TABLES_A_COPIER`). Une édition neuve naît
--    muette et quelqu'un doit décider de l'armer.
--
-- 2. `notification_planifiee` — le journal de ce qui est parti.
--
--    La clé primaire `(edition_id, type, cle)` EST le mécanisme
--    d'idempotence : un job insère avant d'envoyer, en ON CONFLICT DO NOTHING,
--    et n'envoie que si l'insertion a écrit une ligne. Deux exécutions le même
--    jour, un redémarrage au milieu, deux nœuds : personne ne reçoit deux fois
--    le même message. La `cle` est composée par le job (animateur + date pour
--    un rappel, id de demande pour une alerte).
--
--    `libelle` non nul distingue les lignes qui sont AUSSI une alerte à montrer
--    sur l'écran Notifications ; les autres ne sont qu'un verrou. On ne stocke
--    ici que des identifiants, jamais un nom ni une adresse : l'écran résout
--    l'identité depuis le référentiel au moment de la lecture, et le journal
--    reste lisible sans exposer qui que ce soit (docs/rgpd.md).

CREATE TABLE IF NOT EXISTS parametres_notifications (
    edition_id                   TEXT    NOT NULL PRIMARY KEY
                                         REFERENCES edition (id) ON DELETE CASCADE,
    actives                      BOOLEAN NOT NULL DEFAULT FALSE,
    -- Heure locale à partir de laquelle le rappel de la veille peut partir.
    heure_rappel_veille          TIME    NOT NULL DEFAULT '18:00',
    -- Délai après la publication au bout duquel un silence devient une relance.
    delai_relance_heures         INTEGER NOT NULL DEFAULT 72,
    -- Ancienneté d'une demande d'échange en attente qui déclenche l'alerte.
    anciennete_echange_jours     INTEGER NOT NULL DEFAULT 3
);

CREATE TABLE IF NOT EXISTS notification_planifiee (
    edition_id   TEXT        NOT NULL REFERENCES edition (id) ON DELETE CASCADE,
    type         TEXT        NOT NULL,
    cle          TEXT        NOT NULL,
    declenche_le TIMESTAMPTZ NOT NULL DEFAULT now(),
    libelle      TEXT,
    severite     TEXT,
    animateur_id TEXT,
    PRIMARY KEY (edition_id, type, cle)
);

-- L'écran Notifications lit « les alertes récentes de cette édition » : c'est
-- le seul accès qui ne passe pas par la clé primaire.
CREATE INDEX IF NOT EXISTS idx_notification_planifiee_alertes
    ON notification_planifiee (edition_id, declenche_le DESC)
    WHERE libelle IS NOT NULL;
