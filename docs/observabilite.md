# Observabilité

Deux briques, **désactivées en dev/test** : suivi d'erreurs
[Bugsink](https://www.bugsink.com/) (protocole et SDK Sentry) et
[Cloudflare Web Analytics](https://www.cloudflare.com/web-analytics/).

Le câblage se lit dans `SentryInitializer`, `GlobalExceptionMapper` et
`app/core/observability.ts` — ce document porte ce qui ne s'y voit pas.

Une troisième brique est **toujours active** : les sondes de santé
`/q/health/live` et `/q/health/ready` (SmallRye Health), publiques et muettes
sur tout détail de connexion. La disponibilité repose sur un seul contrôle,
l'historique Flyway (`FlywayMigrationsReadinessCheck`), dont le premier rôle
est de dire la base injoignable : l'historique ne se lit pas sans elle. Une
migration en attente ou en échec rend aussi l'instance non prête, mais c'est
une défense en profondeur — une migration interrompue empêche déjà le
démarrage — contre un historique modifié sous une instance en marche. Le
verdict est gardé 5 s, la sonde étant publique. Le contrôle de
source de données fourni par Quarkus est **coupé** : en échec, il recopie dans
la réponse publique le message du pilote, et avec lui l'hôte de la base. Leur usage d'exploitation est
dans [`exploitation.md`](exploitation.md) § 7.

Une quatrième l'est aussi : les **métriques de fonctionnement** au format
Prometheus, servies sur un port à part que la pile de production ne publie
pas — voir [Métriques](#métriques).

## Le point qui n'est pas négociable : le jeton de l'espace animateur

L'URL de l'espace porte le jeton d'accès, **un identifiant unique de personne,
souvent mineure**. Celle de l'affichage mural porte aussi le sien, qui ouvre à
qui le détient le planning nominatif du jour. Trois garde-fous, pour les deux :

- le beacon Cloudflare **n'est pas chargé du tout** sur `/animateur/*` ni sur
  `/mural/*` ;
- les rapports d'erreur sont expurgés **des deux côtés**, pour les pages
  (`/animateur/…`, `/mural/…`) comme pour les appels d'API
  (`/api/espace-animateur/…`, `/api/mural/…`). Côté navigateur,
  `maskTokensEverywhere` parcourt le rapport **entier** (`beforeSend`,
  `beforeBreadcrumb`) plutôt qu'une liste de champs : ne masquer que
  `request.url` et `data.url` laissait passer les deux cas les plus probables —
  un changement de page interne (`data.from` / `data.to`) et un échec HTTP dont
  le message contient l'URL. Côté serveur, `maskTokensInReport` fait de même ;
- `Referrer-Policy: same-origin` ferme le canal des liens sortants.

C'est ce qui rend vraie la phrase que la politique de confidentialité adresse
aux animateurs. **Toute nouvelle voie d'envoi doit préserver cette propriété.**

## Ce qu'une résolution qui meurt ne racontait à personne

Un solve est lancé en `202` : la requête est acceptée tout de suite, la
résolution tourne à part, et son échec arrive plus tard **dans le job**. Il ne
traverse donc jamais `GlobalExceptionMapper`, et sans rien de plus il ne
serait jamais remonté — la seule catégorie de panne que l'organisateur voit
sur son écran et que personne d'autre ne voit jamais.

`SolverJobService.reportFailure` la signale là où l'exception est encore en
main, avec sa pile, et couvre du même coup l'écran, l'API, MCP et la file
rejouée après un redémarrage. L'événement porte le type du job, son
identifiant, l'édition et le périmètre. Au pire (traqueur absent ou en panne),
la ligne de journal reste : une panne signalée ne doit pas devenir une panne
perdue.

Un job **annulé** n'est pas une panne et ne remonte pas.

## Métriques

Sentry dit ce qui a cassé ; les métriques disent comment ça tourne — combien
de temps prend une résolution, combien attendent derrière, si les mails
partent, si la sauvegarde de nuit a tourné. Micrometer, registre Prometheus
(`quarkus-micrometer-registry-prometheus`), toujours actif.

### Où, et pourquoi pas ailleurs

L'endpoint est **`/q/metrics` sur l'interface de management**, port **9000** :
un second serveur HTTP, distinct du port applicatif. Il n'a **aucune
authentification** — un scraper Prometheus n'ouvre pas de session — et sa
seule protection est de n'être joignable que du réseau interne :
`docker-compose.prod.yml` ne publie que `127.0.0.1:8080`, le reverse proxy ne
connaît que ce port-là, et l'image ne déclare pas 9000 en `EXPOSE` (un
`docker run -P` le publierait). Sur le port applicatif, `/q/metrics` répond
`404` ; `MetricsEndpointTest` tient les deux moitiés.

Un Prometheus ajouté à la pile de production le lit sur
`http://app:9000/q/metrics`. Hors conteneur (`quarkus:dev`), l'interface
écoute sur `localhost:9000`.

Les sondes `/q/health/*` et la documentation d'API (`/q/openapi`,
`/q/swagger-ui`) **restent sur le port applicatif** : l'interface de
management les emmènerait sinon avec elle, et le `healthcheck` du compose, les
scripts de déploiement et les sondes externes les cherchent là. Le choix
inverse — tout sur 9000 — se ferait d'un bloc, sondes comprises.

### Ce qui est mesuré

| Métrique | Type | Étiquettes | Ce qu'elle dit |
| --- | --- | --- | --- |
| `planning_solver_duration_seconds` | timer | `type` (`full`, `incremental`), `outcome` (`completed`, `failed`, `refused`, `cancelled`, `interrupted`) | durée d'une résolution, du démarrage à son état final ; `refused` = refus métier (rien à planifier), pas une panne |
| `planning_solver_failures_total` | compteur | `type` | résolutions mortes sur une erreur inattendue — exactement celles que `reportFailure` envoie à Sentry |
| `planning_solver_queue_size` | jauge | — | jobs en file ; reflète aussi la file rejouée au démarrage |
| `planning_solver_active` | jauge (0/1) | — | un job tient le solveur |
| `planning_mail_sent_total`, `planning_mail_failures_total` | compteurs | `template` (`code-acces`, `planning-publie`, `rappel-veille`…) | mails remis au serveur SMTP, ou refusés — y compris les notifications, dont l'échec est avalé sans être perdu pour autant |
| `planning_backup_duration_seconds` | timer | `outcome` (`success`, `failure`) | durée de chaque `pg_dump` |
| `planning_backup_last_success_timestamp_seconds` | jauge | — | date de la dernière sauvegarde réussie (secondes epoch) |
| `planning_backup_size_bytes` | jauge | — | taille de ce dernier dump |
| `http_server_requests_seconds` | timer | `uri` (gabarit de route), `method`, `status`, `outcome` | latence de l'API, fournie par l'extension |

S'y ajoutent ce que l'extension et Timefold publient d'office : JVM, Vert.x,
le pool de connexions (`agroal_*`, allumé par
`quarkus.datasource.metrics.enabled`) et les compteurs du solveur
(`timefold_solver_*`, sans identifiant de résolution).

Trois comportements à connaître pour écrire une alerte :

- **au redémarrage, les compteurs repartent de zéro** — c'est la règle
  Prometheus, `increase()` et `rate()` en tiennent compte. La date de dernière
  sauvegarde réussie, elle, est relue en base au démarrage, et la taille sur le
  dump encore présent sur le disque ;
- **sans `BACKUP_DIR`, les deux jauges de sauvegarde n'existent pas**, et une
  instance qui n'a encore jamais réussi de sauvegarde n'en a pas non plus :
  une date absente plutôt qu'un zéro qui dirait « dernière sauvegarde en
  1970 » ;
- **`MAIL_MOCK=true` compte quand même** : en recette, « le mail serait
  parti » est ce qu'on veut voir.

### Cardinalité bornée : le jeton, encore

Aucune étiquette ne porte une édition, un animateur, un jeton, une adresse ou
un identifiant : chaque étiquette est un ensemble fermé. Une édition n'est pas
une dimension d'exploitation — on surveille le solveur de l'instance.

Pour `uri`, c'est la même exigence que pour les rapports d'erreur : **le
jeton de l'espace et celui de l'abonnement ICS voyagent dans le chemin**, et
une étiquette qui recopierait le chemin les enverrait à la supervision. Trois
réglages, dans `application.properties` :

- une route REST est étiquetée par son **gabarit**, jamais par le chemin
  appelé : `/espace-animateur/{jeton}`, `/abonnements/{token}/planning.ics`
  (l'extension l'écrit sans le préfixe `/api`) ;
- une réponse 4xx/5xx sans route reconnue — connexion refusée, limiteur de
  débit, robot qui essaie `/wp-admin` — est étiquetée `UNKNOWN`
  (`suppress4xx-errors`), un 404 `NOT_FOUND` ;
- tout ce que sert le frontend Angular, y compris le lien d'espace
  `/animateur/<jeton>` et les fichiers à empreinte, est replié en une seule
  valeur, `/{frontend}` (`match-patterns`).

Le nombre de valeurs d'`uri` est donc borné par le nombre de routes (environ
deux cents). Le plafond de l'extension, 100 par défaut, écarterait sans le dire
toutes les routes au-delà de la centième : `max-uri-tags` le place à 500.

`MetricsEndpointTest` appelle les deux routes à jeton, le lien d'espace et un
chemin inconnu avec des jetons factices, puis vérifie qu'aucun n'apparaît dans
la sortie, que chaque `uri` n'est fait que de segments littéraux et de
`{gabarits}`, et qu'aucune étiquette interdite n'existe. **Toute nouvelle
métrique doit préserver cette propriété.**

## Deux pièges de configuration

**La mesure d'audience est éteinte tant qu'on ne lui donne pas de token.** Le
profil `%prod` fournissait autrefois un repli — celui du compte de l'éditeur —
et toute instance déployée sans y penser envoyait donc son audience dans ce
compte, tout en déclarant un transfert hors UE dans ses propres mentions
légales. Le défaut est vide depuis ; poser `CLOUDFLARE_WEB_ANALYTICS_TOKEN` est
maintenant le seul moyen d'allumer la mesure.

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
| `CLOUDFLARE_WEB_ANALYTICS_TOKEN` | vide | Token du compte Cloudflare qui reçoit l'audience. Vide = désactivé |

Le frontend est construit **une seule fois** et servi tel quel : il ne peut pas
recevoir ces clés au build sans dupliquer le bundle par environnement. Le
backend les expose donc via `/api/config`, lu avant `bootstrapApplication()`. Un
DSN et un token de beacon sont par construction des identifiants publics, prévus
pour vivre dans du code navigateur — les exposer ainsi ne crée pas de fuite.

La page Débogage (onglet *Vérifications*) lève une exception de test de chaque côté, pour vérifier un
DSN fraîchement configuré sans attendre un vrai bug.

## RGPD

Bugsink est consommé en **offre hébergée**, pas auto-hébergé : c'est donc un
**sous-traitant**, ce que la politique de confidentialité doit dire.
Hébergement UE annoncé par l'éditeur, donc pas de transfert hors UE à déclarer —
contrairement à Cloudflare.

Elle ne le dit **que si l'outil tourne** : `/api/mentions-legales` renvoie
`mesureAudience` et `suiviErreurs`, calculés sur la **valeur effective** de ces
deux clés — celle que lisent le beacon et le SDK — et la page n'affiche que les
paragraphes correspondants. Poser une variable ne se contente donc pas d'allumer
l'outil, cela ajoute aussi sa déclaration ; la vider retire les deux. Et c'est le
point : un déploiement qui n'envoie rien à Cloudflare ne doit pas annoncer un
transfert hors UE. Ajouter une troisième brique suppose donc un troisième
drapeau, pas un paragraphe de plus en dur.

## Analytics produit : retirée

PostHog a été déposé. Personne ne consultait les tableaux de bord, et la brique
coûtait une dépendance JavaScript, deux variables et un traitement de données
personnelles à justifier — trois prix payés pour rien.

Si le besoin revient, les candidats regardés étaient PostHog (palier gratuit le
plus généreux, hébergement EU), Umami et Plausible.
`app/core/observability.ts` est le seul point d'entrée à reprendre : rien
d'autre dans le code ne connaissait PostHog.
