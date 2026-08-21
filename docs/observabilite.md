# Observabilité

Deux briques optionnelles, **désactivées par défaut en dev/test**. En
production, le suivi d'erreurs reste conditionné à `SENTRY_DSN`, mais la mesure
d'audience Cloudflare est **active par défaut** : le profil `%prod` fournit un
token de repli (`application.properties`). Un exploitant qui n'en veut pas doit
donc positionner `CLOUDFLARE_WEB_ANALYTICS_TOKEN` à vide, et non simplement
« ne rien configurer ».

Deux garde-fous s'appliquent aux pages de l'espace animateur, dont l'URL porte
le jeton d'accès — un identifiant unique de personne, souvent mineure : le
beacon Cloudflare **n'y est pas chargé du tout**, et les rapports d'erreur
remplacent le jeton par `<jeton>` avant de partir (`masquerJetonEspace`). Sans
cela, l'identifiant partait chez un tiers à chaque page vue, et dormait dans
la base de suivi d'erreurs à chaque incident. L'en-tête `Referrer-Policy:
same-origin` ferme le troisième canal, celui des liens sortants.

- **Suivi d'erreurs** — [Bugsink](https://www.bugsink.com/), auto-hébergé,
  compatible avec le protocole/SDK Sentry.
- **Analytics d'usage** — [Cloudflare Web Analytics](https://www.cloudflare.com/web-analytics/)
  (mesure légère des pages vues/navigation).

## Pourquoi ces choix

| Besoin | Outil | Pourquoi |
| --- | --- | --- |
| Erreurs backend + frontend | Bugsink | Auto-hébergé (Docker, SQLite par défaut, pas de dépendance Redis/Celery), donc pas de donnée envoyée à un tiers ; **compatible avec les SDK Sentry** — n'importe quel SDK Sentry officiel (Java, JavaScript, …) fonctionne en pointant simplement son DSN vers l'instance Bugsink. Alternative plus légère à un Sentry auto-hébergé. |
| Audience / pages vues légères | Cloudflare Web Analytics | Script minimal, sans cookie, facile à activer uniquement en production ; capte les pages vues même en navigation SPA (History API) via le beacon officiel Cloudflare. |

**Analytics produit retirée.** Le projet a un temps embarqué PostHog pour
mesurer l'usage des écrans. La brique a été déposée : personne ne consultait
les tableaux de bord, et elle coûtait une dépendance JavaScript, deux variables
d'environnement et un traitement de données personnelles à justifier — trois
prix payés pour rien. Cloudflare Web Analytics couvre le besoin restant
(combien de pages vues, lesquelles) sans cookie ni SDK.

Si le besoin d'événements produit revient, les candidats regardés à l'époque
étaient PostHog (palier gratuit le plus généreux, hébergement EU),
**Umami** (~100k événements/mois) et **Plausible** (pas de palier gratuit
hébergé). `app/core/observability.ts` reste le seul point d'entrée à
reprendre : rien d'autre dans le code ne connaissait PostHog.

## Suivi d'erreurs (Bugsink / Sentry)

### Mise en place de Bugsink

Bugsink s'auto-héberge (image Docker officielle, voir sa documentation) :
créer une organisation puis un projet y donne un DSN
(`https://<clé>@<host>/<projet>`) à copier dans `SENTRY_DSN`. N'importe quel
autre service compatible avec le protocole d'ingestion Sentry (y compris
Sentry SaaS lui-même) fonctionne de la même façon : seule la valeur du DSN
change.

### Intégration dans l'application

- **Backend** : SDK Java `io.sentry:sentry` (pas d'extension Quarkus dédiée
  pour Sentry, donc SDK simple, initialisé manuellement) — voir
  `SentryInitializer` (`@Observes StartupEvent`, no-op si `SENTRY_DSN` est
  vide) et `GlobalExceptionMapper`, qui remonte à Bugsink toute exception REST
  non gérée (`ExceptionMapper<Throwable>`), à l'exclusion des
  `WebApplicationException` volontaires (404, 409, …) déjà gérées par les
  ressources.
- **Frontend** : `@sentry/angular`, initialisé dans `src/main.ts` via
  `app/core/observability.ts`, avant `bootstrapApplication()`. Remplace
  l'`ErrorHandler` Angular par celui de Sentry uniquement quand un DSN est
  configuré.

### Variables d'environnement

| Variable | Défaut | Usage |
| --- | --- | --- |
| `SENTRY_DSN` | *(vide)* | DSN du projet Bugsink (ou tout endpoint compatible Sentry). Vide = désactivé, backend et frontend. |
| `SENTRY_ENVIRONMENT` | `local` | Étiquette d'environnement (`production`, `staging`, …) jointe à chaque erreur remontée. |

### Vérifier le câblage

L'onglet Débogage propose deux boutons, « Exception front » et « Exception
back », qui génèrent chacun une exception de test — respectivement une
exception JS non rattrapée côté navigateur (`ErrorHandler` Angular) et un
appel à `POST /api/debug/test-exception`, qui lève systématiquement côté
serveur pour passer par `GlobalExceptionMapper`. Utile pour confirmer qu'un
DSN fraîchement configuré remonte bien jusqu'à Bugsink/Sentry, sans attendre
un vrai bug.

## Analytics d'usage (Cloudflare Web Analytics)

### Mise en place

Activer Web Analytics sur le site dans le tableau de bord Cloudflare : le
token fourni va dans `CLOUDFLARE_WEB_ANALYTICS_TOKEN`.

### Intégration dans l'application

Uniquement côté frontend, initialisé dans `src/main.ts` via
`app/core/observability.ts` : no-op si le token est vide, sinon injection du
script beacon officiel (`beacon.min.js`) avec le token fourni par
`/api/config`. Le beacon capte les pages vues même en navigation SPA
(History API), ce qui est précisément ce qu'un compteur de pages naïf raterait
sur cette application.

### Variables d'environnement

| Variable | Défaut | Usage |
| --- | --- | --- |
| `CLOUDFLARE_WEB_ANALYTICS_TOKEN` | `987d563a0f264bbbb484df80ab2ab0f8` (profil `%prod`) | Token Cloudflare Web Analytics. Le profil production active ce token par défaut, la variable permet de le surcharger (ou de le vider pour désactiver). |

## Comment le frontend récupère ces clés

Le frontend est construit **une seule fois** par Quinoa et servi tel quel par
Quarkus (voir [`architecture.md`](architecture.md)) : il ne peut donc pas
recevoir ces clés au moment du build sans dupliquer le bundle par
environnement. Le backend les expose à la place via
[`GET /api/config`](api.md#configuration), lu par `src/main.ts` avant
`bootstrapApplication()` (même endroit et même filet de sécurité — un échec
réseau désactive juste l'observabilité pour cette session — que le
chargement du catalogue de traductions anglais). Un DSN Sentry et un token
Cloudflare sont par construction des identifiants publics, prévus pour être
embarqués dans du code navigateur (contrairement à un secret) : les exposer
par cette route ne crée pas de fuite.
