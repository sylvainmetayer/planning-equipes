# Régler le calcul du planning

Vous fixez le budget de calcul de l'instance, vous protégez une instance partagée contre l'enchaînement de calculs, vous dimensionnez la mémoire et vous choisissez le moteur de recherche. Le solveur est **un seul pour toute l'instance** : une résolution à la fois, toutes éditions confondues, la file comprise.

## Fixer le budget de calcul de l'instance

Ce que le calcul fait de ce temps est expliqué dans [combien de temps ça prend](/comment-marche-le-calcul/).

Le budget est **un réglage d'instance**, le même pour toutes les éditions : le solveur est partagé, un calcul à la fois, et la durée qu'un calcul y tient n'est pas la décision d'une édition. L'écran *Paramètres › Calcul* l'affiche sans le modifier.

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `SOLVER_SECONDS_LIMIT` | `900` | Durée maximale d'un calcul, en secondes |
| `SOLVER_UNIMPROVED_SECONDS_LIMIT` | `180` | Arrêt si le plan, déjà faisable, ne progresse plus depuis ce temps, en secondes ; `0` : jamais |
| `SOLVER_SECONDS_LIMIT_MAX` | `3600` | Durée maximale qu'un lancement peut demander (`?seconds=`, MCP), en secondes |

Une durée demandée au-dessus de `SOLVER_SECONDS_LIMIT_MAX` est **refusée**, avec un message qui cite le plafond. Elle n'est pas rognée en silence : qui demande trois heures en obtiendrait deux en croyant le budget épuisé.

**Le démarrage est refusé** si `SOLVER_SECONDS_LIMIT_MAX` est inférieur à `SOLVER_SECONDS_LIMIT` : tout lancement serait sinon refusé.

`SOLVER_UNIMPROVED_SECONDS_LIMIT_MAX` est retirée en 4.0 : plus aucune édition ne règle son plateau, il n'y a plus rien à plafonner. La poser n'empêche pas de démarrer, l'application le dit au démarrage.

## Borner le nombre de calculs d'une instance publique

Le budget ci-dessus borne **un** calcul. Une instance ouverte à des inconnus, ou qui partage sa machine avec une autre (typiquement une démonstration publique à mot de passe partagé à côté d'une production), doit aussi borner combien on peut en enchaîner. Deux gardes, coupées par défaut, valent pour toute l'instance, toutes éditions et tous appelants confondus, **et seulement si l'instance se déclare publique** :

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `PUBLIC_INSTANCE` | `false` | `true` : l'instance est publique. Elle applique les deux gardes ci-dessous et refuse de démarrer sans [mentions légales](mentions-legales.md) |
| `SOLVER_MAX_SOLVES_PER_HOUR` | `0` (aucune limite) | Calculs acceptés sur les 60 dernières minutes glissantes |
| `SOLVER_MAX_QUEUED_JOBS` | `0` (aucune limite) | Tâches qui peuvent attendre derrière celle qui tourne (« Planifier »), celle-ci non comprise |

Le quota compte chaque calcul **accepté au lancement**, quel que soit le chemin : résolution complète ou incrémentale, depuis *Planning › Calculer*, l'API (y compris `POST /api/solve`, synchrone) ou un assistant MCP, et la vérification du besoin par un calcul (*Diagnostic › Besoin en animateurs*, le défaut), qui est un vrai calcul ; la borne seule n'en consomme pas. Un lancement refusé pour une autre raison ne compte pas ; une tâche planifiée puis retirée de la file, si. Au-delà, le lancement est refusé (`409`) par une phrase que l'écran affiche telle quelle et que l'outil MCP rend en erreur :

> Cette instance limite le calcul à 12 résolutions par heure. Prochaine résolution possible à 14:32.

L'heure est donnée dans le fuseau de `NOTIFICATIONS_TIMEZONE`, celui de l'événement. La file pleine se refuse de la même façon (« Cette instance limite la file d'attente du solveur à 2 résolutions planifiées… »).

Sur une instance qui n'est **pas** publique, les deux gardes ne font rien, et l'application dit au démarrage qu'elles sont ignorées si vous les avez posées : l'instance de votre équipe n'a aucune raison de refuser son organisateur.

Le compteur vit **en mémoire** : un redémarrage le remet à zéro, ce qui suffit contre l'abus visé. La file d'attente elle aussi : voir plus bas. Pour une démonstration, un ordre de grandeur : un quota d'une douzaine par heure et une file de deux, avec `SOLVER_SECONDS_LIMIT_MAX` abaissé à ce qu'un visiteur a la patience d'attendre. Le choix de borner ici plutôt qu'au reverse proxy est [l'ADR 0075](repo:docs/decisions/0075-quota-de-calcul-de-l-instance-dans-l-application.md), limité aux instances publiques par [l'ADR 0110](repo:docs/decisions/0110-budget-de-calcul-d-instance-file-en-memoire-quota-public.md).

## La file d'attente est en mémoire

Les calculs « planifiés » attendent leur tour **en mémoire** : un redémarrage de l'application les perd. Au démarrage, ceux qu'une version précédente avait laissés en file ou en cours sont marqués **interrompus** dans l'historique des calculs, avec la raison écrite sur la tâche, et le journal de l'application en donne le nombre. Rien ne repart tout seul : relancez le calcul. Un calcul parti sur une machine de calcul distante est, lui, suivi de nouveau ([Résoudre sur une autre machine](calcul-sur-une-autre-machine.md)).

## La mémoire de l'application

Le conteneur de l'application a besoin de **2 Go** (`mem_limit: 2g` dans `docker-compose.prod.yml`), PostgreSQL de 512 Mo : c'est la mémoire que l'hôte doit pouvoir leur donner. Le calcul d'un planning en est le seul gros consommateur, sa recherche locale allouant sans relâche, et c'est lui qui décide de la taille.

L'image règle la JVM en conséquence, par `JDK_JAVA_OPTIONS` :

| Option | Défaut de la JVM | Pourquoi |
| --- | --- | --- |
| `-XX:MaxRAMPercentage=75` | 25 % de la limite du conteneur | Avec 25 %, le tas plafonne à 512 Mo sous 2 Go (256 Mo sous 1 Go) et le solveur passe son temps à collecter. Trois quarts laissent le reste (métaspace, code compilé, piles, tampons, environ 200 Mo mesurés pendant un calcul) hors du tas |
| `-XX:+UseParallelGC` | G1, ou Serial dans un conteneur de moins de deux cœurs ou d'environ 1,8 Go | Le collecteur fait pour le débit, là où G1 vise des pauses courtes : un calcul qui alloue beaucoup y gagne, pour des pauses de l'ordre du dixième de seconde, sans effet sur les écrans |

Sur une petite machine (un Raspberry Pi 4, trois cœurs alloués, avec une image construite sur place pour arm64 : celle du registre ne vise que `linux/amd64`), ces deux options ensemble font évaluer à la recherche locale **plus de deux fois plus de coups par seconde** que les défauts sous 1 Go (un tas de 256 Mo et Serial). Sur un poste de travail, la différence se voit à peine. Les mêmes données suivent la même trajectoire, seulement plus vite : dans le même temps de calcul, le solveur va donc plus loin, et le plan obtenu peut différer, en mieux, de celui d'avant.

Trois conséquences pour vous :

- 75 % suppose une limite de mémoire sur le conteneur : c'est d'elle que la JVM tire le pourcentage. Lancée sans limite (un `docker run` nu, un manifeste sans `resources.limits`), elle prend les trois quarts de la mémoire de l'hôte au détriment de PostgreSQL et du système, et le collecteur parallèle rend rarement ce qu'il a pris. Posez toujours une limite, ou redéfinissez `JDK_JAVA_OPTIONS` avec un pourcentage plus bas.
- Ne gardez pas 1 Go de limite avec cette image : 75 % d'un Go laissent trop peu hors du tas, et le conteneur risque d'être tué en plein calcul. Un hôte qui ne peut vraiment pas donner 2 Go redéfinit `JDK_JAVA_OPTIONS` avec un pourcentage plus bas, et accepte un calcul plus lent.
- Pour changer ces options, redéfinissez `JDK_JAVA_OPTIONS` dans `environment:`. La valeur remplace celle de l'image en entier : reprenez ce que vous voulez garder. `JAVA_TOOL_OPTIONS` ne convient pas, car la JVM le lit avant la ligne de commande, où le lanceur place `JDK_JAVA_OPTIONS` : la valeur de l'image gagne alors sur la taille du tas, et un autre collecteur choisi là (`-XX:+UseG1GC`, que certains agents de supervision injectent) **empêche la JVM de démarrer** (« Multiple garbage collectors selected »), le conteneur redémarrant alors en boucle.

## Choisir le moteur de recherche

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `SOLVER_ENGINE` | `hand` | `hand` : les résolutions cherchent leur plan avec le moteur direct ; `timefold` : avec la recherche de Timefold, comme avant la 3.0.0 ([décision 0088](repo:docs/decisions/0088-le-moteur-direct-devient-le-moteur-par-defaut.md)) |

C'est un réglage d'**instance**. Une machine de calcul suit celui de l'application qui lui envoie le problème, pas sa propre variable. La vérification du besoin par un calcul suit le moteur de l'instance ; les résolutions « jusqu'à la faisabilité » restent sur Timefold.

Le moteur direct ([décision 0087](repo:docs/decisions/0087-un-second-moteur-de-recherche-arbitre-par-timefold.md)) atteint un plan sans écart dur trois à quatre fois plus tôt sur le scénario `festival-hivernal`, et passe en une minute sous le score que la recherche de Timefold atteint en cinq (mesure et commande dans la décision). Il n'est pas cru sur parole : chaque plan qu'il rend est re-scoré par les contraintes de l'application avant d'être enregistré.

- Une résolution qui repart du plan en place préfère y rester : à score égal, elle garde le titulaire qu'elle a trouvé au lieu d'échanger des animateurs que les règles ne distinguent pas. C'est une préférence de la recherche, pas une garantie siège par siège. Une résolution partie de zéro n'est pas concernée, et un plan publié reste protégé par sa propre règle, qui pèse dans le score.
- Un écart entre les deux scores fait échouer le calcul, avec un message qui donne les deux ; le plan enregistré n'est pas touché et rien ne bascule tout seul sur l'autre moteur. C'est un défaut à signaler ; en attendant, `SOLVER_ENGINE=timefold` et un redémarrage rendent la main à la recherche d'origine.
- Le moteur refuse de lui-même, par un message sur la tâche, ce qu'il ne sait pas scorer. Le remède est le même : `SOLVER_ENGINE=timefold`.

  | Refus | Quand |
  | --- | --- |
  | « l'animateur … est apprécié sur … typologies de jeu proposées par un stand, pour 64 au plus » | Une fiche porte plus de 64 appréciations sur des typologies qu'un stand propose ; l'édition, elle, peut compter autant de typologies qu'elle veut |
  | « le siège … n'a pas de stand ou pas d'horaire complet » | Un créneau sans heure de fin |
  | « le siège … est tenu par un animateur absent du problème » | Un plan de départ qui nomme quelqu'un que l'édition ne connaît plus |
  | « … porte deux états », « … porte deux plafonds », « pas exactement un jeu de paramètres » | Un fait en double, qu'aucune table ne permet |
  | « … couples siège × animateur » | Plus de 15 millions de couples (15 000 sièges pour 1 000 animateurs, par exemple) : la table du moteur n'y tient plus dans une petite machine. Une édition courante en compte moins d'un million ; `extreme-09-120j-400stands-1000animateurs-cumul` (41 370 sièges, 1 000 animateurs) en compte 41 millions. Sous le défaut `hand`, la tâche **échoue**, sans repli sur Timefold : une instance de cette taille règle `SOLVER_ENGINE=timefold` avant de lancer un calcul |
  | « le score … sort de ce que le moteur sait ordonner » | Plus de 500 millions de points soft, ou un medium du même ordre : des poids démesurés |

- « Le moteur direct s'est contredit dans son propre score » et « a échoué sur une erreur inattendue » ne sont pas des refus mais des défauts du moteur : signalez-les, avec le journal de l'application.
- Le journal dit quel moteur a cherché : chaque calcul commence par « Solve with the HAND engine » (ou `TIMEFOLD`), et un plan du moteur direct accepté par l'arbitre laisse « The direct engine's plan is refereed by Timefold at the same score », suivi du score.

Sur une petite machine, le moteur direct ne demande pas plus que la recherche de Timefold. Mesure sur `festival-hivernal` (3 438 sièges, 153 animateurs), deux cœurs partagés, la JVM bornée comme le conteneur de production (tas de 1,5 Go, `ParallelGC`), 300 s, graine 0, une résolution à la fois :

| Moteur | Zéro écart dur | Score à 300 s (medium / soft) | Mémoire résidente au pic |
| --- | --- | --- | --- |
| `timefold` | 95 s | −30 775 / −89 360 | 548 Mo |
| `hand` | 10 s | −23 820 / −92 970 | 434 Mo |

La commande est celle du [banc du solveur](../comprendre/tests-de-scenarios.md#le-banc-du-solveur), avec `-DargLine` pour borner la JVM.

## Calculer sur une autre machine

Le solveur ne se sert que d'un cœur, et une machine plus rapide fait gagner du temps : voir [Résoudre sur une autre machine](calcul-sur-une-autre-machine.md).
