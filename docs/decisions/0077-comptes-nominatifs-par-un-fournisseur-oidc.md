# 0077 — Des comptes nominatifs par n'importe quel fournisseur OIDC, les droits dans l'application

- **Statut** : accepté, implémenté (socle et responsable de stand)
- **Date** : novembre 2026 (première version en septembre 2026, voir « Historique »)
- **Portée** : authentification de l'administration et de MCP, autorisation, schéma (`compte`, `habilitation`, `habilitation_stand`, `parametres_responsables`), exploitation
- **Origine** : les comptes nominatifs (accès RH en lecture seule) et le responsable de stand

## Contexte

L'application authentifie de trois façons, toutes bâties sur des secrets
partagés ou de longue durée :

- un **compte `admin` unique**, dont le mot de passe vit dans une variable
  d'environnement et que l'équipe se transmet : aucune imputabilité, aucun
  second facteur, et retirer l'accès d'une personne veut dire changer le mot
  de passe de toutes les autres ;
- l'**espace animateur**, ouvert par un lien plus un code à six chiffres envoyé
  sur l'adresse de la fiche ;
- le **serveur MCP**, gardé par une clé API unique.

Deux autres besoins attendaient : un accès **RH en lecture seule** et un
**responsable de stand** limité à son périmètre, impossibles avec un seul
compte.

L'application est hébergée pour des organisateurs qui ont souvent **déjà un
annuaire** — une collectivité avec son ADFS, une association avec son
Google Workspace ou son Microsoft 365. Leurs agents doivent se connecter avec le
compte qu'ils ont, sous la politique de mot de passe et de second facteur de
leur structure, et l'organisateur ne doit pas administrer un second annuaire
pour l'occasion.

## Décision

### 1. L'application parle OIDC à n'importe quel fournisseur ; aucun n'est imposé

`OIDC_ENABLED` (éteint par défaut) branche un fournisseur OpenID Connect
quelconque, désigné par son émetteur, un client et son secret :

- **en direct**, un annuaire qui parle OIDC : ADFS 2016 et suivants, Entra ID,
  Google ;
- **par un intermédiaire** — Keycloak, Zitadel — quand l'annuaire ne parle que
  SAML, qu'il faut réunir plusieurs annuaires, ou ajouter des comptes locaux à
  ceux d'un annuaire. La fédération est alors le travail de l'intermédiaire,
  pas de l'application.

Les écarts entre fournisseurs se règlent par configuration, jamais par du code
propre à l'un d'eux : où lire les rôles du jeton (`OIDC_ROLE_CLAIM_PATH`),
exiger ou non `email_verified` (un ADFS ne l'envoie jamais, ses adresses étant
celles de l'annuaire), qui vérifie le second facteur (voir 3).

Écarté — **imposer Keycloak** (la première version de cette décision) : une
brique de plus à exploiter pour chaque instance, de la mémoire qu'un petit hôte
n'a pas, et un second annuaire à tenir pour un organisateur qui en a déjà un.
Écarté — **du code propre à un fournisseur** (provisioning par son API
d'administration, extension, thème) : il lie l'application à un produit, et
c'est précisément ce que le fournisseur de l'organisateur fait déjà.

### 2. Le fournisseur authentifie, l'application autorise

| Où | Quoi | Pourquoi là |
| --- | --- | --- |
| Fournisseur | Identité (adresse), mot de passe, second facteur | C'est l'annuaire de l'organisation : il sait les porter et les imposer |
| Base de l'application | `compte` (identité, désactivation), `habilitation` (rôle, édition ou toutes, expiration), `habilitation_stand` (périmètre) | Un annuaire que l'application ne gère pas ne dira jamais qui administre **cette** instance, ni par édition, ni par stand, ni jusqu'à quand |

- **Administrateur compris** : « Nommer un administrateur » accorde un droit
  `ADMIN`, pour toute l'instance. Un fournisseur qui porte déjà un rôle
  `admin` dans ses jetons est honoré aussi, pour qui préfère le gérer là.
- Le premier administrateur se nomme depuis le **compte local** (point 4).
- Le rôle ordinaire `user` d'un fournisseur **n'ouvre rien** : un privilège
  bâti sur « cette personne existe » atteindrait chaque futur compte sans que
  personne ne l'ait accordé.
- `/api` exige le rôle `admin`, nommé : « toute personne connectée » ouvrirait
  le référentiel à quiconque l'annuaire connaît. Les exceptions sont listées et
  argumentées, tenues par un test structurel.
- Les rôles délégués (`RH`, `RESPONSABLE_STAND`) sont du **code** ; leur
  périmètre est de la **donnée**. Pas de matrice de permissions configurable.
- Une habilitation **expire** et se **retire** ; un compte se **désactive** —
  rien ne se supprime, pour que le journal puisse dire qui avait quel droit à
  quelle date. Désactiver un compte ferme toutes ses portes, quoi qu'en dise le
  fournisseur : l'interrupteur que l'organisateur tire sans toucher à
  l'annuaire.
- Un compte est retrouvé par le `sub` du jeton, puis par l'adresse : un
  changement d'adresse dans l'annuaire suit le même compte.

### 3. Le second facteur des administrateurs : celui du fournisseur, ou vérifié dans le jeton

`OIDC_ADMIN_MFA=provider` (défaut) s'en remet à la politique du fournisseur —
la stratégie de contrôle d'accès d'un ADFS, le flux d'un Keycloak. `token`
fait refuser le rôle `admin` par l'application à une session dont le claim
`amr` ne nomme aucune méthode de la liste `OIDC_ADMIN_MFA_AMR`. Le second mode
existe parce que la politique d'un annuaire tiers n'est pas toujours visible
de qui exploite l'application.

### 4. Le compte local reste, ouvert par défaut

Le compte `admin` / `ADMIN_PASSWORD` et son formulaire restent **la** porte
d'une instance sans fournisseur — rien ne change pour elle à la mise à jour. À
côté d'un fournisseur, il devient la porte de secours du jour où l'annuaire ne
répond plus ; l'exploitant le ferme une fois le fournisseur éprouvé
(`ADMIN_SECOURS_ENABLED=false` : `409` sans lire le mot de passe, et une
session ouverte avant perd son rôle). Un démarrage de production sans aucune
porte est refusé.

### 5. Les animateurs n'ont pas de compte

L'espace animateur garde son lien et son code par e-mail. Les animateurs — des
bénévoles, des saisonniers, des mineurs — ne sont pas dans l'annuaire de
l'organisateur, et les y créer pour un week-end serait un traitement de données
de plus pour rien. Une session du fournisseur dont l'adresse est **celle de la
fiche** désignée par le lien ouvre l'espace sans le code : c'est le même fait,
prouvé autrement, pour l'agent d'une collectivité qui anime son propre
événement. Elle n'ouvre jamais l'espace d'une autre fiche.

### 6. MCP : la clé API reste, OAuth quand un fournisseur est branché

Le transport MCP garde sa clé API. Un fournisseur branché ouvre en plus un
second locataire OIDC, resource server au sens de la spécification MCP
(audience configurable, métadonnées RFC 9728) — pour les clients qui savent
obtenir un jeton. La révélation de la clé lit, pour une session OIDC,
l'`auth_time` du jeton : la dernière preuve d'identité doit dater de moins de
cinq minutes, faute de mot de passe à retaper.

### 7. Le responsable de stand

- il entre par un **écran dédié** (`/responsable`), avec sa seule session
  OIDC — le droit est celui du compte, pas d'une fiche ;
- une habilitation `RESPONSABLE_STAND` en vigueur donne le rôle
  `responsable-stand`, la porte de `/api/responsable/*` et de rien d'autre ;
  quelle édition et quels stands sont lus se décide à chaque requête, contre
  les droits du compte (`StandScope`, porte unique tenue par un test
  structurel) ;
- il lit le **plan publié**, pas le plan de travail ;
- **effectifs par défaut**, prénoms et noms quand l'édition l'allume
  (`parametres_responsables`) ou que l'habilitation le surcharge : tant que le
  planning bouge, « deux animateurs » est une promesse tenable, « Bob et
  Alice » non. Jamais de coordonnées ;
- l'habilitation **expire** obligatoirement.

## Conséquences

- Une instance existante se met à jour sans rien changer : pas de fournisseur,
  compte local ouvert, espace animateur inchangé.
- Brancher un annuaire est une configuration (`docs/oidc.md`) : un client
  déclaré chez le fournisseur, cinq variables, puis nommer les administrateurs
  depuis le compte local et le fermer.
- La suite Java tourne **avec** OIDC, contre un serveur en mémoire, en mode
  `hybrid` (un jeton porteur tient lieu du cookie de session). Le code flow
  lui-même est celui du fournisseur ; aucun n'est embarqué dans les tests.
- Ce qu'un fournisseur ne sait pas faire (l'enregistrement dynamique de
  clients qu'attendent certains clients MCP, un `amr` exploitable) se vérifie
  fournisseur par fournisseur ; la clé API reste la voie de repli de MCP.

## Historique

Une première version (septembre 2026) rendait **Keycloak obligatoire** : un
compte Keycloak par animateur, provisionné par l'application, une extension
« code par e-mail » et un thème, le realm décrit en Terraform, le mode
« remote user » et le code de l'espace retirés. Elle n'a jamais été publiée.
Elle a été revue avant fusion parce que la cible est un hébergement pour des
organisateurs qui ont déjà leur annuaire : ce sont eux qui authentifient, et la
brique imposée pesait sur chaque instance sans servir celles-là.
