# Planning Équipes

Application de gestion de planning pour un festival de jeux de société de
15 jours : elle affecte automatiquement ~150 animateurs aux stands, en respectant
le cadre légal (notamment celui des mineurs), les compétences, les disponibilités
et l'équité de charge.

Ce README couvre deux choses : **démarrer l'application en local** et **ce que
l'application sait faire**. Toute la documentation technique (architecture, API,
modèle de domaine, contraintes, formats d'import/export, contribution) est dans
[`docs/`](docs/README.md).

---

## 1. Démarrer l'application en local

### Prérequis

- Java 25, Maven 3.9.9 et Node 24 — épinglés dans `mise.toml`, installables d'un
  coup avec [mise](https://mise.jdx.dev) : `mise install`
- Docker ou Podman (pour la base PostgreSQL)

Node n'est requis que pour développer le frontend : le build Maven télécharge
lui-même la version de Node dont il a besoin.

### Option A — tout via Docker Compose (le plus simple)

```bash
docker compose --profile app up --build
```

Puis ouvrir <http://localhost:8080>. La base PostgreSQL et une interface pgAdmin
(<http://localhost:5050>) sont démarrées en même temps.

### Option B — mode développement (rechargement à chaud)

```bash
docker compose up -d postgres     # base seule
./mvnw quarkus:dev                # application sur http://localhost:8080
```

Le code Java comme le frontend Angular sont rechargés à chaud : Quarkus démarre
aussi le serveur de développement Angular et le proxifie, tout passe donc par
<http://localhost:8080>.

## Registry login

```bash
echo $CR_PAT | docker login ghcr.io -u USERNAME --password-stdin
```

### Premiers pas dans l'application

1. Ouvrir <http://localhost:8080> — la page **Solver** s'affiche ; le menu
   latéral donne accès à chaque écran.
2. Cliquer sur **Generate sample planning** pour charger le jeu de données
   d'exemple (les référentiels sont ensuite modifiables depuis **Stands**,
   **Animateurs**, **Créneaux**, **Typologies** et **Ad hoc constraints**).
3. Cliquer sur **Solve with Timefold** : la résolution part en tâche de fond
   (plusieurs minutes sur le scénario complet), la navigation reste libre et une
   notification s'affiche à la fin — y compris dans les autres navigateurs
   ouverts sur l'application, qui voient le calcul en cours et son temps écoulé.
4. Consulter le résultat dans **Assignment calendar** (vue mensuelle) ou
   **Day calendar** (vue par jour), et le respect des règles dans **Constraints**.
5. Exporter les plannings individuels en PDF ou en ICS depuis la page
   **Exports**.

### Configuration

| Variable | Défaut | Usage |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/festival` | Connexion PostgreSQL |
| `DB_USER` / `DB_PASSWORD` | `festival` / `festival` | Identifiants base |
| `HTTP_PORT` | `8080` | Port HTTP exposé |
| `SENTRY_DSN` | *(vide = désactivé)* | Suivi d'erreurs (Bugsink ou tout endpoint compatible Sentry) |
| `SENTRY_ENVIRONMENT` | `local` | Étiquette d'environnement jointe aux erreurs remontées |
| `POSTHOG_API_KEY` | *(vide = désactivé)* | Analytics d'usage (PostHog) |
| `POSTHOG_HOST` | `https://eu.i.posthog.com` | Hôte d'ingestion PostHog |

Détails et mise en place : [`docs/observabilite.md`](docs/observabilite.md).

### Lancer les tests

```bash
./mvnw test                       # tests unitaires
./mvnw verify -DskipITs=false     # + tests d'intégration sur l'application packagée
```

Les tests démarrent un PostgreSQL jetable via les *dev services* Quarkus : un
runtime de conteneurs doit être disponible. Détails (Podman, réglages du solveur,
CI) dans [`docs/developpement.md`](docs/developpement.md).

---

## 2. Fonctionnalités métier

### Génération automatique du planning

Le moteur d'optimisation affecte les animateurs aux places à pourvoir sur chaque
stand et chaque créneau, en distinguant trois niveaux d'exigence :

- **contraintes dures**, jamais violées dans un planning valide ;
- **contraintes medium**, respectées autant que possible et signalées sinon ;
- **contraintes souples**, optimisées en dernier pour départager deux plannings
  valides.

La résolution s'exécute en tâche de fond, côté serveur : l'application reste
utilisable pendant le calcul et notifie l'utilisateur à la fin. Le calcul
appartient au serveur, pas au navigateur — une seule résolution tourne à la fois
pour toute l'application. Quiconque ouvre l'application pendant ce temps (autre
poste, autre navigateur, navigation privée) voit le calcul en cours avec son
temps écoulé, ne peut pas en lancer un second, et reçoit la notification de fin
ainsi que le planning résolu.

**Ce qui est garanti (contraintes dures)**

- chaque place ouverte sur un stand est pourvue ;
- aucun animateur n'est affecté un jour qu'il a déclaré indisponible ;
- un animateur n'anime que des stands dont il maîtrise au moins une typologie de
  jeu ;
- un animateur ne tient jamais deux postes qui se chevauchent dans le temps ;
- cadre légal du temps de travail, pour tous : 10 h de travail par jour et 48 h
  par semaine au maximum, 11 h de repos entre deux journées travaillées, jamais
  plus de 6 h de travail d'affilée sans une pause d'au moins 20 minutes, jamais
  plus de 6 jours travaillés dans la même semaine et 35 h de repos consécutives
  par semaine ;
- cadre légal des mineurs, plus protecteur et distinct selon qu'ils ont moins de
  16 ans ou de 16 à 18 ans : pas de travail de nuit (à partir de 20 h avant
  16 ans, de 22 h ensuite), 7 h ou 8 h de travail quotidien maximum, 35 h par
  semaine, 12 h ou 14 h de repos entre deux journées, jamais plus de 4 h 30 de
  travail d'affilée sans une pause d'au moins 30 minutes, deux jours de repos
  consécutifs par semaine, aucun travail les jours fériés, et stands réservés
  aux majeurs respectés ;
- règle de sécurité posée par l'organisateur : un mineur est toujours accompagné
  d'un majeur sur le même stand et le même créneau ;
- les exceptions posées manuellement par l'administrateur sont traitées au même
  niveau de priorité que le cadre légal.

**Ce qui est optimisé**

- au moins un animateur référent par stand et par créneau ;
- charge de travail équilibrée entre animateurs ;
- pas plus de mineurs que de majeurs sur un même stand et créneau ;
- rotation des stands d'un animateur au fil du festival ;
- mixité des niveaux (associer un débutant à un référent).

### Plannings alternatifs

Les créneaux peuvent être organisés en plusieurs groupes (« plannings ») —
un planning normal et, par exemple, un planning de repli en cas
d'imprévu de dernière minute (météo, lieu indisponible, ...). Un seul
groupe est actif à la fois ; l'activer désactive automatiquement les
autres. La résolution ne tient compte que des créneaux du groupe actif, ce
qui permet de préparer un planning alternatif à l'avance et de basculer
dessus en un clic sans perdre le planning courant.

Si le groupe actif change après une résolution (par exemple en basculant sur
le planning de repli), l'application avertit l'utilisateur, sur tous les
écrans concernant les créneaux, que le dernier calcul ne correspond plus au
groupe actif et qu'une nouvelle résolution est nécessaire.

Plus généralement, si une donnée de référence (stand, animateur, créneau,
contrainte ad hoc, activation/désactivation de règle, ...) est modifiée après
la dernière résolution, un indicateur discret apparaît dans la barre d'outils
(et un rappel sur la page Solveur) : le résultat affiché peut ne plus être à
jour. C'est volontairement léger — modifier des données sans relancer tout de
suite le solveur est un usage courant en préparant un planning — mais
l'utilisateur doit pouvoir s'en rendre compte.

Le détail règle par règle est dans [`docs/contraintes.md`](docs/contraintes.md).

Ce diagnostic global se complète d'une explicabilité individuelle : un clic
sur un animateur affecté, dans le calendrier journalier, ouvre le détail
« Pourquoi lui ? » — les règles violées ou non pour ce poste précis, et une
simulation à la demande du delta de score si ce poste était donné à un autre
animateur compétent, sans rien changer au planning en cours.

### Découpage automatique en vacations

Pour un scénario « continu » où chaque jour n'est défini que par une seule
amplitude d'ouverture (ex. 10h-20h, ou 10h-minuit pour une journée + nocturne),
la page « Découpage » génère automatiquement les vacations de travail réelles
à partir de cette amplitude : plusieurs créneaux plus courts et chevauchants,
sans jamais dépasser 6h d'affilée pour un même animateur. La pause (et la
pause repas) de chacun est simplement le trou entre deux de ses vacations —
rien à saisir à la main. Un aperçu montre le découpage avant de le
matérialiser dans un groupe de créneaux dédié, activable en un clic. Le détail
de l'algorithme est dans [`docs/domaine.md`](docs/domaine.md#découpage-automatique-en-vacations).

### Gestion des référentiels

Écrans d'ajout / modification / suppression pour :

- les **animateurs** : identité, date de naissance (le régime applicable — moins
  de 16 ans, 16-18 ans, majeur — est toujours recalculé à la date du créneau,
  jamais saisi), compétences par typologie de jeu avec niveau (débutant /
  autonome / référent), et jours d'indisponibilité (par défaut, un animateur est
  disponible). **tous les
  animateurs sont rémunérés** et relèvent du même cadre légal de temps de
  travail ;
- les **stands** : typologies de jeux proposées, effectif minimum et maximum
  d'animateurs simultanés, restriction éventuelle aux majeurs, et fermetures
  (un stand est ouvert sur chaque créneau par défaut ; une fermeture peut ne
  couvrir qu'une partie d'un créneau, ex. fermé de 14 h à 16 h) — ou, à
  l'inverse pour un stand normalement fermé, des **ouvertures** ponctuelles
  (ex. ouvert seulement de 20 h à 23 h), pour éviter de saisir une fermeture
  sur chaque autre créneau du festival ;
- les **créneaux** : jour du festival, date, heures de début et de fin ;
- les **typologies de jeux**.

### Estimation du besoin en animateurs

Avant même de lancer une génération de planning, une page dédiée calcule, à
partir des stands et créneaux saisis dans les référentiels (effectif minimum
par stand, restriction éventuelle aux majeurs, stands ouverts par créneau) et
de la durée hebdomadaire maximale légale, le nombre minimum d'animateurs à
recruter. Deux minimums sont calculés, et le plus élevé des deux est retenu :

- le **pic de créneau** : sièges ouverts au créneau le plus chargé du
  festival, en supposant qu'un animateur puisse enchaîner n'importe quel
  créneau ;
- la **charge horaire** : le volume total d'heures-personne à couvrir,
  rapporté au plafond légal hebdomadaire sur le nombre de semaines ISO que
  couvre le festival — le pic de créneau seul ignore ce plafond, alors qu'il
  est souvent la contrainte la plus limitante sur un festival de plusieurs
  jours.

Ce minimum retenu est ensuite décomposé en **majeurs** et **mineurs**, en
respectant à la fois l'encadrement obligatoire (au moins un majeur dès qu'un
mineur est présent sur un stand) et l'équilibre visé entre les deux. Le détail
créneau par créneau reste affiché, avec le créneau le plus critique mis en
évidence.

Les deux minimums restent des estimations basses : ils supposent une
répartition parfaite et ignorent les compétences de chaque animateur ainsi que
la façon dont les indisponibilités réelles se superposent. Sur le scénario de
référence de l'application, le pic de créneau seul donnait 58 animateurs, la
charge horaire 102 — et il en fallait en réalité 150 pour obtenir un planning
réellement réalisable (score dur à zéro) une fois le solveur lancé. Une fois de
vrais animateurs saisis, la faisabilité réelle se vérifie sur la page «
Constraints ».

### Exceptions ponctuelles (contraintes ad hoc)

L'administrateur peut poser des règles au cas par cas, sans passer par le code,
avec une raison tracée :

- **indisponibilité forcée** — cet animateur ne doit jamais être affecté sur ce
  jour / créneau / stand ;
- **incompatibilité** — ces deux animateurs ne doivent jamais travailler sur le
  même créneau ;
- **affectation forcée** — cet animateur doit être présent sur ce créneau ou ce
  stand.

Ces exceptions sont traitées par le moteur au même niveau que les contraintes
dures : elles ne sont jamais contournées silencieusement.

### Consultation du planning

- **Calendrier mensuel** avec filtres par animateur et par stand, et détail des
  affectations au clic sur une journée ;
- **Vue par jour**, stand par stand et créneau par créneau ;
- **Page « Constraints »** : catalogue des règles actives et résultat de la
  dernière analyse, avec le score du planning et les contraintes en défaut — ce
  qui permet d'identifier précisément ce qui bloque quand aucun planning
  satisfaisant n'est trouvé.

### Restitution et échanges de données

- **Export PDF** du planning individuel d'un animateur, ou de tous les plannings
  individuels en une archive ZIP ;
- **Export ICS** du planning individuel, importable directement dans Google
  Calendar, Apple Calendar ou Outlook (également disponible en archive ZIP pour
  l'ensemble des animateurs) ;
- **Export / import d'un dump SQL** complet, pour dupliquer ou restaurer un jeu de
  données ;
- **Import d'un fichier scénario YAML** depuis le poste de l'utilisateur,
  avec notification détaillée en cas de fichier invalide.

Formats détaillés dans [`docs/import-export.md`](docs/import-export.md).

---

## Documentation

| Pour… | Voir |
| --- | --- |
| L'architecture technique | [`docs/architecture.md`](docs/architecture.md) |
| Le modèle de domaine | [`docs/domaine.md`](docs/domaine.md) |
| Le référentiel de contraintes | [`docs/contraintes.md`](docs/contraintes.md) |
| L'API REST | [`docs/api.md`](docs/api.md) |
| Les imports / exports | [`docs/import-export.md`](docs/import-export.md) |
| Contribuer (build, tests, CI, Renovate) | [`docs/developpement.md`](docs/developpement.md) |
| L'audit de conformité RH (Code du travail) | [`docs/audit-conformite-rh.md`](docs/audit-conformite-rh.md) |
| Les conventions suivies par les agents IA | [`AGENTS.md`](AGENTS.md) |
