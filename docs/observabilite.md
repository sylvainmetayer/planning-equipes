# Observabilité

Deux briques optionnelles, toutes deux **désactivées par défaut** en dev/test
(et activées en production via la configuration Quarkus) :

- **Suivi d'erreurs** — [Bugsink](https://www.bugsink.com/), auto-hébergé,
  compatible avec le protocole/SDK Sentry.
- **Analytics d'usage** — [PostHog](https://posthog.com/) (événements produit)
  + [Cloudflare Web Analytics](https://www.cloudflare.com/web-analytics/)
  (mesure légère des pages vues/navigation).

## Pourquoi ces choix

| Besoin | Outil | Pourquoi |
| --- | --- | --- |
| Erreurs backend + frontend | Bugsink | Auto-hébergé (Docker, SQLite par défaut, pas de dépendance Redis/Celery), donc pas de donnée envoyée à un tiers ; **compatible avec les SDK Sentry** — n'importe quel SDK Sentry officiel (Java, JavaScript, …) fonctionne en pointant simplement son DSN vers l'instance Bugsink. Alternative plus légère à un Sentry auto-hébergé. |
| Analytics produit (usage des écrans, événements) | PostHog | Palier gratuit le plus généreux du marché pour ce type d'usage (1 million d'événements/mois, hébergement EU disponible pour rester RGPD-friendly) ; alternative *hébergée* à Matomo (qui, lui, ne propose pas de palier gratuit hébergé — seul l'auto-hébergement est gratuit). SDK JavaScript officiel (`posthog-js`), simple à intégrer dans une SPA. |
| Audience / pages vues légères | Cloudflare Web Analytics | Script minimal, sans cookie, facile à activer uniquement en production ; capte les pages vues même en navigation SPA (History API) via le beacon officiel Cloudflare. |

Autres pistes envisagées et écartées : **Umami** (palier cloud gratuit plus
restreint, ~100k événements/mois) et **Plausible** (pas de palier gratuit
hébergé, uniquement l'auto-hébergement) — voir leurs sites respectifs si ce
compromis convient mieux à un déploiement donné ; le code frontend
(`app/core/observability.ts`) n'est pas verrouillé sur PostHog au point de
rendre un changement coûteux.

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

## Analytics d'usage (PostHog + Cloudflare Web Analytics)

### Mise en place de PostHog

Créer un compte gratuit sur [posthog.com](https://posthog.com/) (ou
auto-héberger PostHog Open Source si un service géré n'est pas souhaité),
créer un projet : la clé API du projet (`phc_…`) va dans
`POSTHOG_API_KEY`, l'hôte d'ingestion (`https://eu.i.posthog.com` par défaut
dans ce projet, `https://us.i.posthog.com` si le projet est hébergé aux
États-Unis) dans `POSTHOG_HOST`.

### Intégration dans l'application

Uniquement côté frontend (`posthog-js` + script Cloudflare), initialisé dans
`src/main.ts` via `app/core/observability.ts` :

- PostHog est un no-op si `POSTHOG_API_KEY` est vide, avec
  `capture_pageview: 'history_change'` pour que les changements de route
  Angular (History API, sans rechargement complet) comptent bien comme des
  pages vues ;
- Cloudflare est un no-op si son token est vide, puis injecte le script beacon
  officiel (`beacon.min.js`) avec le token fourni par `/api/config`.

### Variables d'environnement

| Variable | Défaut | Usage |
| --- | --- | --- |
| `POSTHOG_API_KEY` | *(vide)* | Clé API du projet PostHog. Vide = événements PostHog désactivés. |
| `POSTHOG_HOST` | `https://eu.i.posthog.com` | Hôte d'ingestion PostHog du projet. |
| `CLOUDFLARE_WEB_ANALYTICS_TOKEN` | `987d563a0f264bbbb484df80ab2ab0f8` (profil `%prod`) | Token Cloudflare Web Analytics. Le profil production active ce token par défaut, la variable permet de le surcharger (ou de le vider pour désactiver). |

## Comment le frontend récupère ces clés

Le frontend est construit **une seule fois** par Quinoa et servi tel quel par
Quarkus (voir [`architecture.md`](architecture.md)) : il ne peut donc pas
recevoir ces clés au moment du build sans dupliquer le bundle par
environnement. Le backend les expose à la place via
[`GET /api/config`](api.md#configuration), lu par `src/main.ts` avant
`bootstrapApplication()` (même endroit et même filet de sécurité — un échec
réseau désactive juste l'observabilité pour cette session — que le
chargement du catalogue de traductions anglais). Un DSN Sentry, une clé de
projet PostHog et un token Cloudflare sont par construction des identifiants
publics, prévus pour être embarqués dans du code navigateur (contrairement à
un secret) : les
exposer par cette route ne crée pas de fuite.
