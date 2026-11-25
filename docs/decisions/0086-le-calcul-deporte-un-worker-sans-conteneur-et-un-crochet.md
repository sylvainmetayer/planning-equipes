# 0086 — Le calcul déporté : un worker sans conteneur applicatif, et un crochet pour sa machine

- **Statut** : accepté, **remplacé en partie** par [0103](0103-retrait-du-lanceur-de-machine-a-la-demande.md) : la machine démarrée par un crochet est retirée, le worker permanent et son contrat restent
- **Date** : novembre 2026
- **Portée** : résolution (`service/solve`), image, déploiement
- **Met en œuvre** : [0085](0085-un-calcul-deporte-par-contrat-etude.md), dont elle garde le contrat et révise trois points
- **Voisine de** : [0008](0008-file-sequentielle-avant-parallelisme.md) (un calcul à la fois), [0044](0044-le-passe-est-fige.md) (le passé épinglé voyage avec le problème), [0075](0075-quota-de-calcul-de-l-instance-dans-l-application.md) (le quota)

## Contexte

L'étude 0085 a posé le contrat : l'application reste sur une petite machine,
un *worker* reçoit un problème déjà préparé et rend un plan, et rien dans
l'application ne connaît un hébergeur. Le format d'échange a été livré le
premier, seul. Restait à décider **quelle forme prend le worker**, **comment
sa machine démarre**, et **ce que devient une résolution distante quand
l'application redémarre**.

## Décision

### 1. Le worker est un programme Java nu, dans la même image

`SOLVER_WORKER_MODE=true` ne démarre pas l'application « avec tout coupé » :
le point d'entrée de l'image lance à la place `SolveWorkerServer`, un
programme sans conteneur d'injection, sur les classes de l'application. Il
n'ouvre ni base, ni migration, ni tâche planifiée, ni courriel — non parce
qu'une configuration les désactive, mais parce qu'aucun de ces objets n'est
jamais construit. Une variable oubliée ne peut pas les rallumer.

Il sert six routes sous `/worker`, toutes derrière un secret partagé : l'état
et la version, le dépôt d'un problème, son suivi depuis un curseur, son arrêt,
son plan, la fin de son journal. **Un problème à la fois** ; rien n'est écrit
sur disque.

Le worker **ne lit aucun de ses propres réglages de solveur** : durée, plateau
et gain de plateau par siège arrivent avec le problème
(`SolverConfiguration.SearchSettings`), et sa configuration ne lit aucune
source de la machine. Il refuse un problème d'une autre version.

### 2. Côté application, un solveur distant derrière l'interface du solveur

`RemoteSolver` implémente l'interface `Solver` de Timefold. La file des
tâches ne sait donc pas où tourne la recherche : elle tient un solveur pour
l'arrêter, suit ses meilleurs scores pour la courbe et pour l'instant de
faisabilité, comme toujours. Le solveur distant envoie le problème, interroge
le worker une fois par seconde, rejoue sa courbe sous forme d'événements, puis
pose le plan sur **le problème de l'application**, recalcule le score et
refuse le plan au moindre écart.

Tout ce qui entoure la recherche — photographie du plan remplacé, construction
du problème, enregistrement, diagnostic, indicateurs, annonce de fin — reste
dans l'application, par le même chemin.

Seule la résolution complète construite depuis le référentiel peut partir. La
replanification partielle reste sur place (son budget est de l'ordre du temps
de démarrage d'une machine), comme la résolution d'un problème posté en corps
de requête et la vérification du besoin.

### 3. La machine : une adresse, ou un crochet HTTP

Deux formes, exclusives, choisies par des variables d'instance :

- un **worker permanent** : une adresse, un secret, l'empreinte de son
  certificat ;
- une **machine à la demande** : l'application appelle un *lanceur* fourni par
  l'exploitant — `start`, `status`, `stop`, avec un identifiant d'instance —
  et n'apprend de lui qu'une adresse et une empreinte de certificat.

L'application **ne détient aucun jeton capable de créer une machine**. Le
lanceur vit hors d'elle (un exemple est fourni dans `deploy/launcher/`), et
toutes les connexions partent de l'application : ni le worker ni le lanceur ne
la rappellent, elle peut rester derrière un routeur.

Le certificat du worker est **épinglé** : une machine louée pour une heure a
une adresse et pas de nom, aucune autorité ne peut en répondre. Le secret
d'une machine à la demande est dérivé du secret du crochet et d'un aléa propre
à la machine : il n'est pas stocké, et lu sur une machine il n'en ouvre aucune
autre.

La machine sert les résolutions suivantes et est arrêtée après un délai
d'inactivité. Un arrêt qui échoue est redemandé chaque minute et affiché :
c'est la seule panne qui coûte en silence.

### 4. Un échec distant fait échouer la tâche

Machine indisponible, version différente, worker perdu, plan refusé : la tâche
échoue avec son motif, la fin du journal du worker jointe, et la machine à la
demande est arrêtée. Rien ne se replie sur la machine de l'application. Le
lieu se choisit à chaque lancement, le défaut étant un réglage d'instance :
c'est ce qui permet de relancer sur place.

### 5. L'état survit au redémarrage de l'application

Deux tables d'instance, hors édition : la machine en vie (et l'historique dont
se déduit le compteur d'heures du mois), et le calcul qu'une tâche a laissé
sur un worker, avec la table des pseudonymes.

Quand l'application s'arrête pendant un calcul distant, elle **laisse le
worker résoudre** : la tâche reste « en cours », et le démarrage suivant
reconstruit le problème, retrouve le calcul par son identifiant et le suit de
nouveau. Le score recalculé est le filet : si l'édition a changé entre-temps,
le plan est refusé et la tâche échoue, le plan enregistré intact.

## Ce que cette décision révise de l'étude 0085

| Étude 0085 | Ici | Pourquoi |
| --- | --- | --- |
| Le worker est l'application en mode worker | un programme sans conteneur d'injection | des dizaines de composants lisent la base au démarrage ; les garder éteints un par un aurait été un risque permanent pour l'application normale |
| La table des pseudonymes reste en mémoire | elle est écrite en base le temps du calcul | sans elle, un calcul retrouvé après redémarrage ne pourrait pas être rendu à ses animateurs ; elle ne quitte pas la base de l'application |
| Une autorité de certification possible à la place de l'empreinte | l'empreinte, ou la confiance ordinaire de la JVM | un worker permanent derrière un vrai certificat n'a besoin de rien ; une autorité privée se ramène à une empreinte |

## Conséquences

- **Rien de configuré, rien ne change** : ni écran, ni état, ni variable
  obligatoire.
- Le point d'entrée de l'image devient un script qui choisit le programme.
- `RemoteSolver` annonce des meilleurs scores sans solution : un auditeur qui
  lirait la solution d'un événement n'en trouverait pas.
- Le choix du lieu d'une tâche en file n'est pas stocké : rejouée après un
  redémarrage, elle suit le défaut de l'instance.
- Les démarrages et arrêts que l'application décide seule (pour une
  résolution, après inactivité) sont au journal applicatif, pas à l'historique
  des actions, qui est rangé par édition ; les deux gestes de l'exploitant y
  sont.
- Une tâche retrouvée après un redémarrage repasse par tout le chemin d'une
  résolution : le plan en place est photographié une seconde fois.
- Une machine démarrée par une version précédente qui porte encore un calcul
  n'est pas arrêtée au démarrage : elle l'est à la fin de ce calcul.
- Le compteur d'heures compte les machines que l'application a demandées, à
  l'heure entamée. Il n'est ni un plafond ni une facture.

## Options écartées

- **Quarkus avec tout désactivé pour le worker** : voir le tableau.
- **Un repli silencieux sur place** : il doublerait la durée attendue sans le
  dire (déjà écarté par 0085).
- **Arrêter la machine à chaque démarrage de l'application**, sans état en
  base : plus simple, mais un redéploiement pendant un calcul d'un quart
  d'heure le perdait.
- **Un pilote d'hébergeur dans l'application** : il y mettrait un jeton
  capable de créer des machines.
- **Un worker qui vient chercher son travail** : toujours hors de ce contrat ;
  une application louée dont le calcul resterait sur site demande un tunnel
  monté par l'exploitant.
