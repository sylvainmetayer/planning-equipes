# Observabilité

Deux briques, **désactivées en dev/test** : suivi d'erreurs
[Bugsink](https://www.bugsink.com/) (protocole et SDK Sentry) et
[Cloudflare Web Analytics](https://www.cloudflare.com/web-analytics/).

Le câblage se lit dans `SentryInitializer`, `GlobalExceptionMapper` et
`app/core/observability.ts` — ce document porte ce qui ne s'y voit pas.

## Le point qui n'est pas négociable : le jeton de l'espace animateur

L'URL de l'espace porte le jeton d'accès, **un identifiant unique de personne,
souvent mineure**. Trois garde-fous :

- le beacon Cloudflare **n'est pas chargé du tout** sur `/animateur/*` ;
- les rapports d'erreur sont expurgés **des deux côtés**. Côté navigateur,
  `masquerJetonPartout` parcourt le rapport **entier** (`beforeSend`,
  `beforeBreadcrumb`) plutôt qu'une liste de champs : ne masquer que
  `request.url` et `data.url` laissait passer les deux cas les plus probables —
  un changement de page interne (`data.from` / `data.to`) et un échec HTTP dont
  le message contient l'URL. Côté serveur, `maskTokensInReport` fait de même ;
- `Referrer-Policy: same-origin` ferme le canal des liens sortants.

C'est ce qui rend vraie la phrase que la politique de confidentialité adresse
aux animateurs. **Toute nouvelle voie d'envoi doit préserver cette propriété.**

## Deux pièges de configuration

**La mesure d'audience est active par défaut en production** : le profil
`%prod` fournit un token de repli. Ne rien configurer ne la désactive pas — il
faut positionner `CLOUDFLARE_WEB_ANALYTICS_TOKEN` **à vide**.

**Le SDK Sentry n'est chargé que si un DSN est configuré**, par un `import()`
dynamique à l'intérieur de la condition. Il pèse 462 ko (130 ko transférés) :
l'inclure au bundle initial le faisait payer à tous les visiteurs, y compris
sur `/animateur/:jeton`, page publique souvent consultée depuis un téléphone,
et y compris sur un déploiement sans DSN.

## Variables

| Variable | Défaut | Usage |
| --- | --- | --- |
| `SENTRY_DSN` | vide | DSN Bugsink, ou tout endpoint compatible Sentry. Vide = désactivé des deux côtés |
| `SENTRY_ENVIRONMENT` | `local` | Étiquette jointe à chaque erreur |
| `CLOUDFLARE_WEB_ANALYTICS_TOKEN` | un token en profil `%prod` | Vider pour désactiver |

Le frontend est construit **une seule fois** et servi tel quel : il ne peut pas
recevoir ces clés au build sans dupliquer le bundle par environnement. Le
backend les expose donc via `/api/config`, lu avant `bootstrapApplication()`. Un
DSN et un token de beacon sont par construction des identifiants publics, prévus
pour vivre dans du code navigateur — les exposer ainsi ne crée pas de fuite.

L'onglet Débogage lève une exception de test de chaque côté, pour vérifier un
DSN fraîchement configuré sans attendre un vrai bug.

## RGPD

Bugsink est consommé en **offre hébergée**, pas auto-hébergé : c'est donc un
**sous-traitant**, ce que la politique de confidentialité doit dire.
Hébergement UE annoncé par l'éditeur, donc pas de transfert hors UE à déclarer —
contrairement à Cloudflare.

## Analytics produit : retirée

PostHog a été déposé. Personne ne consultait les tableaux de bord, et la brique
coûtait une dépendance JavaScript, deux variables et un traitement de données
personnelles à justifier — trois prix payés pour rien.

Si le besoin revient, les candidats regardés étaient PostHog (palier gratuit le
plus généreux, hébergement EU), Umami et Plausible.
`app/core/observability.ts` est le seul point d'entrée à reprendre : rien
d'autre dans le code ne connaissait PostHog.
