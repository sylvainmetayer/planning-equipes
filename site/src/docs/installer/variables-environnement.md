# Retrouver une variable d'environnement

Vous cherchez le nom, le défaut et l'effet d'un réglage de l'instance : chaque variable que lit l'application, le compose de production ou le script d'entrée de l'image est listée ici, par famille. L'application se règle **par l'environnement**, jamais par un écran, à une exception près : ce qui change d'une édition à l'autre (durées, rappels, poids des règles) se règle dans *Paramètres*, et les variables ci-dessous ne donnent que le défaut ou le plafond de l'instance. Le fichier [`.env.prod.example`](repo:.env.prod.example) en donne la version à copier, commentée, pour le compose de production.

Trois conventions :

- une durée est écrite au format ISO-8601 (`PT15M` : quinze minutes, `P90D` : quatre-vingt-dix jours) ;
- une valeur vide n'est pas une valeur absente : `BACKUP_DIR=` désactive la sauvegarde, comme l'indique la colonne *Défaut* ;
- tout réglage Quarkus qui n'a pas de variable propre se pose aussi par l'environnement, en majuscules et soulignés (`quarkus.http.port` devient `QUARKUS_HTTP_PORT`). Le compose de production en utilise deux (`QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY`, `QUARKUS_HTTP_PROXY_TRUSTED_PROXIES`), et [le guide de l'accès à l'administration](securite-acces-administration.md) en cite un troisième pour le cookie de session OIDC.

| Famille | Pour la mettre en œuvre |
| --- | --- |
| Obligatoires, mentions légales | [Installer l'application sur votre serveur](installation.md), [Renseigner les mentions légales](mentions-legales.md) |
| Réseau, limites, en-têtes | [le durcissement](securite.md) |
| Connexion et comptes | [la connexion par un fournisseur d'identité](oidc.md), [les comptes locaux](comptes-locaux.md) |
| Calcul du planning, machine de calcul | [Régler le calcul du planning](calcul-du-planning.md), [Résoudre sur une autre machine](calcul-sur-une-autre-machine.md) |
| Courriels, envois de nuit | [Faire arriver vos e-mails](courriels.md), [Régler les envois automatiques](envois-automatiques.md) |
| Sauvegarde, conservation | [Sauvegarder et restaurer](sauvegarde-et-restauration.md), [Purger et conserver les données](conservation-des-donnees.md) |
| Webhooks, météo | [Autoriser les webhooks et l'alerte météo](appels-sortants.md) |
| Suivi d'erreurs, audience | [Brancher le suivi d'erreurs et les métriques](observabilite.md) |
| Serveur MCP | [le serveur MCP](../comprendre/mcp.md) |

## Obligatoires en production

Le `docker-compose.prod.yml` refuse de démarrer sans elles (`?` dans le compose), et l'application refuse de démarrer sur les deux mots de passe d'exemple.

L'application, elle, refuse aussi de démarrer en production — quel que soit le
compose — dans les cas suivants, chacun détaillé à sa ligne plus bas :

- une des cinq mentions légales exigées est vide (`LEGAL_EDITEUR`,
  `LEGAL_HEBERGEUR`, `LEGAL_CONTACT`, `LEGAL_BASE_LEGALE`,
  `LEGAL_CONSERVATION`), sauf `LEGAL_DEMO_INSTANCE=true` ;
- `ADMIN_PASSWORD` ou `DB_PASSWORD` vaut la valeur d'exemple ou est vide ;
- aucune porte n'ouvre l'administration (`OIDC_ENABLED=false` et
  `ADMIN_LOCAL_SECOURS=false`), ou `OIDC_CLIENT_SECRET` fait moins de 32
  caractères quand OIDC est actif ;
- `REMOTE_USER_ENABLED=true` sans `REMOTE_USER_SECRET` ;
- un webhook existe et `WEBHOOKS_SECRET_KEY` est absente ou invalide ;
- `BACKUP_RETENTION` sort de 1 à 30, `SOLVER_SECONDS_LIMIT_MAX` est sous 900,
  `SOLVER_UNIMPROVED_SECONDS_LIMIT_MAX` sous 180 ;
- une entrée de `CONNEXION_PROXYS_FIABLES` ou de `WEBHOOKS_RESEAUX_AUTORISES`
  est mal formée ;
- la machine de calcul distante est mal décrite (voir *Calcul du planning*).

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `APP_VERSION` | *(aucun : obligatoire)* | Version de l'image à déployer, une version nommée (`1.2.0`), jamais `main` ni `latest`. Lue par le compose seul, pas par l'application |
| `DB_PASSWORD` | *(aucun : obligatoire)* | Mot de passe PostgreSQL, partagé par l'application et le conteneur de base. Le mot de passe d'exemple refuse le démarrage. Défaut hors compose : `festival` (en dev : celui du `docker-compose.yml`) |
| `ADMIN_PASSWORD` | *(aucun : obligatoire)* | Mot de passe du compte `admin`. Défaut hors compose : `admin`, qui refuse le démarrage en production, comme une valeur vide |
| `SESSION_ENCRYPTION_KEY` | *(aucun : obligatoire)* | Clé d'au moins 16 caractères qui chiffre le cookie de session. Le compose la transmet à l'application sous le nom `QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY` ; sans clé fixe, les sessions tombent à chaque redémarrage |
| `PUBLIC_URL` | `http://localhost:8080` | URL publique de l'instance, imprimée comme lien « espace animateur » sur les PDF et dans les mails. Obligatoire dans le compose de production |
| `MAIL_HOST` | `localhost` | Serveur SMTP. Obligatoire dans le compose de production |
| `MAIL_FROM` | `planning-equipes@localhost` | Adresse expéditrice. Obligatoire dans le compose de production |

## Base de données

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/festival` | URL JDBC de PostgreSQL. Le compose de production la fixe sur le conteneur `postgres` |
| `DB_USER` | `festival` | Utilisateur de la base |
| `FLYWAY_REPAIR_AT_START` | `false` | `true` : réparer l'historique des migrations au démarrage (supprime la ligne d'une migration restée à moitié). Pour un seul démarrage, en connaissance de cause |
| `ALLOW_SCHEMA_AHEAD` | `false` | `true` : démarrer sur une base plus récente que l'image (retour arrière), avec un avertissement. Pour un seul démarrage |

## Réseau et reverse proxy

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `HTTP_PORT` | `8080` | Port HTTP de l'application |
| `PROXY_ADDRESS_FORWARDING` | `true` | Suivre les en-têtes `X-Forwarded-*` d'un reverse proxy qui termine le TLS (redirection de connexion en `https`, cookie `Secure`) |
| `TRUSTED_PROXIES` | *(vide)* | Lue par le compose de production et transmise sous le nom `QUARKUS_HTTP_PROXY_TRUSTED_PROXIES` : les adresses dont les en-têtes `X-Forwarded-*` sont acceptés. Vide : ceux de tout appelant ; à poser dès que l'application est joignable sans passer par le proxy |
| `CONNEXION_PROXYS_FIABLES` | *(vide)* | Proxys inverses dont l'en-tête `X-Forwarded-For` est cru, pour compter par adresse les échecs de connexion administrateur, les lectures refusées de l'affichage mural et, par défaut, les appels MCP. Vide : l'adresse de la connexion est comptée, donc tous les visiteurs derrière un proxy partagent un compteur. Une entrée mal formée empêche le démarrage |
| `CSP` | (politique par défaut) | Politique de sécurité du contenu envoyée à chaque réponse ; vide : en-tête désactivé |
| `HSTS` | `max-age=31536000; includeSubDomains` | En-tête HSTS, envoyé sur une visite HTTPS ; vide : désactivé |
| `MAX_BODY_SIZE` | `10M` | Taille maximale d'un corps de requête |
| `MAX_CONNECTIONS` | `500` | Connexions HTTP simultanées acceptées |
| `HTTP_IDLE_TIMEOUT` | `PT16M` | Délai avant fermeture d'une connexion inactive, à tenir au-dessus du plus long calcul synchrone |
| `TZ` | `Europe/Paris` (compose de production) | Fuseau des conteneurs, lu par le compose |
| `LOG_MAX_SIZE` | `20m` (compose de production) | Taille maximale d'un fichier de journal de conteneur, lue par le compose |
| `LOG_MAX_FILE` | `5` (compose de production) | Nombre de fichiers de journal conservés par conteneur, lu par le compose |

## Connexion et comptes

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `OIDC_ENABLED` | `false` | Activer la connexion par un fournisseur d'identité OpenID Connect |
| `OIDC_AUTH_SERVER_URL` | *(vide)* | URL de l'émetteur du fournisseur, la même vue du navigateur et de l'application |
| `OIDC_CLIENT_ID` | `planning-app` | Identifiant du client déclaré chez le fournisseur |
| `OIDC_CLIENT_SECRET` | *(vide)* | Secret du client, d'au moins 32 caractères : plus court, démarrage refusé en production quand OIDC est actif |
| `OIDC_REQUIRE_EMAIL_VERIFIED` | `true` | Exiger que le fournisseur affirme l'adresse vérifiée ; `false` pour un annuaire qui n'envoie pas cette information |
| `OIDC_SCOPES` | `profile,email` | Périmètres demandés au fournisseur |
| `OIDC_SESSION_AGE_EXTENSION` | `30M` | Prolongation de la session après expiration du jeton d'identité |
| `OIDC_POST_LOGOUT_PATH` | `/login` | Chemin où l'on revient après la déconnexion |
| `OIDC_PRINCIPAL_CLAIM` | `email` | Revendication qui porte l'identité de la personne |
| `OIDC_CONNECTION_DELAY` | `30S` | Durée pendant laquelle la découverte du fournisseur est retentée au démarrage |
| `OIDC_MCP_AUDIENCE` | `planning-mcp` | Audience exigée des jetons présentés par un assistant IA connecté en OAuth : un jeton émis pour un autre client du fournisseur est refusé. Déclarez cette audience chez le fournisseur |
| `AUTH_RECENT_WINDOW` | `PT10M` | Ancienneté maximale de la connexion d'un administrateur pour générer ou afficher la clé de l'assistant IA |
| `OIDC_FORCE_HTTPS` | `false` | `true` derrière un proxy qui termine le TLS : redirections en `https` |
| `ADMIN_LOCAL_SECOURS` | *(vide)* | Compte `admin` de la configuration. Vide : ouvert sans OIDC, fermé derrière un fournisseur OIDC ; `true` le garde ouvert comme compte de secours. `false` sans OIDC refuse le démarrage (aucune porte n'ouvrirait l'administration) |
| `AUTH_LOCAL_SECONDE_ETAPE` | `true` | Les comptes locaux reçoivent un code à six chiffres par mail après leur mot de passe ; `false` coupe cette seconde étape |
| `CONNEXION_MAX_ECHECS` | `5` | Échecs de connexion tolérés avant verrouillage |
| `CONNEXION_DUREE_BLOCAGE` | `PT15M` | Durée du verrouillage, depuis le dernier échec |
| `ESPACE_CODE_MAX_DEMANDES` | `3` | Codes d'accès non utilisés tolérés par animateur avant refus |
| `ESPACE_CODE_FENETRE` | `PT10M` | Fenêtre sur laquelle ce plafond se compte |
| `ESPACE_DECLARATION_MAX_ENVOIS` | `20` | Déclarations de disponibilités qu'un animateur peut envoyer depuis son espace sur la fenêtre |
| `ESPACE_DECLARATION_FENETRE` | `PT10M` | Fenêtre de ce plafond |
| `ESPACE_COLLEGUES_MAX` | `40` | Collègues différents dont un animateur peut consulter les postes (choix d'un échange) sur la fenêtre |
| `ESPACE_COLLEGUES_FENETRE` | `PT1H` | Fenêtre de ce plafond |
| `AFFICHAGE_MURAL_MAX_REFUS` | `30` | Lectures refusées (lien inconnu ou révoqué) de l'affichage mural tolérées par adresse sur la fenêtre |
| `AFFICHAGE_MURAL_MAX_LECTURES_PAR_LIEN` | `120` | Lectures permises par lien d'affichage mural sur la fenêtre |
| `AFFICHAGE_MURAL_FENETRE` | `PT1M` | Fenêtre de ces deux plafonds |
| `REMOTE_USER_ENABLED` | `false` | Authentification par en-tête derrière un proxy d'accès (déprécié) |
| `REMOTE_USER_HEADER` | `Remote-Email` | En-tête qui porte l'adresse authentifiée |
| `REMOTE_USER_SECRET_HEADER` | `Remote-Auth-Secret` | En-tête qui porte le secret partagé |
| `REMOTE_USER_SECRET` | *(vide)* | Secret partagé avec le proxy ; obligatoire si le mode est actif |
| `REMOTE_USER_ADMIN_EMAIL` | *(vide)* | Adresse qui obtient le rôle administrateur |

## Serveur MCP

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `PLANNING_MCP_API_KEY` | *(vide)* | Clé d'API attendue pour authentifier le serveur MCP ; vide : MCP inutilisable |
| `PLANNING_MCP_API_KEY_HEADER` | `X-MCP-Api-Key` | En-tête qui porte la clé (ou `Authorization: Bearer <clé>`) |
| `PLANNING_MCP_REQUIRED_HEADERS` | *(vide)* | En-têtes exigés en plus de la clé, `Nom=valeur` séparés par des virgules |
| `PLANNING_MCP_PANGOLIN_ACCESS_TOKEN_ID` | *(vide)* | Identifiant du jeton d'accès Pangolin, révélable depuis la page MCP |
| `PLANNING_MCP_PANGOLIN_ACCESS_TOKEN` | *(vide)* | Jeton d'accès Pangolin correspondant |
| `PLANNING_MCP_MAX_REQUESTS` | `120` | Requêtes tolérées par adresse et par fenêtre ; `0` ou moins désactive le plafond |
| `PLANNING_MCP_RATE_WINDOW` | `PT1M` | Durée de la fenêtre, au format ISO-8601 |
| `PLANNING_MCP_TRUSTED_PROXIES` | (valeur de `CONNEXION_PROXYS_FIABLES`) | Proxys dont `X-Forwarded-For` est cru pour compter les appels |
| `PLANNING_MCP_MAX_FAILURES` | `5` | Clés refusées consécutives tolérées par adresse avant blocage ; `0` ou moins désactive le verrou |
| `PLANNING_MCP_LOCKOUT_DURATION` | `PT10M` | Durée de ce blocage |

## Calcul du planning

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `SOLVER_ENGINE` | `hand` | `hand` : moteur direct, chaque plan re-scoré par les contraintes ; `timefold` : la recherche d'avant la 3.0.0 |
| `SOLVER_SECONDS_LIMIT_MAX` | `3600` | Plafond, en secondes, du temps de calcul qu'une édition peut régler ou qu'un lancement peut demander ; sous le défaut de 900 s, démarrage refusé |
| `SOLVER_UNIMPROVED_SECONDS_LIMIT_MAX` | (celui de la durée) | Plafond de l'arrêt sans amélioration ; sous le défaut de 180 s, démarrage refusé |
| `SOLVER_MAX_SOLVES_PER_HOUR` | `0` (aucune limite) | Calculs acceptés par l'instance sur les 60 dernières minutes glissantes, toutes éditions et tous appelants confondus ; au-delà, refus qui dit quand revenir |
| `SOLVER_MAX_QUEUED_JOBS` | `0` (aucune limite) | Tâches qui peuvent attendre derrière le calcul en cours |
| `PASSE_FIGE` | `true` | `false` : le solveur peut de nouveau réécrire les journées déjà travaillées. À réserver à une recette qui rejoue une édition ancienne |
| `JOBS_STREAM_HEARTBEAT` | `PT20S` | Battement du flux de suivi des calculs, qui garde les proxys ouverts |
| `JOBS_STREAM_SCORE` | `PT1S` | Fréquence d'envoi de la courbe de score d'un calcul en cours |
| `SOLVER_WORKER_URL` | *(vide)* | Adresse d'un worker permanent qui reçoit les calculs complets. En `https`, sauf sur la boucle locale ; exige `SOLVER_WORKER_SECRET` ; exclut `SOLVER_WORKER_HOOK_URL`. Sinon, démarrage refusé |
| `SOLVER_WORKER_SECRET` | *(vide)* | Secret partagé avec le worker, la même valeur des deux côtés : obligatoire avec `SOLVER_WORKER_URL`, et pour démarrer l'image en mode worker |
| `SOLVER_WORKER_TLS_FINGERPRINT` | *(vide)* | Empreinte du certificat du worker, épinglée |
| `SOLVER_WORKER_HOOK_URL` | *(vide)* | Adresse du lanceur qui crée une machine à la demande (incompatible avec l'URL d'un worker permanent). En `https`, sauf sur la boucle locale ou un réseau privé ; exige `SOLVER_WORKER_HOOK_SECRET` |
| `SOLVER_WORKER_HOOK_SECRET` | *(vide)* | Secret du lanceur |
| `SOLVER_WORKER_INSTANCE` | `planning` | Nom que l'instance donne au lanceur, quand il en sert plusieurs |
| `SOLVER_WORKER_DEFAULT` | `local` | `remote` : un calcul part sur la machine de calcul quand son lancement ne dit rien ; `local` sinon |
| `SOLVER_WORKER_START_TIMEOUT` | `PT5M` | Temps accordé à une machine pour démarrer |
| `SOLVER_WORKER_IDLE_TIMEOUT` | `PT45M` | Inactivité au bout de laquelle une machine à la demande est arrêtée |
| `SOLVER_WORKER_MODE` | `false` | `true` : l'image démarre le worker de calcul au lieu de l'application. Lue par `docker/entrypoint.sh` |
| `SOLVER_WORKER_PORT` | `8443` | Mode worker : port d'écoute |
| `SOLVER_WORKER_TLS_KEYSTORE` | (aucun) | Mode worker : fichier PKCS#12 du certificat TLS. Sans lui, le worker écoute en HTTP sur la seule boucle locale ; avec lui, en HTTPS sur toutes les interfaces |
| `SOLVER_WORKER_TLS_KEYSTORE_PASSWORD` | *(vide)* | Mode worker : mot de passe de ce fichier |
| `JDK_JAVA_OPTIONS` | `-XX:MaxRAMPercentage=75 -XX:+UseParallelGC` (posé par l'image) | Options de la JVM ; les redéfinir remplace la valeur de l'image en entier ([la mémoire](calcul-du-planning.md#la-mémoire-de-lapplication)) |

## Courriels

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `MAIL_HOST` | `localhost` | Serveur SMTP (Mailpit en local) |
| `MAIL_PORT` | `1025` | Port SMTP (587 dans le compose de production) |
| `MAIL_USERNAME` | *(vide)* | Identifiant SMTP |
| `MAIL_PASSWORD` | *(vide)* | Mot de passe SMTP |
| `MAIL_START_TLS` | `DISABLED` | `DISABLED`, `OPTIONAL` ou `REQUIRED` (`REQUIRED` dans le compose de production) |
| `MAIL_MOCK` | `false` | `true` : les mails sont journalisés au lieu d'être envoyés |
| `MAIL_FROM` | `planning-equipes@localhost` | Adresse expéditrice |
| `MAIL_ADMIN` | *(vide)* | Adresse de l'administrateur : demandes d'échange, fin de résolution si l'édition le demande, alerte météo, sauvegarde de nuit en échec puis rétablie ; vide : désactivé, et un échec de sauvegarde ne se voit que sur l'écran des paramètres |

## Sauvegarde, tâches de nuit et conservation

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `BACKUP_DIR` | *(vide)* | Répertoire où la sauvegarde de nuit écrit ses `pg_dump` ; vide : sauvegarde désactivée (`/backup` dans le compose de production) |
| `BACKUP_RETENTION` | `10` | Nombre de sauvegardes conservées, de 1 à 30 ; hors bornes, démarrage refusé |
| `BACKUP_CRON` | `0 0 4 * * ?` | Horaire de la sauvegarde (expression cron) |
| `BACKUP_TIMEZONE` | `Europe/Paris` | Fuseau dans lequel se lit cet horaire |
| `PG_DUMP` | `pg_dump` | Binaire `pg_dump` à lancer |
| `BACKUP_TIMEOUT` | `PT30M` | Temps accordé à un dump |
| `JOURNAL_RETENTION` | `P90D` | Durée de conservation de l'historique des actions, du journal des connexions et du résultat des mails envoyés aux animateurs |
| `NOTIFICATIONS_CRON` | `0 5 * * * ?` | Rythme du réveil des envois de nuit (rappels, relances) |
| `NOTIFICATIONS_TIMEZONE` | `Europe/Paris` | Fuseau de l'événement, pour ces envois et pour l'alerte météo |
| `EDITIONS_RAPPEL_JOURS` | `7` | Nombre de jours avant le premier créneau où l'on rappelle d'activer l'édition |
| `HORLOGE_SIMULEE_AUTORISEE` | `false` | `true` : les Paramètres peuvent figer la date et l'heure lues par « Aujourd'hui » et l'espace animateur. Démonstration ou recette uniquement |

## Appels sortants : webhooks et météo

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `WEBHOOKS_ENABLED` | `true` | `false` coupe toute sortie par webhook |
| `WEBHOOKS_CRON` | `0 * * * * ?` | Rythme de reprise des livraisons en attente de réessai |
| `WEBHOOKS_SECRET_KEY` | *(vide)* | Clé AES-256 (32 octets en base64, `openssl rand -base64 32`) qui chiffre les secrets des webhooks. Sans elle, créer un webhook est refusé, et le démarrage l'est dès qu'un webhook existe ; une clé mal formée refuse le démarrage |
| `WEBHOOKS_RESEAUX_AUTORISES` | *(vide)* | Réseaux (adresses ou CIDR, séparés par des virgules) qu'un webhook peut viser alors que la garde les refuse d'ordinaire (boucle locale, réseaux privés) ; `http://` n'est accepté que vers eux. Une entrée mal formée empêche le démarrage |
| `METEO_ENABLED` | `true` | `false` coupe l'alerte météo pour toute l'instance |
| `METEO_CRON` | `0 0 6 * * ?` | Heure de l'interrogation du service météo |
| `METEO_URL` | `https://api.open-meteo.com/v1/forecast` | Service de prévision interrogé (l'offre gratuite d'Open-Meteo est réservée à un usage non commercial) |
| `SENTRY_DSN` | *(vide)* | Suivi d'erreurs (Bugsink ou tout point d'entrée compatible Sentry) ; vide : désactivé |
| `SENTRY_ENVIRONMENT` | `local` | Étiquette d'environnement jointe aux erreurs (`production` dans le compose de production) |
| `CLOUDFLARE_WEB_ANALYTICS_TOKEN` | *(vide)* | Jeton Cloudflare Web Analytics, profil de production seulement ; vide : désactivé |

## Marque et documents

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `BRANDING_PRODUCT_NAME` | `Planning Équipes` | Nom du produit : onglet, titres, sujets des mails, PDF |
| `BRANDING_ORGANISATION` | *(vide)* | Client pour lequel l'instance est déployée, imprimé au pied des PDF et des mails |
| `BRANDING_LOGO_URL` | *(vide)* | Logo affiché dans l'application (URL, ou fichier servi à la racine) |
| `BRANDING_ACCENT_COLOR` | *(vide)* | Couleur d'accent de l'interface, toute couleur CSS |
| `BRANDING_MASCOT_URL` | *(vide)* | Mascotte en grand, montrée par le code Konami |
| `BRANDING_MASCOT_ICON_URL` | *(vide)* | La même mascotte en petit |
| `BRANDING_SUPPORT_EMAIL` | `planning@sylvain.dev` | Adresse de support nommée par la page Aide ; vide : le paragraphe disparaît |
| `BRANDING_PDF_LOGO` | *(vide)* | Logo de l'en-tête des PDF et des mails HTML (embarqué, jamais chargé à distance) |
| `BRANDING_PDF_STRIP` | *(vide)* | Bandeau décoratif de la première page du planning individuel |
| `BRANDING_PDF_HEADLINE` | `#1f2933` | Encre principale des PDF |
| `BRANDING_PDF_MUTED` | `#6b7280` | Texte secondaire des PDF |
| `BRANDING_PDF_ACCENT` | `#3a6ea5` | Accent des PDF et des mails HTML |
| `BRANDING_PDF_HIGHLIGHT` | `#e4eaf1` | Fond des tuiles de statistiques et des en-têtes de tableau |
| `BRANDING_PDF_PILL` | `#f1f4f8` | Fond des pastilles d'horaire et filets de tableau |

## Mentions légales

Les cinq marquées **exigée** refusent le démarrage en production quand elles sont vides, sauf `LEGAL_DEMO_INSTANCE=true`. Elles s'affichent sur la page publique `/mentions-legales`.

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `LEGAL_DEMO_INSTANCE` | `false` | `true` : instance qui n'est pas en service, dispensée des mentions exigées |
| `LEGAL_EDITEUR` | *(vide)* | **Exigée.** Éditeur (nom, forme juridique, adresse, immatriculation) |
| `LEGAL_DIRECTEUR_PUBLICATION` | *(vide)* | Directeur de la publication |
| `LEGAL_HEBERGEUR` | *(vide)* | **Exigée.** Hébergeur (nom et adresse) |
| `LEGAL_CONTACT` | *(vide)* | **Exigée.** Adresse de contact, y compris pour exercer ses droits |
| `LEGAL_RESPONSABLE_TRAITEMENT` | *(vide)* | Responsable de traitement, quand il diffère de l'éditeur ; vide : l'éditeur |
| `LEGAL_BASE_LEGALE` | *(vide)* | **Exigée.** Base légale du traitement des données personnelles |
| `LEGAL_CONSERVATION` | *(vide)* | **Exigée.** Durée de conservation des données personnelles |
| `LEGAL_ACCESSIBILITE_ETAT` | *(vide)* | Déclaration d'accessibilité : conformité `totale`, `partielle` ou `non` |
| `LEGAL_ACCESSIBILITE_DATE_AUDIT` | *(vide)* | Date de l'audit d'accessibilité |
| `LEGAL_ACCESSIBILITE_CONTENUS` | *(vide)* | Contenus non accessibles |
| `LEGAL_ACCESSIBILITE_SIGNALEMENT` | *(vide)* | Voie de signalement ; vide : l'adresse de contact des mentions |

## Marque blanche

Le modèle de déploiement est **une instance par client** : l'identité se règle
donc par déploiement, avec les variables `BRANDING_*` de la section *Marque et documents*, et rien n'est
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

## Ce qui n'est pas une option d'exploitation

`OUTILLAGE_TEST_DUMP_SQL` ouvre deux routes d'export et d'import SQL de toute la
base, par lesquelles les suites de tests s'amorcent. Elle reste à `false` en
production et ne figure pas dans `.env.prod.example` : c'est un outil de test,
jamais un réglage d'exploitation.
