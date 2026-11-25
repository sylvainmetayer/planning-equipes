# Résoudre sur une autre machine

Vous confiez les résolutions complètes à une machine plus rapide que votre serveur, un poste du réseau ou une machine louée à l'heure, sans déménager l'application. Le solveur ne se sert que d'**un cœur** : ce qui fait la durée d'une résolution est la vitesse de ce cœur, quelle que soit la taille de la machine.

Rien de ce qui suit n'est obligatoire : **sans ces variables, tout se résout sur place, comme toujours**. La décision est dans l'[ADR 0086](repo:docs/decisions/0086-le-calcul-deporte-un-worker-sans-conteneur-et-un-crochet.md).

Pour la version sans jargon, avec ce que le calcul fait et le temps qu'il prend, voir [comment fonctionne le calcul du planning](/comment-marche-le-calcul/).

## 1. Choisir la formule

| Votre situation | Ce qu'il vous faut |
| --- | --- |
| Tout sur la même machine | rien |
| Un poste puissant allumé en permanence sur le réseau | un **worker permanent** : `SOLVER_WORKER_URL` |
| Une machine louée seulement le temps des calculs | un **lanceur** et son crochet : `SOLVER_WORKER_HOOK_URL` |
| L'application louée, le calcul chez vous derrière un routeur | **non servi** tel quel : l'application doit pouvoir joindre le worker, il faut un tunnel ou un VPN de votre côté |

Toutes les connexions **partent de l'application** : ni le worker ni le lanceur ne la rappellent, elle peut rester derrière un routeur sans port ouvert. Seule la résolution complète part ; la replanification partielle, le diagnostic et la vérification du besoin restent sur place.

## 2. Installer un worker permanent

C'est **la même image**, lancée avec `SOLVER_WORKER_MODE=true` : un autre programme, qui n'ouvre ni base de données ni courriel et ne garde rien sur disque. Il lui faut la **même version** que l'application : il refuse un problème d'une autre.

Sur la machine de calcul, créez un certificat, son empreinte et le worker :

```bash
# Un certificat pour le worker, et son empreinte pour l'application
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
  -keyout key.pem -out crt.pem -days 3650 -subj "/CN=solve-worker"
MDP="$(openssl rand -hex 16)"
openssl pkcs12 -export -inkey key.pem -in crt.pem -out worker.p12 -passout "pass:$MDP"
openssl x509 -in crt.pem -noout -fingerprint -sha256     # → SOLVER_WORKER_TLS_FINGERPRINT

docker run -d --name solve-worker --restart unless-stopped -p 8443:8443 \
  -e SOLVER_WORKER_MODE=true \
  -e SOLVER_WORKER_SECRET="$(openssl rand -hex 32)" \
  -e SOLVER_WORKER_TLS_KEYSTORE=/worker/worker.p12 \
  -e SOLVER_WORKER_TLS_KEYSTORE_PASSWORD="$MDP" \
  -v "$PWD/worker.p12:/worker/worker.p12:ro" \
  ghcr.io/sylvainmetayer/planning-equipes:<version>
```

Notez le secret généré (`SOLVER_WORKER_SECRET`) : l'application doit présenter le même. Le worker prend trois quarts de la mémoire de sa machine, comme l'application (`JDK_JAVA_OPTIONS`) ; 2 Go suffisent à une édition de taille courante.

| Variable du worker | Défaut | Rôle |
| --- | --- | --- |
| `SOLVER_WORKER_MODE` | `false` | `true` lance le worker à la place de l'application |
| `SOLVER_WORKER_SECRET` | — | Obligatoire : ce que l'application doit présenter |
| `SOLVER_WORKER_PORT` | `8443` | Port d'écoute |
| `SOLVER_WORKER_TLS_KEYSTORE` | — | Fichier PKCS#12 du certificat. Sans lui, le worker parle HTTP et n'écoute que la boucle locale : un poste de développement, rien d'autre |
| `SOLVER_WORKER_TLS_KEYSTORE_PASSWORD` | vide | Mot de passe de ce fichier |

## 3. Brancher l'application

Dans le `.env.prod` de l'application :

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `SOLVER_WORKER_URL` | — | Adresse d'un worker permanent, en `https://`. `http://` n'est accepté que vers la boucle locale |
| `SOLVER_WORKER_SECRET` | — | Le secret de ce worker (obligatoire avec l'adresse) |
| `SOLVER_WORKER_TLS_FINGERPRINT` | — | Empreinte SHA-256 de son certificat : lui seul est alors accepté, quel que soit le nom qu'il porte. Vide : la confiance ordinaire (un certificat signé par une autorité reconnue) |
| `SOLVER_WORKER_HOOK_URL` | — | Adresse du lanceur d'une machine à la demande, en `https://`, ou en `http://` s'il est sur la boucle locale ou un réseau privé, là où il doit être. **Exclusif** de `SOLVER_WORKER_URL` : les deux ensemble, l'application refuse de démarrer |
| `SOLVER_WORKER_HOOK_SECRET` | — | Le secret du lanceur (obligatoire avec son adresse) ; le secret de chaque machine en est dérivé |
| `SOLVER_WORKER_INSTANCE` | `planning` | Le nom que cette instance donne au lanceur, quand il en sert plusieurs : lettres, chiffres et tirets |
| `SOLVER_WORKER_DEFAULT` | `local` | `remote` : une résolution part sur la machine de calcul quand son lancement ne dit rien |
| `SOLVER_WORKER_START_TIMEOUT` | `PT5M` | Attente maximale d'une machine qui démarre ; au-delà la tâche échoue et la machine est arrêtée |
| `SOLVER_WORKER_IDLE_TIMEOUT` | `PT45M` | Inactivité après laquelle une machine à la demande est arrêtée. Sous l'heure, pour qui est facturé à l'heure entamée |

Une fois configuré, l'écran Solveur propose « Sur place » ou « Sur la machine de calcul » à chaque lancement. Pour une machine à la demande, il montre aussi son état, l'heure de son arrêt prévu, les heures de machine du mois, un bouton pour la préparer avant une séance et un pour l'arrêter tout de suite.

## 4. Savoir ce qui se passe en cas de panne

- **Une résolution distante qui échoue fait échouer la tâche**, avec son motif et la fin du journal du worker ; rien ne se replie sur place. Relancez en choisissant « Sur place », ou réessayez. Le plan enregistré n'est jamais touché par un échec.
- **L'application peut redémarrer pendant un calcul distant** : le worker continue, et le suivi reprend au démarrage suivant.

## 5. Louer une machine à la demande avec un lanceur

L'application ne crée jamais de machine : elle appelle un petit service que vous faites tourner à côté d'elle, qui détient le jeton de votre hébergeur. Trois appels, tous avec `Authorization: Bearer <SOLVER_WORKER_HOOK_SECRET>` :

| Appel | Corps → réponse | Sens |
| --- | --- | --- |
| `POST <crochet>/start` | `{instance, version, secretWorker}` | crée la machine de cette instance si elle n'existe pas (**idempotent**) et y lance l'image à cette version, en mode worker, avec ce secret |
| `GET <crochet>/status?instance=…` | → `{state, url, certFingerprint}` | `state` vaut `creating`, `ready` ou `absent` ; l'adresse et l'empreinte du certificat une fois prête |
| `POST <crochet>/stop` | `{instance}` | détruit la machine (**idempotent**) |

[`deploy/launcher/launcher.py`](repo:deploy/launcher/launcher.py) en est un exemple complet, sans dépendance (Python et `openssl`), avec deux pilotes : une machine Hetzner créée depuis une image système nue et préparée par cloud-init, et un conteneur local pour essayer le contrat sans rien louer. Ses variables sont décrites en tête du fichier ; il ne sert que les instances nommées dans `LAUNCHER_INSTANCES` (`planning` par défaut, comme `SOLVER_WORKER_INSTANCE`).

Essayé tel quel chez Hetzner sur le scénario versionné `festival-hivernal` : une machine `ccx13` créée depuis `debian-13` reçoit son problème une minute après le lancement, et la résolution suivante part sans attente.

Quatre règles valent pour tout lanceur :

- **il n'écoute que l'application** (boucle locale ou réseau privé) : c'est un service qui crée des machines ;
- son jeton est celui d'un **projet dédié** aux workers, jamais celui du projet qui héberge autre chose ;
- il pose une **durée de vie maximale** sur ce qu'il crée et balaie : si l'application disparaît avec une machine allumée, c'est lui qui l'arrête ;
- le pare-feu de la machine n'ouvre que le port du worker, à la seule adresse de sortie de l'application quand elle est connue.

Un arrêt que le lanceur refuse est redemandé chaque minute et affiché en alerte sur l'écran Solveur : une machine que personne n'arrête continue de coûter.

Ce qui sort de votre serveur dans ce mode, et vers qui, est détaillé dans [le calcul déporté](securite-appels-sortants.md#calcul-déporté--ce-qui-sort-et-vers-qui).
