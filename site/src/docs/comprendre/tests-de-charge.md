# Tester la charge d'une démo publique

Avant d'ouvrir une [instance de démonstration](../installer/instance-de-demo.md) à tous, vous vérifiez qu'elle tient les visiteurs qui arriveront ensemble. Quatre scénarios [k6](https://grafana.com/docs/k6/) le mesurent. Ils sont dans le dépôt, sous [`src/test/charge/`](repo:src/test/charge/README.md), et tournent par l'image Docker officielle de k6 : il n'y a rien à installer d'autre que Docker, et aucune dépendance npm ni Maven.

## Les quatre scénarios

| Scénario | Ce qu'il joue | Ce qui le fait échouer |
| --- | --- | --- |
| `visiteur` | N visiteurs à la fois : connexion par le formulaire, puis les lectures que font Accueil, Planning, Diagnostic et Règles en s'ouvrant | p95, taux d'erreur, une seule 5xx |
| `calculs` | N visiteurs appuient sur « Calculer » au même instant | plus d'un calcul en cours et de `FILE_MAX` en file, un refus sans phrase ou autre qu'un 409 ou un 429, une 5xx |
| `espace` | l'espace animateur de la démo : code affiché à l'écran, session, planning, ICS de l'espace et flux d'abonnement | p95, taux d'erreur, une 5xx, moins de 90 % de sessions ouvertes |
| `pic` | montée de 0 à N visiteurs en une minute, puis un plateau | p95, taux d'erreur, une 5xx |

Chaque scénario porte ses seuils : un seuil dépassé fait sortir `lancer.sh` en erreur. Le p95 (1,5 s), le taux d'erreur (1 %), N et les durées se règlent par des variables. Le README du dossier en donne la liste.

## Les lancer

Le mot de passe du compte `admin` se passe en variable, jamais en clair dans le dépôt ni sur la ligne de commande de k6 :

```bash
# Une démo locale, sur un dump de scripts/demo/generer-dump.sh
ADMIN_PASSWORD=… src/test/charge/demo-locale.sh reference.dump

# Un scénario contre elle, ou contre la démo publique
BASE_URL=http://127.0.0.1:18080 ADMIN_PASSWORD=… N=20 src/test/charge/lancer.sh pic

src/test/charge/demo-locale.sh --arreter
```

Lancez k6 depuis une autre machine que celle qui sert la démo : sur la même, il prend au serveur le processeur qu'il mesure. Montez N par paliers (5, 10, 20, 40…) et regardez à chaque palier le p95 et ce que consomme le conteneur de l'application (`docker stats`). La limite est le palier où un seuil casse.

Sur une démo qui n'a qu'une édition, la file ne se remplit jamais : une édition n'a qu'un calcul en attente derrière celui qui tourne, et les suivants sont refusés en 409 avec une phrase qui dit d'attendre. Le seuil vérifie qu'on ne dépasse pas `FILE_MAX` plus un, pas qu'on l'atteint.

Les limites de mémoire de la pile locale sont celles de `docker-compose.prod.yml` (2 Go pour l'application, `APP_MEM_LIMIT` pour en essayer une autre) : mesurée sans elles, la JVM prend les trois quarts de la machine et la mesure ne vaut pas pour la démo.

Le scénario `calculs` consomme le quota horaire de l'instance (`SOLVER_MAX_SOLVES_PER_HOUR`), et les calculs qu'il met en file occupent le processeur plusieurs minutes. Ne le jouez pas sur une démo ouverte.

## Le workflow à la demande

Le workflow « Charge de la démo » (`.github/workflows/charge.yml`) ne part qu'à la main, depuis l'onglet Actions, jamais sur une PR. Il fabrique un dump avec l'image choisie (`:main` par défaut, ou une version), monte la démo dans le runner et joue les quatre scénarios avec de petits N. Il dit qu'une version ne casse pas sous quelques visiteurs ; un runner partagé ne dit pas où est la limite d'un vrai serveur. Pour la chercher, jouez `lancer.sh` contre une machine de la taille de votre hôte de démo.
