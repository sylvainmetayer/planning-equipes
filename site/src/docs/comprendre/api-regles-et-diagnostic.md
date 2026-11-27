# Lire les règles et diagnostiquer un plan par l'API

Vous saurez lire le catalogue des règles avec leur poids, leurs écarts et leurs planchers, régler une règle, rafraîchir l'analyse sans lancer de solveur, expliquer une affectation, déplacer ou placer quelqu'un à la main et utiliser l'assistant de réparation, avec les refus et les préconditions de chaque écriture.

## Le catalogue d'analyse

`GET /api/constraints` sert le catalogue de [`ConstraintCatalog`](repo:src/main/java/dev/sylvain/planning/solver/ConstraintCatalog.java) enrichi du résultat de la dernière analyse. Chaque entrée porte :

| Champ | Sens |
| --- | --- |
| `poids` | la valeur du déploiement, écrasée par ce que l'édition a enregistré |
| `protegee` | règle d'ordre public ou de sécurité des mineurs : l'IHM confirme avant désactivation |
| `dosable` | les règles MEDIUM de « Qualité d'organisation », les seules dont l'importance relative varie d'un organisateur à l'autre |
| `remediation` | le levier que l'organisateur a réellement sur la règle (recruter, ouvrir un stand plus tard, baisser un poids). Une règle légale n'a jamais pour réponse « changez un réglage » ; jamais vide |

La vue porte aussi `contraintesAdHocEnCause` : les exceptions saisies à la main que la dernière analyse trouve encore en écart, la plus en écart d'abord, avec leur id, leur raison et leur nombre d'écarts. C'est la réponse à « lesquelles de mes exceptions » quand une règle ad hoc affiche douze correspondances. La liste est vide quand le plan les honore toutes, et quand rien n'a été analysé.

## Régler une règle

Deux routes, un corps chacune : `PUT /api/constraints/{name}` allume ou éteint, `PUT /api/constraints/{name}/poids` pèse.

- Le poids est un entier de **1 à 500** (`ConstraintCatalog.MAX_WEIGHT`). `0` est refusé : une règle pesée zéro serait éteinte en fait tout en s'affichant active, et pour une règle légale sans passer par la confirmation. Éteindre passe par l'interrupteur.
- L'écran n'offre que trois positions (faible 1, normale 5, forte 25) et le poids exact dans le panneau de la règle ; l'API accepte tout entier de la plage ([ADR 0057](repo:docs/decisions/0057-importance-d-une-regle-en-trois-positions.md)).
- `poids: null` supprime la surcharge et rend la règle à la valeur du déploiement.
- Une désactivation insère une ligne dans `constraint_toggle`, une réactivation la supprime ; la table ne porte rien d'autre.

Ce que chaque réglage change pour le plan se lit dans [doser et désactiver les règles](doser-et-desactiver-les-regles.md).

## Historique des réglages de pondération : le journal des actions

Il n'y a plus d'historique des pondérations. Les deux routes `…/historique` de `/api/constraints` sont retirées ; les changements d'activation, de poids et de niveau d'une règle se lisent dans [le journal des actions](api-integrations.md#journal-des-actions), `GET /api/historique?nature=regles`.

- Chaque ligne dit **quelle règle** (`entiteId`), **quelle action** (`CONTRAINTE_ACTIVEE`, `CONTRAINTE_DESACTIVEE`, `CONTRAINTE_PONDEREE`, `CONTRAINTE_NIVEAU`), **quand** et **par qui**, et jamais la valeur : le journal ne garde que des noms et expire.
- Le dosage sous lequel une résolution a tourné reste, lui, stocké avec la ligne KPI (`kpi.dosage`) et avec le plan (`planning_resolution.dosage`, recopié par chaque instantané) : il est pris juste avant le démarrage du solveur, un poids changé pendant la résolution vaut pour la suivante. Il ne garde que ce qui s'écarte d'un défaut : `weights` (écart au déploiement), `disabled` et `enabled` (écart au catalogue), plus `instanceWeights`, les poids du déploiement. `null` sur une ligne ancienne veut dire « dosage inconnu », jamais « défaut ».
- Les anciennes lignes ne sont pas migrées : la migration `V158` renomme la table en `ponderation_contrainte_historique_avant_4_0`, que la 5.0 supprime ; l'application ne la lit ni ne l'exporte.

Chaque ligne de `GET /api/mesures-edition/indicateurs` porte `kpi.couvertureParJour` : par date ISO, `{ postes, pourvus }`, des sièges et jamais des personnes, ce qui reste compatible avec la conservation illimitée de cet historique. Les sommes des jours valent `postesTotal` et `postesPourvus`. La clé vaut `null` sur une ligne ancienne et sur un instantané recalculé en mode dégradé : le lecteur dit « non mesurée », jamais « journée vide ». La colonne `kpi` est un `jsonb`, et `lister_mesures_edition` (MCP) la rend telle quelle.

## Le plancher : signalé par règle, jamais appliqué

Chaque entrée non dure porte `postesEvalues`, le nombre d'éléments que la règle a évalués à la dernière analyse, **au grain de la règle** (sièges pourvus, groupes stand par créneau, paires consécutives), malgré le nom.

Quand la règle a matché au moins 95 % de ces éléments (et au moins dix évalués), l'entrée porte un objet `plancher` :

| Champ | Sens |
| --- | --- |
| `ratio` | 0,95 et au-delà |
| `motif` | code de la donnée absente, `null` quand aucune ne l'explique |
| `libelle` | la phrase à afficher, en français |
| `lien` | route Angular de l'écran de saisie, `null` sans donnée à saisir |

L'objet est `null` pour une règle dure, pour une règle sans lecture par élément et tant que rien n'a été analysé.

La vue porte aussi `scoreHorsPlancher`, `plancherMedium` et `plancherSoft` : le score brut moins, niveau par niveau, ce que coûtent les règles signalées, et les deux parts constantes, **signées comme le score** (`-5000` pour cinq mille points que rien ne rattrapera). Sur un `PlanningKpi`, `scoreMediumHorsPlancher` et `plancherMedium` suivent la règle de `violationsParContrainte` : `null` quand la mesure n'existait pas, jamais zéro. Un plancher ne change ni `actif` ni `poids` : il est signalé, pas décidé. Le raisonnement est dans [pourquoi ces règles](pourquoi-ces-regles.md).

## Où se concentrent les écarts

`pivotEcarts` dit **où** les écarts tombent, là où le reste de la vue dit combien il y en a : une cellule par contrainte et par clé d'axe (`JOUR` en date ISO, `STAND`, `ANIMATEUR`), avec le nombre d'écarts que la règle y compte. Seules les cellules portant au moins un écart existent ; le tableau de zéros est dessiné par l'écran. Les clés sont des **identifiants**, jamais des noms.

Deux points à connaître :

- Toutes les règles y figurent, pas seulement les dures. `violations` s'arrête aux règles dures parce que *lister* des milliers de lignes coûte ; les *compter* ne coûte pas. Une case de règle moyenne s'ouvre donc sur un compte et une phrase, pas sur une liste.
- Un écart qui nomme trois stands compte sur les trois. La cellule répond « combien d'écarts touchent ce stand », et écarter les cas ambigus ne répondrait à rien. C'est l'inverse délibéré de `references`, qui ouvre une fiche et ne doit pas ouvrir la mauvaise.

## Rafraîchir l'analyse sans solveur

`POST /api/constraints/diagnostic` recalcule le score, règle par règle, du **plan persisté** et renvoie la même vue que `GET /api/constraints`. C'est un seul calcul de score, sans solveur tenu : il reste donc disponible pendant qu'une résolution tourne. Sans rien de persisté, la réponse est la vue vide (`analysedAt` nul).

L'analyse vit en mémoire et le plan en base. Un redémarrage ne vaut donc pas « aucune analyse » : la première lecture qui ne trouve rien en mémoire redérive l'analyse du plan persisté et la garde (un calcul de score par édition et par redémarrage ; sans siège persisté, un `COUNT` suffit). Un calcul qui échoue rend la vue vide et laisse une trace dans les journaux : un écran de diagnostic ne devient pas une page d'erreur parce que l'analyse n'a pas pu être refaite. Aucune analyse ne passe par un plan qu'on résout puis qu'on jette ([ADR 0013](repo:docs/decisions/0013-diagnostic-par-le-score-director.md)).

## Exceptions ad hoc contradictoires

`POST /api/contraintes-ad-hoc` répond **400**, avec un message qui nomme les deux exceptions, quand la contrainte envoyée ne peut pas être satisfaite en même temps qu'une exception déjà enregistrée. L'import de scénario applique le même contrôle au jeu entier. Ce qui est refusé, ce qui passe délibérément et pourquoi aucun plafond numérique n'existe : [les exceptions ad hoc](exceptions-ad-hoc.md).

Réenregistrer une contrainte **sous son propre id** n'est jamais refusé : la version envoyée remplace la précédente. C'est la seule façon de modifier une exception, l'API n'exposant que `POST` et `DELETE`. Tout se remplace sauf l'**auteur** : `creeParUtilisateurId` est posé par le serveur à la création (le compte de la requête, `admin` ou `mcp`, ou le geste serveur `jour-j` ou `collecte` : un rôle, jamais une personne) et aucune écriture ne le change. La valeur qu'un client envoie est ignorée. Un import de scénario crée ses ajustements au nom du compte qui importe ; une duplication d'édition et un dump SQL recopient l'auteur tel quel.

## Expliquer une affectation

`GET /api/postes/{posteId}/explication` cible un seul poste du plan enregistré, **déjà résolu et jamais re-résolu**. Le plan est préparé côté serveur comme une résolution le prépare (contraintes désactivées, poids et quotas de l'édition) ; l'outil MCP `analyser_affectation` lit la même chose. Un plan envoyé par l'écran ne porterait pas ces règles : expliqué nu, il reprocherait au titulaire une contrainte que l'organisateur a éteinte.

« Respectée » signifie seulement qu'aucun écart n'a été trouvé pour ce poste précis, pas que la contrainte s'applique à lui : l'IHM ne la présente pas comme un satisfecit.

`POST /api/postes/{posteId}/simulation-swap` note un candidat qu'on lui désigne, sur un plan que l'appelant envoie, sans rien persister. Aucun écran ne l'appelle et la route est dépréciée jusqu'en 5.0 : `GET /api/postes/{posteId}/simulation?animateur=A` note le même changement sur le plan enregistré, avec les réglages de l'édition.

## Déplacer une affectation à la main

Deux routes : `GET /api/postes/{posteId}/simulation` chiffre (« Simuler un changement » du panneau du siège ; un `GET`, donc ouvert au lecteur), `POST /api/postes/{posteId}/deplacement` écrit. L'ancien `POST …/deplacement/simulation` en reste un alias déprécié jusqu'en 5.0. Le geste se désigne par `?cible=P` (un siège) ou `?animateur=A` (une personne). Les outils MCP sont `simuler_affectation` et `modifier_affectation`.

La simulation ne persiste rien. Sans corps, elle lit le verdict sur les règles de l'édition, celles que l'organisateur a désactivées comprises : l'appelant n'a pas à les fournir. L'écriture porte sur le plan enregistré ; `&occupant=X` dit qui l'appelant croit sur le siège de départ, et si quelqu'un d'autre l'occupe, c'est `409` sans écriture.

Une règle, trois gestes. Après le geste, le siège `{posteId}` tient `animateurCibleId` (personne quand il est nul) et `posteCibleId`, s'il y en a un, tient `animateurSourceId` :

| Dépôt sur | Effet |
| --- | --- |
| un siège **vide** (`cible`) | la personne y va, son siège reste vide : le trou se déplace, le score dur ne bouge pas |
| un siège **tenu** | les deux personnes échangent leurs sièges |
| une **personne** (`animateur`) | elle prend le siège ; si elle en tient déjà un sur le même créneau, les deux sièges sont échangés plutôt que de la mettre à deux endroits |

Les refus :

- **Le verdict est celui du planning entier**, comme pour un échange : déplacer un siège peut casser une règle dure ailleurs (heures hebdomadaires, repos). Avec `casseContrainteDure` vrai, l'écriture répond `400` en nommant les règles (`nouvellesViolationsDures`) et n'écrit rien. La simulation est refaite **côté serveur au moment d'écrire**, quoi que le navigateur ait vérifié.
- Un siège verrouillé refuse (`400`) ; une résolution en cours aussi (`409`).
- Les deux lignes s'écrivent dans une seule transaction, et l'analyse stockée est re-dérivée du plan après coup, comme après une restauration.
- **Le passé est figé** ([ADR 0044](repo:docs/decisions/0044-le-passe-est-fige.md)) : un siège dont le créneau est déjà commencé, à l'une ou l'autre extrémité du geste, refuse (`400`, « Ce créneau est déjà commencé : le passé ne se modifie plus »), simulation comprise, tant que `PASSE_FIGE` est allumé.

## Placer quelqu'un sur un siège vide

`POST /api/postes/{posteId}/placement?animateur=A` assied `A` sur le siège vide du plan enregistré, écrit et répond comme un déplacement (`animateurSourceId` nul, scores avant et après). Il porte les mêmes contrôles que l'écriture directe de l'assistant de réparation et de l'outil MCP `modifier_affectation`, dont c'est le service :

- résolution en cours (`409`), créneau commencé ([ADR 0044](repo:docs/decisions/0044-le-passe-est-fige.md)), siège verrouillé ;
- **règle dure lue deux fois**, sur le planning entier et sur le siège. Remplir un siège vide rend son point `posteDoitEtrePourvu` ; le verdict global seul laisserait passer un mineur posé sur une nuit (`400`, règle nommée) ;
- le siège doit être **encore vide** (`409` sinon : le panneau montre un plan chargé plus tôt, et sans cette garde on délogerait sans un mot celui qui l'a pris entre-temps) ;
- la personne ne doit pas être sous un verrou qui lui interdit un nouveau siège sur ce créneau.

Le `delta` de la réponse permet à l'écran de prévenir quand le placement coûte en qualité (niveau medium).

Garder la personne au prochain calcul passe par un verrou `ANIMATEUR_CRENEAU` que l'écran pose ensuite par `POST /api/verrouillages`, case cochée par défaut. Le placement n'écrit que le siège : le journal garde deux lignes (le poste attribué et le verrou posé), et un verrou refusé laisse le placement en place, dit comme tel. Le dialogue n'offre « Placer » ni à une personne sous un verrou `ANIMATEUR` ou `ANIMATEUR_CRENEAU` de ce créneau, ni sur un siège dont le créneau est commencé (`seatStarted` du banc) : ce sont les deux refus que les règles du banc ne portent pas.

« Libérer » pose le verrou symétrique, case cochée par défaut : un `ANIMATEUR_CRENEAU` sur la personne libérée et ce créneau, le mécanisme d'un échange accepté pour tenir à l'écart celui qu'il libère (`animateurVerrouilleCreneauFige`). Sans lui, « Corriger le reste » remplirait le trou avec la personne qu'on vient d'en retirer. Les verrous sont décrits dans [verrous, gel et publication](plan-verrous-et-publication.md).

## Assistant de réparation

`POST /api/jour-j/postes/{posteId}/suggestions?plafond=N&depuis=M` cherche les remplaçants viables sur le plan enregistré et ne persiste rien. L'un d'eux s'applique par `POST /api/postes/{posteId}/affectation?animateurId=X&occupant=Y` ; sans `animateurId`, le siège est vidé.

Là où la simulation note le candidat qu'on lui donne, l'assistant **énumère** : il ne garde que les candidats que le filtre d'éligibilité du solveur accepte, simule chacun, écarte ceux qui dégraderaient le score dur du plan entier (et pas du seul poste) puis classe le reste par impact décroissant.

**Le coût est borné.** Chaque candidat coûte une analyse complète, donc seuls les `plafond` premiers candidats éligibles sont simulés (20 par défaut, 100 au maximum ; une valeur nulle ou négative retombe sur le défaut). La réponse porte `candidatsEligibles` et `candidatsEvalues` : quand ils diffèrent, la liste est la meilleure de ce qui a été vu, **pas une réponse exhaustive**, et l'IHM doit le dire. L'ordre de passage garde les plus prometteurs : libres au moment du poste, puis sans aucun poste ce jour-là, puis le moins de minutes travaillées, puis l'ordre naturel des identifiants.

Ce classement est identique à chaque appel : `depuis` (0 par défaut) fait simuler à partir de ce rang et la réponse le renvoie. Un appelant qui n'a trouvé personne rappelle avec `depuis = depuis + candidatsEvalues` (c'est « Chercher plus loin » de l'écran Aujourd'hui) jusqu'à trouver ou épuiser le vivier. `candidatsEvalues` compte ce que **cet appel** a simulé ; un `depuis` au-delà du vivier répond une liste vide.

Appliquer une suggestion réaffecte **ce seul siège** dans `poste_affectation`, sans solveur ni réécriture du reste : le résultat est exactement le plan simulé.

- Un poste couvert par un verrouillage est refusé (`400`) ; déverrouillez-le d'abord.
- Un poste dont le créneau est **terminé** l'est aussi, à la suggestion comme à l'application (`400`, [ADR 0044](repo:docs/decisions/0044-le-passe-est-fige.md)).
- **Un poste dont le créneau est en cours est scindé, pas refusé** ([ADR 0066](repo:docs/decisions/0066-le-passe-est-fige-a-la-minute.md)). L'écriture coupe le siège à la minute courante de l'horloge du jour J : l'origine garde son titulaire jusqu'à cette minute et la **suite**, un siège nouveau d'identifiant `<id>~HHmm` qui porte `suiteDe`, reçoit l'écriture. L'identifiant écrit n'est donc pas celui qu'on a envoyé. Pour le retrouver, relisez le plan (`GET /api/planning/persisted`) ou la réponse de `POST /api/jour-j/absences`, dont `postesLiberes` nomme les suites. Les suggestions d'un tel siège sont calculées sur sa suite, comme l'écriture le fera.
- **Aucun candidat qui casserait une règle dure sur ce siège n'est proposé**, même quand le score dur global ne bouge pas : sur un siège vide, pourvoir la place règle un point dur (`posteDoitEtrePourvu`) et peut le dépenser une seconde fois ailleurs, ce qui placerait en tête de liste, à delta nul, l'animateur qu'on vient de déclarer indisponible. `violationsIntroduites` ne porte donc jamais de `HARD`.

**`occupant` est une précondition portée par l'écriture même.** Le panneau du siège montre un plan lu plus tôt : « Libérer » et « Remplacer puis Appliquer » envoient la personne affichée, et le serveur répond `409` sans rien écrire si quelqu'un d'autre tient le siège entre-temps (un échange accepté, une réparation du jour J, un autre onglet). La vérification sur le plan lu donne le message ; la clause `animateur_id IS NOT DISTINCT FROM ?` de l'`UPDATE` ferme la fenêtre entre cette lecture et l'écriture, même principe que `placement`, qui n'écrit que sur un siège encore vide. Sans `occupant`, aucune précondition : l'outil MCP `modifier_affectation` et le mode jour J écrivent ainsi.

L'`UPDATE` ne revérifie rien d'autre, hors verrouillage. Une liste de suggestions calculée **avant** une autre écriture est périmée, et l'appliquer peut créer un chevauchement que personne ne signale. Une IHM qui garde plusieurs listes à l'écran les invalide après chaque application, comme le fait le mode jour J ([API du jour J](api-jour-j-et-animateurs.md)).
