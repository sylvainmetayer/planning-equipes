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
- repos (ou stand plus facile) après un créneau sur un stand physiquement
  épuisant, plutôt qu'un enchaînement direct vers un autre stand épuisant ;
- répartition équitable des créneaux pénibles (stands épuisants ou premium)
  entre animateurs ;
- journée peu dispersée : au-delà de trois emplacements distincts visités dans
  la même journée, la dispersion est pénalisée — quelles que soient les
  distances, là où la règle voisine ne regarde que les changements éloignés
  entre deux créneaux consécutifs ;
- rotation des stands d'un animateur au fil du festival ;
- mixité des niveaux (associer un débutant à un référent).

### Éditions

Toutes les données — stands, animateurs, typologies, emplacements, créneaux,
paramètres et planning résolu — appartiennent à une **édition** du festival :
« Année 2025 », « Année 2026 ». Rien ne circule de l'une à l'autre, ce qui
permet de garder 2025 consultable et de préparer 2026 à côté, au lieu
d'écraser l'une pour construire l'autre. Un bandeau en haut de chaque écran
rappelle en permanence quelle édition est consultée, et la page « Éditions »
permet d'en créer une vide, d'en **dupliquer** une existante (« 2026 = 2025
moins les affectations »), de la renommer ou de la supprimer.

L'édition consultée est un choix propre à chaque onglet du navigateur, pas un
état partagé : on peut relire les résultats de 2025 dans un onglet pendant
qu'on travaille sur 2026 dans l'autre. Le détail est dans
[`docs/editions.md`](docs/editions.md).

### Plans alternatifs (canicule, repli)

L'édition est l'unique porteur de variante : un plan alternatif est une
**édition dupliquée**, pas une grille parallèle. La veille d'une bascule
(canicule annoncée, imprévu), on duplique l'édition courante — la copie
embarque stands, horaires, animateurs et leurs indisponibilités du moment,
mais ni les affectations ni les liens d'espace, chaque édition frappant les
siens —, on applique les restrictions imposées dans la copie (l'édition en
masse des horaires s'y prête), on lance la résolution pour la nuit, et le
matin venu on bascule d'édition dans le bandeau puis on ré-envoie les
plannings. Au retour à la normale : re-bascule vers l'édition nominale,
restée intacte, et nouvel envoi. Une absence de dernière minute se saisit sur
la seule édition vivante — une fois.

### Instantanés de plan

Un seul planning est enregistré à la fois par édition : chaque résolution
écrase le précédent. Un **instantané** met un plan de côté, avec son score et
sa date, et permet de le remettre en place plus tard. Un instantané est pris
**automatiquement avant chaque résolution** — c'est le filet qui protège même
l'utilisateur qui n'y a pas pensé ; les cinq derniers sont conservés, ceux
enregistrés à la main ne sont jamais purgés.

Restaurer est refusé, sans rien écrire, si le référentiel a trop bougé depuis
la capture (un stand ou un créneau cité n'existe plus) : l'application dit ce
qui manque plutôt que de reconstituer un planning que personne n'a calculé.

### Historique des KPI

Chaque résolution terminée laisse une ligne de mesures : score par niveau,
couverture des postes, **équité** (dispersion des heures par animateur),
nombre de retouches manuelles actives au moment du calcul, et durée réelle du
solve. La page « Historique des KPI » les affiche **toutes éditions
confondues**, ce qui permet de répondre à « est-ce que 2026 est mieux réparti
que 2025 ? » sans rejouer quoi que ce soit.

L'historique est délibérément découplé du reste : aucune clé étrangère, et le
nom de l'édition recopié sur chaque ligne. Supprimer une édition efface son
référentiel et son planning, jamais la trace de ce qu'elle a produit — c'est
précisément ce qui doit lui survivre.

Rien de nominatif n'y est stocké : l'équité est un écart-type d'heures, pas un
classement de personnes.

### Simulation « et si ? »

Trois désistements, un recrutement de dernière minute, un stand fermé par la
météo, un stand qui demande une personne de plus : la page « Simulation »
mesure l'impact **sans toucher aux données**. La réponse immédiate est un
contrôle de capacité, affiché à côté de celui des données actuelles pour lire
l'écart ; une résolution courte, explicitement demandée et de durée affichée,
donne au besoin un score comparable. Rien n'est enregistré, et il n'y a
volontairement aucun bouton « appliquer pour de vrai ».

Plus généralement, si une donnée de référence (stand, animateur, créneau,
contrainte ad hoc, activation/désactivation de règle, ...) est modifiée après
la dernière résolution, un indicateur discret apparaît dans la barre d'outils
(et un rappel sur la page Solveur) : le résultat affiché peut ne plus être à
jour. C'est volontairement léger — modifier des données sans relancer tout de
suite le solveur est un usage courant en préparant un planning — mais
l'utilisateur doit pouvoir s'en rendre compte.

Le détail règle par règle est dans [`docs/contraintes.md`](docs/contraintes.md).

Ce diagnostic global se complète d'une explicabilité individuelle : un clic
sur un animateur affecté, dans le calendrier journalier ou le calendrier des
affectations, ouvre le détail « Pourquoi lui ? » — les règles violées ou non
pour ce poste précis, et une simulation à la demande du delta de score si ce
poste était donné à un autre animateur compétent, sans rien changer au
planning en cours.

### Découpage automatique en vacations

Pour un scénario « continu » où chaque jour n'est défini que par une seule
amplitude d'ouverture (ex. 10h-20h, ou 10h-minuit pour une journée + nocturne),
le découpage génère automatiquement les vacations de travail réelles (réglages sur la page « Paramètres », génération depuis la page « Créneaux »)
à partir de cette amplitude : plusieurs créneaux plus courts et chevauchants,
sans jamais dépasser 6h d'affilée pour un même animateur. La pause (et la
pause repas) de chacun est simplement le trou entre deux de ses vacations —
rien à saisir à la main. Un aperçu montre le découpage avant de le
matérialiser — les vacations remplacent alors les amplitudes en place. Le détail
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
  d'animateurs simultanés, restriction éventuelle aux majeurs, et leurs
  **horaires d'ouverture**. Un stand est ouvert sur chaque créneau par défaut,
  et son planning se saisit à deux niveaux :
  - des **règles récurrentes**, pour le motif qui se répète : « ouvert de 10 h à
    12 h puis de 14 h à la fermeture, tous les jours » tient en une règle à deux
    fenêtres, au lieu d'une plage datée par jour de festival. Une règle peut ne
    viser que certains jours de la semaine (« le week-end on ouvre dès 10 h »),
    une plage de dates ou des dates précises, et dire aussi bien quand le stand
    est ouvert que quand il est fermé. Laisser l'heure de fin vide veut dire
    « jusqu'à la fermeture », ce qui laisse une même règle couvrir un jour
    fermant à 20 h et un jour fermant à minuit ;
  - des **fermetures et ouvertures ponctuelles**, datées : elles priment sur les
    règles pour le seul jour qu'elles nomment, et peuvent ne couvrir qu'une
    partie d'un créneau (ex. fermé de 14 h à 16 h).

  Un aperçu jour par jour montre, sous l'éditeur, ce que le solveur lira une fois
  règles et exceptions résolues. Un bouton **« Compacter les horaires »** réécrit
  des plages datées répétées en règles équivalentes — utile pour des données
  saisies avant l'arrivée des règles ; il affiche d'abord ce qu'il ferait, et
  laisse inchangé tout stand dont les règles ne reproduiraient pas exactement les
  mêmes ouvertures ;
- les **créneaux** : jour du festival, date, heures de début et de fin ;
- les **emplacements** : lieux géolocalisés (kiosque, mairie…) auxquels un stand
  peut être rattaché ;
- les **typologies de jeux**.

Chaque écran permet de **cocher plusieurs lignes** pour les traiter d'un seul
geste : suppression multiple (après une confirmation unique — une ligne refusée
par le serveur, par exemple une typologie encore utilisée, n'annule pas les
autres), et modification en masse des champs partagés. Chaque champ d'une
modification en masse vaut « ne pas modifier » par défaut : seuls les champs
réellement renseignés sont écrits, les autres gardent la valeur propre à chaque
ligne. Sont modifiables ainsi :

- pour les **animateurs** : l'appréciation sur une typologie (ajout / retrait),
  les souhaits, le statut manager et un jour d'indisponibilité commun ;
- pour les **stands** : l'emplacement, les typologies proposées, l'effectif
  minimum / maximum et les indicateurs (majeurs, premium, niveau d'effort) ;
- pour les **créneaux** : les horaires ;
- pour les **emplacements** : les coordonnées GPS, saisies ou pointées sur la
  carte, appliquées à toute la sélection.

Chaque ligne de ces quatre référentiels ouvre une **vue de détail** (icône
« consulter ») : tout ce que porte l'élément, y compris ce que le formulaire de
saisie ne montre pas — les libellés humains des typologies plutôt que leurs
identifiants, les stands rattachés à un emplacement, les stands et animateurs
qui référencent une typologie, le statut majeur/mineur déduit de la date de
naissance. Pour un stand, chaque **règle d'horaire** est écrite en clair — ce
qu'elle ouvre ou ferme, les jours qu'elle couvre, ses fenêtres — ainsi que
chaque exception datée avec son motif. La modification reste accessible depuis cette vue, et y est bloquée
pendant une résolution du solveur comme partout ailleurs.

Chacun de ces référentiels (stands, emplacements, animateurs, typologies)
dispose d'un champ de **filtre rapide** : la table se réduit aux lignes
contenant tous les mots saisis, accents et casse ignorés, ce qui permet de
retrouver une information dans une liste de plusieurs dizaines (ou centaines)
de lignes sans la parcourir. Les actions de masse ne portent jamais que sur les
lignes affichées.

### Estimation du besoin en animateurs

Avant même de lancer une génération de planning, une page dédiée calcule le
nombre minimum d'animateurs à recruter. Le calcul part des **places réellement
à pourvoir** — exactement celles qu'une génération de planning devrait remplir,
horaires d'ouverture des stands résolus, vacations générées comprises — et non
d'un simple produit stands × créneaux, qui comptait plusieurs fois la même
heure du même stand dès qu'un planning était découpé en vacations. Trois
minimums sont calculés, et le plus élevé est retenu :

- le **pic simultané** : places ouvertes au même instant, au moment le plus
  chargé du festival — personne ne peut en tenir deux à la fois ;
- le **pic avec pause légale** : le même pic, chaque vacation prolongée de la
  pause minimale obligatoire entre deux vacations d'une même personne. C'est le
  nombre exact d'animateurs distincts qu'exige la journée la plus chargée :
  aucune durée de calcul, si longue soit-elle, ne descendra en dessous ;
- la **charge horaire** : le volume total d'heures-personne à couvrir, rapporté
  au plafond légal hebdomadaire sur le nombre de semaines que couvre le
  festival — un pic seul ignore ce plafond, alors qu'il est souvent la
  contrainte la plus limitante sur un festival de plusieurs jours.

Ce minimum retenu est ensuite décomposé en **majeurs** et **mineurs**, en
respectant à la fois l'encadrement obligatoire (au moins un majeur dès qu'un
mineur est présent sur un stand) et l'équilibre visé entre les deux. Le détail
journée par journée reste affiché, avec la journée la plus critique mise en
évidence.

Ces minimums restent des estimations basses : ils supposent une répartition
parfaite et ignorent les compétences de chaque animateur ainsi que la façon
dont les indisponibilités réelles se superposent. Une fois de vrais animateurs
saisis, la faisabilité réelle se vérifie sur la page « Contraintes ».

### Exceptions ponctuelles (contraintes ad hoc)

L'administrateur peut poser des règles au cas par cas, sans passer par le code,
avec une raison tracée :

- **indisponibilité forcée** — cet animateur ne doit jamais être affecté sur ce
  jour / créneau / stand ;
- **incompatibilité** — ces deux animateurs ne doivent jamais travailler sur le
  même créneau ;
- **affectation forcée** — cet animateur doit être présent sur ce créneau ou ce
  stand ;
- **affinité** — ces deux animateurs fonctionnent bien ensemble : les mettre
  sur le même stand quand c'est possible, sans jamais l'imposer.

Les trois premières exceptions sont traitées par le moteur au même niveau que
les contraintes dures : elles ne sont jamais contournées silencieusement.
L'affinité est au contraire une préférence : le moteur récompense chaque
créneau où la paire est réunie sur un même stand, mais ne sacrifie ni
l'équilibre de charge ni les disponibilités pour y parvenir. Déclarer une même
paire à la fois incompatible et en affinité est refusé à la saisie.

La page « Contraintes ad hoc » est rangée dans la section « En cours de
développement » de la navigation et affiche un bandeau d'avertissement : la
fonctionnalité est utilisable, mais son comportement et les données saisies
peuvent encore évoluer.

### Verrouillage partiel du planning

Quand une partie du planning a été validée, on peut la **geler** pour que la
prochaine résolution ne la remette pas en cause et n'optimise que le reste :

- **un animateur** — son planning est considéré satisfaisant : ses postes ne
  bougent plus et aucun poste supplémentaire ne lui est attribué ;
- **un stand** — sur l'ensemble de ses créneaux ;
- **une journée** — tous les créneaux de la journée ;
- **un créneau** en particulier.

Une place restée non pourvue n'est jamais gelée : elle resterait vide
définitivement. Les places gelées continuent d'être évaluées par les règles
métier, donc un verrouillage peut laisser une alerte visible plutôt que de
masquer un problème.

Les verrous se gèrent depuis la page « Verrouillages » et sont signalés par un
cadenas dans les calendriers. Ils appartiennent à leur édition, comme le reste
du référentiel.

Comme les contraintes ad hoc, cette page est rangée dans la section « En cours
de développement » de la navigation et affiche un bandeau d'avertissement.

### Foire au planning : échanges de créneaux en libre-service

Chaque animateur dispose d'un **espace personnel** accessible par un lien
imprimé sur son planning PDF — aucun compte à créer. Il y consulte son
planning à jour (avec ses coéquipiers), et peut y proposer d'**échanger un de
ses créneaux** avec un collègue : il constitue sa liste de demandes (créneau,
collègue, motif) puis la soumet en une fois. Il peut aussi **désigner le
créneau du collègue qu'il veut récupérer** en échange du sien (« je te laisse
mon lundi et je prends ton mardi, je préfère être libre lundi ») — sans ce
choix, l'échange se joue sur son seul créneau.

Le **collègue ciblé donne d'abord son accord** depuis son propre espace :
la demande n'atteint l'organisation qu'une fois les deux animateurs d'accord
(décliner la clôt directement) — plus besoin de demander à chacun.

Chaque demande est **prévalidée** contre les règles dures du planning : si
l'échange poserait un problème (temps de travail dépassé, repos insuffisant…),
l'animateur en est informé en langage métier — la demande part quand même,
l'organisation tranche.

Côté organisation, l'écran **Échanges** liste les demandes en attente avec
leur impact mesuré sur le planning actuel (échange croisé ou simple reprise,
effet sur le score, règles impactées). **Accepter** applique l'échange
immédiatement, exactement comme simulé, et le **verrouille** sur son créneau :
une régénération ultérieure du planning ne le défera pas. **Refuser** ne
modifie rien ; dans les deux cas l'animateur est prévenu par e-mail (si son
adresse est renseignée sur sa fiche), avec le commentaire éventuel de
l'organisation. Aucun échange n'est jamais appliqué sans cette validation
explicite.

L'accès à l'application d'administration est désormais protégé par un compte
administrateur ; seuls les espaces animateurs restent accessibles par leur
lien personnel.

### Consultation du planning

- **Ouvertures des stands** : une grille stand × jour de ce que le planning
  retiendra réellement, une fois les règles d'horaire étendues, les exceptions
  datées appliquées et chaque fenêtre bornée aux créneaux du planning actif.
  Chaque case dit d'un coup d'œil si le stand est ouvert toute la journée,
  partiellement ou pas du tout, quelle part de l'amplitude il couvre, combien de
  places il génère, et **qui a décidé** de ce jour-là — rien, une règle, ou une
  exception datée. La page signale aussi les trois façons dont une saisie
  d'horaires part habituellement de travers : un stand qui n'est finalement
  ouvert aucun jour, une fenêtre saisie en dehors des heures du jour (elle ne
  change rien), et une plage ouverte trop courte pour être un vrai créneau de
  travail. À utiliser avant de lancer un calcul : c'est le moyen de valider des
  horaires sans attendre plusieurs minutes de résolution pour découvrir qu'un
  stand était fermé par erreur ;
- **Calendrier mensuel** avec filtres par animateur et par stand, et détail des
  affectations au clic sur une journée ;
- **Vue par jour**, stand par stand et créneau par créneau ;
- **Heatmap de charge**, par jour croisé avec le stand (places pourvues /
  requises, pour repérer les trous de couverture) ou avec l'animateur (nombre
  de postes par jour, pour repérer les surcharges — une pastille colorée par
  typologie de jeu suit le nom de l'animateur, et le survol liste les stands
  distincts sur lesquels il intervient ainsi que les typologies couvertes) ;
- **Timeline individuelle par animateur** : récapitulatif des stands à couvrir
  (nombre, dénomination et nombre de typologies de jeu différentes, chaque
  stand étant coloré selon sa typologie) puis amplitude journalière, vacations et
  pauses/déplacements entre elles, jour par jour — utile en réparation
  manuelle d'un planning, avec export PDF ou ICS du planning de l'animateur
  affiché. Chaque vacation nomme les **coéquipiers** présents sur le même stand
  au même moment, ou signale que l'animateur y sera seul — l'information la plus
  demandée avant d'arriver sur place, présente aussi sur le PDF individuel ;
- **Heures planifiées par animateur**, semaine ISO par semaine ISO, avec une
  ligne de total « tous les animateurs » : le volume horaire que représente
  l'événement entier, et la moyenne par animateur ;
- **Page « Constraints »** : catalogue des règles actives et résultat de la
  dernière analyse, avec le score du planning et les contraintes en défaut — ce
  qui permet d'identifier précisément ce qui bloque quand aucun planning
  satisfaisant n'est trouvé.

### Aide intégrée

Un écran « Aide » réunit le mode d'emploi de l'application : l'ordre dans lequel
enchaîner les écrans pour construire un planning, le rôle de chaque référentiel,
les réglages qui agissent réellement sur le solveur (durée de résolution,
paramètres légaux, activation des contraintes, contraintes ad hoc,
verrouillages, paramètres de découpage), la lecture du score dur / medium /
souple et des écrans de diagnostic, puis un guide symptôme par symptôme de ce
qu'il faut corriger — et dans quel ordre — quand un planning n'est pas
réalisable. Le contenu est cherchable et ne dépend d'aucune donnée : la page
reste consultable pendant qu'une résolution tourne, ou sur une édition encore
vide.

### Assistant IA

Un serveur MCP permet à un assistant IA de consulter et piloter
l'application en langage naturel : ajouter ou modifier des animateurs, des
stands, des créneaux et des typologies, régler les paramètres légaux et de
découpage, importer un scénario, activer ou désactiver une contrainte, lancer
ou arrêter une résolution, puis explorer le planning obtenu (heures
travaillées, explication d'une affectation, simulation d'un échange, détail
des contraintes légales encore violées). Les données personnelles des
animateurs — nom, prénom, date de naissance — ne sortent jamais par ce canal :
seuls circulent l'identifiant et le statut majeur/mineur. Protégé par une clé
API, désactivé tant qu'aucune clé n'est configurée. Détails dans
[`docs/mcp.md`](docs/mcp.md).

### Restitution et échanges de données

- **Export PDF global**, pour l'organisateur : un seul document reprenant
  toutes les affectations, d'abord journée par journée (qui tient quoi, à quelle
  heure, à quel emplacement), puis stand par stand (qui s'y relaie sur tout le
  festival). Chaque ligne affiche l'effectif pourvu sur l'effectif attendu et
  signale en rouge les places restées vides ;
- **Export PDF** du planning individuel d'un animateur, ou de tous les plannings
  individuels en une archive ZIP. Les journées du festival sans affectation y
  figurent explicitement comme jours de **repos**, dans le PDF comme dans
  l'espace en ligne de l'animateur — un jour absent se lirait comme un oubli ;
- **Export ICS** du planning individuel, importable directement dans Google
  Calendar, Apple Calendar ou Outlook (également disponible en archive ZIP pour
  l'ensemble des animateurs) ; les jours de repos y apparaissent en événements
  « journée entière » qui ne bloquent pas la disponibilité du calendrier ;
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
