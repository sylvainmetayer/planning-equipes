# Sauvegarder et restaurer

Vous vous assurez qu'une copie récente de la base existe chaque nuit, vous savez en faire une à la main, et vous savez restaurer sans aggraver les choses. Tout l'état de l'application vit dans PostgreSQL : référentiel, planning résolu, instantanés, jetons d'espace animateur, sessions. Il n'y a **rien à sauvegarder d'autre**, et rien qui se reconstruise sans la base.

Pour un hébergeur qui décide encore, [la page sur l'hébergement](/heberger/) résume la sauvegarde de nuit en quelques lignes.

![Sauvegarde automatique et restauration hors application](repo:docs/diagrammes/sauvegarde.svg)

<sub>Source : [`diagrammes/sauvegarde.puml`](repo:docs/diagrammes/sauvegarde.puml).</sub>

## 1. Laisser la sauvegarde de nuit tourner

L'application dumpe la base **toute seule, chaque nuit à 4 h**, dès que `BACKUP_DIR` désigne un répertoire. C'est un `pg_dump --format=custom` complet (schéma et données, toutes éditions) écrit dans ce répertoire. Seules les `BACKUP_RETENTION` copies les plus récentes sont gardées : la plus ancienne part quand une nouvelle arrive.

| Variable | Défaut | Effet |
| --- | --- | --- |
| `BACKUP_DIR` | *(vide)* | Répertoire des sauvegardes. Vide : **rien n'est planifié**, et l'écran *Paramètres*, onglet *Instance* (derrière « Réglages avancés »), l'annonce. Le compose de production le pose sur `/backup` |
| `BACKUP_RETENTION` | `10` | Nombre de copies conservées, entre 1 et 30. Hors bornes, **le démarrage est refusé** : une rétention rognée en silence ne se découvre que le jour où l'on cherche le dump manquant |
| `BACKUP_TIMEZONE` | `Europe/Paris` | Fuseau dans lequel « 4 h » se lit, et dans lequel le fichier est nommé ; réglé à part, indépendamment de `TZ` |
| `BACKUP_CRON` | `0 0 4 * * ?` | La planification elle-même, si 4 h ne convient pas |

`docker-compose.prod.yml` règle aussi **`TZ`** (défaut `Europe/Paris`) sur les deux conteneurs. Ce fuseau-là n'entre pas dans les deux planifications de nuit, qui ont chacune le leur, mais décide de **la limite du jour** partout ailleurs : quels créneaux sont « déjà commencés » et donc figés pendant l'événement, le jour où la collecte de disponibilités et la foire aux échanges ouvrent et ferment, l'heure imprimée sur les PDF, la date des fichiers exportés. Il doit être celui de l'événement : en UTC, tout cela basculerait une à deux heures après minuit, heure de Paris.

Les fichiers s'appellent `planning-AAAAMMJJ-HHMMSS.dump`. Un dump déposé là à la main sous un autre nom n'est jamais touché par la rotation.

### Préparer le répertoire

Le répertoire doit appartenir à l'**uid 1000**, l'utilisateur du conteneur, qui est aussi celui du premier compte d'une machine Linux. `docker-compose.prod.yml` y monte le répertoire `./backup` de l'hôte, à côté du fichier compose : le montage du déploiement de référence, et celui qu'un outil de sauvegarde externe (borgmatic, restic…) recopie hors machine. **Créez-le avant le premier démarrage**, sinon Docker le crée en `root` et la première sauvegarde échoue sur un refus d'écriture :

```bash
mkdir -p backup && sudo chown 1000:1000 backup
```

> Le répertoire survit au conteneur, **pas à la machine**. Une sauvegarde qui ne quitte jamais l'hôte protège de la fausse manipulation, mais pas de la perte du serveur : recopiez `./backup` ailleurs, chiffré.

### Suivre l'état de la sauvegarde

L'écran *Paramètres*, onglet *Instance*, montre l'emplacement, la rétention, la prochaine sauvegarde, les fichiers présents et le compte rendu de la dernière tentative, succès comme échec. C'est là que « la sauvegarde ne tourne plus depuis trois semaines » se voit avant que ça ne compte. Un interrupteur la **suspend** ; il ne la met pas en service, c'est `BACKUP_DIR` qui le fait.

**Une nuit en échec prévient l'administration par e-mail**, sans attendre qu'on ouvre l'écran : l'adresse `MAIL_ADMIN` reçoit la date de la tentative, la raison (celle que l'écran affiche), la date de la dernière sauvegarde réussie et le nombre de nuits consécutives en échec. Un second message part à la première sauvegarde réussie qui suit, pour fermer la boucle.

- Rien ne part quand la sauvegarde est suspendue ou que `BACKUP_DIR` est vide (c'est un choix, pas une panne), ni quand `MAIL_ADMIN` est vide : l'application le dit alors une fois au démarrage, et l'écran le rappelle.
- Si le serveur SMTP est lui-même en panne, l'échec d'envoi est journalisé et rien de plus.
- Si c'est la base qui ne répond plus, le message part quand même : la date de la dernière réussite est alors lue sur le plus récent dump du répertoire (« inconnue » s'il n'y en a aucun), et les nuits qui n'ont pas pu être enregistrées sont comptées dans la série, et dans le message de rétablissement, dès que la base répond de nouveau, tant que l'application n'a pas redémarré entre-temps.
- L'exception de la sauvegarde part aussi au suivi d'erreurs quand `SENTRY_DSN` est renseigné.
- Une instance qui ne tourne plus du tout la nuit ne peut pas s'alerter elle-même : c'est le rôle de [la sonde externe](surveillance.md#sonder-lextérieur).

### Si la sauvegarde échoue sur un refus d'écriture

Deux anciennes installations peuvent être concernées.

**Conteneur passé de l'uid 1001 à 1000.** Le volume de sauvegarde existant appartient encore à l'ancien utilisateur. Après mise à jour de l'image, la sauvegarde de nuit échoue sur un refus d'écriture, visible dans le compte rendu de *Paramètres*, onglet *Instance*. Reprenez la propriété une fois, pile arrêtée :

```bash
sudo chown -R 1000:1000 backup
```

Une installation qui utilisait encore le volume nommé `planning_backups` (monté sur `/backups`) garde ses anciens dumps dedans : recopiez-les une fois dans `./backup` :

```bash
docker run --rm -v planning_backups:/old -v "$PWD/backup":/new \
  alpine sh -c 'cp -a /old/. /new/ && chown -R 1000:1000 /new'
```

**Répertoire `/backups` devenu `/backup`.** Un `.env.prod` qui écrit encore `BACKUP_DIR=/backups` **continue de fonctionner** : l'image fait de `/backups` un lien vers `/backup`, et les dumps arrivent donc dans `./backup`. Deux gestes restent à faire une fois, sans urgence : recopier les anciens dumps comme ci-dessus, et écrire `BACKUP_DIR=/backup` dans `.env.prod`. Le lien est une compatibilité, pas un chemin à utiliser.

## 2. Sauvegarder à la main

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T postgres \
  pg_dump -U festival -d festival --format=custom \
  > "planning-$(date +%Y%m%d-%H%M).dump"
```

Le service s'appelle `postgres`, la base et son propriétaire `festival` : ils sont fixés par `docker-compose.prod.yml`, pas par le `.env.prod`.

Rythme conseillé : **quotidien hors événement, horaire pendant**. Le volume est petit (quelques mégaoctets) ; ce qui coûte cher, c'est la journée de saisie perdue, pas le stockage.

> Un dump contient des **données personnelles de mineurs** : noms, dates de naissance, adresses électroniques, disponibilités. Chiffrez-le au repos, rangez-le hors de la machine, et ne le déposez jamais sur une instance de démonstration ni dans un ticket.

## 3. Restaurer

**La restauration n'est pas une fonctionnalité de l'application, et c'est délibéré** : elle se fait sur la base PostgreSQL, par vous, sur l'hôte. Un bouton « restaurer » dans une interface web serait précisément le geste que la sauvegarde automatique existe pour rattraper ([décision 0015](repo:docs/decisions/0015-sauvegarde-par-pg-dump-restauration-hors-application.md)). L'ancien export et import de dump SQL de l'écran Paramètres n'est plus proposé : sauvegarder, c'est le `pg_dump` de nuit ; échanger un jeu de données, c'est le scénario YAML.

Le dépôt livre un **script guidé**, `scripts/restaurer.sh`, à lancer depuis le répertoire de `docker-compose.prod.yml` (bash et `docker compose`, rien d'autre) :

```bash
tmux new -s restauration                              # d'abord : voir la note ci-dessous
scripts/restaurer.sh                                  # liste les dumps du volume, demande lequel
scripts/restaurer.sh planning-20260308-040000.dump    # un dump du volume, nommé
scripts/restaurer.sh --fichier ./copie.dump           # un fichier de l'hôte
scripts/restaurer.sh --essai planning-20260308-040000.dump   # l'exercice, sans toucher la production
```

Faites l'exercice une fois, à froid, avant d'en avoir besoin : **`--essai`** restaure dans un conteneur temporaire de l'image du service `postgres` de la pile (lue dans `docker-compose.prod.yml`, donc la même majeure que la production), supprimé en fin d'exécution, et affiche la durée, le nombre d'éditions et la version du schéma.

Le script enchaîne :

| Étape | Ce qu'elle fait |
| --- | --- |
| Contrôle | le fichier est **lu en entier** par `pg_restore` (vers `/dev/null`) : un fichier qui n'est pas un dump `--format=custom`, ou un dump tronqué (même après sa table des matières, que `pg_restore --list` lirait sans broncher) est refusé **avant** tout arrêt. La version du schéma du dump est comparée à celle des migrations de l'image : un dump **plus récent** que l'image est signalé, et une confirmation demandée (`PLUS-RECENT`, réputée donnée sous `--oui`). La pile doit tourner, et le volume avoir la place d'un dump de précaution |
| Précaution | dump de l'état actuel, `planning-avant-restauration-AAAAMMJJ-HHMMSS.dump`, dans le volume. Son nom est ignoré par la rotation, donc il **ne s'efface jamais seul** : supprimez-le à la main une fois la restauration validée. Son nom est affiché : c'est le chemin du retour |
| Confirmation | le script dit ce qui sera écrasé (base `festival`, instance `PUBLIC_URL`) et demande de taper `RESTAURER` (`--oui` pour une procédure écrite) |
| Restauration | arrêt de `app`, base supprimée puis recréée, `pg_restore` en **une seule transaction** qui s'arrête à la première erreur, relance de `app`. Échec ou interruption (Ctrl-C, session SSH coupée) : le dump de précaution est restauré à la place avant la relance, si bien que l'application ne redémarre jamais sur une base vide ou à moitié restaurée |
| Vérification | attente que `app` redevienne `healthy` (`/q/health/ready`), durée de l'interruption affichée |

> **Lancez le script dans `tmux`** (ou `screen`, ou sous `nohup … > restauration.log 2>&1 &`) quand vous êtes connecté en SSH. Une session qui tombe pendant la restauration ne laisse pas la base à moitié restaurée : le script remet le dump de précaution en place et relance l'application même sans terminal. Mais ce retour arrière se fait alors sans témoin, et vous vous reconnectez sans savoir s'il a abouti. Sous `tmux`, rien n'est interrompu, et `tmux attach -t restauration` retrouve l'écran là où il en était.

La base est **recréée** plutôt que nettoyée (`--clean`) : `--clean` ne supprime que les objets présents dans le dump, et restaurer un dump plus ancien que le schéma (le cas d'un retour arrière) laisserait les tables des migrations suivantes, que Flyway rejouerait au démarrage pour échouer.

`pg_restore` et `pg_dump` viennent du conteneur `postgres`, donc de la même majeure que le serveur. Le volume des sauvegardes est lu par un conteneur jetable de l'image applicative, point d'entrée remplacé : le script marche aussi le jour où l'application ne démarre plus, et avec un montage de répertoire hôte comme avec le volume nommé. La copie de travail du dump est effacée en sortie ; rien ne quitte l'hôte.

### Restaurer un dump plus récent que l'image

Un dump plus récent que l'image déployée (retour arrière d'image, dump venu d'une autre instance) porte un schéma en avance sur les migrations que l'image connaît, et l'application refusera de démarrer dessus ([les retours arrière](mettre-a-jour.md#une-base-en-avance-sur-limage)). La restauration le signale **avant** tout arrêt (version lue dans l'historique Flyway du dump, comparée aux migrations embarquées dans l'image) et demande confirmation. Le bon geste est presque toujours de déployer d'abord l'image de la version du dump (`APP_VERSION`).

### Ce que la restauration fait perdre

Restaurer pendant l'événement perd toutes les écritures postérieures au dump : c'est ce qui justifie le rythme horaire conseillé plus haut.

Les **sauvegardes avant vidage** d'une édition (`sauvegarde_avant_vidage`, [décision 0084](repo:docs/decisions/0084-sauvegarder-l-edition-avant-de-la-vider.md)) suivent le geste choisi. Restaurer un dump `pg_dump` les ramène telles qu'elles étaient à son heure. Rejouer un **export SQL de l'application** (outillage de test, plus proposé à l'opérateur) les **efface toutes**, pour toutes les éditions : l'export ne les porte pas, et l'import supprime chaque édition avant de la réécrire. C'est un geste global d'exploitation, pas un vidage d'édition ; rien n'est sauvegardé avant lui.

