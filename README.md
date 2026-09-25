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
   **État de l'édition** s'affiche : la checklist du cycle, chaque étape avec
   son état et un lien vers l'écran qui la fait avancer ; le menu latéral donne accès à chaque écran (en
   mode simple par défaut : « Menu simple », en tête du menu, bascule vers le
   menu avancé qui liste aussi la quinzaine d'écrans spécialisés — diagnostic
   approfondi, vues d'analyse et outils techniques).
2. Sur **Débogage**, choisir un scénario livré puis **Charger le scénario
   sélectionné** pour remplir l'édition courante (les référentiels sont ensuite
   modifiables depuis **Stands**, **Emplacements**, **Animateurs**,
   **Créneaux** et **Typologies**).
3. Ouvrir **Solveur** et cliquer sur **Calculer le planning** : la résolution
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
| `DB_USER` / `DB_PASSWORD` | `festival` / `festival` (en dev : celui du `docker-compose.yml`) | Identifiants base. En production, le mot de passe d'exemple refuse le démarrage |
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
| `PLANNING_MCP_MAX_REQUESTS` | `120` | Requêtes tolérées sous `/mcp` par adresse et par fenêtre ; `0` ou moins désactive le plafond — voir [`mcp.md`](docs/mcp.md#limitation-de-débit) |
| `PLANNING_MCP_RATE_WINDOW` | `PT1M` | Durée de la fenêtre du plafond MCP, au format ISO-8601 |
| `PLANNING_MCP_TRUSTED_PROXIES` | *(valeur de `CONNEXION_PROXYS_FIABLES`)* | Proxys inverses dont `X-Forwarded-For` est cru pour compter les appels `/mcp` |
| `PLANNING_MCP_MAX_FAILURES` | `5` | Clés refusées consécutives tolérées par adresse avant blocage de `/mcp` ; `0` ou moins désactive le verrou |
| `PLANNING_MCP_LOCKOUT_DURATION` | `PT10M` | Durée de ce blocage, comptée depuis le dernier échec |
| `PLANNING_MCP_PANGOLIN_ACCESS_TOKEN_ID` | *(vide)* | Identifiant du jeton d'accès Pangolin, révélable depuis la page MCP (menu avancé ; même contrôle par mot de passe admin que la clé API) |
| `PLANNING_MCP_PANGOLIN_ACCESS_TOKEN` | *(vide)* | Jeton d'accès Pangolin correspondant, révélable de la même façon |
| `ADMIN_PASSWORD` | `admin` | Mot de passe du compte administrateur `admin`. En production, le défaut refuse le démarrage : il faut en donner un |
| `PROXY_ADDRESS_FORWARDING` | `true` | Suivre les en-têtes `X-Forwarded-*` d'un reverse proxy qui termine le TLS, indispensable pour que la redirection de connexion reste en `https` — voir [`api.md`](docs/api.md#derrière-un-reverse-proxy-qui-termine-le-tls) |
| `REMOTE_USER_ENABLED` | `false` | Authentification par en-tête derrière un proxy d'accès, en plus du form login — voir [`api.md`](docs/api.md#mode-remote-user-facultatif-désactivé-par-défaut) |
| `REMOTE_USER_SECRET` | — | Secret partagé avec le proxy. **Obligatoire** si `REMOTE_USER_ENABLED=true` : sans lui le démarrage échoue |
| `REMOTE_USER_ADMIN_EMAIL` | — | Adresse qui obtient le rôle admin ; les autres adresses reconnues sont des animateurs |
| `SESSION_ENCRYPTION_KEY` | *(vide = clé générée au démarrage)* | Clé (≥ 16 caractères) de chiffrement du cookie de session admin ; la définir pour que les sessions survivent aux redémarrages |
| `PASSE_FIGE` | `true` | `false` : le solveur peut de nouveau réécrire les journées déjà travaillées. Par défaut, toute résolution pendant l'événement reprend du plan enregistré les places des créneaux commencés et les fige — voir [`domaine.md`](docs/domaine.md#le-passé-est-figé). À réserver à une recette qui rejoue une édition ancienne |
| `SOLVER_SECONDS_LIMIT_MAX` | `3600` | Plafond, en secondes, de la durée qu'une édition peut régler ou qu'un lancement peut demander ; au-dessus, refus. Démarrage refusé s'il est sous le défaut de 900 s — voir [`exploitation.md`](docs/exploitation.md#le-temps-de-calcul-que-chaque-édition-peut-prendre) |
| `SOLVER_UNIMPROVED_SECONDS_LIMIT_MAX` | celui de la durée | Plafond de l'arrêt sans amélioration d'une édition |
| `HORLOGE_SIMULEE_AUTORISEE` | `false` | `true` : la page Débogage (onglet *Vérifications*) peut figer la date et l'heure que lisent le mode jour J et l'espace animateur, comme sous `quarkus:dev`. Pour un serveur de recette ; jamais en production — voir [`api.md`](docs/api.md#figer-la-date-du-jour--développement-et-recette-uniquement) |
| `GLISSER_DEPOSER_ACTIF` | `false` | `true` : les vues Journée (stand par stand) et Rail proposent de glisser une affectation vers une autre ligne pour la déplacer ou l'échanger, et le même déplacement au clavier. Coupé par défaut tant que le geste est en test ; le déplacement reste possible par l'API et l'assistant MCP |
| `MAIL_HOST` / `MAIL_PORT` | `localhost` / `1025` | Serveur SMTP des notifications d'échange (Mailpit en local) |
| `MAIL_MOCK` | `false` (tests : toujours mockés) | `true` : les mails sont journalisés au lieu d'être envoyés |
| `MAIL_FROM` | `planning-equipes@localhost` | Adresse expéditrice |
| `MAIL_ADMIN` | *(vide = désactivé)* | Adresse de l'administrateur : demandes d'échange soumises, fin de résolution si l'édition le demande, et sauvegarde de nuit en échec puis rétablie. Vide, un échec de sauvegarde ne se voit que sur l'écran des paramètres |
| `BRANDING_PRODUCT_NAME` | `Planning Équipes` | Nom du produit : onglet du navigateur, titre de chaque page, sujets des mails, en-tête du dump SQL, `PRODID` des exports ICS, en-tête des PDF |
| `BRANDING_ORGANISATION` | *(vide)* | Client pour lequel cette instance est déployée, imprimé au pied des PDF et des mails ; vide = seule la date de génération y figure |
| `BRANDING_LOGO_URL` | *(vide = aucun logo)* | URL du logo affiché dans les barres d'outils et sur la carte de connexion (`logo.png` pour un fichier servi à la racine, ou une URL absolue) |
| `BRANDING_ACCENT_COLOR` | *(vide = accent Material compilé)* | Couleur d'accent de l'IHM, toute couleur CSS ; alimente `--app-accent`. Chaque thème en dérive une jumelle bornée en clarté, à teinte constante, pour rester lisible : une couleur très pâle sera donc assombrie en thème clair, une couleur très foncée éclaircie en sombre |
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
| État de l'édition | La page d'accueil : les étapes du cycle (référentiels, cohérence du référentiel, collecte des disponibilités, ouvertures, besoin, résolution, problèmes, relecture, publication, accusés de réception, foire) en checklist calculée — chaque ligne dit si l'étape est faite, à vérifier, pour information ou à faire, avec le chiffre qui compte et le lien vers l'écran concerné ; les avertissements sans bloquant se lisent « pour information », en orange, parce qu'un planning sans le moindre avertissement n'existe pas ; une résolution en cours s'y lit sans rien bloquer ; la ligne « Cohérence du référentiel » rassemble toutes les anomalies déjà détectées ailleurs — avertissements de saisie recalculés sur tout le référentiel, anomalies d'ouverture, contrôle de la grille, ajustements contradictoires ou intenables, besoin non couvert — et déplie leur liste, groupée par famille, chaque ligne menant à la fiche qui la corrige ; au-dessus, un encadré « À traiter aujourd'hui », affiché seulement s'il a quelque chose à dire, rassemble ce qui attend une décision ce jour-là — déclarations de disponibilité, échanges à arbitrer (en alerte au-delà de l'ancienneté réglée), journées non relues de la semaine qui vient, silencieux à relancer, données modifiées depuis la résolution, personnes à prévenir — chaque ligne ouvrant l'écran avec son filtre ; une fois le dernier jour de l'événement passé, un lien propose d'archiver l'édition |
| Génération automatique du planning | Le moteur d'optimisation affecte les animateurs aux places à pourvoir, sous trois niveaux d'exigence : le cadre légal et les incompatibilités (jamais franchis), la couverture des postes, puis l'équité, les souhaits et le confort |
| Pause : un trou, ou un relais | Une pause légale due se prend de deux manières, et l'outil n'en connaît pas de troisième : un trou d'au moins la durée réglée dans la grille, ou un relais — un collègue du même stand tenant une place pendant toute la pause. Sans l'un ni l'autre, le plan est refusé comme il le serait pour un dépassement d'heures. Rien à déclarer : ouvrez la place, le solveur s'en sert. En contrepartie, la pause est du repos, donc **déduite partout** : plafonds quotidien et hebdomadaires, écran Heures, équité, Besoin, KPI — un seul nombre d'heures, le même sur tous les écrans |
| Jours d'affilée | Pas trop de jours travaillés consécutifs — **huit par défaut, et le nombre se règle** sur la page Paramètres : le solveur pénalise les jours en trop sans bloquer, aucun texte n'imposant un décompte glissant, et une organisation qui veut le plafond bloquant l'allume pour son édition depuis la page Contraintes. Le seuil vaut mieux que le dosage : sur une grille qui réclame presque tout le monde tous les jours, un jour de plus ou de moins décide si un planning sans écart existe, là où aucun poids ne le peut |
| Stabilité du plan publié | Une fois le planning envoyé, chaque personne déplacée coûte au solveur : un recalcul après un changement tardif bouge le minimum de gens déjà prévenus, et le récapitulatif dit combien la publication préviendrait |
| Trois façons de lancer le solveur | « Calculer le planning » repart du plan enregistré s'il existe et cherche à l'améliorer, sans rien figer hormis les verrouillages ; « Corriger après un changement » fige ce qui tient et ne recalcule que les postes rouverts ; « Recommencer de zéro » abandonne l'acquis, et le dit avant. La page annonce d'où partira le prochain calcul et d'où le dernier est parti |
| Budget de calcul par édition | Chaque édition règle combien de temps un calcul peut durer et quand il s'arrête faute de progrès — seulement une fois le planning faisable, jamais tant que des places restent à pourvoir — sous un plafond que fixe l'exploitant de l'instance, affiché sur l'écran Solveur. « Revenir au défaut » rend l'édition aux valeurs de l'instance |
| File d'attente du solveur | Planifier une résolution derrière celle qui tourne : elle démarre d'elle-même, ce qui permet de préparer l'édition suivante sans attendre devant l'écran. La file survit à un redémarrage du serveur ; la résolution qui était en cours, elle, est perdue et signalée comme interrompue |
| Courbe de score en direct | Les trois niveaux de score se tracent pendant la résolution, chacun à sa propre échelle, pour voir quand le calcul plafonne et l'arrêter à propos plutôt qu'attendre la fin du budget. Seule la résolution en cours est tracée |
| Replanification incrémentale | Repart du planning enregistré, fige ce qui reste valable et ne recalcule que ce qu'un changement tardif a invalidé — quelques dizaines de secondes au lieu de plusieurs minutes |
| Verrouillage partiel | Geler un animateur, un stand, une journée ou un créneau pour que la prochaine résolution n'y touche plus et optimise le reste. Un verrou est un geste sur un plan déjà calculé : il conserve ce qu'une résolution a produit, sans rien dire de ce qui devrait s'y trouver |
| Relecture d'une journée | Marquer une journée « relue et acceptée », et voir où l'on en est — « 3 journées sur 12 » — sur la Journée, le Calendrier et le Solveur. Avant d'accepter, l'écran affiche les prérequis de ce jour-là (écarts durs, sièges vides, pauses sans relais, postes irremplaçables) et laisse accepter quand même, en le disant. Accepter ne fige rien : le verrouillage est proposé à côté, jamais imposé. La validation porte toujours sur la journée entière, quel que soit le filtre affiché |
| Relecture retirée par une résolution | Une résolution qui déplace un siège d'une journée relue retire cette relecture et l'annonce (« 2 journées validées ont bougé ») : personne n'a relu ce que le solveur vient d'écrire. Une journée qui portait aussi un verrouillage garde sa relecture, puisque rien n'a pu y bouger. Le panneau de publication dit combien de journées non relues partiraient |
| Ajustements manuels | Exceptions ponctuelles tracées avec leur raison : indisponibilité forcée, incompatibilité entre deux personnes, affectation imposée, paire à privilégier. Ce sont des règles sur mesure posées avant le calcul, pour placer ou écarter quelqu'un — l'inverse d'un verrou, qui fige après coup. Une seconde lecture dessine les affinités et les incompatibilités en réseau : les grappes de personnes liées, celles liées seulement par des incompatibilités, et l'incompatibilité glissée à l'intérieur d'un groupe d'affinités |
| Ajustements contradictoires refusés | Un ajustement qui ne peut pas tenir en même temps qu'un autre déjà saisi est refusé à l'enregistrement, avec un message qui nomme les deux — plutôt qu'un planning déclaré infaisable plusieurs minutes plus tard, sans que rien n'en désigne la cause |

### Décider et diagnostiquer

| Fonctionnalité | En une phrase |
| --- | --- |
| Historique des actions | Ce qui a été fait dans l'édition et par qui : chaque écriture, chaque export, chaque envoi, qu'il vienne d'un écran, d'un assistant ou d'une tâche de nuit — avec les champs qu'une modification a réellement changés. Un filtre « Exports » dit qui a sorti quel fichier et quand, planning téléchargé par un animateur depuis son espace compris, sur toute la durée conservée. Aucun nom n'y est conservé : les identités sont retrouvées à l'affichage, et les lignes trop anciennes sortent d'elles-mêmes |
| Diagnostic — Problèmes | Premier onglet de la page Diagnostic : vue unique des blocages, triés par gravité : causes d'infaisabilité détectées sans résolution, et règles encore en défaut après la dernière analyse. Quand une règle a buté sur des exceptions saisies à la main, elles sont nommées une par une, et les pauses légales que personne ne peut relayer — et chaque cause ou écart pointe la fiche à corriger, qui s'ouvre directement |
| Que faire ? | Sous chaque problème du Diagnostic, les gestes qui le règlent, du plus probable au moins probable, chacun avec sa raison et un bouton qui ouvre l'écran déjà positionné : le banc de touche du créneau en sous-effectif, la grille Compétences sur la typologie du stand, les Ouvertures sur le stand, les ajustements en cause affichés seuls, le verrou à lever, la règle de qualité mise en évidence pour baisser son poids, la donnée manquante d'un plancher. Aucun bouton n'écrit rien : l'écran ouvert garde ses aperçus et ses confirmations. Une journée déjà commencée ne propose rien qui la modifierait. Le conseil d'une règle est celui de la page Contraintes, mot pour mot |
| Lecture du score | Le score dit en quelques phrases, pour qui n'a jamais entendu parler de « medium » : les règles impératives respectées ou non, les places restées vides et le jour qui en compte le plus, les deux ou trois règles qui pèsent sur l'organisation avec leur part, ce qui vient d'une donnée absente, les ajustements manuels en cause — et, juste après une résolution, ce qui a changé par rapport au plan d'avant. Chaque règle et chaque jour cité mène à sa ligne ; aucune personne n'est nommée. En tête de la page Solveur et du Diagnostic, et pour chaque plan du Comparateur |
| Liens du symptôme vers l'écran | Un siège libre, une pause sans relais, un stand découvert sur la carte mènent au banc de touche du créneau ; un goulot de typologie ou une compétence rare mènent aux animateurs qui la détiennent ; un avertissement à l'enregistrement rouvre la fiche ; une cause d'infaisabilité ouvre le créneau ou le stand en cause |
| Ouvertures des stands | Grille stand × jour de ce que le planning retiendra réellement, et les trois erreurs de saisie d'horaires habituelles — à vérifier avant de lancer un calcul ; la même grille se retourne en saisie, un effectif par stand et par créneau comme dans un tableur — collage d'un bloc, recopie d'un jour, reprise de la ligne du dessus, application d'une case à toute sa colonne ; une troisième vue pose une journée sur l'axe du temps, un stand par ligne, et hachure une ouverture déclarée hors de toute vacation ; un calendrier combiné superpose, semaine par semaine, les horaires de chaque stand (règle ou exception datée), les créneaux de la grille et la consigne du jour aux sièges que le calcul recevra, chaque case expliquée en une phrase et menant à l'écran qui porte sa couche ; une dernière vue compare deux à huit stands à un stand de référence, jour par jour et case par case, souligne chaque écart d'ouverture, d'heures ou d'effectif et range leurs règles côte à côte, puis ouvre la copie des horaires de la référence vers les autres ; un jour férié y est marqué par son nom, sans rien interdire |
| Diagnostic — Besoin en animateurs | Effectif minimum estimé à partir des seuls stands et créneaux : dit si le problème est un manque de monde plutôt qu'un manque de temps de calcul, et sur quelle typologie de jeu le vivier de compétents est trop mince |
| Placement intenable dit avant le calcul | Une exception posée à la main qu'aucune place ne peut tenir — un mineur forcé sur une nuit, un jour férié ou un stand réservé aux majeurs, quelqu'un dont l'emploi du temps est verrouillé — est signalée au moment de la saisie, nommée dans les causes bloquantes, et « Calculer » demande confirmation avant de dépenser du temps de calcul sur un problème qui finira en dur négatif. Le manque de monde, lui, ne demande rien : le solveur l'atténue encore |
| Siège écrit à la main | Poser directement quelqu'un sur un siège — depuis l'écran de réparation ou un assistant — est simulé avant d'écrire et refusé si le geste casse une règle dure, en la nommant. Libérer un siège passe toujours : marquer une absence le jour J ne doit jamais être bloqué par le plan qu'on répare |
| Volumétrie du problème | Avant de lancer, la taille de ce qui va être calculé : animateurs, postes à pourvoir, créneaux, ajustements manuels, et surtout les **heures à pourvoir face au plafond légal** de ce que l'équipe peut travailler, jours d'indisponibilité déduits. Un taux de remplissage proche de 1 annonce un planning infaisable avant qu'une minute de calcul soit dépensée |
| Diagnostic — Fragilité du planning | Qui est un point de défaillance unique : pour chaque personne, les créneaux qui passeraient sous l'effectif minimum si elle se désiste — et surtout ceux que personne d'autre ne pourrait reprendre — plus les stands tenus par une seule personne compétente. Le tableau qui dit où recruter ou former |
| Diagnostic — À former | Qui former, typologie par typologie : pour chaque typologie que le besoin en animateurs dit en manque ou que la fragilité montre tenue par un seul spécialiste, les chiffres de ces deux lectures, les jours en tension, et les candidats — débutants et autonomes de la typologie, jamais un référent ni un polyvalent — classés par jours en tension où ils sont disponibles, souhait puis niveau. Une typologie sans candidat relève du recrutement. Aucun calcul lancé, rien d'écrit ; l'onglet s'exporte en CSV |
| Diagnostic — Banc de touche | Pour un créneau, qui n'est de service nulle part et quelle règle l'empêcherait de tenir la place restée libre — indisponibilité, repos légal, plafond d'heures, appréciation manquante — toutes les raisons applicables à la fois |
| Catalogue des contraintes | Toutes les règles, leur niveau, et le résultat de la dernière analyse — activables ou désactivables une par une pour diagnostiquer, et **dosables** : l'importance des règles de qualité d'organisation se règle par édition, selon ce qui compte pour l'organisateur |
| Contraintes-plancher | Une règle qui pénalise la quasi-totalité de ce qu'elle évalue mesure une donnée absente du référentiel — aucun souhait déclaré, aucun référent — et ses points sont un plancher qu'aucune résolution ne fera bouger : la page Contraintes le signale, nomme la donnée et mène à sa saisie, et le score hors plancher s'affiche à côté du score brut, jusque dans le Comparateur et l'Autopsie. Rien n'est désactivé à la place de l'organisateur |
| Garde-fou sur les règles légales | Désactiver une règle qui fonde le planning en droit (mineurs, temps de travail) ou la sécurité des mineurs demande une confirmation, qui rappelle que l'organisateur reste l'employeur et le responsable du planning diffusé ; l'écran montre en permanence ce qui est désactivé |
| Historique des pondérations | Chaque changement de poids ou d'activation d'une règle est gardé avec ses valeurs avant et après, sa date et son origine — écran, assistant, scénario, duplication —, sans rien de nominatif, aussi longtemps que l'édition. La page Contraintes le pose règle par règle à côté des résolutions qui ont suivi et de leurs écarts à cette règle ; chaque résolution garde le dosage sous lequel elle a été lancée, que l'Autopsie affiche et filtre, et le Comparateur prévient quand deux plans ont été calculés sous des poids différents |
| Comparateur A/B | Deux plannings côte à côte — deux instantanés, ou un instantané et le plan actuel — sur le score, la couverture, l'équité et les écarts aux règles, toutes éditions confondues |
| Notification de fin de résolution | Un e-mail à l'administrateur dès qu'une résolution se termine — édition, score, faisabilité — pour ne pas rester devant l'écran ; s'active par édition sur la page Paramètres, onglet *E-mails automatiques* |
| Autopsie du planning | Une ligne de mesures par résolution terminée, toutes éditions confondues et sans rien de nominatif ; survit à la suppression de l'édition décrite. Un rejeu parcourt les résolutions d'une édition, supprimée comprise : scores, couverture et équilibre en petites courbes, un curseur au clavier ou à la souris et une lecture pas à pas, ce qui a bougé d'une résolution à la suivante — règles apparues ou disparues comprises — et la couverture jour par jour |
| Instantanés de plan | Met un planning de côté avec son score et sa date, et le remet en place plus tard ; une capture est prise automatiquement avant chaque résolution. Chaque instantané dit s'il est encore à jour, et en remettre un périmé — dont le référentiel a changé depuis la capture — demande une confirmation qui nomme la cause |
| Données modifiées depuis la résolution | L'écran Solveur signale qu'un référentiel a bougé depuis le calcul affiché, et dit lequel : le compte par famille (« 3 animateurs, 1 stand, 2 créneaux ») et les dernières lignes de l'historique, avec un lien vers celui-ci. Seules les actions qui changent ce qu'un solve reçoit sont comptées — un envoi ou un export ne périme rien |
| Résolution qui dégrade le plan | Le score d'avant s'affiche à côté de celui d'après : relancer un calcul sur un bon planning peut le dégrader sans que le score dur bouge. Quand c'est le cas, l'écran le dit et propose de revenir au plan précédent |
| Mode jour J | L'écran du jour même, pensé pour un téléphone : quelqu'un ne s'est pas présenté, on le marque absent pour la suite de la journée, on voit qui peut reprendre ses postes, on applique. Les créneaux déjà tenus ne bougent pas, aucun calcul n'est relancé, et rien n'est envoyé aux animateurs tant que le planning n'a pas été republié |

### Organiser l'année

| Fonctionnalité | En une phrase |
| --- | --- |
| Éditions | Tout le référentiel et les résultats sont cloisonnés par édition (« Année 2025 », « Année 2026 ») ; deux onglets peuvent travailler sur deux éditions à la fois |
| Plans alternatifs | Dupliquer l'édition pour préparer une vraie variante (autre grille, autre équipe), la résoudre à l'avance, et basculer le matin venu |
| Consigne d'édition | Un arrêté ferme une bande horaire pour tous les stands sur quelques jours : la consigne la pose sur les dates à venir, propose cochés les stands qui perdent des heures, les rouvre sur des fenêtres de compensation — le soir, le matin — et ne détruit rien de la grille : les vacations fermées perdent leurs sièges, les seuls créneaux ajoutés sont ceux que les réouvertures exigent. Un aperçu chiffré précède chaque pose, prolongation ou levée ; la résolution incrémentale et la publication font le reste, et la levée rend leurs après-midis aux titulaires. Le courriel, l'espace et le PDF disent « horaires modifiés » avec le motif ; une journée passée garde pour toujours la consigne qui l'a gouvernée ; un préréglage nommé (« Plan canicule ») rend chaque vague identique au geste près |

### Référentiels

| Fonctionnalité | En une phrase |
| --- | --- |
| Stands | Au moins une typologie (toujours), effectifs minimum et maximum, restriction aux majeurs, indicateurs premium et effort, horaires en règles récurrentes complétées d'exceptions datées — chaque fenêtre d'ouverture peut nommer son propre effectif, pour un stand qui n'a pas le même besoin le matin, l'après-midi et en nocturne. Les horaires d'un stand se copient sur un autre depuis sa fiche, ou sur toute une sélection depuis la modification en masse — règles, exceptions datées et effectifs de fenêtre compris, avec un signal quand une fenêtre dépasse l'effectif maximum du stand cible ; la fiche de chaque stand liste ses anomalies d'ouverture, dont une fenêtre déclarée à une heure qu'aucune vacation ne couvre |
| Animateurs | Identité, compétences par typologie et niveau, souhaits, jours d'indisponibilité ; le régime légal se déduit de l'âge à la date de chaque créneau |
| Fiche animateur | Tout ce qu'on sait d'une personne sur une seule page : identité et régime légal (et son changement si elle devient majeure pendant l'événement), jours d'indisponibilité sur la frise de l'événement avec les indisponibilités forcées et la déclaration en attente, appréciations et souhaits côte à côte, sa position dans l'équité avec l'écart à la médiane, les créneaux où son départ laisserait un trou, ses affectations, et le suivi — dernière publication, accusé de réception, échanges en cours, ajustements et verrous. Les chiffres sont ceux des écrans Équité et Fragilité, jamais recalculés ; on y arrive depuis la page Animateurs, la palette Ctrl+K et les noms des écrans d'analyse |
| Grille des compétences | Toutes les appréciations d'un coup, animateurs en lignes et typologies en colonnes : une case se change au clic ou aux touches 0 à 3, les flèches se déplacent comme dans un tableur, une ligne reprend celle du dessus et une case s'applique à toute sa colonne, rien n'est écrit avant « Enregistrer » et chaque fiche garde son garde de modification concurrente. Les souhaits déclarés sont visibles dans la même case. C'est la seule saisie des appréciations en masse : la grille ne s'échange pas par fichier |
| Créneaux | Les vacations que le solveur remplit — une vacation est une tranche de travail réelle, et il n'y a pas d'autre sorte de créneau. D'abord des journées types nommées — les vacations d'une sorte de journée, un R pour un relais repas — posées sur un calendrier de dates, qui sont celles de l'édition, et appliquées par différence après un aperçu chiffré ; ou saisi à la main, en série, ou déduit des horaires des stands. La page décrit la grille en place et en lit le contrôle de cohérence ; les jours fériés y sont marqués à la saisie, et le contrôle rappelle qu'un mineur n'y travaille pas quand l'édition en compte un ce jour-là |
| Typologies | Vocabulaire commun entre compétences et jeux d'un stand, dont la typologie « ninja » des polyvalents, et le plafond de créneaux qu'un animateur peut y tenir. Chaque typologie montre combien de personnes la maîtrisent, combien l'ont souhaitée et combien de stands la proposent, avec un badge quand elle est orpheline (proposée par un stand, maîtrisée par personne hors polyvalents), fragile (une seule personne) ou inutilisée (aucun stand) ; l'État de l'édition compte les orphelines avant tout calcul. Une lecture du planning par typologie s'y charge à la demande : qui est réellement affecté, combien de postes, combien d'heures, et l'écart entre les animateurs appréciés et ceux que le solveur y a mis |
| Emplacements | Lieux géolocalisés rattachés aux stands, choisis sur une carte, pour éviter les déplacements lointains d'un créneau à l'autre |
| Modification concurrente | Deux onglets ou deux personnes sur la même fiche : la seconde à enregistrer est prévenue que la fiche a bougé depuis son ouverture, et choisit de recharger ou d'écraser en connaissance de cause — rien n'est écrasé en silence |
| Avertissements de saisie | À la création comme à la modification, la saisie est enregistrée et un message signale ce qui mérite un second regard : une indisponibilité hors des dates de l'événement ou posée sur un jour sans créneau, une date de naissance qui rend l'animateur mineur pendant l'événement (en disant à partir de quand il devient majeur), un créneau qu'aucun stand n'est ouvert à couvrir. Une modification ne signale que ce qu'elle change |

### Consulter le planning

| Fonctionnalité | En une phrase |
| --- | --- |
| Calendrier des affectations | Vue mensuelle avec filtres par animateur et par stand, et détail au clic sur une journée |
| Journée — Calendrier | Une seule page « Journée » pour quatre rendus du même jour, sous le même sélecteur de date et les mêmes filtres (stand, animateur), portés par l'adresse. Le premier rendu : la journée stand par stand et créneau par créneau — et, si l'instance l'active, le glisser-déposer d'un nom vers un autre stand pour corriger à la main : déplacement sur un siège libre, échange sur une personne, refusé si une règle dure serait cassée |
| Heures | Heures planifiées par animateur, semaine ISO par semaine ISO, avec le total de l'événement, et les trois compteurs de la paie : dimanche, jours fériés, heures au-delà de 22 h |
| Équité | Une ligne par animateur affecté : heures totales et par semaine, heures de soirée (à partir d'une heure réglable dans les paramètres légaux), de week-end et de jour férié, postes pénibles, stands, typologies et emplacements distincts, part des souhaits et des appréciations satisfaits, jours travaillés, de repos et plus longue série — chaque valeur avec son écart à la médiane, une synthèse par colonne (médiane, min, max, écart-type), un tri, un filtre, un export CSV, et la mention des colonnes que le solveur mesure ; pour arbitrer avant de publier et répondre après. Deux lectures au choix : le tableau, ou la fiche d'une seule personne — un nom saisi avec autocomplétion, ses indicateurs l'un sous l'autre avec leur écart à la médiane, quand la largeur du tableau arrête l'œil, et à côté un radar qui dessine la personne face à la médiane et à l'étendue de l'édition — heures, soirées, week-ends, pénibilité, souhaits, et d'autres axes au choix — avec, si besoin, une seconde personne par-dessus pour arbitrer entre deux |
| Heatmap de charge | Jour croisé avec le stand (trous de couverture) ou avec l'animateur (surcharges) |
| Répartition des heures | Ce qui pèse dans l'édition, et où ça coince, en une image : un rectangle par stand, d'autant plus grand que le stand demande d'heures-sièges, et coloré selon la part de ces heures réellement tenue — rayé sous 80 %. Regroupé par emplacement ou par typologie (un stand à plusieurs typologies compte une fois, sous leur combinaison), restreint à une semaine, un jour ou un emplacement ; un clic agrandit un groupe, un second ouvre la journée du stand, et un tableau repliable donne les mêmes chiffres |
| Marge disponible | Jour croisé avec la tranche horaire, et dans chaque case ce qui reste : les animateurs disponibles à ce moment-là moins les sièges à pourvoir — rouge en dessous de zéro, vert au-dessus. Avant résolution, la capacité brute face aux sièges qu'une résolution devrait pourvoir ; après, les personnes réellement libres face aux sièges restés vides. Les tranches sont les créneaux de la grille, une case mène au banc de touche du créneau ou aux ouvertures de la journée, et la pire tranche de chaque journée est rappelée sous la grille. Une troisième lecture, « Tension », croise la marge après résolution avec la fragilité du planning : chaque case est notée calme, surveillée, élevée ou critique par des règles nommées — sièges vides que personne ne peut tenir, siège qu'aucun autre ne pourrait reprendre, stand sans spécialiste, sièges fragiles au-delà de la marge — et son détail donne les raisons avec un lien vers le banc de touche, la timeline de la personne irremplaçable ou la fragilité du stand ; une tranche déjà commencée est grisée |
| Timeline animateur | Le planning d'une personne : amplitude, vacations, trous entre elles, pauses légales telles que la rotation les pose — en rouge quand personne ne peut la relayer — et coéquipiers présents sur le même stand |
| Jours de repos | Une ligne par animateur et une colonne par journée : qui travaille, qui se repose, qui était indisponible, et qui n'a aucune journée libre sur tout l'événement ; sous deux affichages — la grille jour par jour et la frise, qui tient un événement d'un mois sur un écran |
| Journée — Pauses | Où tombent les pauses légales, jour par jour et stand par stand : qui sort au plus tard à quelle heure, pour combien de temps, et qui est là pour relayer — et, à côté, la coupure repas due par chaque journée à cheval sur une fenêtre repas, avec ce qui manque quand la grille ne lui laisse pas de place. Chaque animateur retrouve ses pauses sur son espace, son PDF et son calendrier |
| Journée — Rail | La même journée vue par personne : une ligne par animateur, vacations placées dans le temps, pauses légales posées dessus (en rouge sans relais), lignes vides pour qui reste mobilisable ; si l'instance l'active, une vacation se glisse vers une autre personne, qui la prend ou échange la sienne |
| Journée — Carte | La même journée sur la carte des emplacements : un curseur temporel, et chaque lieu coloré selon que ses stands y sont ouverts et pourvus, ouverts avec des places vides, ou ouverts sans personne ; chaque lieu porte le nombre de personnes présentes à cette heure-là, sa taille suit l'effectif, et une grille lieu × tranche horaire — ou lieu × jour, au pic de chaque journée — dit combien de monde se trouve où, pour dimensionner l'encadrement et la logistique d'un lieu |
| Journée — Comparer deux jours | Deux journées côte à côte, deux samedis par exemple, sur le calendrier ou le rail : un bandeau chiffre pour chacune sièges, pourvus, vides, pauses sans relais, animateurs et stands avec l'écart de l'une sur l'autre, puis une ligne par stand ou par personne alignée des deux côtés, les différences signalées et filtrables. En lecture seule, et l'adresse garde la comparaison pour la partager |
| Graphe | Navigation descendante des lieux vers les stands puis vers les personnes |
| Notifications | Journal des alertes de l'édition — résolutions terminées, contraintes en défaut, erreurs de saisie — consultable après coup |

### Diffuser et échanger

| Fonctionnalité | En une phrase |
| --- | --- |
| Export PDF global | Toutes les affectations dans un seul document pour l'organisateur : un sommaire cliquable, la grille « qui tient quel stand chaque jour », puis les mêmes affectations journée par journée, stand par stand et animateur par animateur, places vides signalées |
| Export PDF individuel | Le planning d'un animateur, ou de tous en une archive : une vue d'ensemble de ses journées sur une frise, le détail de chaque jour, puis ses coéquipiers et ses lieux rassemblés ; les journées sans affectation y figurent explicitement comme jours de repos |
| Feuille recto-verso | Le même planning individuel plié sur une seule feuille A4 paysage — calendrier au recto, coéquipiers et lieux au verso : un tirage par personne au lieu de cinq. L'animateur choisit son format depuis son espace, l'organisation depuis l'écran de diffusion |
| QR code vers l'espace | Les deux PDF portent un QR code vers l'espace personnel : on scanne le papier au lieu de recopier une adresse que personne ne recopie |
| Export ICS | Le planning individuel importable dans Google Calendar, Apple Calendar ou Outlook |
| Abonnement au calendrier | Une adresse d'abonnement permanente, donnée une fois à son agenda : il se remet à jour tout seul à chaque republication, au lieu de rester figé sur le fichier téléchargé la première fois. Elle est personnelle, et l'animateur la remplace lui-même en un clic si elle a fuité |
| Publication | Envoyer leur planning et le lien de leur espace aux seules personnes dont l'emploi du temps a changé, en leur disant ce qui change — y compris quand une vacation disparaît de la grille après coup : elle se dit « retirée » au lieu de s'effacer en silence de leur espace |
| Relecture avant envoi | Voir, avant de publier, une ligne par personne à prévenir — ce qui change pour elle, sa dernière confirmation — la trier par ampleur, replier les changements mineurs, exporter le détail en CSV, et différer le message de qui on préfère appeler d'abord : ces personnes ne reçoivent rien et restent à prévenir à la publication suivante |
| Espace animateur | Un espace personnel par lien nominatif : son planning, ses jours de repos, ses demandes d'échange, ses disponibilités déclarées — sans compte à créer |
| Ce qui a changé pour vous | Au-dessus de ses journées, l'animateur relit les phrases exactes de la dernière publication qui a déplacé son emploi du temps — les mêmes que son e-mail, pas un calcul refait, repliées derrière un titre qui en donne le nombre. Le message perdu, classé en indésirable ou jamais reçu n'emporte plus avec lui la seule trace de ce qui a bougé ; une première diffusion, elle, annonce un planning et non une liste de corrections, et le bandeau passe en arrière-plan une fois la présence confirmée. Les demandes d'échange restent sur leur onglet, avec leur statut du moment |
| Repère du jour | Pendant l'événement, l'espace s'ouvre sur l'instant : la journée du jour, le poste en cours ou le prochain avec la pause qui reste à prendre, les cartes des postes écoulés estompées sans disparaître. Un poste de nuit reste « en cours » après minuit. Avant le premier jour et après le dernier, l'espace ouvre sur la première journée et ne raconte rien de « maintenant » |
| Espace sur téléphone | L'espace se lit debout, sur un téléphone, pendant l'événement : trois onglets — la **journée** (une bande de jours à faire défiler du pouce, l'état de l'instant, une carte par créneau avec son lieu, son itinéraire et ses coéquipiers), l'**aperçu** (quatre chiffres et une frise de toutes les journées, colorée par typologie de stand) et les **coéquipiers** (« suis-je avec quelqu'un ? », par nom, accents et casse ignorés ; les journées où presque tout l'effectif est là ne donnent qu'un nombre). Proposer un échange reste à portée de pouce sous les trois |
| Où se tient mon stand | Chaque poste, et chaque pause, nomme l'emplacement de son stand quand il en a un, et l'ouvre sur une carte quand il est géolocalisé. Le fichier d'agenda et le PDF le disaient déjà : l'écran le dit maintenant aussi |
| Accusé de réception | Un bouton « J'ai lu et je serai là » sur le planning publié, et une colonne confirmé / relancé / silencieux côté organisation, avec la synthèse en tête de page et la date de la dernière publication. Republier ne redemande une confirmation qu'aux personnes dont l'emploi du temps a réellement changé |
| Relance à la main | Deux filtres sur la page Animateurs, portés par l'adresse de la page — jamais confirmés, silencieux depuis N jours, chacun restreint au besoin aux jamais relancés — et une action groupée « Relancer maintenant » qui envoie le même rappel que la nuit, sans l'attendre. Une seule relance par personne et par publication, que la nuit ou l'organisateur l'ait envoyée ; le compte rendu nomme ceux qui n'ont pas été écrits et pourquoi |
| Rappels automatiques | La veille au soir, chacun reçoit la liste de ses postes du lendemain ; les silencieux sont relancés une fois, sauf si l'organisateur l'a déjà fait à la main ; les demandes d'échange qui dorment remontent à l'organisation. Rien ne part d'une édition qui ne l'a pas explicitement demandé, et personne ne reçoit deux fois le même message |
| Foire au planning | Les animateurs proposent leurs échanges de créneaux en libre-service ; le collègue visé donne son accord, l'organisation arbitre, rien ne s'applique sans validation. Un échange accepté le dit dans l'espace du demandeur en annonçant qu'il n'entrera dans son planning qu'à la publication suivante, et l'écran d'arbitrage montre les décisions qui restent à publier. Ouvrable sur une période datée, comme la collecte des disponibilités |
| Qui peut me remplacer ? | Sans personne en tête, l'animateur ne désigne que son créneau : l'application cherche les échanges qui tiennent vraiment et les range en trois familles — on vous libère, vous permutez sur le même créneau, ou vous l'échangez contre un créneau d'un autre jour |
| Collecte des disponibilités | L'organisation ouvre une période pendant laquelle chaque animateur déclare lui-même, depuis son espace, les jours où il ne peut pas venir et ce qu'il aimerait animer — au doigt sur un téléphone, en cochant des jours. La déclaration reste une proposition : elle n'est prise en compte qu'une fois appliquée par l'organisation, en bloc |
| Invitation à déclarer | Une case à cocher au moment d'ouvrir la collecte envoie à chacun le lien de son espace. À cocher au premier tour, à laisser de côté quand on rouvre la période après une correction |
| Aide de l'espace animateur | Un onglet « Aide » dans l'espace, écrit pour l'animateur et non pour l'organisation : lire son planning, l'emporter, demander un échange et suivre ce qu'il devient — en questions repliées, lisibles au doigt sur un téléphone |

### Outils

| Fonctionnalité | En une phrase |
| --- | --- |
| Imports | Tous les fichiers qui remplissent une édition sur un seul écran, un onglet par référentiel et dans l'ordre où les données se tiennent : typologies, emplacements, stands, créneaux, journées types, animateurs, puis la grille des stands, et enfin le fichier scénario — le seul qui remplace l'édition au lieu de la compléter. Les trois premiers demandent le minimum — un code et un libellé, un code et un nom, un code, un nom et des typologies — l'effectif d'un stand et les coordonnées d'un emplacement restant facultatifs. Les créneaux se reconnaissent à leur date et à leurs deux heures plutôt qu'à un identifiant, les journées types à leur nom, leurs vacations tenant sur une ligne (« 09:00-12:00, 12:00-13:00 R, 14:00-20:00 ») et leurs dates venant compléter le calendrier sans le remplacer — la grille, elle, ne bouge qu'à l'application du calendrier. Une colonne absente n'efface rien, une ligne dont le code est connu est mise à jour, et une typologie citée sans exister est créée après l'avoir annoncée. Aucun identifiant n'est jamais saisi ni lu dans un fichier : l'application attribue les siens (A1, A2… pour les animateurs, S1… pour les stands), numérotés dans chaque édition ; une ligne se désigne par son code, sinon par son nom, et un animateur par son adresse e-mail, sinon par ses nom et prénom |
| Exports | Trois formes sur un écran. L'archive ZIP : un CSV par référentiel — typologies, emplacements, stands, créneaux, journées types, animateurs — chacun à cocher, et chacun dans la forme exacte que l'onglet d'import correspondant relit, de quoi recopier une édition sur l'autre, rejouer le calendrier d'une année sur l'autre, ou corriger en masse dans un tableur puis réimporter. Le fichier scénario : l'édition entière dans un seul YAML, celui que l'onglet Scénario des imports relit |
| Archive de fin d'événement | Garder la trace d'une édition terminée en un clic : un seul ZIP réunit, au choix, le planning global, l'équité, les heures travaillées, les référentiels, le scénario réimportable, la relecture de publication et les plannings individuels, avec un manifeste lisible sans l'application qui dit de quel plan viennent ces fichiers, quand il a été calculé et publié, combien de journées ont été relues. Les fichiers sont ceux que chaque écran aurait téléchargés ; aucun jeton d'accès n'y figure, et le téléchargement est inscrit à l'historique. L'État de l'édition le propose une fois le dernier jour de l'événement passé |
| Import / export de scénario | Un fichier YAML décrit une configuration complète d'événement ; l'import valide le fichier et explique ce qui cloche. Les cinquante et quelques scénarios livrés avec l'application — fixtures de démonstration, gamme de tests et cas extrêmes — se chargent depuis le sélecteur de la page **Débogage** |
| Import de la grille des stands | La matrice du classeur — stands en lignes, jours et créneaux en colonnes, un effectif par case — arrive telle quelle : chaque colonne se pose sur son créneau, une colonne inconnue est ignorée et listée, chaque stand voit son horaire réécrit depuis ses cases après un aperçu ligne par ligne. La grille actuelle se télécharge comme modèle |
| Import CSV des animateurs | Le tableur de bénévoles arrive tel quel : on désigne quelle colonne est quel champ, on lit ligne par ligne ce que l'import ferait — acceptée, rejetée et pourquoi, avec le numéro de ligne du fichier — et rien n'est écrit tant qu'on n'a pas validé. Par défaut il ajoute et met à jour sans supprimer personne, et complète les jours d'indisponibilité déjà déclarés au lieu de les effacer. Un CSV d'exemple est téléchargeable depuis l'écran |
| Export / import d'un dump SQL | Dupliquer ou restaurer un jeu de données complet |
| Sauvegarde automatique | Chaque nuit, toute la base est copiée sur le disque de l'hébergeur, et seules les dernières copies sont gardées. L'écran des paramètres dit où elles vont, lesquelles existent et si la dernière nuit s'est bien passée. Une nuit en échec prévient l'administrateur par e-mail — la raison, la dernière copie réussie, le nombre de nuits d'affilée — et un second message annonce la première nuit qui réussit de nouveau. La restauration, elle, est une opération de l'exploitant sur la base |
| Assistant IA (MCP) | Un assistant IA consulte et pilote l'application en langage naturel, sans jamais voir les données personnelles des animateurs — voir [`docs/mcp.md`](docs/mcp.md) |
| Mentions légales | Page publique, lisible sans être connecté et sans lien valide : éditeur, hébergeur, contact, propriété intellectuelle |
| Politique de confidentialité | Page publique elle aussi : quelles données, pourquoi, combien de temps, qui y accède — y compris les outils de mesure d'audience et de suivi d'erreurs — et comment exercer ses droits. S'adresse explicitement aux animateurs mineurs |
| Conditions d'utilisation | La ligne de partage, écrite noir sur blanc : l'application calcule des propositions, l'organisation décide. Elle reste l'employeur, le responsable des données et du respect de la réglementation ; le logiciel est fourni en l'état |
| Déclaration d'accessibilité | Page publique, liée depuis chaque écran : l'état de conformité au RGAA que l'organisation déclare, les contenus non accessibles, où signaler un défaut. Vide, elle le dit plutôt que d'annoncer une conformité. Les écrans se parcourent au clavier — déplacer une affectation compris — et les raccourcis à une touche se coupent |
| Aide intégrée | Le mode d'emploi complet, cherchable, consultable pendant qu'une résolution tourne ou sur une édition vide |
| Nouveautés | Ce que la version installée a apporté, et depuis quelle version : les évolutions sont lues dans l'historique du dépôt au moment de la construction, regroupées par version — ce qui n'est pas encore publié apparaît sous « À venir » — et triées comme les notes de version, « À surveiller » d'abord. Rien à tenir à jour à la main, donc rien qui puisse mentir sur ce qui tourne |
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
