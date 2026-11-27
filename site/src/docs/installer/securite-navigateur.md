# Régler les en-têtes de sécurité du navigateur et la CSP

L'application envoie d'elle-même des en-têtes de sécurité sur toute réponse, sans rien à poser de votre côté. Vous savez lesquels partent, lesquels se règlent (`CSP`, `HSTS`) et pourquoi la politique de contenu refuse tout gestionnaire `on*` : de quoi resserrer la politique, ajouter `preload` ou comprendre un blocage constaté dans un vrai navigateur.

## En-têtes de sécurité navigateur

`SecurityHeadersFilter` ajoute à **toute** réponse, fichiers statiques du SPA
compris, les en-têtes suivants :

| En-tête | Valeur | Pourquoi ici |
| --- | --- | --- |
| `Content-Security-Policy` | `planning.securite.csp` (voir ci-dessous) | Le SPA ne charge aucun script tiers hormis la balise Cloudflare Web Analytics, et seulement quand son jeton est posé : tout ce qui serait injecté dans une page est refusé à l'exécution |
| `Referrer-Policy` | `no-referrer` | Le jeton de l'espace animateur, celui de l'abonnement ICS **et** celui de l'affichage mural voyagent **dans l'URL** ; sans cet en-tête ils partent dans le `Referer` de chaque navigation sortante (lien d'attribution OpenStreetMap, lien vers le dépôt). Une exception pour les tuiles, ci-dessous |
| `X-Frame-Options` | `DENY` | Rien n'est prévu pour être encadré, et détourner un clic dans une session qui peut réécrire tout le planning n'a pas de contrepartie |
| `Cross-Origin-Opener-Policy` | `same-origin` | Isole la fenêtre de tout `window.opener` ouvert depuis un autre site |
| `X-Content-Type-Options` | `nosniff` | Les exports (PDF, ICS, SQL, CSV) sont servis avec leur type ; qu'un navigateur en devine un autre n'apporte rien |
| `Permissions-Policy` | `geolocation=(), camera=(), microphone=(), payment=()` | Aucune de ces API n'est utilisée : le sélecteur de carte place un point à la souris |
| `Strict-Transport-Security` | `planning.securite.hsts`, **seulement sur une visite HTTPS** | Un an, sous-domaines compris. Absent sur la pile locale, qui est en clair |
| `X-Robots-Tag` | `noindex, nofollow` | Un outil de planification interne n'a rien à faire dans un moteur de recherche, et la seule URL qu'un robot peut atteindre sans compte (l'espace d'un animateur) **est** son jeton d'accès. Non réglable, contrairement à la CSP : il n'existe pas de page à référencer ici |

### Ne pas être référencé, dit trois fois

`robots.txt` (à la racine, recopié depuis `src/main/webui/public/`) interdit
tout à tous les robots ; une balise `<meta name="robots">` dans `index.html`
répète la consigne dans la page ; l'en-tête ci-dessus la répète sur **chaque
réponse**. Les trois ne font pas double emploi :

- le **fichier** est une consigne qu'un robot est libre d'ignorer, et il ne dit
  rien d'une URL déjà connue ;
- la **balise** n'existe qu'une fois la page interprétée comme du HTML ; elle
  ne couvre ni un PDF exporté, ni un flux ICS, ni une réponse d'API ;
- l'**en-tête** est le seul des trois qui *désindexe* une adresse déjà
  atteinte. C'est le cas qui compte : un lien d'espace circule par courriel,
  et il suffit qu'il soit collé une fois sur une page publique pour être
  visité.

### `script-src` interdit tout `on*=""`, et ce que ça oblige à désactiver

Pas d'`'unsafe-inline'`, pas d'`'unsafe-hashes'` : le navigateur refuse **tout
attribut `on*=""`**. C'est la posture voulue, mais une optimisation
d'Angular devient alors silencieusement destructrice.

L'*inlining du CSS critique* sert la vraie feuille de style en
`media="print" onload="this.media='all'"`. Quand la CSP bloque ce gestionnaire,
**la feuille reste en `print`** : rien ne lève d'erreur, et tout ce que
l'extraction n'a pas retenu disparaît (une police d'icônes, par exemple).

`inlineCritical` est donc **désactivé**, et `scripts/check-csp-index.js` échoue
le build si l'`index.html` produit contient le moindre gestionnaire inline. Ni
le build (le HTML est valide) ni les tests (ils ne chargent jamais
`index.html`) ne peuvent voir le problème : un manquement à la CSP n'apparaît que
dans un vrai navigateur. Elle est donc vérifiée sur l'artefact.

Trois exceptions volontaires :

- **la CSP s'arrête au seuil de `/q/*`** : Swagger UI y sert des scripts inline
  que le projet ne contrôle pas. Les autres en-têtes s'y appliquent ;
- **HSTS n'est envoyé que sur une visite HTTPS** : un navigateur l'ignorerait
  en clair, et le poser en local épinglerait `localhost` en https ;
- **les tuiles de la carte échappent au `no-referrer`** :
  `tile.openstreetmap.org` [bloque le trafic sans `Referer`](https://osm.wiki/blocked),
  la carte resterait grise. `MapPicker` pose donc
  `referrerPolicy: 'strict-origin-when-cross-origin'` sur sa seule couche de
  tuiles. Le jeton ne fuit pas : cette politique n'envoie que l'origine, jamais
  le chemin ni la requête.

### Une seule origine

Le filtre CORS est allumé, et il ne nomme **aucune** origine tierce. Une requête qui porte un en-tête `Origin` autre que l'adresse de l'application est refusée en 403 avant d'atteindre quoi que ce soit, sur `/mcp` comme sur `/api` et sur la connexion. Les pages de l'application elles-mêmes passent comme avant, et un client qui n'envoie pas d'`Origin` n'est pas concerné : un client MCP hors navigateur, un agenda abonné, `curl`.

C'est la validation de l'en-tête `Origin` que la spécification MCP demande à un serveur HTTP. Elle ne protège pas contre le *DNS rebinding*, où l'origine du piège et l'hôte demandé sont les mêmes ; ce n'est pas nécessaire ici, puisque `/mcp` exige une clé ou un jeton dans un en-tête, qu'une page piégée n'a pas.

« La même origine » se lit sur le schéma et l'hôte de la requête, donc derrière le reverse proxy sur `X-Forwarded-Proto` et `Host`, les deux en-têtes dont la connexion admin dépend déjà. Un proxy qui réécrit `Host` demande `QUARKUS_HTTP_PROXY_ENABLE_FORWARDED_HOST=true`. Une instance servie en https sur un port autre que 443 demande en plus `X-Forwarded-Port`.

Aucune variable de l'application n'ouvre une origine tierce, exprès : ne renseignez pas non plus `QUARKUS_HTTP_CORS_ORIGINS`, que Quarkus lit tout seul, car l'ouvrir pour un client MCP dans un navigateur l'ouvrirait aussi à toute l'API d'administration. `QUARKUS_HTTP_CORS_ENABLED=false` éteint le filtre et rend le comportement de la 4.0.0, le temps de corriger un proxy qui ne transmet pas ces en-têtes.

### Réglage

| Variable | Défaut | Usage |
| --- | --- | --- |
| `CSP` | politique ci-dessus | Politique de sécurité du contenu complète. **Vide = en-tête désactivé** |
| `HSTS` | `max-age=31536000; includeSubDomains` | **Vide = en-tête désactivé**. N'ajoutez `preload` qu'après avoir décidé que le domaine ne resservira jamais en clair : c'est irréversible à l'échelle du navigateur |

La CSP par défaut ne nomme que ce que l'application contacte d'elle-même :

| Directive | Origines tierces | Quand |
| --- | --- | --- |
| `script-src` | `https://static.cloudflareinsights.com` | seulement si `CLOUDFLARE_WEB_ANALYTICS_TOKEN` est posé |
| `connect-src` | `https://cloudflareinsights.com` | seulement si `CLOUDFLARE_WEB_ANALYTICS_TOKEN` est posé |
| `connect-src` | l'origine de `SENTRY_DSN` (`https://bugsink.exemple.org`) | seulement si `SENTRY_DSN` est posé |
| `connect-src` | `https://api.github.com` | toujours : l'indication « nouvelle version disponible » de la barre d'outils, demandée une fois la session d'administration confirmée (voir la vérification de nouvelle version dans [`versioning.md`](versioning.md)) |
| `img-src` | `https:` | toujours : les tuiles OpenStreetMap de la carte et le logo de `BRANDING_LOGO_URL`, propres à chaque déploiement |

L'application ajoute les origines Cloudflare et Sentry au démarrage, d'après ces
deux variables. Elle les ajoute aussi à une `CSP` que vous écrivez vous-même,
dans les directives `script-src` et `connect-src` qu'elle porte : une politique
qui n'a pas ces directives reste telle quelle. Pour éteindre la vérification de
nouvelle version, retirez `https://api.github.com` de `connect-src`.

`style-src` garde `'unsafe-inline'` dans tous les cas : Angular Material écrit
ses styles dans la page.

## Mise en cache des pages

Le navigateur doit redemander la page d'entrée à chaque visite : c'est elle qui
désigne les fichiers de la version en place. `index.html`, à la racine comme sur
chaque adresse de l'interface, part en `Cache-Control: no-cache`, comme les
fichiers servis sous un nom fixe (catalogues de traduction, polices, favicon,
`robots.txt`). `no-cache` ne veut pas dire « ne pas garder » : le navigateur
garde sa copie et la revalide, ce qui coûte une requête répondue `304` tant que
rien n'a changé. Les fichiers dont le nom porte une empreinte (`main-*.js`,
`chunk-*.js`) gardent leur cache long : une nouvelle version leur donne un
nouveau nom.

Sans cela, une mise à jour, correctif de sécurité compris, peut laisser
l'ancienne interface en place jusqu'à un jour chez un visiteur déjà venu. Si
votre reverse proxy ajoute ses propres en-têtes de cache, laissez-lui ceux de
l'application sur ces adresses.
