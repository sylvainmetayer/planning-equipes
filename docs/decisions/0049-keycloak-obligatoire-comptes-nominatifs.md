# 0049 — Keycloak obligatoire, un compte par personne, les droits fins dans l'application

- **Statut** : accepté, implémenté (socle)
- **Date** : septembre 2026
- **Portée** : authentification (administration, espace animateur, MCP), autorisation, schéma (`compte`, `habilitation`, `habilitation_stand`), exploitation (Keycloak, Terraform, Ansible)
- **Issues** : #294 (comptes nominatifs), #295 (responsable de stand) ; reprend le chantier des PR #565 et #587 du dépôt privé

## Contexte

L'application authentifiait de trois façons, toutes bâties sur des secrets
partagés ou de longue durée :

- un **compte `admin` unique**, dont le mot de passe vit dans une variable
  d'environnement et que tout le monde se transmet : aucune imputabilité, aucun
  second facteur, et retirer l'accès d'une personne veut dire changer le mot de
  passe de toutes les autres ;
- l'**espace animateur**, ouvert par un lien plus un code à six chiffres envoyé
  sur l'adresse de la fiche ;
- le **serveur MCP**, gardé par une clé API unique, sans expiration ni
  révocation.

Deux autres besoins attendaient ce chantier : un accès **RH en lecture seule**
(#294) et un **responsable de stand** limité à son périmètre (#295), tous deux
impossibles avec un seul compte.

Une première tentative (PR #565, #587 du dépôt privé) a posé un mode Keycloak
**facultatif**, éteint par défaut, avec les rôles portés **uniquement** par le
realm. Elle a trouvé, en tournant contre un vrai Keycloak, une série de pièges
que ce dépôt reprend (PKCE des deux côtés, rôles lus dans le jeton d'accès,
`clientScopes` absent du realm, descriptions ≤ 255 caractères, sous-flux du
second facteur sous le formulaire). Elle laissait trois questions ouvertes, que
cette décision tranche.

## Décision

### 1. Keycloak est obligatoire ; le compte embarqué devient une porte de secours fermée

`OIDC_ENABLED` vaut `true` par défaut. Le formulaire `/j_security_check` et le
compte `admin` / `ADMIN_PASSWORD` subsistent **seulement** comme porte de
secours, pour le jour où le realm ne répond plus : fermée par défaut
(`ADMIN_SECOURS_ENABLED=false`), elle répond `409` sans lire le mot de passe,
et une session ouverte pendant un incident perd son rôle dès la fermeture.

Un démarrage de production sans Keycloak **et** sans porte de secours est
refusé : ce serait une application que personne ne peut administrer.

Écarté — **le mode facultatif** de #565 : il garde deux configurations de
référence à tester et à documenter pour toujours, et la sécurité d'un
déploiement dépendrait d'une variable que personne n'a pensé à changer.
Écarté — **supprimer le compte embarqué** : plus de voie de secours quand
l'annuaire tombe.

### 2. Tout passe par Keycloak, MCP compris ; la clé API reste à côté

- L'**administration** : code flow, PKCE, second facteur imposé au rôle
  `admin` par le realm.
- L'**espace animateur** : la session Keycloak **remplace** le code à six
  chiffres, qui disparaît (routes, table, gabarit de mail, écran). Elle doit
  porter le rôle `animateur` et une adresse **vérifiée**, égale à celle de la
  fiche que le jeton d'URL désigne. Deux portes vers le même espace, dont l'une
  est un aller-retour de boîte mail, laisseraient la plus faible fixer le
  niveau de sécurité.
- **MCP** : second locataire OIDC, resource server au sens de la
  spécification (audience `planning-mcp`, métadonnées RFC 9728). La clé API
  **reste** : derrière un proxy d'accès qui consomme `Authorization`, un jeton
  porteur n'a pas d'en-tête de repli.

Le mode « remote user » (attestation par en-tête d'un proxy d'accès) est
**retiré** : deux chaînes de confiance pour une même identité en font une de
trop, et Keycloak couvre ce que ce mode apportait.

La révélation de la clé MCP exigeait de retaper le mot de passe admin, qui
n'existe plus pour une session Keycloak. Elle lit à la place l'`auth_time` du
jeton : la dernière preuve d'identité donnée au realm, second facteur compris,
doit dater de moins de cinq minutes.

### 3. Un compte Keycloak est une personne, pas une fiche

Keycloak ne sait pas à la fois tolérer des adresses en double et laisser se
connecter par adresse. La connexion par adresse étant l'exigence, un animateur
présent sur deux éditions a **deux fiches et un compte**. Le compte dit qui
frappe ; le jeton de l'URL dit quelle porte s'ouvre — le cloisonnement par
édition reste là où il est déjà tenu et testé.

Le provisioning (facultatif) crée le compte à l'enregistrement d'une fiche et
le **désactive**, jamais ne le supprime, quand plus aucune fiche d'aucune
édition ne porte l'adresse.

### 4. Droits hybrides : le realm dit qui, l'application dit où

| Où | Quoi | Pourquoi là |
| --- | --- | --- |
| Realm Keycloak | Identité (adresse vérifiée), second facteur, rôles **globaux** `admin`, `mcp`, `animateur` | Ce sont des faits sur la personne, que l'annuaire sait porter et imposer |
| Base de l'application | `compte` (identité, désactivation), `habilitation` (rôle délégué, édition ou toutes, expiration), `habilitation_stand` (périmètre) | Un rôle de realm n'est ni par édition, ni par stand, ni daté : #295 aurait dû réinventer ce modèle |

- Le rôle ordinaire `user` du realm **n'ouvre rien**. Un privilège bâti sur
  « cette personne existe » atteindrait chaque futur compte sans que personne
  ne l'ait accordé.
- `/api` exige le rôle `admin`, nommé : « toute personne connectée » ouvrirait
  désormais le référentiel à un animateur. Toute route est admin sauf les
  exceptions listées et argumentées, tenues par un test structurel.
- Les rôles délégués (`RH`, `RESPONSABLE_STAND`) sont du **code** ; leur
  périmètre est de la **donnée**. Pas de matrice de permissions configurable.
- Une habilitation **expire** et se **retire** ; rien ne se supprime, pour que
  le journal puisse dire qui avait quel droit à quelle date.
- Désactiver un compte dans l'application ferme toutes ses portes, quoi qu'en
  pense le realm : l'interrupteur que l'organisateur tire sans console
  Keycloak.

Cette PR pose le **socle** : les tables, l'identité réelle dans
`SecurityIdentity`, l'API d'administration des comptes, le refus par défaut.
Les rôles délégués n'ouvrent encore **aucune route** ; chacun arrivera avec ses
projections et son test structurel.

### 5. Le realm se décrit en Terraform, les personnes se gèrent en Ansible

Terraform décrit le realm de production (rôles, clients, flows, scopes) :
déclaratif, un `plan` vide prouve la concordance. Il ne touche pas aux
personnes : **Terraform supprime** ce qu'on retire d'un fichier, et une
invitation est une action, pas un état. Le playbook Ansible se réduit donc aux
comptes du personnel et à leurs invitations. Le realm versionné
(`docker/keycloak/realm-planning.json`) reste la référence de développement et
d'e2e ; un test structurel tient les deux descriptions ensemble.

### 6. Keycloak plutôt que Rauthy

Repris du PoC du dépôt privé (#586) : Rauthy ne sait pas imposer un second
facteur **par rôle** — c'est tout le monde ou personne. C'était la raison
d'être du chantier.

## Conséquences

- Tout déploiement exploite désormais un Keycloak : une base, des sauvegardes,
  un certificat de plus. L'image du dépôt (`planning-equipes-keycloak`) et le
  profil `keycloak` du compose de production le rendent déployable avec la même
  version que l'application.
- La suite Java reste exécutable sans Docker : elle tourne **avec** OIDC,
  contre un serveur en mémoire, en mode `hybrid` (un jeton porteur tient lieu
  du cookie de session). Le code flow et le second facteur sont couverts par
  la suite Playwright contre un vrai Keycloak.
- Un animateur sans adresse e-mail sur sa fiche ne peut plus ouvrir son
  espace — c'était déjà le cas avec le code.
- Mise à jour d'une instance existante : voir `docs/keycloak.md` (§ migration).
