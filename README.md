# Planning Équipes

Application de gestion de planning pour un événement : elle affecte
automatiquement les animateurs aux stands, en respectant le cadre légal
(notamment celui des mineurs), les compétences, les disponibilités et l'équité
de charge.

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

⚠️ Cette pile est faite pour le poste de développement : elle publie PostgreSQL
sur l'hôte, ajoute pgAdmin en `admin/admin` et des mots de passe par défaut.
Pour un déploiement accessible depuis Internet, utiliser
`docker-compose.prod.yml` et lire [`docs/securite.md`](docs/securite.md).

### Option B — mode développement (rechargement à chaud)

```bash
docker compose up -d postgres     # base seule
./mvnw quarkus:dev                # application sur http://localhost:8080
```

Le code Java comme le frontend Angular sont rechargés à chaud : Quarkus démarre
aussi le serveur de développement Angular et le proxifie, tout passe donc par
<http://localhost:8080>.

### Se connecter au registre d'images

```bash
echo $CR_PAT | docker login ghcr.io -u USERNAME --password-stdin
```

### Premiers pas dans l'application

1. Ouvrir <http://localhost:8080> et se connecter (compte `admin`, mot de passe
   `admin` par défaut en local — variable `ADMIN_PASSWORD`) — la page
   **Solveur** s'affiche ; le menu latéral donne accès à chaque écran (en
   mode simple par défaut : « Menu simple », en tête du menu, bascule vers le
   menu avancé qui liste aussi la quinzaine d'écrans spécialisés — diagnostic
   approfondi, vues d'analyse et outils techniques).
2. Sur **Paramètres**, choisir un scénario puis **Charger le scénario
   sélectionné** pour remplir l'édition courante (les référentiels sont ensuite
   modifiables depuis **Stands**, **Emplacements**, **Animateurs**,
   **Créneaux** et **Typologies**).
3. Revenir sur **Solveur** et cliquer sur **Calculer le planning** : la résolution
   part en tâche de fond (plusieurs minutes sur le scénario complet), la
   navigation reste libre, le score se trace en direct et une notification
   s'affiche à la fin — y compris dans les autres navigateurs ouverts sur
   l'application, qui voient le calcul en cours et son temps écoulé.
4. Consulter le résultat dans **Calendrier des affectations** (vue mensuelle) ou
   **Journée** (une journée sous quatre rendus : par stand, par animateur, sur
   la carte, par ses pauses), ce qui bloque dans **Diagnostic**, et le respect
   des règles dans **Contraintes**.
5. Exporter le planning : le PDF global de l'organisateur ou l'archive complète
   des plannings individuels (PDF + ICS) depuis la page **Solveur**, ou le
   planning d'un seul animateur depuis la page **Timeline animateur**.
6. Le mode d'emploi complet est dans l'application, page **Aide** (menu
   *Planning → Aide*, ou <kbd>Ctrl</kbd>+<kbd>K</kbd> puis « aide »).

### Configuration

| Variable | Défaut | Usage |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/festival` | Connexion PostgreSQL |
| `DB_USER` / `DB_PASSWORD` | `festival` / `festival` | Identifiants base. En production, le mot de passe d'exemple refuse le démarrage |
| `HTTP_PORT` | `8080` | Port HTTP exposé |
| `SENTRY_DSN` | *(vide = désactivé)* | Suivi d'erreurs (Bugsink ou tout endpoint compatible Sentry) |
| `SENTRY_ENVIRONMENT` | `local` | Étiquette d'environnement jointe aux erreurs remontées |
| `BACKUP_DIR` | *(vide = sauvegarde automatique désactivée)* | Répertoire où la sauvegarde de nuit écrit ses `pg_dump` — voir [`docs/exploitation.md`](docs/exploitation.md) |
| `JOURNAL_RETENTION` | `P90D` | Durée de conservation de l'historique des actions ; la purge passe avec les envois de nuit |
| `BACKUP_RETENTION` | `10` | Nombre de sauvegardes conservées, entre 1 et 30 ; hors bornes, le démarrage est refusé |
| `BACKUP_TIMEZONE` | `Europe/Paris` | Fuseau dans lequel se lit l'heure de la sauvegarde (4 h) |
| `PLANNING_MCP_API_KEY` | *(vide = MCP inutilisable)* | Clé API attendue pour authentifier le serveur MCP |
| `PLANNING_MCP_API_KEY_HEADER` | `X-MCP-Api-Key` | En-tête HTTP portant la clé (ou `Authorization: Bearer <clé>`) |
| `PLANNING_MCP_REQUIRED_HEADERS` | *(vide)* | En-têtes supplémentaires exigés en plus de la clé, `Nom=valeur` séparés par des virgules (déploiement derrière un proxy type Pangolin) |
| `PLANNING_MCP_PANGOLIN_ACCESS_TOKEN_ID` | *(vide)* | Identifiant du jeton d'accès Pangolin, révélable depuis la page MCP (menu avancé ; même contrôle par mot de passe admin que la clé API) |
| `PLANNING_MCP_PANGOLIN_ACCESS_TOKEN` | *(vide)* | Jeton d'accès Pangolin correspondant, révélable de la même façon |
| `ADMIN_PASSWORD` | `admin` | Mot de passe du compte administrateur `admin`. En production, le défaut refuse le démarrage : il faut en donner un |
| `PROXY_ADDRESS_FORWARDING` | `true` | Suivre les en-têtes `X-Forwarded-*` d'un reverse proxy qui termine le TLS, indispensable pour que la redirection de connexion reste en `https` — voir [`api.md`](docs/api.md#derrière-un-reverse-proxy-qui-termine-le-tls) |
| `REMOTE_USER_ENABLED` | `false` | Authentification par en-tête derrière un proxy d'accès, en plus du form login — voir [`api.md`](docs/api.md#mode-remote-user-facultatif-désactivé-par-défaut) |
| `REMOTE_USER_SECRET` | — | Secret partagé avec le proxy. **Obligatoire** si `REMOTE_USER_ENABLED=true` : sans lui le démarrage échoue |
| `REMOTE_USER_ADMIN_EMAIL` | — | Adresse qui obtient le rôle admin ; les autres adresses reconnues sont des animateurs |
| `SESSION_ENCRYPTION_KEY` | *(vide = clé générée au démarrage)* | Clé (≥ 16 caractères) de chiffrement du cookie de session admin ; la définir pour que les sessions survivent aux redémarrages |
| `MAIL_HOST` / `MAIL_PORT` | `localhost` / `1025` | Serveur SMTP des notifications d'échange (Mailpit en local) |
| `MAIL_MOCK` | `false` (tests : toujours mockés) | `true` : les mails sont journalisés au lieu d'être envoyés |
| `MAIL_FROM` | `planning-equipes@localhost` | Adresse expéditrice |
| `MAIL_ADMIN` | *(vide = désactivé)* | Adresse de l'administrateur : demandes d'échange soumises, et fin de résolution si l'édition le demande |
| `BRANDING_PRODUCT_NAME` | `Planning Équipes` | Nom du produit : onglet du navigateur, titre de chaque page, sujets des mails, en-tête du dump SQL, `PRODID` des exports ICS, en-tête des PDF |
| `BRANDING_ORGANISATION` | *(vide)* | Client pour lequel cette instance est déployée, imprimé au pied des PDF et des mails ; vide = seule la date de génération y figure |
| `BRANDING_LOGO_URL` | *(vide = aucun logo)* | URL du logo affiché dans les barres d'outils et sur la carte de connexion (`logo.png` pour un fichier servi à la racine, ou une URL absolue) |
| `BRANDING_ACCENT_COLOR` | *(vide = accent Material compilé)* | Couleur d'accent de l'IHM, toute couleur CSS ; alimente `--app-accent`. À choisir sur le thème clair : le thème sombre en dérive une jumelle éclaircie |
| `BRANDING_MASCOT_URL` | *(vide = pas d'easter egg)* | Mascotte du déploiement en grand, montrée par le code Konami ; même syntaxe que `BRANDING_LOGO_URL` |
| `BRANDING_MASCOT_ICON_URL` | *(vide = icône Material)* | La même mascotte découpée en petit : elle tourne dans la barre pendant une résolution et saute sur l'invite de défilement |
| `BRANDING_SUPPORT_EMAIL` | `planning@sylvain.dev` | Adresse de support nommée par la page Aide (« Contact et support ») ; vide = le paragraphe et son lien disparaissent |
| `BRANDING_PDF_LOGO` | *(vide = aucun logo)* | Logo de l'en-tête des PDF et de la version HTML des mails (embarqué dans le message, jamais chargé à distance) : `classpath:/branding/xxx.png` pour une image embarquée, sinon un chemin de fichier monté |
| `BRANDING_PDF_STRIP` | *(vide = aucun bandeau)* | Bandeau décoratif de la première page du planning individuel, même syntaxe |
| `BRANDING_PDF_HEADLINE` | `#1f2933` | Encre principale des PDF (titres, noms, corps des tableaux) |
| `BRANDING_PDF_MUTED` | `#6b7280` | Texte secondaire des PDF (horaires, emplacements, coéquipiers, pied de page) |
| `BRANDING_PDF_ACCENT` | `#3a6ea5` | Accent des PDF (pastilles de journée, titres d'encadré, bordures de carte, alertes) et des mails HTML (liseré, liens, code d'accès) — la palette `BRANDING_PDF_*` habille aussi les mails |
| `BRANDING_PDF_HIGHLIGHT` | `#e4eaf1` | Fond des tuiles de statistiques et des en-têtes de tableau |
| `BRANDING_PDF_PILL` | `#f1f4f8` | Fond des pastilles d'horaire et couleur des filets de tableau |
| `LEGAL_EDITEUR` | *(vide)* | Éditeur du site (nom, forme juridique, adresse, immatriculation) affiché sur `/mentions-legales` |
| `LEGAL_DIRECTEUR_PUBLICATION` | *(vide)* | Directeur de la publication |
| `LEGAL_HEBERGEUR` | *(vide)* | Hébergeur (nom et adresse) |
| `LEGAL_CONTACT` | *(vide)* | Adresse de contact, y compris pour exercer ses droits sur ses données |
| `LEGAL_RESPONSABLE_TRAITEMENT` | *(vide = l'éditeur)* | Responsable de traitement au sens du RGPD, quand il diffère de l'éditeur au sens de la LCEN |
| `LEGAL_BASE_LEGALE` | *(vide)* | Base légale du traitement des données personnelles |
| `LEGAL_CONSERVATION` | *(vide)* | Durée de conservation des données personnelles |
| `PUBLIC_URL` | `http://localhost:8080` | URL publique de l'application, imprimée comme lien « espace animateur » sur les PDF |
| `CSP` | *(politique par défaut)* | Politique de sécurité du contenu envoyée à chaque réponse ; vide = en-tête désactivé — voir [`securite.md`](docs/securite.md) |
| `HSTS` | `max-age=31536000; includeSubDomains` | En-tête HSTS, envoyé uniquement sur une visite HTTPS ; vide = désactivé |
| `MAX_BODY_SIZE` | `10M` | Taille maximale d'un corps de requête — dimensionnée par l'import de dump SQL |
| `MAX_CONNECTIONS` | `500` | Connexions HTTP simultanées acceptées |
| `ESPACE_CODE_MAX_DEMANDES` | `3` | Codes d'accès non utilisés tolérés par animateur avant `429` — voir [`securite.md`](docs/securite.md) |
| `ESPACE_CODE_FENETRE` | `PT10M` | Fenêtre sur laquelle ce plafond se compte |
| `CONNEXION_MAX_ECHECS` | `5` | Échecs de connexion admin tolérés par adresse avant verrouillage |
| `CONNEXION_DUREE_BLOCAGE` | `PT15M` | Durée du verrouillage, comptée depuis le dernier échec |

#### Marque blanche

Le modèle de déploiement est **une instance par client** : l'identité se règle
donc par déploiement, avec les variables `BRANDING_*` ci-dessus, et rien n'est
stocké en base — pas d'écran d'administration de la marque, un redémarrage est
le seul moment où un logo change. Le frontend les lit une fois, avant son
démarrage, sur l'endpoint public `GET /api/branding`.

Sans aucune de ces variables, l'application démarre, s'appelle « Planning
Équipes », **n'affiche aucun logo** plutôt que celui d'un autre, et imprime ses
documents dans une palette gris-bleu neutre.

Deux limites à connaître :

- la **palette d'Angular Material** (boutons, barres, champs) est compilée par
  `mat.theme()` dans `src/main/webui/src/material-theme.scss` :
  `BRANDING_ACCENT_COLOR` ne pilote que `--app-accent`, lu par les feuilles de
  style propres à l'application. Recolorer les composants Material eux-mêmes
  demande une recompilation du frontend ;
- les **images** ne sont pas téléversables depuis l'application. Un logo se
  fournit soit par une URL (`BRANDING_LOGO_URL` — la CSP par défaut accepte
  `https:` et `data:`), soit par un fichier monté à côté du conteneur
  (`BRANDING_PDF_LOGO`), soit par une image embarquée dans le jar
  (`classpath:/branding/…`). **Aucun visuel n'est livré avec le dépôt** : les
  images d'un déploiement appartiennent à son client, elles se montent à côté
  du conteneur ou se servent par URL.

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
| Stabilité du plan publié | Une fois le planning envoyé, chaque personne déplacée coûte au solveur : un recalcul après un changement tardif bouge le minimum de gens déjà prévenus, et le récapitulatif dit combien la publication préviendrait |
| Trois façons de lancer le solveur | « Calculer le planning » repart du plan enregistré s'il existe et cherche à l'améliorer, sans rien figer hormis les verrouillages ; « Corriger après un changement » fige ce qui tient et ne recalcule que les postes rouverts ; « Recommencer de zéro » abandonne l'acquis, et le dit avant. La page annonce d'où partira le prochain calcul et d'où le dernier est parti |
| Découpage automatique en vacations | Transforme l'amplitude d'ouverture d'une journée en vacations réelles : durée cible, relais, pauses repas et stratégie de couverture pendant la pause |
| File d'attente du solveur | Planifier une résolution derrière celle qui tourne : elle démarre d'elle-même, ce qui permet de préparer l'édition suivante sans attendre devant l'écran. La file survit à un redémarrage du serveur ; la résolution qui était en cours, elle, est perdue et signalée comme interrompue |
| Courbe de score en direct | Les trois niveaux de score se tracent pendant la résolution, chacun à sa propre échelle, pour voir quand le calcul plafonne et l'arrêter à propos plutôt qu'attendre la fin du budget. Seule la résolution en cours est tracée |
| Replanification incrémentale | Repart du planning enregistré, fige ce qui reste valable et ne recalcule que ce qu'un changement tardif a invalidé — quelques dizaines de secondes au lieu de plusieurs minutes |
| Verrouillage partiel | Geler un animateur, un stand, une journée ou un créneau pour que la prochaine résolution n'y touche plus et optimise le reste. Un verrou est un geste sur un plan déjà calculé : il conserve ce qu'une résolution a produit, sans rien dire de ce qui devrait s'y trouver |
| Ajustements manuels | Exceptions ponctuelles tracées avec leur raison : indisponibilité forcée, incompatibilité entre deux personnes, affectation imposée, paire à privilégier. Ce sont des règles sur mesure posées avant le calcul, pour placer ou écarter quelqu'un — l'inverse d'un verrou, qui fige après coup |
| Ajustements contradictoires refusés | Un ajustement qui ne peut pas tenir en même temps qu'un autre déjà saisi est refusé à l'enregistrement, avec un message qui nomme les deux — plutôt qu'un planning déclaré infaisable plusieurs minutes plus tard, sans que rien n'en désigne la cause |

### Décider et diagnostiquer

| Fonctionnalité | En une phrase |
| --- | --- |
| Historique des actions | Ce qui a été fait dans l'édition et par qui : chaque écriture, chaque export, chaque envoi, qu'il vienne d'un écran, d'un assistant ou d'une tâche de nuit — avec les champs qu'une modification a réellement changés. Aucun nom n'y est conservé : les identités sont retrouvées à l'affichage, et les lignes trop anciennes sortent d'elles-mêmes |
| Diagnostic — Problèmes | Premier onglet de la page Diagnostic : vue unique des blocages, triés par gravité : causes d'infaisabilité détectées sans résolution, et règles encore en défaut après la dernière analyse. Quand une règle a buté sur des exceptions saisies à la main, elles sont nommées une par une, et les pauses légales que personne ne peut relayer |
| Ouvertures des stands | Grille stand × jour de ce que le planning retiendra réellement, et les trois erreurs de saisie d'horaires habituelles — à vérifier avant de lancer un calcul ; la même grille se retourne en saisie, un effectif par stand et par créneau comme dans un tableur |
| Diagnostic — Besoin en animateurs | Effectif minimum estimé à partir des seuls stands et créneaux : dit si le problème est un manque de monde plutôt qu'un manque de temps de calcul, et sur quelle typologie de jeu le vivier de compétents est trop mince |
| Volumétrie du problème | Avant de lancer, la taille de ce qui va être calculé : animateurs, postes à pourvoir, créneaux, ajustements manuels, et surtout les **heures à pourvoir face au plafond légal** de ce que l'équipe peut travailler, jours d'indisponibilité déduits. Un taux de remplissage proche de 1 annonce un planning infaisable avant qu'une minute de calcul soit dépensée |
| Diagnostic — Fragilité du planning | Qui est un point de défaillance unique : pour chaque personne, les créneaux qui passeraient sous l'effectif minimum si elle se désiste — et surtout ceux que personne d'autre ne pourrait reprendre — plus les stands tenus par une seule personne compétente. Le tableau qui dit où recruter ou former |
| Diagnostic — Banc de touche | Pour un créneau, qui n'est de service nulle part et quelle règle l'empêcherait de tenir la place restée libre — indisponibilité, repos légal, plafond d'heures, appréciation manquante — toutes les raisons applicables à la fois |
| Catalogue des contraintes | Toutes les règles, leur niveau, et le résultat de la dernière analyse — activables ou désactivables une par une pour diagnostiquer, et **dosables** : l'importance des règles de qualité d'organisation se règle par édition, selon ce qui compte pour l'organisateur |
| Contraintes-plancher | Une règle qui pénalise la quasi-totalité de ce qu'elle évalue mesure une donnée absente du référentiel — aucun souhait déclaré, aucun référent — et ses points sont un plancher qu'aucune résolution ne fera bouger : la page Contraintes le signale, nomme la donnée et mène à sa saisie, et le score hors plancher s'affiche à côté du score brut, jusque dans le Comparateur et l'Autopsie. Rien n'est désactivé à la place de l'organisateur |
| Garde-fou sur les règles légales | Désactiver une règle qui fonde le planning en droit (mineurs, temps de travail) ou la sécurité des mineurs demande une confirmation, qui rappelle que l'organisateur reste l'employeur et le responsable du planning diffusé ; l'écran montre en permanence ce qui est désactivé |
| Comparateur A/B | Deux plannings côte à côte — deux instantanés, ou un instantané et le plan actuel — sur le score, la couverture, l'équité et les écarts aux règles, toutes éditions confondues |
| Notification de fin de résolution | Un e-mail à l'administrateur dès qu'une résolution se termine — édition, score, faisabilité — pour ne pas rester devant l'écran ; s'active par édition sur la page Paramètres |
| Autopsie du planning | Une ligne de mesures par résolution terminée, toutes éditions confondues et sans rien de nominatif ; survit à la suppression de l'édition décrite |
| Instantanés de plan | Met un planning de côté avec son score et sa date, et le remet en place plus tard ; une capture est prise automatiquement avant chaque résolution |
| Résolution qui dégrade le plan | Le score d'avant s'affiche à côté de celui d'après : relancer un calcul sur un bon planning peut le dégrader sans que le score dur bouge. Quand c'est le cas, l'écran le dit et propose de revenir au plan précédent |
| Mode jour J | L'écran du jour même, pensé pour un téléphone : quelqu'un ne s'est pas présenté, on le marque absent pour la suite de la journée, on voit qui peut reprendre ses postes, on applique. Les créneaux déjà tenus ne bougent pas, aucun calcul n'est relancé, et rien n'est envoyé aux animateurs tant que le planning n'a pas été republié |

### Organiser l'année

| Fonctionnalité | En une phrase |
| --- | --- |
| Éditions | Tout le référentiel et les résultats sont cloisonnés par édition (« Année 2025 », « Année 2026 ») ; deux onglets peuvent travailler sur deux éditions à la fois |
| Plans alternatifs | Dupliquer l'édition pour préparer un scénario de repli (canicule…), le résoudre à l'avance, et basculer le matin venu |

### Référentiels

| Fonctionnalité | En une phrase |
| --- | --- |
| Stands | Au moins une typologie (toujours), effectifs minimum et maximum, restriction aux majeurs, indicateurs premium et effort, horaires en règles récurrentes complétées d'exceptions datées — chaque fenêtre d'ouverture peut nommer son propre effectif, pour un stand qui n'a pas le même besoin le matin, l'après-midi et en nocturne. Les horaires d'un stand se copient sur un autre depuis sa fiche, ou sur toute une sélection depuis la modification en masse — règles, exceptions datées et effectifs de fenêtre compris, avec un signal quand une fenêtre dépasse l'effectif maximum du stand cible |
| Animateurs | Identité, compétences par typologie et niveau, souhaits, jours d'indisponibilité ; le régime légal se déduit de l'âge à la date de chaque créneau |
| Grille des compétences | Toutes les appréciations d'un coup, animateurs en lignes et typologies en colonnes : une case se change au clic ou aux touches 0 à 3, les flèches se déplacent comme dans un tableur, rien n'est écrit avant « Enregistrer » et chaque fiche garde son garde de modification concurrente. Les souhaits déclarés sont visibles dans la même case. La grille s'exporte en CSV (identifiants et niveaux, sans nom) et se réimporte après un aperçu ligne par ligne : une case vide laisse l'appréciation telle quelle, l'import ne retire jamais rien |
| Créneaux | Découpage temporel que le solveur remplit, saisi à la main, en série (une journée type répétée sur les jours choisis), généré par le découpage, ou déduit des horaires déjà saisis sur les stands — une coupure à chaque heure où un stand ouvre ou ferme ; la page déclare si la grille contient des amplitudes ou des vacations, et en lit le contrôle |
| Typologies | Vocabulaire commun entre compétences et jeux d'un stand, dont la typologie « ninja » des polyvalents |
| Emplacements | Lieux géolocalisés rattachés aux stands, choisis sur une carte, pour éviter les déplacements lointains d'un créneau à l'autre |
| Modification concurrente | Deux onglets ou deux personnes sur la même fiche : la seconde à enregistrer est prévenue que la fiche a bougé depuis son ouverture, et choisit de recharger ou d'écraser en connaissance de cause — rien n'est écrasé en silence |
| Avertissements de saisie | À la création comme à la modification, la saisie est enregistrée et un message signale ce qui mérite un second regard : une indisponibilité hors des dates de l'événement ou posée sur un jour sans créneau, une date de naissance qui rend l'animateur mineur pendant l'événement (en disant à partir de quand il devient majeur), un créneau qu'aucun stand n'est ouvert à couvrir. Une modification ne signale que ce qu'elle change |

### Consulter le planning

| Fonctionnalité | En une phrase |
| --- | --- |
| Calendrier des affectations | Vue mensuelle avec filtres par animateur et par stand, et détail au clic sur une journée |
| Journée — Calendrier | Une seule page « Journée » pour quatre rendus du même jour, sous le même sélecteur de date et les mêmes filtres (stand, animateur), portés par l'adresse. Le premier rendu : la journée stand par stand et créneau par créneau — et le glisser-déposer d'un nom vers un autre stand pour corriger à la main : déplacement sur un siège libre, échange sur une personne, refusé si une règle dure serait cassée |
| Heures | Heures planifiées par animateur, semaine ISO par semaine ISO, avec le total de l'événement |
| Équité | Une ligne par animateur affecté : heures totales et par semaine, heures de soirée (à partir d'une heure réglable dans les paramètres légaux), de week-end et de jour férié, postes pénibles, stands, typologies et emplacements distincts, part des souhaits et des appréciations satisfaits, jours travaillés, de repos et plus longue série — chaque valeur avec son écart à la médiane, une synthèse par colonne (médiane, min, max, écart-type), un tri, un filtre, un export CSV, et la mention des colonnes que le solveur mesure ; pour arbitrer avant de publier et répondre après |
| Heatmap de charge | Jour croisé avec le stand (trous de couverture) ou avec l'animateur (surcharges) |
| Timeline animateur | Le planning d'une personne : amplitude, vacations, trous entre elles, pauses légales telles que la rotation les pose — en rouge quand personne ne peut la relayer — et coéquipiers présents sur le même stand |
| Jours de repos | Une ligne par animateur et une colonne par journée : qui travaille, qui se repose, qui était indisponible, et qui n'a aucune journée libre sur tout l'événement |
| Journée — Pauses | Où tombent les pauses légales, jour par jour et stand par stand : qui sort au plus tard à quelle heure, pour combien de temps, et qui est là pour relayer — et, à côté, la coupure repas due par chaque journée à cheval sur une fenêtre repas, avec ce qui manque quand la grille ne lui laisse pas de place. Chaque animateur retrouve ses pauses sur son espace, son PDF et son calendrier |
| Journée — Rail | La même journée vue par personne : une ligne par animateur, vacations placées dans le temps, pauses légales posées dessus (en rouge sans relais), lignes vides pour qui reste mobilisable ; une vacation se glisse vers une autre personne, qui la prend ou échange la sienne |
| Journée — Carte | La même journée sur la carte des emplacements : un curseur temporel, et chaque lieu coloré selon que ses stands y sont ouverts et pourvus, ouverts avec des places vides, ou ouverts sans personne |
| Graphe | Navigation descendante des lieux vers les stands puis vers les personnes |
| Notifications | Journal des alertes de l'édition — résolutions terminées, contraintes en défaut, erreurs de saisie — consultable après coup |

### Diffuser et échanger

| Fonctionnalité | En une phrase |
| --- | --- |
| Export PDF global | Toutes les affectations dans un seul document pour l'organisateur, journée par journée puis stand par stand, places vides signalées |
| Export PDF individuel | Le planning d'un animateur, ou de tous en une archive ; les journées sans affectation y figurent explicitement comme jours de repos |
| Export ICS | Le planning individuel importable dans Google Calendar, Apple Calendar ou Outlook |
| Abonnement au calendrier | Une adresse d'abonnement permanente, donnée une fois à son agenda : il se remet à jour tout seul à chaque republication, au lieu de rester figé sur le fichier téléchargé la première fois. Elle est personnelle, et l'animateur la remplace lui-même en un clic si elle a fuité |
| Publication | Envoyer leur planning et le lien de leur espace aux seules personnes dont l'emploi du temps a changé, en leur disant ce qui change |
| Espace animateur | Un espace personnel par lien nominatif : son planning, ses jours de repos, ses demandes d'échange, ses disponibilités déclarées — sans compte à créer |
| Accusé de réception | Un bouton « J'ai lu et je serai là » sur le planning publié, et une colonne confirmé / relancé / silencieux côté organisation, avec la synthèse en tête de page et la date de la dernière publication. Republier ne redemande une confirmation qu'aux personnes dont l'emploi du temps a réellement changé |
| Relance à la main | Deux filtres sur la page Animateurs, portés par l'adresse de la page — jamais confirmés, silencieux depuis N jours — et une action groupée « Relancer maintenant » qui envoie le même rappel que la nuit, sans l'attendre. Une seule relance par personne et par publication, que la nuit ou l'organisateur l'ait envoyée ; le compte rendu nomme ceux qui n'ont pas été écrits et pourquoi |
| Rappels automatiques | La veille au soir, chacun reçoit la liste de ses postes du lendemain ; les silencieux sont relancés une fois, sauf si l'organisateur l'a déjà fait à la main ; les demandes d'échange qui dorment remontent à l'organisation. Rien ne part d'une édition qui ne l'a pas explicitement demandé, et personne ne reçoit deux fois le même message |
| Foire au planning | Les animateurs proposent leurs échanges de créneaux en libre-service ; le collègue visé donne son accord, l'organisation arbitre, rien ne s'applique sans validation. Ouvrable sur une période datée, comme la collecte des disponibilités |
| Qui peut me remplacer ? | Sans personne en tête, l'animateur ne désigne que son créneau : l'application cherche les échanges qui tiennent vraiment et les range en trois familles — on vous libère, vous permutez sur le même créneau, ou vous l'échangez contre un créneau d'un autre jour |
| Collecte des disponibilités | L'organisation ouvre une période pendant laquelle chaque animateur déclare lui-même, depuis son espace, les jours où il ne peut pas venir et ce qu'il aimerait animer — au doigt sur un téléphone, en cochant des jours. La déclaration reste une proposition : elle n'est prise en compte qu'une fois appliquée par l'organisation, en bloc |
| Invitation à déclarer | Une case à cocher au moment d'ouvrir la collecte envoie à chacun le lien de son espace. À cocher au premier tour, à laisser de côté quand on rouvre la période après une correction |
| Aide de l'espace animateur | Un onglet « Aide » dans l'espace, écrit pour l'animateur et non pour l'organisation : lire son planning, l'emporter, demander un échange et suivre ce qu'il devient — en questions repliées, lisibles au doigt sur un téléphone |

### Outils

| Fonctionnalité | En une phrase |
| --- | --- |
| Import / export de scénario | Un fichier YAML décrit une configuration complète d'événement ; l'import valide le fichier et explique ce qui cloche |
| Import de la grille des stands | La matrice du classeur — stands en lignes, jours et créneaux en colonnes, un effectif par case — arrive telle quelle : chaque colonne se pose sur son créneau, une colonne inconnue est ignorée et listée, chaque stand voit son horaire réécrit depuis ses cases après un aperçu ligne par ligne. La grille actuelle se télécharge comme modèle |
| Import CSV des animateurs | Le tableur de bénévoles arrive tel quel : on désigne quelle colonne est quel champ, on lit ligne par ligne ce que l'import ferait — acceptée, rejetée et pourquoi, avec le numéro de ligne du fichier — et rien n'est écrit tant qu'on n'a pas validé. Par défaut il ajoute et met à jour sans supprimer personne, et complète les jours d'indisponibilité déjà déclarés au lieu de les effacer. Un CSV d'exemple est téléchargeable depuis l'écran |
| Export / import d'un dump SQL | Dupliquer ou restaurer un jeu de données complet |
| Sauvegarde automatique | Chaque nuit, toute la base est copiée sur le disque de l'hébergeur, et seules les dernières copies sont gardées. L'écran des paramètres dit où elles vont, lesquelles existent et si la dernière nuit s'est bien passée. La restauration, elle, est une opération de l'exploitant sur la base |
| Assistant IA (MCP) | Un assistant IA consulte et pilote l'application en langage naturel, sans jamais voir les données personnelles des animateurs — voir [`docs/mcp.md`](docs/mcp.md) |
| Mentions légales | Page publique, lisible sans être connecté et sans lien valide : éditeur, hébergeur, contact, propriété intellectuelle |
| Politique de confidentialité | Page publique elle aussi : quelles données, pourquoi, combien de temps, qui y accède — y compris les outils de mesure d'audience et de suivi d'erreurs — et comment exercer ses droits. S'adresse explicitement aux animateurs mineurs |
| Conditions d'utilisation | La ligne de partage, écrite noir sur blanc : l'application calcule des propositions, l'organisation décide. Elle reste l'employeur, le responsable des données et du respect de la réglementation ; le logiciel est fourni en l'état |
| Aide intégrée | Le mode d'emploi complet, cherchable, consultable pendant qu'une résolution tourne ou sur une édition vide |
| Palette de commandes | Ctrl+K ouvre une zone de saisie unique qui mène à n'importe quel écran et retrouve un animateur, un stand ou un créneau ; « g » suivi d'une lettre va droit à un écran, « / » saisit le filtre de la page et « ? » liste les raccourcis |
| Navigation au clavier des tableaux | Dans les tableaux de données de référence, « / » saisit le filtre de la page puis Flèche bas entre dans le tableau ; les flèches haut et bas déplacent ensuite la ligne courante, Entrée l'ouvre et Espace la coche pour une action groupée. Un clic sur une ligne la focalise de la même façon, et la tabulation entre aussi dans le tableau et en ressort sans jamais y rester coincée |
| Thème clair ou sombre | Un bouton de la barre d'outils fait tourner l'affichage entre « automatique », « clair » et « sombre ». « Automatique » suit le réglage du système d'exploitation et le suit en direct ; un choix explicite est retenu par le navigateur et survit au rechargement comme au changement d'humeur de la machine |
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
| Le durcissement avant mise sur Internet | [`docs/securite.md`](docs/securite.md) |
| **Exploiter une instance** (installation, e-mails, sauvegarde, conservation) | [`docs/exploitation.md`](docs/exploitation.md) |
| Héberger pour un tiers : sous-traitance et registre RGPD | [`docs/rgpd.md`](docs/rgpd.md) |
| Les imports / exports | [`docs/import-export.md`](docs/import-export.md) |
| Contribuer (build, tests, CI, Renovate) | [`docs/developpement.md`](docs/developpement.md) |
| Les conventions suivies par les agents IA | [`AGENTS.md`](AGENTS.md) |
| Contribuer (checklist de PR) | [`.github/CONTRIBUTING.md`](.github/CONTRIBUTING.md) |
| Signaler une faille | [`.github/SECURITY.md`](.github/SECURITY.md) |
| Code de conduite | [`.github/CODE_OF_CONDUCT.md`](.github/CODE_OF_CONDUCT.md) |

---

## Licence

**AGPL-3.0-only** — voir [`LICENSE`](LICENSE) et [`NOTICE`](NOTICE).

Copyright (C) 2026 Sylvain METAYER (sylvain.dev).

Concrètement : le code est libre d'usage, y compris commercial, à condition que
toute version modifiée **mise à disposition sur un réseau** publie ses sources
sous la même licence (article 13 de l'AGPL). Héberger l'application pour ses
propres besoins n'impose rien de plus que de rendre disponible le code que l'on
fait tourner.

Les contributions sont acceptées sous cette licence — voir
[`.github/CONTRIBUTING.md`](.github/CONTRIBUTING.md).

## Assistance IA

Ce projet est développé avec l'assistance d'outils d'IA, sous revue humaine
systématique. Les choix d'architecture, le modèle de domaine et
l'interprétation du cadre légal sont assumés par l'auteur.
