# Ouvrir un agenda ou un écran sans ouvrir l'administration

Deux liens s'ouvrent par une URL seule, sans session : le flux d'agenda d'un animateur et l'écran mural de la salle de contrôle. Cette page dit ce que permet exactement chacun, comment le révoquer, et quelle exception d'authentification déclarer sur un proxy d'accès sans ouvrir plus que nécessaire. Tous deux portent leur jeton dans le chemin, et leur périmètre est volontairement d'une seule route chacun : il ne doit pas s'élargir.

## L'abonnement à l'agenda (ICS) : un second jeton, au périmètre d'un seul document

`GET /api/abonnements/{token}/planning.ics` est la **seule** route de
l'application qu'une URL seule ouvre. Elle existe parce qu'un client d'agenda
abonné ne porte aucun cookie et ne sait pas répondre à un défi
d'authentification : sans elle, l'abonnement serait un `401` à la première
resynchronisation.

![Lecture d'un abonnement ICS et révocation](repo:docs/diagrammes/abonnement-ics.svg)

<sub>Source : [`diagrammes/abonnement-ics.puml`](repo:docs/diagrammes/abonnement-ics.puml).</sub>

### Pourquoi un jeton de plus plutôt que le jeton d'espace

Le jeton d'espace ne suffit à rien seul aujourd'hui : il permet de *demander*
un code par e-mail, et c'est tout. Le réutiliser ici en aurait fait, sur cette
route, une preuve d'accès complète et durable au planning nominatif sans second
facteur — et un lien imprimé sur un PDF circule.

Le jeton d'abonnement est donc une clé séparée (`animateur.abonnement_token`),
avec la même génération que l'autre (`gen_random_uuid()`, 122 bits d'aléa,
unicité globale) et un cycle de vie indépendant. Voir
[décision 0019](repo:docs/decisions/0019-jeton-et-chemin-dedies-pour-l-abonnement-ics.md).

### Ce qu'il permet, exactement

Qui détient un jeton d'abonnement peut lire, indéfiniment et sans rien d'autre,
**un seul document** : le planning publié de son propriétaire — ses vacations,
ses stands, et les coéquipiers que son PDF individuel imprime déjà. C'est une
donnée nominative, et il faut le lire ainsi : ce jeton dit *qui travaille où et
quand*, pour une personne.

Il ne permet **rien** d'autre. Il n'ouvre pas l'espace animateur, ne lit ni les
demandes d'échange, ni les disponibilités, ni le trombinoscope, ne télécharge
pas le PDF, et n'écrit nulle part. Le garde qui le résout est lié à une seule
route (`@AbonnementTokenRequired`), et le jeton d'espace ne fonctionne pas sur
cette route — ni l'inverse. `AbonnementIcsTest` vérifie les deux sens.

### Révocation

**L'animateur révoque lui-même**, depuis son espace (« cette adresse a fuité,
la remplacer ») : l'adresse n'est jamais affichée ailleurs que là, donc son
porteur est la seule personne en position de savoir qu'elle a fuité, et
atteindre ce bouton coûte déjà le jeton d'espace **et** le code e-mail — une
preuve plus forte que celle que l'abonnement demandera jamais. La rotation tue
l'ancienne adresse immédiatement et n'affecte pas le jeton d'espace : le lien
déjà imprimé sur un PDF survit.

Une fiche supprimée ou une édition supprimée emportent leur jeton avec elles :
l'adresse cesse simplement d'exister (`404`). Il n'existe pas d'état « abonné /
non abonné » : le jeton existe toujours, et ne sert que si quelqu'un s'en est
servi.

Ce qui **n'existe pas**, et qui est un manque assumé : un geste côté admin pour
révoquer l'abonnement de quelqu'un d'autre. Un exploitant qui doit couper
l'accès d'une personne partie supprime sa fiche, ce qui coupe tout. Ajouter le
bouton à l'écran Animateurs est un ajout d'écran, pas de modèle.

### Le jeton dans le chemin, et les journaux

Même défaut que le jeton d'espace, et la même conséquence : il atterrit tel
quel dans les journaux d'accès du reverse proxy, **et il y atterrit beaucoup
plus souvent** — un client d'agenda resynchronise seul, plusieurs fois par
jour, depuis chaque appareil. Ces journaux se purgent ou s'écrivent sans ces
chemins (voir [ce qui reste à la charge du reverse proxy](securite.md#ce-qui-reste-à-la-charge-du-reverse-proxy)). `Referrer-Policy: no-referrer` couvre le
reste : le jeton ne part dans le `Referer` d'aucune navigation sortante.

### Pas de limiteur de débit ici, et pourquoi

`AdminLoginLimiter`, `CodeRequestLimiter` et `McpRateLimiter` bornent des choses
précises : des tentatives d'authentification, un envoi de mail, et — pour le
dernier — l'usage d'une clé partagée qui ouvre tout, plus les essais pour la
deviner. Cette route ne fait rien de tout cela — elle
lit, sans effet de bord, sur un jeton qui n'ouvre qu'un document.

Un plafond par animateur y serait **contre-productif** : un abonnement se
resynchronise tout seul, depuis un téléphone, un ordinateur et une tablette à
la fois, à un rythme que l'application ne choisit pas. Le plafond ne
distinguerait pas l'abus de l'usage, et un flux coupé au mauvais moment se
manifeste par un agenda silencieusement figé — exactement le symptôme que cette
fonctionnalité supprime.

Reste l'énumération : elle n'est pas praticable sur 122 bits d'aléa, un jeton
inconnu ne répond qu'un `404` portant une phrase fixe (« Abonnement inconnu ou
révoqué »), la même pour un jeton révoqué que pour un jeton inventé — elle ne
renseigne donc sur rien —, et **la limitation par IP du reverse proxy reste la
première ligne**, comme pour les exports et le reste de l'API.

Ce qui **reste ouvert** : rien ne borne le coût d'un client mal réglé qui
appellerait toutes les minutes. Chaque appel reconstruit le plan publié, comme
le fait déjà le téléchargement de l'espace, donc ce n'est pas une classe de
charge nouvelle — mais c'est la première qui soit déclenchée par une machine
plutôt que par un humain.

### La règle côté proxy d'accès

Le préfixe `/api/abonnements/` **n'existe que pour cette route**, et c'est sa
raison d'être : un proxy d'accès qui authentifie ses visiteurs (Pangolin et
consorts) a besoin d'un motif d'URL qui ne désigne que le flux à excepter.

| À configurer | Valeur |
| --- | --- |
| Exception d'authentification | `/api/abonnements/*` — et **ce préfixe seul** |
| Ce qu'il ne faut pas faire | élargir l'exception à `/api/espace-animateur/*` : cela ouvrirait du même geste toutes les routes de l'espace, dont la seule écriture publique |

Côté application, la même exception est déclarée dans `application.properties`
(`quarkus.http.auth.permission.abonnement-ics`), et `AuthentificationAdminTest`
vérifie à la fois qu'elle s'applique et qu'elle **ne déborde pas** du préfixe.

## L'affichage mural : un troisième jeton, pour un écran sans surveillance

`GET /api/mural/{jeton}` est la route que lit la TV de la salle de contrôle, et
la **seule** sous le préfixe `/api/mural/`, exempté de l'authentification admin
comme l'est `/api/abonnements/`. Le raisonnement est celui de l'abonnement ICS,
appliqué à un écran : une TV reste allumée des jours dans une pièce où passent
des bénévoles, et une session admin laissée dessus — prolongée par le
rafraîchissement de l'écran lui-même — y donnerait l'écriture sur tout le
planning. Voir la
[décision 0053](repo:docs/decisions/0053-affichage-mural-par-jeton-dedie.md).

### Ce qu'il permet, exactement

Une lecture, pour **une** édition : les stands ouverts du jour, qui les tient
(prénom et initiale du nom, ou nom complet si le lien a été créé ainsi), les
places libres, les pauses sans relais et la consigne du jour. Rien d'autre : pas
le référentiel, pas les exports, pas l'espace animateur, aucune écriture. Ni
téléphone, ni e-mail, ni âge, ni motif d'absence — le numéro de téléphone que
la fiche porte désormais ne sort que vers une session d'administration (fiche,
écran Aujourd'hui) : la vue murale n'a aucun champ qui puisse le recevoir, et
le nombre de « changements non publiés » qu'elle affiche est un compte, jamais
une liste de noms. Création d'un lien depuis Aujourd'hui (« Afficher sur la
TV ») : le même lien que depuis les Paramètres, toute l'édition, initiales. Le jeton ne fonctionne sur
aucune autre route, et un jeton d'espace ou d'abonnement ne fonctionne pas sur
celle-ci : `AffichageMuralSecurityTest` le vérifie sous la vraie politique
d'authentification.

### Création, stockage, révocation

L'administrateur crée les liens depuis **Paramètres → Affichage mural**, avec un
libellé, l'option des noms complets et, au besoin, les emplacements affichés
(une TV par zone). Le jeton — 256 bits d'aléa — n'est montré **qu'une fois**, à
la création : la base n'en garde que l'empreinte SHA-256, comme les sessions de
l'espace. Une copie de la base ne rouvre donc aucun écran, et une adresse perdue
se remplace par un nouveau lien. La liste montre la date du dernier accès, à la
minute près. Révoquer coupe l'écran à sa lecture suivante ; supprimer l'édition
emporte ses liens. Un jeton inconnu et un jeton révoqué reçoivent le même `404`,
avec la même phrase. Création et révocation sont journalisées ; les lectures ne
le sont pas — une par minute et par écran n'est pas une trace.

### L'impression d'une journée par l'administrateur

« Imprimer cette journée », sur la page Planning, ouvre la même mise en page
(`/impression/:date`) sans aucun jeton : elle lit
`GET /api/affichage-mural/apercu`, **sous la session admin**, comme la gestion
des liens. Rien ne s'ajoute donc sous `/api/mural/`, et le périmètre du jeton
reste une seule route — `AffichageMuralSecurityTest` vérifie que l'aperçu refuse
une requête sans session, jeton en en-tête ou en paramètre compris.

Les liens ne voyagent ni dans l'export SQL ni dans la duplication d'une édition :
ce sont des accès de cette instance, pas des données du jeu. Revers attendu :
**importer un dump SQL les supprime tous**, puisque l'import vide les éditions et
que les liens partent avec elles en cascade — y compris pour une édition que le
dump recrée à l'identique. Les liens se recréent depuis Paramètres après chaque
import, et chaque TV se rouvre sur sa nouvelle adresse.

### Débit, cache et journaux

`AffichageMuralRateLimiter` porte deux gardes, et aucune ne compte les
lectures valides d'une adresse :

- **les lectures refusées, par adresse.** Seul un `404` — jeton inconnu ou
  révoqué — compte. Au-delà du plafond, l'adresse reçoit `429` avec un
  `Retry-After` pour tout jeton qu'elle n'a pas déjà lu avec succès, jusqu'à une
  fenêtre après son dernier refus : essayer des jetons coûte du temps, et ne
  touche plus la base ;
- **les lectures, par lien valide** : un plafond large, qui borne ce qu'une
  adresse fuitée peut tirer, un lien pouvant être ouvert sur plusieurs écrans.

Un jeton déjà servi (retenu en mémoire par son empreinte, jamais en clair)
passe donc même quand son adresse est verrouillée : du bruit venu de la même
adresse ne coupe jamais un écran. C'est ce qui compte **derrière un proxy non
déclaré** — `CONNEXION_PROXYS_FIABLES` vide, le défaut —, où toutes les requêtes
portent l'adresse du proxy : un plafond sur toutes les requêtes laissait trente
requêtes au hasard par minute, venues de n'importe où, éteindre toutes les TV de
l'événement. Ce que la déclaration des proxys garde nécessaire : sans elle, un
tel bruit verrouille l'adresse partagée, et un écran **nouveau** — ou le premier
affichage après un redémarrage, qui vide la mémoire — attend la fin de la
fenêtre. L'adresse se lit comme pour les autres plafonds.

| Variable | Défaut | Usage |
| --- | --- | --- |
| `AFFICHAGE_MURAL_MAX_REFUS` | `30` | Lectures refusées tolérées par adresse avant le verrou ; `0` le coupe |
| `AFFICHAGE_MURAL_MAX_LECTURES_PAR_LIEN` | `120` | Lectures servies par lien et par fenêtre ; `0` coupe le plafond |
| `AFFICHAGE_MURAL_FENETRE` | `PT1M` | Durée du verrou (depuis le dernier refus) et de la fenêtre par lien |

La réponse porte `Cache-Control: no-store` (elle nomme des personnes et change
chaque minute) et, comme toutes les réponses, `X-Robots-Tag: noindex`. Le jeton
voyage dans le chemin et revient **toutes les minutes** dans les journaux
d'accès du reverse proxy : mêmes précautions que pour l'abonnement (voir [ce qui reste à la charge du reverse proxy](securite.md#ce-qui-reste-à-la-charge-du-reverse-proxy)). Côté application, il ne part chez aucun tiers : la page
`/mural/*` ne charge pas le beacon Cloudflare, et les rapports d'erreur des deux
côtés remplacent le jeton de `/mural/…` et de `/api/mural/…` par `<jeton>`,
comme celui de l'espace ([`observabilite.md`](observabilite.md)).

### La règle côté proxy d'accès

| À configurer | Valeur |
| --- | --- |
| Exception d'authentification | `/api/mural/*` — et **ce préfixe seul**, en plus de la route SPA `/mural/*` qui ne porte aucune donnée |
| Ce qu'il ne faut pas faire | élargir l'exception à `/api/affichage-mural/*` : c'est la gestion des liens, réservée à l'admin |

Côté application : `quarkus.http.auth.permission.affichage-mural`.
