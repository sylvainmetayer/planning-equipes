# Planning Équipes

Application de gestion de planning pour un festival de jeux de société de
15 jours : elle affecte automatiquement ~150 animateurs aux stands, en respectant
le cadre légal (notamment celui des mineurs), les compétences, les disponibilités
et l'équité de charge.

Ce README couvre deux choses : **démarrer l'application en local** et
**l'inventaire de ce qu'elle sait faire**. Le mode d'emploi, lui, est dans
l'application (page « Aide »), et la documentation technique (architecture,
API, modèle de domaine, contraintes, formats d'import/export, contribution)
dans [`docs/`](docs/README.md).

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

1. Ouvrir <http://localhost:8080> et se connecter (compte `admin`, mot de passe
   `admin` par défaut en local — variable `ADMIN_PASSWORD`) — la page
   **Solver** s'affiche ; le menu latéral donne accès à chaque écran.
2. Cliquer sur **Generate sample planning** pour charger le jeu de données
   d'exemple (les référentiels sont ensuite modifiables depuis **Stands**,
   **Animateurs**, **Créneaux**, **Typologies** et **Ad hoc constraints**).
3. Cliquer sur **Solve with Timefold** : la résolution part en tâche de fond
   (plusieurs minutes sur le scénario complet), la navigation reste libre et une
   notification s'affiche à la fin — y compris dans les autres navigateurs
   ouverts sur l'application, qui voient le calcul en cours et son temps écoulé.
4. Consulter le résultat dans **Assignment calendar** (vue mensuelle) ou
   **Day calendar** (vue par jour), et le respect des règles dans **Constraints**.
5. Exporter le planning : le PDF global de l'organisateur ou l'archive complète
   des plannings individuels (PDF + ICS) depuis la page **Solveur**, ou le
   planning d'un seul animateur depuis la page **Timeline animateur**.

### Configuration

| Variable | Défaut | Usage |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/festival` | Connexion PostgreSQL |
| `DB_USER` / `DB_PASSWORD` | `festival` / `festival` | Identifiants base |
| `HTTP_PORT` | `8080` | Port HTTP exposé |
| `SENTRY_DSN` | *(vide = désactivé)* | Suivi d'erreurs (Bugsink ou tout endpoint compatible Sentry) |
| `SENTRY_ENVIRONMENT` | `local` | Étiquette d'environnement jointe aux erreurs remontées |
| `PLANNING_MCP_API_KEY` | *(vide = MCP inutilisable)* | Clé API attendue pour authentifier le serveur MCP |
| `PLANNING_MCP_API_KEY_HEADER` | `X-MCP-Api-Key` | En-tête HTTP portant la clé (ou `Authorization: Bearer <clé>`) |
| `PLANNING_MCP_REQUIRED_HEADERS` | *(vide)* | En-têtes supplémentaires exigés en plus de la clé, `Nom=valeur` séparés par des virgules (déploiement derrière un proxy type Pangolin) |
| `ADMIN_PASSWORD` | `admin` | Mot de passe du compte administrateur `admin` (à changer hors local) |
| `PROXY_ADDRESS_FORWARDING` | `true` | Suivre les en-têtes `X-Forwarded-*` d'un reverse proxy qui termine le TLS, indispensable pour que la redirection de connexion reste en `https` — voir [`api.md`](docs/api.md#derrière-un-reverse-proxy-qui-termine-le-tls) |
| `REMOTE_USER_ENABLED` | `false` | Authentification par en-tête derrière un proxy d'accès, en plus du form login — voir [`api.md`](docs/api.md#authentification-par-en-tête-remote-user-facultative) |
| `REMOTE_USER_SECRET` | — | Secret partagé avec le proxy. **Obligatoire** si `REMOTE_USER_ENABLED=true` : sans lui le démarrage échoue |
| `REMOTE_USER_ADMIN_EMAIL` | — | Adresse qui obtient le rôle admin ; les autres adresses reconnues sont des animateurs |
| `SESSION_ENCRYPTION_KEY` | *(vide = clé générée au démarrage)* | Clé (≥ 16 caractères) de chiffrement du cookie de session admin ; la définir pour que les sessions survivent aux redémarrages |
| `MAIL_HOST` / `MAIL_PORT` | `localhost` / `1025` | Serveur SMTP des notifications d'échange (Mailpit en local) |
| `MAIL_MOCK` | `false` (tests : toujours mockés) | `true` : les mails sont journalisés au lieu d'être envoyés |
| `MAIL_FROM` | `planning-equipes@localhost` | Adresse expéditrice |
| `MAIL_ADMIN` | *(vide = désactivé)* | Adresse prévenue quand des demandes d'échange sont soumises |
| `PUBLIC_URL` | `http://localhost:8080` | URL publique de l'application, imprimée comme lien « espace animateur » sur les PDF |

Détails et mise en place : [`docs/observabilite.md`](docs/observabilite.md) (Sentry/Cloudflare),
[`docs/mcp.md`](docs/mcp.md) (serveur MCP).

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

**Le mode d'emploi est dans l'application**, sur la page « Aide » (menu
*Planning → Aide*) : à quoi sert chaque écran, dans quel ordre les enchaîner,
comment lire un score, et quoi corriger — dans quel ordre — quand un planning
n'est pas réalisable. Cette page vit avec le code et se cherche au clavier ;
elle ne peut donc pas se périmer comme le ferait un README.

La liste ci-dessous dit seulement **ce qui existe**. Pour le fonctionnement
interne (modèle, contraintes, API, formats), voir [`docs/`](docs/README.md).

### Planifier

| Fonctionnalité | En une phrase |
| --- | --- |
| Génération automatique du planning | Le moteur d'optimisation affecte les animateurs aux places à pourvoir, sous trois niveaux d'exigence : le cadre légal et les incompatibilités (jamais franchis), la couverture des postes, puis l'équité, les souhaits et le confort |
| Découpage automatique en vacations | Transforme l'amplitude d'ouverture d'une journée en vacations réelles : durée cible, relais, pauses repas et stratégie de couverture pendant la pause |
| File d'attente du solveur | Planifier une résolution derrière celle qui tourne : elle démarre d'elle-même, ce qui permet de préparer l'édition suivante sans attendre devant l'écran |
| Replanification incrémentale | Repart du planning enregistré, fige ce qui reste valable et ne recalcule que ce qu'un changement tardif a invalidé — quelques dizaines de secondes au lieu de plusieurs minutes |
| Verrouillage partiel | Geler un animateur, un stand, une journée ou un créneau pour que la prochaine résolution n'y touche plus et optimise le reste |
| Exceptions ponctuelles | Contraintes ad hoc tracées avec leur raison : indisponibilité forcée, incompatibilité entre deux personnes, affectation imposée, paire à privilégier |

### Décider et diagnostiquer

| Fonctionnalité | En une phrase |
| --- | --- |
| Problèmes | Vue unique des blocages, triés par gravité : causes d'infaisabilité détectées sans résolution, et règles encore en défaut après la dernière analyse |
| Ouvertures des stands | Grille stand × jour de ce que le planning retiendra réellement, et les trois erreurs de saisie d'horaires habituelles — à vérifier avant de lancer un calcul |
| Besoin en animateurs | Effectif minimum estimé à partir des seuls stands et créneaux : dit si le problème est un manque de monde plutôt qu'un manque de temps de calcul |
| Catalogue des contraintes | Toutes les règles, leur niveau, et le résultat de la dernière analyse — activables ou désactivables une par une pour diagnostiquer |
| Simulation « et si ? » | Impact d'un désistement, d'un recrutement ou de la fermeture d'un stand, sans rien écrire |
| Comparateur A/B | Deux plannings côte à côte — deux instantanés, ou un instantané et le plan actuel — sur le score, la couverture, l'équité et les violations, toutes éditions confondues |
| Historique des KPI | Une ligne de mesures par résolution terminée, toutes éditions confondues et sans rien de nominatif ; survit à la suppression de l'édition décrite |
| Instantanés de plan | Met un planning de côté avec son score et sa date, et le remet en place plus tard ; une capture est prise automatiquement avant chaque résolution |

### Organiser l'année

| Fonctionnalité | En une phrase |
| --- | --- |
| Éditions | Tout le référentiel et les résultats sont cloisonnés par édition (« Année 2025 », « Année 2026 ») ; deux onglets peuvent travailler sur deux éditions à la fois |
| Plans alternatifs | Dupliquer l'édition pour préparer un scénario de repli (canicule…), le résoudre à l'avance, et basculer le matin venu |

### Référentiels

| Fonctionnalité | En une phrase |
| --- | --- |
| Stands | Typologies, effectifs minimum et maximum, restriction aux majeurs, indicateurs premium et effort, horaires en règles récurrentes complétées d'exceptions datées |
| Animateurs | Identité, compétences par typologie et niveau, souhaits, jours d'indisponibilité ; le régime légal se déduit de l'âge à la date de chaque créneau |
| Créneaux | Découpage temporel que le solveur remplit, saisi à la main ou généré par le découpage |
| Typologies | Vocabulaire commun entre compétences et jeux d'un stand, dont la typologie « ninja » des polyvalents |
| Emplacements | Lieux géolocalisés rattachés aux stands, choisis sur une carte, pour éviter les déplacements lointains d'un créneau à l'autre |

### Consulter le planning

| Fonctionnalité | En une phrase |
| --- | --- |
| Calendrier des affectations | Vue mensuelle avec filtres par animateur et par stand, et détail au clic sur une journée |
| Calendrier journalier | Une journée, stand par stand et créneau par créneau |
| Heures | Heures planifiées par animateur, semaine ISO par semaine ISO, avec le total de l'événement |
| Heatmap de charge | Jour croisé avec le stand (trous de couverture) ou avec l'animateur (surcharges) |
| Timeline animateur | Le planning d'une personne : amplitude, vacations, pauses et coéquipiers présents sur le même stand |
| Graphe | Navigation descendante des lieux vers les stands puis vers les personnes |
| Notifications | Journal des alertes de l'édition — résolutions terminées, contraintes en défaut, erreurs de saisie — consultable après coup |

### Diffuser et échanger

| Fonctionnalité | En une phrase |
| --- | --- |
| Export PDF global | Toutes les affectations dans un seul document pour l'organisateur, journée par journée puis stand par stand, places vides signalées |
| Export PDF individuel | Le planning d'un animateur, ou de tous en une archive ; les journées sans affectation y figurent explicitement comme jours de repos |
| Export ICS | Le planning individuel importable dans Google Calendar, Apple Calendar ou Outlook |
| Envoi par e-mail | Envoyer à chaque animateur son planning et le lien vers son espace, en une action |
| Espace animateur | Un espace personnel par lien nominatif : son planning, ses jours de repos, ses demandes d'échange — sans compte à créer |
| Foire au planning | Les animateurs proposent leurs échanges de créneaux en libre-service ; le collègue visé donne son accord, l'organisation arbitre, rien ne s'applique sans validation |

### Outils

| Fonctionnalité | En une phrase |
| --- | --- |
| Import / export de scénario | Un fichier YAML décrit une configuration complète de festival ; l'import valide le fichier et explique ce qui cloche |
| Export / import d'un dump SQL | Dupliquer ou restaurer un jeu de données complet |
| Assistant IA (MCP) | Un assistant IA consulte et pilote l'application en langage naturel, sans jamais voir les données personnelles des animateurs — voir [`docs/mcp.md`](docs/mcp.md) |
| Aide intégrée | Le mode d'emploi complet, cherchable, consultable pendant qu'une résolution tourne ou sur une édition vide |
| Accès | Connexion administrateur par mot de passe, ou attestation par en-tête derrière un proxy d'accès ; les espaces animateurs restent joignables par leur lien |

Formats d'échange détaillés dans
[`docs/import-export.md`](docs/import-export.md), contraintes dans
[`docs/contraintes.md`](docs/contraintes.md).

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
