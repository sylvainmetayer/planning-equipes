# Mettre à jour vers une nouvelle version

Vous passez l'instance à une version plus récente sans casser les navigateurs ouverts ni la base, et vous savez revenir en arrière ou débloquer une migration interrompue. Avant de commencer, faites une [sauvegarde](sauvegarde-et-restauration.md#2-sauvegarder-à-la-main) : le schéma de la base ne va que vers l'avant, et revenir à l'image précédente ne suffit pas à revenir en arrière.

## Savoir qu'une version plus récente existe

Vous n'avez pas à surveiller le dépôt : sur un build **posé sur un tag**, la barre d'outils de l'administration affiche, à côté de la cloche des notifications, une icône dont l'infobulle nomme la dernière release publiée quand elle est plus récente que celle qui tourne. Le clic ouvre ses notes de version.

C'est le navigateur de l'administrateur qui interroge l'API publique de GitHub (`/releases/latest`, une fois par chargement de page), jamais le serveur, qui peut très bien être déployé sans sortie réseau. **Seul celui d'un administrateur connecté** le fait : la coquille d'administration n'a pas de garde de route, elle s'affiche puis redirige sur le premier 401, si bien qu'un visiteur quelconque aurait joint GitHub avant cette redirection. L'adresse IP d'un animateur, souvent mineur, aurait été livrée pour rien, et le quota anonyme par IP de GitHub épuisé au détriment des vrais administrateurs. La session est donc confirmée sur `/api/auth/me` avant le moindre appel sortant.

Un build sur un SHA n'interroge rien : « une version plus récente existe » y est vrai tous les jours et n'apprend rien. GitHub injoignable, quota d'API épuisé, réponse inattendue : l'icône reste absente, sans message, c'est une courtoisie et non une alerte. Si vous nommez des endpoints dans `CSP` ([les en-têtes du navigateur](securite-navigateur.md)), laissez-y `https://api.github.com` pour la conserver, ou omettez-le pour la désactiver.

Ce que promet chaque numéro, et comment lire les notes de version, est dans [les numéros de version](versioning.md).

## 1. Choisir la version à déployer

Un déploiement de production référence une **version nommée** (`1.2.0`), jamais `:main` ni `:latest`. Le workflow publie aussi une image `:main` à chaque fusion et la reconstruit sans cache chaque dimanche pour les correctifs Ubuntu : c'est l'**image de recette**, qui sert à valider ce qui sortira, pas à tourner chez un client. `:main` désigne un commit différent chaque jour ; `:latest` désigne ce que le registre veut bien ; ni l'un ni l'autre n'est une réponse à « vous tourniez sur quoi ? ».

`docker-compose.prod.yml` prend la version par la variable `APP_VERSION` du `.env.prod`. La version tourne aussi **en réponse** : `GET /api/config` porte un champ `version` (`X.Y.Z` sur une image de release, le SHA court sur une image de recette, `999-SNAPSHOT` en local), le même que la ligne de démarrage.

Lisez les notes de la release avant de monter : l'entrée « ⚠️ Attention » signale ce qui demande une action de votre part, comme une nouvelle contrainte légale qui peut rendre insatisfiable un planning qui passait, ou une valeur à convertir à la main.

## 2. Déployer

1. Mettez `APP_VERSION` à la nouvelle version dans `.env.prod`.
2. Tirez l'image et recréez la pile :

   ```bash
   docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
   ```

3. Vérifiez : `scripts/verifier-deploiement.sh https://<domaine>` compare la version exposée à `APP_VERSION`, avec la disponibilité et les mentions légales ([le script](installation.md#mettre-la-pile-en-route)).

Chaque démarrage réussi d'une **autre** version que la précédente est noté en base (table `version_applicative` : version, premier démarrage, dernière migration à ce moment). Sur un démarrage forcé par `ALLOW_SCHEMA_AHEAD`, c'est la dernière migration que cette version connaît qui est notée, pour qu'elle ne passe pas ensuite pour celle qui a migré la base. La page *Débogage* l'affiche : de quoi écrire dans un rapport d'incident « base migrée par la 1.3.0, puis ouverte par la 1.2.4 ».

## 3. Redéployer sans casser les navigateurs ouverts

Chaque page du frontend est chargée à la demande, sous un nom qui change avec son contenu (`chunk-*.js`) : un redéploiement remplace tout le lot. Un navigateur qui garde l'ancien shell en cache demanderait alors des fichiers qui n'existent plus, et chaque lien du menu échouerait en silence, sur mobile comme sur bureau.

Deux mécanismes évitent d'avoir à demander aux utilisateurs de « vider leur cache » :

- le serveur sert en `Cache-Control: no-cache` tout ce qui porte un nom stable : `index.html`, les catalogues `/i18n/*.json`, mais aussi le favicon, le `robots.txt`, les logos et les polices, que le build Angular recopie tels quels depuis `public/`. *Revalider avant réutilisation* : une requête conditionnelle répondue `304` tant que rien n'a changé. Les fichiers hashés, eux, restent cachés longtemps sans risque (`SpaCacheControlFilter`) ;
- si un shell périmé tourne malgré tout (onglet resté ouvert pendant le redéploiement), le premier clic dont le chunk a disparu déclenche **un rechargement complet vers la page demandée** au lieu d'échouer en silence (`core/stale-chunk-reload.ts` côté frontend). Le rechargement *remplace* l'entrée d'historique : le shell cassé n'est pas laissé à un retour arrière près. Navigateur hors ligne, en revanche, rien ne se déclenche : un tunnel produit la même erreur qu'un chunk supprimé, et recharger y échangerait un lien mort contre la page « pas de connexion » et toute la session avec.

Aucun réglage : redéployer suffit, les navigateurs se remettent d'aplomb à la visite suivante ou au premier clic.

Pour diagnostiquer, retenez ce détail. Le routage SPA étant actif (`quarkus.quinoa.enable-spa-routing`), un chunk disparu ne donne pas un `404` mais un `200` avec l'`index.html` courant. C'est le mauvais type MIME qui fait échouer l'import dynamique. Dans la console du navigateur, cherchez *failed to fetch dynamically imported module* (ou son équivalent Firefox et WebKit), pas un code d'erreur HTTP.

## 4. Les montées qui demandent un geste

### La migration `V126` et le fuseau

La migration `V126` convertit cinq horodatages sans fuseau en `timestamptz`, en les lisant dans le fuseau de l'instance au moment de la migration. Ce fuseau doit donc être celui sous lequel les lignes ont été écrites (le fuseau de la JVM, `TZ`) : migrer sous un autre décale ces dates. L'heure répétée lors du passage à l'heure d'hiver est ambiguë dans tous les cas. La migration refuse aussi de démarrer, avec un message nommant la table, si des lignes visent un créneau d'une autre édition : c'est un incident de données à examiner, jamais réparé automatiquement.

### L'échelle des poids

La migration `V108` multiplie par cinq les poids que chaque édition a enregistrés : l'importance d'une règle se lit désormais faible 1, normale 5, forte 25 ([décision 0057](repo:docs/decisions/0057-importance-d-une-regle-en-trois-positions.md)). Elle ne réécrit que la base. Ce qui porte un poids ailleurs garde l'ancienne échelle, et la règle concernée pèserait alors cinq fois moins que les autres :

| Ce qui garde l'ancienne échelle | Que faire |
| --- | --- |
| une surcharge `planning.constraint-weights.<règle>` du déploiement | la multiplier par cinq en même temps que la mise à jour, ou la retirer si elle ne faisait que répéter l'ancien défaut `1` : le défaut est désormais celui du catalogue |
| un export SQL de l'application (retiré de l'écran en 3.2) pris avant la mise à jour et réimporté après | multiplier par cinq les poids de la table `ponderation_contrainte` du fichier avant de l'importer, ou reprendre les importances sur l'écran Règles du planning après |
| un fichier scénario écrit avant | multiplier ses `contraintes.poids` par cinq ([le format du scénario](../comprendre/scenario-yaml.md)) |

Une restauration par `scripts/restaurer.sh` d'un dump `pg_dump` pris avant la mise à jour n'est pas concernée : l'historique Flyway du dump ne connaît pas `V108`, que l'application rejoue au démarrage.

### Les mentions légales et l'édition active

Une instance déjà en service qui n'a jamais renseigné les mentions légales ne redémarre pas ([les renseigner](mentions-legales.md#monter-de-version-avec-une-instance-déjà-en-service)). À la mise à jour vers `V118`, l'édition active est choisie automatiquement ([les envois automatiques](envois-automatiques.md#2-activer-lédition-qui-doit-écrire)).

## Passer de PostgreSQL 18 à PostgreSQL 19

L'image `postgres:` de `docker-compose.prod.yml` et le client `pg_dump` de l'image applicative sont en **version 19**. Un `pg_dump` d'une majeure plus ancienne que le serveur refuse de tourner (« server version mismatch »), et la sauvegarde de nuit échoue alors sans que l'application s'arrête. Le serveur 19 ne relit pas le répertoire de données d'un serveur 18 : il en crée un neuf, vide, à côté, et il faut y restaurer un dump.

À faire **avant** de tirer cette image, depuis le répertoire de la pile :

```bash
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_dump -U festival -d festival --format=custom > avant-pg19.dump   # sur l'ancienne image
docker compose -f docker-compose.prod.yml down
# ne changez pas le `name:` du volume (`postgres_data_pg18`, un nom historique) :
# un autre nom serait un volume vide. Le volume est monté sur `/var/lib/postgresql`
# avec un sous-dossier par majeure : le serveur 19 initialise `19/docker` et laisse
# `18/docker` intact, ce qui sert de retour arrière tant que vous ne le supprimez pas
docker compose -f docker-compose.prod.yml up -d postgres
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_restore -U festival -d festival --no-owner --exit-on-error < avant-pg19.dump
docker compose -f docker-compose.prod.yml up -d
```

`pg_upgrade` reste possible pour qui le maîtrise (le volume est monté sur `/var/lib/postgresql`, un sous-dossier par majeure). Les dumps `--format=custom` déjà présents dans le volume des sauvegardes se restaurent sur le serveur 19.

## Débloquer une migration interrompue

Un démarrage coupé au milieu d'une migration laisse une ligne en échec dans la table d'historique de Flyway, et toutes les migrations suivantes refusent de partir. `FLYWAY_REPAIR_AT_START=true` retire cette ligne au démarrage suivant.

**Repassez-la à `false` une fois l'instance repartie.** Laissée à `true`, la réparation réaligne aussi les empreintes de l'historique sur les fichiers livrés : une migration modifiée après coup passerait alors sans un mot, alors que c'est précisément ce que le refus de Flyway signale.

La réparation ne touche **pas** aux migrations réussies que l'image ne connaît pas : une base en avance sur le binaire n'est pas une migration interrompue, et `FLYWAY_REPAIR_AT_START` ne la débloque pas. Sur une telle base, la réparation est même **refusée**, avec le message de la section suivante : elle effacerait la migration interrompue d'une version plus récente, et l'ancienne image repartirait sur un schéma à moitié migré sans que l'historique en garde trace.

## Une base en avance sur l'image

Redescendre d'image après une montée qui a migré la base fait démarrer un code plus ancien sur un schéma plus récent. **Une image plus ancienne refuse de démarrer sur une base plus récente.** Au démarrage, l'application compare l'historique Flyway de la base aux migrations qu'elle embarque. Si la base porte une migration que le binaire ne connaît pas, elle **s'arrête** avec un message qui nomme sa version, la dernière migration qu'elle connaît, la dernière appliquée et la version de l'application qui l'a appliquée. Pour le lire :

```bash
docker compose -f docker-compose.prod.yml logs app
```

Deux issues, et aucune n'est de retoucher le schéma à la main :

| Issue | Quand |
| --- | --- |
| Redéployer la version qui a migré la base (`APP_VERSION`) | le cas ordinaire : le retour arrière était une erreur, ou le correctif attendu existe dans une version au moins aussi récente |
| Restaurer une sauvegarde antérieure à la montée, avec `scripts/restaurer.sh` ([la procédure](sauvegarde-et-restauration.md#3-restaurer)) | il faut vraiment l'ancienne version, au prix des écritures faites depuis la montée. Le script prévient d'ailleurs quand le dump est plus récent que l'image |

`ALLOW_SCHEMA_AHEAD=true` force le démarrage malgré tout, avec un avertissement `WARN` au journal et à Sentry, pour qui sait que la migration en avance est additive et que l'ancien code la supporte. La sonde de disponibilité ne compte alors plus les migrations en échec de la version plus récente, que le démarrage forcé a acceptées : sans quoi l'instance ne serait jamais déclarée prête. **Repassez-la à `false` ensuite**, comme la réparation : laissée à `true`, elle laisserait passer en silence le prochain retour arrière, celui qu'on n'a pas vérifié.

Le contrôle se fonde sur les migrations, jamais sur le numéro de version : un build local `999-SNAPSHOT` sur une copie de la base de production suit la même règle, et un correctif de branche de maintenance qui ajouterait une migration que `main` n'a pas se verrait au premier démarrage de la version suivante.

La page *Débogage* liste les versions qui ont ouvert la base, avec la dernière migration appliquée au moment de leur premier démarrage.

![La page Débogage : version, migrations et versions ayant ouvert la base](/images/fr/docs/debug.webp "La page Débogage")
