-- « Je ne pourrai pas venir » désactivable par édition : une organisation qui
-- ne veut pas de ce geste — le planning sera tenu tel quel — l'éteint depuis
-- Paramètres › Édition, carte « Guichets ».
--
-- Activé par défaut, comme la foire (V42) : le geste existe depuis V111 et
-- l'éteindre à la migration changerait le comportement de toute édition
-- existante. La ligne n'existe qu'une fois que l'organisation a décidé ; son
-- absence vaut « activé ».
--
-- Éteint, le geste disparaît partout : l'espace ne le propose plus et refuse
-- un envoi, et les signalements déjà reçus ne s'affichent plus, ni dans
-- l'espace ni sur l'écran Jour J. Ils restent en base : rallumer les rend tels
-- qu'ils étaient.
CREATE TABLE parametres_signalement (
    edition_id          VARCHAR(64) NOT NULL DEFAULT 'DEFAUT' REFERENCES edition (id) ON DELETE CASCADE,
    signalements_actifs BOOLEAN     NOT NULL DEFAULT TRUE,
    PRIMARY KEY (edition_id)
);
