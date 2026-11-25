# Choisir et protéger la connexion à l'administration

Avant d'ouvrir l'instance à d'autres personnes que vous, choisissez par quelle porte entrent vos administrateurs (annuaire OIDC, compte local ou compte `admin` de la configuration), gardez le compte de secours fermé sans vous enfermer dehors, et réglez le verrou de connexion derrière votre reverse proxy ou votre tunnel. Cette page décrit ces trois choix.

## Les cinq portes de l'administration

Cinq portes, de la plus forte à la plus faible :

1. **Un fournisseur d'identité OIDC** ([mise en place](oidc.md), [ADR 0093](repo:docs/decisions/0093-connexion-par-un-fournisseur-oidc-et-secours-ferme.md)) : chaque
   organisateur se connecte sous son propre nom, avec le second facteur du
   fournisseur. Un compte inconnu ou désactivé de l'application n'a aucun rôle,
   et `/api/*` répond `403` (la page de connexion l'explique) ; seul un droit
   `ADMIN` en vigueur donne le rôle `admin`. Les pages `/q/openapi` et
   `/q/swagger-ui` suivent la même règle.
2. **Un compte local** ([ADR 0094](repo:docs/decisions/0094-comptes-locaux-par-mot-de-passe.md)) : adresse et mot de passe choisi par son
   propriétaire seul, par un lien à usage unique reçu par mail (72 h à
   l'invitation, une heure à la réinitialisation ou à « mot de passe oublié »),
   puis un **code à six chiffres reçu par mail**, activé par défaut
   (`AUTH_LOCAL_SECONDE_ETAPE=false` le coupe). Le mot de passe est un hachage
   bcrypt de douze caractères au moins ; lien, défi et code ne sont stockés que
   hachés, en tables globales (`compte_jeton`, `compte_defi`), hors de l'export
   SQL. Le formulaire est verrouillé par adresse comme celui de `admin`
   (`CONNEXION_MAX_ECHECS`), les demandes de code et de lien sont limitées par
   compte, et une réponse ne dit jamais si une adresse a un compte. Ouvert **même
   derrière un fournisseur OIDC** : seul le secours est fermé. Désactiver un
   compte coupe sa session à la requête suivante, et **choisir un nouveau mot
   de passe ferme toutes les sessions ouvertes avec l'ancien** (le cookie porte
   l'adresse et la date du dernier mot de passe) ; le titulaire en est prévenu
   par mail. Le verrou du compte local compte par **paire (adresse IP, compte)** au
   seuil du formulaire (`CONNEXION_MAX_ECHECS`), par **adresse seule à dix fois
   ce seuil**, et par **compte** toutes adresses confondues : derrière une
   adresse partagée (le wifi d'un festival), les fautes de frappe de plusieurs
   personnes ne bloquent pas tout le monde, mais un mot de passe essayé sur
   beaucoup de comptes, ou beaucoup de mots de passe sur un compte, rencontrent
   un mur. Une connexion réussie ne remet à zéro que les échecs de sa propre
   paire. Choix assumé : un attaquant peut verrouiller un compte précis en le
   ratant cinq fois (déni de service ciblé, levé au bout de
   `CONNEXION_DUREE_BLOCAGE`), faute de quoi on ne l'arrêterait pas depuis
   plusieurs adresses. Une session dont le mot de passe a changé, ou dont le
   compte a été désactivé puis réactivé, est anonyme (`401`) : l'époque des
   sessions (`sessions_depuis`) avance à chacun de ces événements. Réponses `429` et `409` après un
   bon mot de passe (verrou, code non envoyé) le confirment : risque faible,
   accepté. Hors développement, un lien n'est jamais écrit vers `localhost`
   (`PUBLIC_URL` à renseigner, avertissement au démarrage).
3. **Le compte `admin` de la configuration**, un mot de passe partagé sans
   second facteur et sans nom dans le journal des actions (l'acteur y est `admin`).
   Sans fournisseur, c'est la porte ordinaire. Derrière un fournisseur, il est le
   **secours**, fermé par défaut : voir
   [Le compte de secours](#le-compte-de-secours--ne-jamais-senfermer-dehors).
4. **Le mode en-tête de proxy** (`REMOTE_USER_*`, voir [`api.md`](../comprendre/api.md)) : **déprécié,
   retiré en 4.0**. Il fonctionne encore, un avertissement le rappelle à chaque
   démarrage ; il reste indépendant du secours (le secret partagé est son
   identifiant).
5. La clé d'API du `/mcp`, qui n'ouvre que le MCP : renouvelable depuis l'écran MCP
   (hachée, montrée une fois, recouvrement choisi), ou jeton OAuth du fournisseur
   pour un compte actif ([ADR 0097](repo:docs/decisions/0097-mcp-par-oauth-et-rotation-de-la-cle.md), [`mcp.md`](../comprendre/mcp.md)).

**Exécutez l'application derrière https.** Le cookie de session OIDC et celui du
formulaire n'ont de sens que chiffrés en transit. Le déploiement étant agnostique,
rien n'est forcé : derrière un proxy qui termine le TLS, posez
`OIDC_FORCE_HTTPS=true` (redirections en https) et, si le proxy ne transmet pas
le schéma, `QUARKUS_OIDC_AUTHENTICATION_COOKIE_FORCE_SECURE=true`
(`quarkus.oidc.authentication.cookie-force-secure`) pour que le cookie ne parte
jamais en clair. Les tentatives de mot de passe sur le secours fermé sont
journalisées comme des échecs (adresse seulement), sans compter dans le verrou.

### Le compte de secours : ne jamais s'enfermer dehors

**La menace.** Le compte `admin` est un mot de passe partagé, sans second
facteur ni nom dans le journal. Derrière un fournisseur, le laisser ouvert
annulerait ce que le fournisseur apporte ; le fermer expose à l'inverse au
verrouillage de tous les administrateurs le jour où le fournisseur tombe, ou où
son URL change (le compte est lié au couple émetteur + sujet,
[`oidc.md`](oidc.md#changer-lurl-du-fournisseur)).

**Ce que « fermé » veut dire.** `409` sur `/j_security_check` (y compris sous
un préfixe, que Quarkus accepte) et session préexistante dépouillée de son rôle.
`ADMIN_LOCAL_SECOURS=true` le rouvre ; l'ouverture au démarrage
(`Compte de secours ouvert au démarrage`) et chaque connexion par lui
(`Connexion par le compte de secours`) sont dans le journal des connexions
admin (Historique, onglet Connexions), et l'application journalise un
avertissement tant qu'il est ouvert. `ADMIN_PASSWORD` reste contrôlé même
fermé (valeur d'exemple refusée au démarrage).

**Avant de brancher le fournisseur** (ou avec le secours ouvert) :

1. Connectez-vous en `admin`, créez votre compte (votre adresse) dans le
   référentiel des comptes et accordez-lui le droit `ADMIN`. Au démarrage
   suivant, l'application journalise un avertissement si le secours est fermé
   et qu'aucun administrateur actif n'existe.
2. Connectez-vous **par le fournisseur** avec ce compte : c'est cette première
   connexion qui le lie à votre identité chez lui. Constatez que
   l'administration s'ouvre.
3. Laissez `ADMIN_LOCAL_SECOURS` absent (ou `false`).

**Le jour où le fournisseur ne répond plus, ou où l'accès administrateur est
perdu** : positionnez `ADMIN_LOCAL_SECOURS=true`, redémarrez, connectez-vous en
`admin` avec `ADMIN_PASSWORD`, corrigez (réactivez le compte, rendez-lui son
droit), puis **retirez la variable et redémarrez**, et relisez le journal des
connexions après l'incident. Si c'est l'URL du fournisseur qui a changé, déliez
aussi les comptes ([`oidc.md`](oidc.md#changer-lurl-du-fournisseur)). Sans
accès à l'environnement de déploiement, la seule issue est la base
(`desactive_le` ou une ligne `habilitation`) : tenez `ADMIN_PASSWORD` et ce
paragraphe à portée de l'opérateur.

## Verrouiller le formulaire de connexion de l'admin

L'application n'a qu'un compte, `admin`, sans second facteur : une seule paire
d'identifiants ouvre les données personnelles de ~150 personnes, mineurs
compris. `/j_security_check` acceptait pourtant les tentatives au rythme du
réseau — alors que la révélation de la clé MCP se verrouillait déjà au bout de
cinq essais.

Au-delà de `CONNEXION_MAX_ECHECS` échecs consécutifs, l'adresse reçoit `429`
+ `Retry-After` pendant `CONNEXION_DUREE_BLOCAGE`, **y compris avec le bon mot
de passe** : sans quoi il suffirait d'attendre son tour. Une connexion réussie
efface le compteur, et la fenêtre court depuis le dernier échec réel.

| Variable | Défaut | Usage |
| --- | --- | --- |
| `CONNEXION_MAX_ECHECS` | `5` | Échecs consécutifs tolérés par adresse |
| `CONNEXION_DUREE_BLOCAGE` | `PT15M` | Durée du verrouillage, comptée depuis le dernier échec |

Succès et échec répondent tous deux une redirection vers la **même page**, donc
ni le statut ni `Location` ne les distinguent. Les deux se lisent ailleurs :
l'échec par l'`AuthenticationFailureEvent` de Quarkus, qui porte le contexte
HTTP donc l'adresse ; le succès sur la requête elle-même — l'événement Quarkus
correspondant ne transporte aucun contexte, donc aucune adresse à qui rendre
son crédit.

**L'adresse retenue est l'entrée la plus à droite de `X-Forwarded-For` qui n'est
pas un proxy déclaré**, et l'en-tête n'est lu que si la connexion elle-même
vient d'un proxy déclaré dans `CONNEXION_PROXYS_FIABLES`. Sans cette liste —
le défaut — l'en-tête est entièrement ignoré et c'est l'adresse de connexion qui
compte : sûr par construction, et correct pour un déploiement sans proxy.

Lire l'en-tête **par la gauche** est ce qui rendait le verrou inopérant. Un proxy
*ajoute* son entrée plutôt que de remplacer l'en-tête, donc le premier élément
est celui que le client a écrit : un `X-Forwarded-For` forgé par tentative
achetait un compteur neuf.

`QUARKUS_HTTP_PROXY_TRUSTED_PROXIES` **ne ferme pas ce trou**, et il ne faut pas
compter dessus pour cela : il décide si Quarkus lit l'en-tête, jamais quel
élément il retient — `ForwardedParser` prend toujours le premier. Passer par
`remoteAddress()` ne corrigeait donc rien non plus. C'est la raison pour laquelle
le verrou lit l'en-tête lui-même, par la droite, avec sa propre liste de proxys.

**Renseignez `CONNEXION_PROXYS_FIABLES`** avec les adresses de vos proxys inverses,
telles qu'elles apparaissent côté application. Sans elle, tous les visiteurs
derrière le proxy partagent un compteur, et le premier attaquant venu verrouille
la connexion de tout le monde.

Le réglage accepte des **adresses littérales et des blocs CIDR**, séparés par des
virgules : `172.18.0.0/16`, ou `127.0.0.1,10.0.0.0/8`. Une entrée mal formée
empêche le démarrage plutôt que d'être ignorée — sans quoi vous croiriez avoir
déclaré votre proxy pendant que le verrou compterait tout le monde ensemble.

Déclarer un bloc, c'est faire confiance à toutes les machines qu'il contient.
C'est la bonne granularité pour un réseau privé dont les membres sont vos
propres services, et la mauvaise pour une plage publique.

> **Derrière un tunnel (Pangolin, Cloudflare Tunnel, `newt`…)**, l'adresse à
> déclarer n'est ni celle du client, ni l'IP publique du proxy : c'est celle du
> client de tunnel **tel que votre application le voit**, c'est-à-dire un
> conteneur sur un réseau bridge. Cette adresse est attribuée à l'attachement et
> bouge au redémarrage ; le sous-réseau, lui, est fixé à la création du réseau.
> D'où le bloc :
>
> ```bash
> docker network inspect <réseau> -f '{{(index .IPAM.Config 0).Subnet}}'
> # → 172.18.0.0/16, la valeur à passer en CONNEXION_PROXYS_FIABLES
> ```
>
> Vérifiez d'abord que l'en-tête arrive : si votre client de tunnel relaie en
> TCP sans que rien n'ait posé `X-Forwarded-For` en amont, ce réglage ne vous
> apportera rien et il faut d'abord le faire ajouter côté proxy.

Ce verrou ne remplace pas la limitation de débit par IP du proxy, qui vaut pour
tout le reste — exports, résolution, API entière.

### La trace qu'il laisse

Chaque issue du form login est consignée dans le **journal des connexions** de
l'instance (table `journal_connexion`, onglet « Connexions » de la page
*Historique*, quelle que soit l'édition choisie —
[ADR 0076](repo:docs/decisions/0076-journal-des-connexions-admin-par-instance.md)) :
la connexion réussie, **chaque échec**, et **le verrouillage**, une fois, sur
l'échec qui atteint `CONNEXION_MAX_ECHECS`. Chaque ligne porte son horodatage et
l'adresse que le verrou compte, lue de la même façon (`X-Forwarded-For` par la
droite derrière un proxy déclaré). **Ni le mot de passe ni l'identifiant
saisis** n'y entrent : un identifiant faux est souvent un mot de passe tapé
dans le mauvais champ.

Une tentative refusée en `429` pendant le verrou n'écrit rien : elle n'a essayé
aucun identifiant, et une ligne par refus livrerait la table à quiconque
martèle le formulaire. L'écriture quitte la boucle d'événements pour un
travailleur ; si elle échoue, la tentative est tranchée quand même et l'échec
part au journal applicatif, en une ligne, sans pile d'appels. Les lignes suivent
la rétention de l'historique (`JOURNAL_RETENTION`, purge de nuit) et ne
voyagent pas dans l'export SQL.

Le verrou est par adresse : une attaque menée depuis beaucoup d'adresses n'est
jamais bloquée longtemps, et chacun de ses échecs serait une ligne de plus. La
trace est donc bornée, pour l'instance entière :

| Plafond | Valeur | Au-delà |
| --- | --- | --- |
| Tentatives échouées journalisées | 1 000 par heure | comptées, non écrites (l'échec et son éventuel verrouillage ensemble) |
| Écritures en attente d'un travailleur | 64 | comptées, non écrites — connexions réussies comprises |

Une connexion réussie n'est jamais retenue par le premier plafond. Une fenêtre
saturée le dit en **un seul** avertissement, à la première tentative écartée ;
leur nombre suit en une ligne à l'ouverture de la fenêtre suivante. Seule la
trace est bornée : le verrou compte toujours chaque échec. Une adresse seule
reste loin du plafond (une vingtaine d'échecs par heure avec les réglages par
défaut) : l'atteindre est déjà le signe d'une attaque distribuée.

Une connexion attestée par le proxy (mode `planning.auth.remote-user.*`) ne
passe pas par ce formulaire et n'y figure donc pas : c'est le proxy qui la
journalise.
