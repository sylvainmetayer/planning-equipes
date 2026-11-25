# Résoudre sur une autre machine

Vous confiez les résolutions complètes à une machine plus rapide que votre serveur, un poste du réseau ou une machine que vous louez et laissez allumée, sans déménager l'application. Le solveur ne se sert que d'**un cœur** : ce qui fait la durée d'une résolution est la vitesse de ce cœur, quelle que soit la taille de la machine.

Rien de ce qui suit n'est obligatoire : **sans ces variables, tout se résout sur place, comme toujours**. La décision est dans l'[ADR 0086](repo:docs/decisions/0086-le-calcul-deporte-un-worker-sans-conteneur-et-un-crochet.md), dont l'[ADR 0103](repo:docs/decisions/0103-retrait-du-lanceur-de-machine-a-la-demande.md) retire la machine démarrée à la demande.

Pour la version sans jargon, avec ce que le calcul fait et le temps qu'il prend, voir [comment fonctionne le calcul du planning](/comment-marche-le-calcul/).

## 1. Choisir la formule

| Votre situation | Ce qu'il vous faut |
| --- | --- |
| Tout sur la même machine | rien |
| Un poste puissant allumé en permanence sur le réseau | un **worker permanent** : `SOLVER_WORKER_URL` |
| L'application louée, le calcul chez vous derrière un routeur | **non servi** tel quel : l'application doit pouvoir joindre le worker, il faut un tunnel ou un VPN de votre côté |

Toutes les connexions **partent de l'application** : le worker ne la rappelle pas, elle peut rester derrière un routeur sans port ouvert. Seule la résolution complète part ; la replanification partielle, le diagnostic et la vérification du besoin restent sur place.

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
| `SOLVER_WORKER_URL` | — | Adresse du worker permanent, en `https://`. `http://` n'est accepté que vers la boucle locale |
| `SOLVER_WORKER_SECRET` | — | Le secret de ce worker (obligatoire avec l'adresse) |
| `SOLVER_WORKER_TLS_FINGERPRINT` | — | Empreinte SHA-256 de son certificat : lui seul est alors accepté, quel que soit le nom qu'il porte. Vide : la confiance ordinaire (un certificat signé par une autorité reconnue) |
| `SOLVER_WORKER_DEFAULT` | `local` | `remote` : une résolution part sur le worker quand son lancement ne dit rien |

Une fois configuré, l'écran Solveur propose « Sur place » ou « Sur la machine de calcul » à chaque lancement.

## 4. Savoir ce qui se passe en cas de panne

- **Une résolution distante qui échoue fait échouer la tâche**, avec son motif et la fin du journal du worker ; rien ne se replie sur place. Relancez en choisissant « Sur place », ou réessayez. Le plan enregistré n'est jamais touché par un échec.
- **L'application peut redémarrer pendant un calcul distant** : le worker continue, et le suivi reprend au démarrage suivant.

## 5. Louer une machine à l'heure

L'application ne crée plus de machine à la demande (retiré en 4.0). Louez la machine de votre côté, allumez-la quand vous en avez besoin avec les outils de votre hébergeur, lancez-y le worker comme au § 2 et renseignez son adresse : elle reste un worker permanent pour l'application. Si elle est éteinte, une résolution lancée « sur la machine de calcul » échoue avec son motif, et « Sur place » reste disponible.

Ce qui sort de votre serveur dans ce mode, et vers qui, est détaillé dans [le calcul déporté](securite-appels-sortants.md#calcul-déporté--ce-qui-sort-et-vers-qui).
