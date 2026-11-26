# Jour J, banc de touche et affichage mural côté API

Vous saurez ce que répondent les routes qui servent le jour même de l'événement : l'écran Aujourd'hui et ses absences, le banc de touche, l'horloge simulée et l'affichage mural. Les lectures de capacité sont dans [faisabilité, fragilité et pauses côté API](api-faisabilite-et-pauses.md), l'espace des animateurs dans [l'espace animateur côté API](api-espace-animateur.md).

## Qu'est-ce qu'une journée en cours

La borne d'une journée n'est ni minuit ni une heure d'exploitation fixe : c'est le premier créneau réellement programmé. La journée en cours à un instant donné est la plus récente dont le premier créneau a commencé et dont le dernier n'est pas terminé.

- Un créneau appartient à la journée que son début ouvre, et à elle seule. Un créneau 22h-2h est du soir qui l'ouvre.
- À 1h du matin, tant que ce créneau tourne, la journée en cours est celle de la veille : `GET /api/jour-j` sans paramètre répond `date` = la veille.
- Avant le premier créneau ou après le dernier, aucune journée n'est en cours et la date du calendrier répond.
- Une date sans créneau n'a pas de journée : l'écran répond `creneauxDuJour: 0` et des listes vides, et `POST /api/jour-j/absences` y est refusé (`400`).

`date` et `maintenant` valent par défaut la journée en cours et l'horloge du serveur (`JourJClock`, date figée comprise). `maintenant` est un instant complet (`AAAA-MM-JJTHH:MM`), parce qu'une journée qui déborde de minuit met les deux sur des dates différentes : à `2026-07-09T01:00`, la journée regardée est `2026-07-08`. La réponse renvoie `maintenant`, pour que l'écran énonce l'heure du serveur et non celle du téléphone. Lire une journée qui n'est pas la journée en cours la lit en entier.

## L'écran Aujourd'hui et les absences

`GET /api/jour-j` rend, en lecture seule, les créneaux restants, les animateurs de service (avec le téléphone de la fiche), les places vides, les absences et signalements du jour, les stands ouverts, les alertes du mural, `aPrevenir` (les destinataires de la prochaine publication, différés compris) et les échanges à arbitrer. Une place vide est `nouveau` quand le plan publié avait quelqu'un dessus, `resteDuCreneau` porte la suite d'un siège scindé.

`POST /api/jour-j/absences` déclare une absence (`{ "animateurId": "A1", "raison": "…" }`, `creneauId` facultatif) : une `INDISPONIBILITE_FORCEE` par créneau restant, et les sièges tenus sur ces créneaux sont vidés. `DELETE /api/jour-j/absences/{animateurId}` l'annule, un créneau si `creneauId` est donné, toute la journée sinon. Le remplacement passe par `POST /api/jour-j/postes/{posteId}/suggestions` : l'assistant de réparation y répond sur le plan enregistré, préparé comme l'écriture qui suit le lira (contraintes désactivées, poids, quotas de l'édition), si bien qu'il ne propose aucun candidat que l'écriture refuserait ensuite.

| Règle | Raison |
| --- | --- |
| La portée d'une absence est l'ensemble des créneaux restants, dont la **fin** est postérieure à l'heure de référence (le créneau en cours en fait partie) | Les créneaux terminés ont été tenus : les transformer en indisponibilités ferait mentir le planning sur ce qui s'est passé |
| Un animateur n'est « déjà absent » que si une indisponibilité couvre un créneau encore devant | Une indisponibilité du matin, saisie des semaines plus tôt, n'empêche pas de le marquer absent pour la suite |
| Tout ou rien : contradictions et verrous sont vérifiés sur toute la portée avant la moindre écriture | Un refus sur le troisième créneau ne doit pas laisser les deux premiers écrits |
| Annuler ne rend pas les sièges | Qui tient un siège est une décision : supposer que l'ancien occupant le récupère effacerait un remplacement appliqué entre-temps |
| L'annulation ne supprime que ce que l'écran a écrit, reconnu à l'id dérivé du couple (animateur, créneau), et seulement les exceptions qui ne visent que cet animateur | Sinon elle emporterait une indisponibilité de longue date saisie dans Ajustements manuels, et élargirait en silence ce que la prochaine résolution peut faire |

Deux refus, tous deux en `400` : une `AFFECTATION_FORCEE` sur un créneau visé (la contradiction de [`ContrainteAdHocContradictions`](exceptions-ad-hoc.md), dont le message nomme les deux exceptions), ou un siège à libérer couvert par un verrou (le message nomme le stand et l'heure et invite à le lever d'abord). Chaque exception écrite porte `raison`, `creeParUtilisateurId` (le compte admin de la session, `jour-j` à défaut) et `creeLe`, et se retrouve sur la page Ajustements manuels. Rien ici ne lance de résolution ni n'envoie de courriel : l'écran affiche le compte de la [publication](plan-verrous-et-publication.md) et renvoie vers son bouton.

## Banc de touche

`GET /api/banc-de-touche/{creneauId}` (paramètres `standId` et `posteId`) dit qui n'est pas de service sur un créneau et pourquoi il ne pourrait pas l'être. `GET /api/banc-de-touche` sans créneau répond sur le premier créneau que le plan pourvoit et le nomme dans `creneauId`. La réponse lit le dernier plan enregistré, sans solveur. L'écran envoie `standId` et `posteId` à chaque appel, vides quand ils ne s'appliquent pas : le serveur lit une valeur vide comme absente.

Ne rien avoir à dire n'est pas une erreur. Le référentiel porte légitimement plus de créneaux que le plan (aucun stand ouvert, ou créneau dérivé après la dernière résolution) : un `404` ouvrait alors l'écran sur « Créneau inconnu » pour un créneau bien réel. Le champ `statut` distingue trois cas, et `404` reste réservé à un créneau absent de l'édition.

| `statut` | Sens |
| --- | --- |
| `NO_PLAN` | Rien n'est enregistré (aussi le cas quand aucun créneau n'est pourvu) |
| `NO_SEAT` | Le plan existe, mais sans siège sur ce créneau ou ce stand |
| `EVALUATED` | Un siège a été sondé : la liste et ses motifs |

Hors `EVALUATED`, `posteCibleId`, `standCibleId` et `animateurCibleId` sont nuls, les listes vides et `seatStarted` faux. Sur `EVALUATED`, `seatStarted` dit que le créneau du siège est déjà commencé au sens du [passé figé](repo:docs/decisions/0044-le-passe-est-fige.md) lu sur l'horloge du serveur (toujours faux quand `PASSE_FIGE=false`) : le dialogue n'offre alors « Placer » à personne, l'écriture le refuserait. `creneauxAvecSieges` liste les créneaux que le plan pourvoit, pour ne proposer que des réponses non vides d'avance. Le siège évalué est `posteCibleId` : celui que `posteId` désigne, sinon le premier siège libre du créneau (restreint à `standId` s'il est fourni), sinon son premier siège, auquel cas la question devient « qui pourrait le remplacer ? » et `animateurCibleId` nomme l'occupant.

Les raisons ne sont pas réécrites ici. Chacune porte le `contrainte` d'une règle réellement appliquée, avec le `niveau`, la `categorie` et la `description` de `GET /api/constraints`. Elles viennent de deux sources et d'aucune troisième :

- ce que le couple (poste, animateur) décide à lui seul (indisponibilité, stand réservé aux majeurs, jour férié, nuit, plafonds mineurs d'un créneau isolé) est lu dans le filtre d'éligibilité du solveur, celui de l'assistant de réparation ;
- ce qui dépend du reste du plan (plafonds quotidiens et hebdomadaires, repos, pauses, chevauchements, encadrement d'un mineur, appréciation manquante) est obtenu en posant le siège à chaque candidat et en demandant aux contraintes ce qui a empiré. Aucun seuil n'est recopié.

**Empiré par rapport au siège vide, jamais à son occupant.** Comparé à l'occupant, un candidat qui casse la règle que l'occupant casse déjà laisse le total inchangé, et l'écran répondait « rien contre lui » : un animateur déjà de service ailleurs à cette heure sortait « Disponible ». Le siège est donc vidé avant de sonder. Chaque ligne porte deux verdicts :

| Champ | Sens |
| --- | --- |
| `disponible` | Aucune règle dure ne s'oppose à l'affectation, siège vide pour référence. C'est ce que l'écran affiche |
| `degradeLePlan` | Le score dur du plan serait réellement plus mauvais qu'aujourd'hui |

`disponible` implique `!degradeLePlan`, jamais l'inverse : reprendre le siège de quelqu'un qui casse déjà une règle peut en casser une autre sans que le plan empire. `delta` chiffre l'écart au plan actuel, ce qui suppose de sonder aussi l'occupant.

La garantie face à l'assistant de réparation tient en une implication : tout animateur `disponible` est un candidat que `suggererReparations` propose sur le même siège (`CreneauAvailabilityCoherenceTest`). La réciproque est fausse volontairement : le second filtre de l'assistant lit les écarts du siège match par match, et le reproduire ici dupliquerait cette analyse. Tous les motifs applicables sont listés, pas seulement le plus bloquant : savoir que lever l'indisponibilité en laisserait trois autres derrière aide à décider s'il faut négocier.

« Compétence absente » n'est pas une exclusion : l'appréciation est une contrainte medium (`appreciationIncompatible`), donc un motif de niveau `MEDIUM` sur une ligne qui peut rester `disponible`. Un renfort (typologie polyvalente) n'en porte jamais, `Animateur.hasCompetenceFor` le jugeant compétent partout.

## Figer la date du jour

`/api/horloge` remplace la date que le serveur tient pour « aujourd'hui », afin de tester jour J hors événement. L'écran est la carte « Date et heure simulées » de Paramètres, onglet Instance.

`GET /api/horloge` rend `{ "dateDuJour", "heureDuJour", "modifiable" }`, `PUT /api/horloge` fige la date et, si elle est donnée, l'heure. Une date vide ou `null` rend la main à l'horloge de la machine, heure comprise. Une heure sans date répond `400` (sur la date de la machine, elle glisserait d'un jour à minuit).

Le `PUT` répond `400` sur toute instance lancée ni avec `quarkus:dev` ni avec `HORLOGE_SIMULEE_AUTORISEE=true`, variable à `false` par défaut et à y laisser en production : un mock activable sur un vrai événement ferait mentir l'écran jour J. Le garde-fou est côté serveur, sur l'écriture ; `modifiable` ne sert qu'à masquer un contrôle inutilisable. Il porte aussi sur la lecture : la valeur vit dans une ligne ordinaire que des chemins hors de l'application peuvent amener (restauration `pg_dump`, volume recopié, session `psql`, voir [la décision 0015](repo:docs/decisions/0015-sauvegarde-par-pg-dump-restauration-hors-application.md)). Là où l'horloge simulée n'est pas autorisée, la ligne est donc ignorée. Le dump applicatif n'en fait pas partie : `horloge_jour_j` est hors des tables de `/api/database/export`, et l'import la refuse (`400`).

Ce que la date figée remplace :

- le jour regardé par l'écran jour J, ses créneaux restants, la portée d'une absence ;
- la frontière des consignes ;
- le passé que le solveur fige ([décision 0044](repo:docs/decisions/0044-le-passe-est-fige.md)) : les places des créneaux commencés à cet instant sont reprises du plan enregistré et épinglées, ce qui permet de répéter une résolution « pendant l'événement » hors saison ;
- le repère du jour de l'espace animateur, calculé dans le navigateur : `GET /api/espace-animateur/{jeton}` porte `dateDuJourFigee` et `heureDuJourFigee` (nuls là où l'horloge simulée n'est pas autorisée) que la page substitue à la date du téléphone.

Une heure fixe donne le même 14h30 à chaque rechargement ; sans heure, les créneaux passent derrière au fil de l'après-midi. Restent hors de portée, volontairement : le statut mineur ou majeur (dérivé de la date de naissance contre la date du créneau, un mock pourrait faire travailler un mineur de nuit), l'horodatage de ce qui est écrit (`creeLe`, dates de publication et d'instantané), la sauvegarde de nuit, les travaux du solveur et les codes de l'espace. Le réglage est persisté en table `horloge_jour_j`, non cloisonnée par édition (c'est l'horloge du serveur), pour survivre à un rechargement à chaud.

## Affichage mural

L'écran de la salle de contrôle (`/mural/:jeton`) lit `GET /api/mural/{token}` chaque minute, sans session : le jeton est la clé et n'ouvre que cette lecture ([décision 0053](repo:docs/decisions/0053-affichage-mural-par-jeton-dedie.md), [sécurité](../installer/securite.md)). Les liens se gèrent sous `/api/affichage-mural`, derrière la session admin.

- Le jeton n'est rendu qu'une fois, dans la réponse `201` de `POST /api/affichage-mural` : la base n'en garde que l'empreinte SHA-256. Le QR code se demande à la création (`POST /api/affichage-mural/qr-code`, une grille de modules que l'écran dessine en SVG) ; plus tard il n'y a plus d'adresse à coder. `DELETE /api/affichage-mural/{id}` révoque.
- Un jeton inconnu et un jeton révoqué répondent le même `404`, au mot près.
- Le plan lu est le plan persisté, pas le plan publié : un remplacement fait en jour J apparaît à la lecture suivante, sans republication.
- « Maintenant » est l'heure du serveur, et la journée suit la règle ci-dessus : à 1h, la vacation 22h-2h est encore celle de la veille, et en cours.
- Toute la journée voyage, pas seulement la vacation en cours et la suivante : l'écran les choisit contre `now`, avancé du temps écoulé depuis la lecture, si bien qu'une vacation se termine à sa minute sans attendre la lecture suivante. `?impression=1` montre la journée entière.
- Une vacation regroupe les sièges d'un stand qui partagent la même fenêtre effective ; une fin qui n'est pas après le début tombe le lendemain.
- Les alertes sont les places libres d'une vacation en cours ou qui commence dans les deux heures, et les pauses sans relais du jour pas encore finies, lues dans le même rapport que l'écran Pauses.
- Une consigne « jusqu'à minuit » n'a pas de fin : `closedUntil` est `null` et l'écran écrit « minuit ».
- Un lien restreint à des emplacements le reste : si ces emplacements sont supprimés, l'écran n'affiche plus rien, jamais toute l'édition sans prévenir.
- `lastAccessAt` est tamponné au plus une fois par minute : il dit qu'un écran lit, pas combien de fois.
- Importer un dump SQL supprime tous les liens : ils ne voyagent pas dans l'export et l'import vide la table `edition`, dont la suppression en cascade les emporte. Après un import, chaque écran affiche « lien inconnu ou révoqué » ; recréez les liens depuis Paramètres.

L'impression côté admin ne passe par aucun jeton : `GET /api/affichage-mural/apercu?date=AAAA-MM-JJ` rend la même vue, sous la session admin, avec tous les stands et les noms complets que la page Planning montre déjà (la journée en cours sans `date`, `400` sur une date invalide). Elle vit sous `/api/affichage-mural` et jamais sous `/api/mural/` : ce préfixe est ce qu'un proxy laisse passer sans authentification et il ne doit nommer qu'une route.
