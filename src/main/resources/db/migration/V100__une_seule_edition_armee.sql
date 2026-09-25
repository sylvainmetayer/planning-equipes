-- Une seule édition peut avoir ses envois de nuit armés à la fois.
--
-- Le planificateur balaie TOUTES les éditions et sert chacune qui est armée.
-- Deux éditions armées portant des créneaux le même jour écrivaient donc deux
-- fois la même nuit aux mêmes personnes — « vous êtes attendu·e demain » avec
-- deux plannings différents — et rien ne le voyait : la clé d'idempotence de
-- `notification_planifiee` inclut `edition_id`, chaque envoi était unique de
-- son côté.
--
-- La règle devient un invariant de la base, comme l'édition par défaut
-- (`idx_edition_defaut_unique`) : armer une seconde édition échoue, et
-- l'écran Paramètres demande de désarmer la première d'abord.
--
-- Une base où plusieurs éditions sont déjà armées ne pourrait pas recevoir
-- l'index. On garde armée l'édition par défaut si elle en fait partie, sinon
-- la première par id, et on désarme les autres : désarmer n'envoie rien, alors
-- qu'en garder deux continuerait de doubler les rappels. Réarmer une autre
-- édition reste un geste explicite sur l'écran Paramètres.
UPDATE parametres_notifications p
   SET actives = FALSE
 WHERE p.actives
   AND p.edition_id <> (
       SELECT q.edition_id
         FROM parametres_notifications q
         JOIN edition e ON e.id = q.edition_id
        WHERE q.actives
        ORDER BY e.defaut DESC, q.edition_id
        LIMIT 1);

CREATE UNIQUE INDEX IF NOT EXISTS idx_parametres_notifications_actives_unique
    ON parametres_notifications (actives) WHERE actives;
