# Régler les en-têtes de sécurité du navigateur et la CSP

L'application envoie d'elle-même des en-têtes de sécurité sur toute réponse, sans rien à poser de votre côté. Cette page dit lesquels, lesquels se règlent (`CSP`, `HSTS`) et pourquoi la politique de contenu refuse tout gestionnaire `on*` ; vous y revenez pour resserrer la politique, ajouter `preload` ou comprendre un blocage constaté dans un vrai navigateur.

## En-têtes de sécurité navigateur

`SecurityHeadersFilter` ajoute à **toute** réponse — fichiers statiques du SPA
compris — les en-têtes suivants :

| En-tête | Valeur | Pourquoi ici |
| --- | --- | --- |
| `Content-Security-Policy` | `planning.securite.csp` (voir ci-dessous) | Le SPA ne charge aucun script tiers hormis la balise Cloudflare Web Analytics : tout ce qui serait injecté dans une page est refusé à l'exécution |
| `Referrer-Policy` | `no-referrer` | Le jeton de l'espace animateur, celui de l'abonnement ICS **et** celui de l'affichage mural voyagent **dans l'URL** ; sans cet en-tête ils partent dans le `Referer` de chaque navigation sortante (lien d'attribution OpenStreetMap, lien vers le dépôt). Une exception pour les tuiles, ci-dessous |
| `X-Frame-Options` | `DENY` | Rien n'est prévu pour être encadré, et détourner un clic dans une session qui peut réécrire tout le planning n'a pas de contrepartie |
| `Cross-Origin-Opener-Policy` | `same-origin` | Isole la fenêtre de tout `window.opener` ouvert depuis un autre site |
| `X-Content-Type-Options` | `nosniff` | Les exports (PDF, ICS, SQL, CSV) sont servis avec leur type ; qu'un navigateur en devine un autre n'apporte rien |
| `Permissions-Policy` | `geolocation=(), camera=(), microphone=(), payment=()` | Aucune de ces API n'est utilisée — le sélecteur de carte place un point à la souris |
| `Strict-Transport-Security` | `planning.securite.hsts`, **seulement sur une visite HTTPS** | Un an, sous-domaines compris. Absent sur la pile locale, qui est en clair |
| `X-Robots-Tag` | `noindex, nofollow` | Un outil de planification interne n'a rien à faire dans un moteur de recherche, et la seule URL qu'un robot peut atteindre sans compte — l'espace d'un animateur — **est** son jeton d'accès. Non réglable, contrairement à la CSP : il n'existe pas de page à référencer ici |

### Ne pas être référencé, dit trois fois

`robots.txt` (à la racine, recopié depuis `src/main/webui/public/`) interdit
tout à tous les robots ; une balise `<meta name="robots">` dans `index.html`
répète la consigne dans la page ; l'en-tête ci-dessus la répète sur **chaque
réponse**. Les trois ne font pas double emploi :

- le **fichier** est une consigne qu'un robot est libre d'ignorer, et il ne dit
  rien d'une URL déjà connue ;
- la **balise** n'existe qu'une fois la page interprétée comme du HTML — elle
  ne couvre ni un PDF exporté, ni un flux ICS, ni une réponse d'API ;
- l'**en-tête** est le seul des trois qui *désindexe* une adresse déjà
  atteinte. C'est le cas qui compte : un lien d'espace circule par courriel,
  et il suffit qu'il soit collé une fois sur une page publique pour être
  visité.

### `script-src` interdit tout `on*=""`, et ce que ça oblige à désactiver

Pas d'`'unsafe-inline'`, pas d'`'unsafe-hashes'` : le navigateur refuse **tout
attribut `on*=""`**. C'est la posture voulue, mais elle rend une optimisation
d'Angular silencieusement destructrice.

L'*inlining du CSS critique* sert la vraie feuille de style en
`media="print" onload="this.media='all'"`. Quand la CSP bloque ce gestionnaire,
**la feuille reste en `print`** : rien ne lève d'erreur, et tout ce que
l'extraction n'a pas retenu disparaît — une police d'icônes, par exemple.

`inlineCritical` est donc **désactivé**, et `scripts/check-csp-index.js` échoue
le build si l'`index.html` produit contient le moindre gestionnaire inline. Ni
le build (le HTML est valide) ni les tests (ils ne chargent jamais
`index.html`) ne peuvent voir le problème : un manquement à la CSP n'apparaît que
dans un vrai navigateur. Elle est donc vérifiée sur l'artefact.

Trois exceptions volontaires :

- **la CSP s'arrête au seuil de `/q/*`** : Swagger UI y sert des scripts inline
  que le projet ne contrôle pas. Les autres en-têtes s'y appliquent ;
- **HSTS n'est envoyé que sur une visite HTTPS** — un navigateur l'ignorerait
  en clair, et le poser en local épinglerait `localhost` en https ;
- **les tuiles de la carte échappent au `no-referrer`** :
  `tile.openstreetmap.org` [bloque le trafic sans `Referer`](https://osm.wiki/blocked),
  la carte resterait grise. `MapPicker` pose donc
  `referrerPolicy: 'strict-origin-when-cross-origin'` sur sa seule couche de
  tuiles. Le jeton ne fuit pas : cette politique n'envoie que l'origine, jamais
  le chemin ni la requête.

### Réglage

| Variable | Défaut | Usage |
| --- | --- | --- |
| `CSP` | politique ci-dessus | Politique de sécurité du contenu complète. **Vide = en-tête désactivé** |
| `HSTS` | `max-age=31536000; includeSubDomains` | **Vide = en-tête désactivé**. N'ajoutez `preload` qu'après avoir décidé que le domaine ne resservira jamais en clair : c'est irréversible à l'échelle du navigateur |

La CSP par défaut laisse volontairement `connect-src https:` et `img-src https:`
ouverts : l'endpoint Bugsink et le serveur de tuiles sont propres à chaque
déploiement. Un déploiement qui connaît les siens gagne à les nommer dans `CSP`
plutôt qu'à garder `https:` — en y ajoutant `https://api.github.com` s'il tient
à l'indication « nouvelle version disponible » de la barre d'outils, la seule
autre destination qu'un navigateur contacte de lui-même, et seulement une fois
la session d'administration confirmée
(voir la vérification de nouvelle version dans [`versioning.md`](versioning.md)).

`style-src` garde `'unsafe-inline'` dans tous les cas : Angular Material écrit
ses styles dans la page.
