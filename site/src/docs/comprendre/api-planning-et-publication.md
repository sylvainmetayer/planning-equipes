# Consignes, publication et gel : ce que l'API garantit

Vous saurez poser, simuler et lever une consigne par l'API, prévisualiser puis publier un plan sans prévenir tout le monde, lire qui a reçu quelle version et qui a confirmé, figer une famille du référentiel, et lire ce qui a bougé sur une journée. Le schéma donne la forme des appels ; ce qui suit est ce qu'il ne dit pas.

## Consignes

Une consigne ferme une bande horaire pour **tous** les stands d'une date et rouvre ceux qu'on nomme sur des fenêtres de compensation. Le modèle est décrit dans [les horaires et les consignes](horaires-et-fenetres-des-stands.md), le raisonnement dans l'[ADR 0043](repo:docs/decisions/0043-consigne-d-edition-fermer-une-bande-sans-rien-detruire.md).

![Préremplir, simuler, poser et lever une consigne](repo:docs/diagrammes/consigne.svg)

<sub>Source : [`diagrammes/consigne.puml`](repo:docs/diagrammes/consigne.puml).</sub>

**Jours à venir seulement.** Poser, modifier et lever refusent (`400`) une date qui n'est pas strictement après « aujourd'hui », celui de l'horloge du jour J (donc de la date figée par `/api/horloge` en développement, démonstration et recette). `GET /api/consignes` renvoie `aujourdhui` pour que l'écran calcule la même frontière que le serveur. Une date déjà travaillée garde pour toujours la consigne qui l'a gouvernée, et une date sans créneau est refusée (« rien à fermer ce jour-là »).

**Une même requête pose, remplace ou prolonge.** `POST /api/consignes` prend des dates : une date libre reçoit la consigne, une date déjà sous consigne la voit **remplacée en place** (`dejaSousConsigne` dans l'aperçu), et prolonger une alerte est le même appel avec des dates en plus. Un créneau que la consigne précédente avait ajouté est conservé, sièges compris, quand une fenêtre l'exige encore exactement, et retiré sinon (`creneauxARetirer`). Les dates d'une requête s'écrivent en **une seule transaction** : un arrêté sur cinq jours dont la troisième date échoue ne laisse rien des deux premières. Les heures s'acceptent en `HH:mm` ; une fin absente ou à `00:00` se lit « jusqu'à minuit » et **s'enregistre comme fin ouverte** (la bande, une fenêtre par défaut ou une ouverture : `00:00` écrit, `null` relu, sur l'écran, en MCP et à l'import de scénario pareillement).

**L'aperçu vaut validation complète, sans écriture.** `POST /api/consignes/apercu` rejoue toute la validation (bande, motif, fenêtres hors de la bande, stands connus, effectifs sous le maximum du stand, un stand par fenêtre) et rend une ligne par date : sièges et minutes avant et après, créneaux à ajouter et à retirer, vacations qui perdent leurs sièges, stands entrants et sortants, exceptions cochées, mineurs et majeurs disponibles, validation retirée, verrous et règles ad hoc qui nomment une vacation de la bande, personnes du plan enregistré assises dans la bande. `POST /api/consignes` renvoie ce même aperçu, calculé **avant** l'écriture, car une fois les créneaux ajoutés plus rien ne les distingue de la grille nominale. `creneauxDuJour` lève une ambiguïté : sans lui, une date où l'édition n'a pas de grille se lirait comme une date que la consigne a vidée. L'effectif est celui de **l'ouverture**, non du stand : un même stand rouvert le matin à 2 et le soir à 7 porte deux ouvertures, chacune avec le sien ; absent, il hérite du plus fort effectif que le stand perd dans la bande, sinon de son minimum. Les deux écritures sont refusées (`409`) pendant un calcul de l'édition, comme celles du référentiel.

**La présélection écarte sans interdire.** `POST /api/consignes/preselection` propose cochés les stands qui perdent des minutes dans la bande. Un stand qui a posé ses horaires à la main ce jour-là (`exceptionDatee`, avec son `motif`) est écarté mais reste cochable : quelqu'un a décidé quelque chose pour cette date, et la consigne ne le contredit pas en silence. `ouvertures` dit ce que la consigne existante de la date ouvre déjà sur ce stand.

**Lever supprime les créneaux ajoutés, sièges compris.** C'est la seule suppression de la fonctionnalité, et elle est annoncée : `POST /api/consignes/levee/apercu` nomme les vacations qui partent et compte les personnes qui y sont assises, et la publication suivante les dit « retirées » depuis l'instantané publié, comme toute vacation disparue de la grille. Les vacations nominales de la bande n'ont jamais bougé : elles retrouvent leurs sièges à la levée, et la stabilité du plan publié rend l'après-midi à son titulaire au calcul suivant.

**L'aperçu de la levée avertit quand les journées figées vident la semaine** ([ADR 0089](repo:docs/decisions/0089-la-levee-avertit-quand-les-journees-figees-vident-la-semaine.md)). Une levée rend à la journée sa grille nominale, mais un calcul complet fige tout siège qu'un verrou couvre et tout jour passé. Quand la levée ajoute des minutes-siège et qu'au moins un animateur disponible ce jour-là tient déjà un siège figé sur chacun des six autres jours de sa semaine ISO, l'aperçu le compte (`animateursSansJourLibre`), nomme les journées verrouillées `JOUR` à rouvrir (`joursADeverrouiller`) et dit combien elles en libèrent (`animateursLiberes`). C'est un avertissement, jamais un refus : la journée peut encore tenir sans eux. Personne n'est nommé, seulement compté, et aucun calcul n'est lancé.

**Poser ou lever retire la validation de relecture** des journées touchées, verrou ou non, là où un calcul la conserve sur une journée verrouillée ([ADR 0039](repo:docs/decisions/0039-validation-de-relecture-distincte-du-verrou.md)). Un verrou fige des sièges, et ceux de la bande n'existent plus. L'aperçu le dit (`validationRetiree`).

### Ce que la consigne emporte avec elle

- **`POST /api/planning/reset` efface aussi les consignes et les préréglages** de l'édition, comme tout ce qu'un calcul reçoit. L'export et l'import de base les emportent.
- **Les préréglages sont copiés à la duplication d'édition, les consignes non.** Un préréglage (« Plan canicule ») décrit la forme de l'événement ; une consigne appartient aux jours d'une édition, comme le plan. Le nom du préréglage porté par une consigne est un nom, non une clé : supprimer le préréglage ne réécrit pas l'histoire des dates qu'il a gouvernées. Son `id` est facultatif à la création (`POST /api/consignes/prereglages`, comme dans un fichier de scénario) : le serveur en frappe un.
- **Le motif atteint la personne dont la bande vide la journée.** Les lignes « journées aux horaires modifiés » du courriel de publication sont lues sur les dates où la personne tient un siège dans le plan publié **ou dans celui qu'il remplace** ; l'espace animateur et le PDF individuel portent le motif sur ses jours de repos aussi. Sans cela, quelqu'un dont l'arrêté a fermé toute la journée lirait une « vacation retirée » nue.
- **`GET /api/consignes` porte aussi les indicateurs** : une ligne par journée sous consigne (sièges nominaux et sous consigne, minutes fermées, minutes rouvertes, animateurs concernés), lue sur les sièges et non sur la saisie. `animateursConcernes` compte les personnes du plan enregistré qui tiennent un siège ce jour-là. Le KPI du plan en dérive `journeesSousConsigne` et `heuresFermeesParConsigne`. `GET /api/journees-types` renvoie `datesSousConsigne` : ces dates ne sont ni « en écart » ni touchées par une application, puisque les créneaux ajoutés par une consigne appartiennent à la consigne.

### Des fenêtres repas propres à la consigne

Une consigne, la demande et le préréglage peuvent porter leur `repas` : `midiDebut`/`midiFin`, `soirDebut`/`soirFin`, `coupureMinutes`, `justification`. Chaque champ absent garde la valeur de l'édition. Une fenêtre se donne avec ses deux bornes ou aucune, et **doit contenir la coupure** (celle de la consigne, sinon celle de l'édition) : un soir 19 h-19 h 30 sous une coupure de 60 min est refusé (`400`), car le solveur écarte une fenêtre trop courte pour sa coupure comme intenable, et la date se retrouverait sans aucune règle au lieu d'une règle plus stricte. La `justification`, en termes métier, est obligatoire dès qu'un champ est renseigné (`400` sinon) et s'affiche à côté de la journée.

La surcharge est **datée par construction** : elle ne gouverne que la date de la consigne. Le solveur, les écrans Pauses, Besoin et Intendance et le contrôle de grille lisent la fenêtre de la consigne ce jour-là, celle de l'édition les autres jours, et elle part avec la consigne à la levée. `GET /api/parametres-legaux` ne bouge jamais : rien à remettre en place, rien à oublier. Seules les fenêtres repas se surchargent, car la coupure repas est la règle de l'organisateur ; les plafonds légaux (repos quotidien, durées, jours par semaine) restent hors d'atteinte d'une consigne. Le cas d'usage est le soir de canicule : une compensation 18 h-22 h demanderait deux personnes par siège sous la fenêtre 19 h-21 h de l'édition, alors que « soir 18 h-22 h, les équipes mangent pendant la fermeture » ramène le besoin à une.

## Publication

`GET /api/planning/publication` dit qui serait prévenu et ce qu'il lirait, sans rien envoyer ; `POST /api/planning/publication` publie.

![Aperçu puis publication du planning](repo:docs/diagrammes/publication.svg)

<sub>Source : [`diagrammes/publication.puml`](repo:docs/diagrammes/publication.puml).</sub>

Publier marque le plan de travail comme **le plan communiqué** et n'écrit qu'aux personnes dont l'emploi du temps a changé depuis la dernière publication. Le décompte est **par personne** : renommer un stand ne réveille personne, échanger deux vacations réveille exactement deux personnes. Chaque destinataire arrive avec les phrases exactes de son courriel (`changements`), que l'administrateur relit avant tout envoi ([ADR 0011](repo:docs/decisions/0011-publier-plutot-qu-envoyer-a-tous.md)).

L'aperçu se lit à la demande : rien n'est poussé ni scruté. Le seul garde-fou est de concurrence : publier pendant un calcul figerait un plan sur le point d'être réécrit, d'où `409`. Il en va de même quand personne n'est concerné : c'est le but, non une erreur à contourner.

`premiereDiffusion` distingue quelqu'un à qui rien n'a jamais été envoyé : son planning entier est la nouvelle, non une liste de corrections. Tant que `jamaisPublie` vaut `true`, **l'espace animateur est vide** : l'application ne peut pas prétendre avoir communiqué un plan qu'elle n'a pas envoyé.

Un envoi qui échoue **ne revient pas** dans le décompte suivant, car le compteur mesure ce qui a changé et non ce qui a été délivré. Le rapport nomme les manqués, et `POST /api/planning/envoi/animateur/{animateurId}` est le rattrapage : il renvoie le plan **publié**, et répond `400` tant que rien ne l'a été.

### Deux registres d'envoi

Chaque envoi, d'une publication ou d'un renvoi, laisse son état dans `envoi_planning`, des dates et des états seulement (`ENVOYE`, `ECHEC`, `SANS_EMAIL`, `EXCLU`), jamais de contenu. Sur un échec s'ajoute une cause courte (`ADRESSE_REFUSEE`, `BOITE_PLEINE`, `SERVEUR_INJOIGNABLE`, `AUTRE`) plutôt que le message du serveur de messagerie, qui cite volontiers l'adresse. L'aperçu en tire `envoisEnEchec` (les personnes dont le **dernier** envoi a échoué), et `GET /api/editions/courant/etat` ne dit plus « fait » sur la ligne de publication tant qu'il en reste : rien n'a changé pour elles, elles n'ont simplement rien reçu.

| Registre | Question | Où il se lit |
| --- | --- | --- |
| `envoi_planning` | quelle version a-t-il reçue ? | l'écran Diffuser |
| `envoi_mail` | a-t-il pu être joint ? | la page Animateurs |

`envoi_mail` garde le résultat de **tout** courriel adressé à un animateur (code d'accès, invitation, relances, rappel de la veille, échanges, covoiturage, absences, plannings), écrit au point de sortie unique des courriels, avec une catégorie lue sur le code SMTP : `RELAIS_INJOIGNABLE`, `AUTHENTIFICATION`, `ADRESSE_REFUSEE`, `TEMPORAIRE`, `AUTRE`. Un code étendu l'emporte sur le code de réponse, et seul un `5.1.x` désigne le destinataire : un `553 5.7.1` est l'expéditeur refusé. On le lit par `dernierEnvoi` dans `GET /api/animateurs/confirmations`, à côté du statut de confirmation sans jamais s'y mêler, et par `echecsEnvoi` dans la synthèse. `ENVOYE` veut dire « remis au relais », rien de plus, et rien du tout sous `MAIL_MOCK=true`, que `GET /api/config` dit (`mailMock`) pour que l'écran écrive « simulé ».

`GET /api/planning/publication/etat` rend **qui a reçu quelle version**, une ligne par animateur de l'édition, qu'il reste ou non quelqu'un à prévenir : la version annoncée (`version.numero`, le rang de la publication dans l'édition), le dernier envoi (`envoi` : nature, état, cause, date), le rappel de la veille et la relance lus dans `notification_planifiee`, l'accusé de réception, `aPrevenir`, `differe` et `joursAPrevenir`. Rien n'y est recalculé : chaque colonne lit le magasin qui répond déjà à sa question.

### Publier pour quelques personnes

`{"cibles": ["id", …]}` restreint une publication aux personnes nommées (« Prévenir les 2 personnes » après un échange accepté, ou un remplacement le jour J). Tous les autres concernés sont **différés** ([ADR 0047](repo:docs/decisions/0047-differer-le-message-d-une-personne-sans-la-perdre-de-vue.md)) : la capture reste globale, leur repère ne bouge pas, ils reviennent au décompte suivant. Des cibles qui ne nomment aucun concerné laissent la publication sans destinataire : `409`.

### Le corps du POST

Le `POST` prend un corps JSON `{"cibles": ["id", …]}` et exige `Content-Type: application/json` : la liste est de longueur libre et un id d'animateur vient du client, donc ni un séparateur ni une URL n'est un endroit sûr pour la porter. Un corps vide, ou sans `cibles`, publie pour toutes les personnes concernées. Le champ `exclusions` de la 3.x a disparu ; un client qui l'envoie encore reçoit un `200` : le champ est **ignoré** (personne n'est écarté d'une publication générale) et la réponse porte `avertissements`, une liste de phrases qui dit que l'exclusion n'existe plus, jusqu'en 5.0. L'alerte `EXCLUSION_PUBLICATION_RETIREE` de l'accueil se referme à la première publication de l'édition.

Une personne différée garde son repère de comparaison (`animateur.plan_notifie_id`) : la publication suivante la nomme à nouveau, avec l'écart cumulé depuis ce qu'elle a réellement reçu, et `reporte` vaut `true` sur sa ligne d'aperçu.

Chaque ligne d'aperçu porte de quoi trier et filtrer sans lire de français : `ajouts`, `retraits`, `deplacements`, et `mineur`. Ce dernier signale la même vacation, sur le même stand, glissée d'un quart d'heure au plus, sans décision d'échange en attente. C'est un critère de lecture, jamais une exclusion automatique : décider de ne pas prévenir quelqu'un est un acte. Chaque ligne porte aussi `jours`, les dates de ses changements (`ChangementVacation.touches`), qui sont la même règle que l'onglet « Changements » de la Journée applique pour garder une personne sur un jour. L'écran Diffuser et la Journée comptent donc les mêmes personnes pour un même jour, ce que tient le test `ChangementsJourneeServiceTest#theDiffuserDayFilterAndTheChangesOfTheDayCountTheSamePeople`.

### La trace des destinataires

`GET /api/planning/publication/export` rend la liste de l'aperçu en CSV, une ligne par personne, les phrases dans une cellule. `GET /api/planning/publication/destinataires` rend la trace : qui a été prévenu de quoi et quand, y compris ceux qu'on n'a pas pu joindre (`SANS_EMAIL`, `ECHEC`). Sans paramètre, celle de la dernière publication, ou une liste vide s'il n'y en a jamais eu.

La trace range séparément les deux moitiés du message : `changements` pour l'emploi du temps, `demandes` pour les échanges. Elles ne vieillissent pas pareil : une phrase d'emploi du temps dit ce qui a été annoncé et le dira toujours, alors que « votre demande est en attente de décision » cesse d'être vraie dès que l'organisation tranche, sans qu'aucune publication parte.

L'espace animateur est un second lecteur de cette trace. `GET /api/espace-animateur/{jeton}` renvoie, dans `changements`, les phrases de la dernière ligne qui concerne le porteur du jeton, celles de son emploi du temps et rien d'autre, avec `changementsLe`, l'instant de cet envoi. Elles ne sont jamais recalculées : c'est ce qui a été lu, non un diff refait sur le référentiel d'aujourd'hui, donc un stand renommé depuis ne réécrit pas ce qui a été annoncé. La ligne est servie quel que soit le sort de l'envoi : sans adresse, ou après un échec, l'espace est le seul endroit où ces phrases existent encore. Les phrases d'échange n'y sont pas rejouées : l'onglet Échanges porte le statut vivant de chaque demande, qu'une phrase d'attente recopiée contredirait. Une première diffusion (`premiere_diffusion` sur la trace) renvoie une liste vide.

**Une décision d'échange n'est pas annoncée au moment où elle est prise** mais par la publication qui la porte : accepter un échange change le plan de travail, pas le plan publié, et prévenir tout de suite promettrait un planning que l'espace ne montre pas encore.

## Les changements d'une journée

`GET /api/journees/{jour}/changements?reference=publication|resolution` dit ce qui a bougé sur une journée, lu deux fois sur les mêmes faits : par siège (stand, heures, titulaire avant et après : `nouveau`, `retiré`, `remplacé`, `horaires modifiés`) et par animateur, dans les phrases mêmes que la publication met dans le courriel. La seconde lecture **est** le diff de publication filtré sur la date, jamais une comparaison refaite : l'onglet Journée et l'écran Publication ne peuvent pas raconter deux histoires sur la même journée. Ce diff est calculé sans le cadre de première diffusion : l'onglet lit des changements, jamais un planning annoncé en entier. La lecture par siège apparie sur la clé naturelle (stand, jour, heures) ; les sièges d'une même case sont interchangeables.

Une personne **gardée sur son stand à d'autres heures** (un créneau rogné par une consigne) est une ligne `HORAIRES` (`heureDebutAvant`/`heureFinAvant` portent les heures de la référence, `heureDebut`/`heureFin` celles du plan), comptée dans `horairesModifies`, et non un siège retiré plus un siège nouveau. La règle est celle de la lecture par animateur : même jour, même stand, exactement un siège de chaque côté pour cette personne. Deux sièges rognés sur un même stand restent deux retraits et deux nouveaux, des deux côtés, de sorte que les compteurs des deux lectures disent la même chose.

Deux références, et **aucune n'est inventée** :

- `publication` compare au dernier instantané publié ;
- `resolution` compare à l'instantané automatique pris juste avant la dernière résolution **effective**, celle qui a réellement remplacé le plan enregistré (`snapshot_avant_solve_id`, retenu par `planning_resolution`). Un calcul interrompu par le serveur ou refusé sur son problème capture le plan puis n'écrit rien : il ne déplace pas la référence, sinon l'onglet comparerait le plan à lui-même. Une restauration ou un import entre deux calculs n'y touchent pas non plus : ce qu'ils ont changé se lit comme changé depuis le calcul.

La purge des instantanés automatiques épargne celui que la référence désigne ; une suppression à la main ramène à « rien à comparer ». Sans paramètre, la publication quand il y en a une, le calcul sinon, et la réponse dit laquelle (`reference`). Quand la référence n'existe pas, la réponse porte `referenceDisponible: false` et des compteurs à zéro qui ne disent rien : « rien à comparer » n'est pas « aucun changement ». Une journée que la grille ne porte pas répond vide, pas `404`. C'est une lecture, sans trace au journal ; l'outil MCP `changements_journee` rend la même chose par ids.

## Accusé de réception

`POST /api/espace-animateur/{jeton}/confirmation` (« j'ai lu et je serai là ») est la seule chose que l'espace écrit à propos du planning lui-même. Cliquer deux fois répond `200` avec la **première** date, jamais une erreur : c'est un geste normal sur une page rafraîchie. Confirmer avant toute publication répond `409`, il n'y a rien à confirmer. `GET /api/animateurs/confirmations` rend une ligne par animateur, `NON_VU` compris, pour la colonne de la page Animateurs, et `GET /api/animateurs/confirmations/synthese` les mêmes réponses en trois nombres (confirmés, relancés, silencieux), comptés parmi les personnes qui ont un poste sur le plan publié, avec la date de cette publication.

| Statut | Ce qu'il dit |
| --- | --- |
| `NON_VU` | rien n'est revenu : personne n'a encore été interrogé, ou le plan a bougé depuis |
| `CONFIRME` | le bouton a été cliqué, avec la date |
| `RELANCE` | une relance est partie, de nuit ou à la main, et reste sans réponse |

L'absence de ligne en base **vaut** `NON_VU` : la remise à zéro est une suppression, il n'y a donc jamais deux façons d'écrire « cette personne n'a pas répondu ». `affecte` distingue quelqu'un qui n'a aucun poste dans le plan publié : il n'est pas silencieux, on ne lui a rien demandé, et la colonne ne le compte pas parmi les gens à relancer.

**Republier ne remet à `NON_VU` que les personnes dont l'emploi du temps a réellement changé** (`PublicationDiffService`). Quelqu'un qu'on prévient seulement d'une décision d'échange lit les mêmes journées qu'avant : lui redemander de confirmer ferait du bouton un réflexe plutôt qu'une réponse.

### Une relance ne part jamais deux fois

`POST /api/animateurs/relances` (corps `{ "animateurIds": […] }`) envoie le rappel de confirmation sans attendre la nuit, avec le même texte et le même gabarit, et **réserve la même clé** que le job nocturne (`animateurId|date de publication`) dans le journal des envois planifiés, avant de faire partir le courriel. Quelqu'un relancé à la main n'est donc pas relancé la nuit suivante, et quelqu'un que la nuit a déjà écrit est rendu dans `dejaRelancesPourCettePublication` au lieu d'être écrit une seconde fois. La clé porte la date de la dernière publication de l'édition : elle change pour tout le monde à chaque republication, y compris ceux à qui rien n'a été renvoyé. C'est le statut qui ferme cette porte-là : une personne déjà en `RELANCE` est refusée, et seule une republication qui bouge réellement son emploi du temps la remet à `NON_VU`, ce qui rouvre la relance.

Le compte rendu ne porte que des ids, un par liste : `envoyes`, `dejaConfirmes`, `sansEmail`, `dejaRelancesPourCettePublication`, `echecs`, `sansPoste`. Un envoi échoué est **compté**, non avalé (le geste est explicite, contrairement à la notification de nuit). Il rend la clé et laisse une alerte dans les « Messages récents » de l'accueil : la personne reste silencieuse et réessayer est possible, de la main comme de la nuit. Une fiche sans adresse laisse la même trace. La route répond `400` tant que rien n'a jamais été publié, ou si un id ne désigne personne, auquel cas rien ne part, pas même aux ids valides qui le précédaient.

## Phase de l'édition et gel du référentiel

La phase de l'édition décide quelles familles du référentiel sont figées ([ADR 0115](repo:docs/decisions/0115-la-phase-de-l-edition-remplace-le-gel.md)) : elle se lit dans `GET /api/editions/courant` (`phase`, `phaseDepuis`, `structureDebloquee`) et se déclare par `PUT /api/editions/courant/phase` (corps `{ "phase": "CALCUL" }`), retour en arrière compris. Déclarer la phase où l'on est déjà ne change pas sa date. La publication fait passer l'édition en `PUBLIEE` d'elle-même. En `PUBLIEE` seulement, `PUT /api/editions/courant/deblocage-structure` rouvre la structure et `DELETE` la refige ; ailleurs, `409`. Entrer dans une phase et publier refigent une structure débloquée.

`GET /api/editions/courant/gel` liste l'état des quatre familles d'après la phase. Les routes `PUT` et `DELETE /api/editions/courant/gel/{famille}` de la 3.x restent jusqu'en 5.0, marquées dépréciées : elles traduisent le geste en phase, si bien que figer **une** famille en Préparation les fige **toutes** (passage en Calcul).

Une écriture qu'une famille figée couvre est refusée en **`409`**, comme une écriture périmée. On les distingue par le `code` du corps : `MODIFICATION_CONCURRENTE` appelle « recharger ou écraser », `REFERENTIEL_FIGE` appelle « changer de phase ou débloquer la structure » et porte `familles`, les familles en cause ([les conventions](api-conventions.md#lire-un-refus)). Trois conséquences ne se lisent pas dans le schéma :

- **le refus porte sur le champ, non sur la route.** Un `PUT /api/stands/{id}` qui ne change que le nom passe sous un gel des stands ; le même `PUT` qui change un effectif est refusé. La comparaison se fait contre la fiche stockée, si bien que l'édition en lot (un `PUT` par ligne avec la fiche entière) refuse exactement les lignes qu'elle modifie vraiment ;
- **un fichier est refusé en entier**, avant la première ligne : l'import CSV d'une famille figée, l'import de grille des stands, et l'import d'animateurs dès qu'il change les compétences d'une personne déjà inscrite (un nouvel arrivant garde les siennes) ;
- **ce qui remplace tout est refusé dès qu'une famille quelconque est figée** : l'import de scénario, la restauration d'une copie avant vidage, la remise à zéro, et un **calcul dont le client fournit le problème** (`POST /api/solve`, `POST /api/solve/async`), dont l'atterrissage réécrit les stands, créneaux et compétences que ce problème porte. Un calcul construit par le serveur à partir de l'édition n'est jamais refusé.

Deux cas restent ouverts. L'import d'un dump SQL n'est pas refusé : il remplace toute la base et restaure la phase que portait chaque édition. Les consignes restent ouvertes aussi, leur écriture de grille ne passant pas par les méthodes que le gel garde.
