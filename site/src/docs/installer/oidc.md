# Brancher votre annuaire d'entreprise (OIDC)

Pour que vos organisateurs se connectent avec leur compte d'entreprise et le second facteur de votre fournisseur d'identité (Keycloak, Zitadel, ADFS 2016 ou plus récent, tout fournisseur OpenID Connect), déclarez l'application chez lui, renseignez quelques variables, puis créez les comptes. C'est facultatif : sans configuration, le compte `admin` de la configuration ouvre l'administration, comme avant. Le raisonnement du choix est dans l'[ADR 0093](repo:docs/decisions/0093-connexion-par-un-fournisseur-oidc-et-secours-ferme.md).

## Ce que fait le fournisseur, ce que fait l'application

- Le fournisseur dit qui se connecte (un sujet stable, une adresse) et porte le second facteur.
- L'application décide ce que la personne peut faire : la table `compte` (la personne) et la table `habilitation` (ses droits : `ADMIN`, `ORGANISATEUR` ou `RESPONSABLE_STAND`). Sans compte actif portant un droit en vigueur, la session n'ouvre rien, quels que soient les rôles que le fournisseur mettrait dans son jeton. Se connecter ne crée aucun compte.
- Les animateurs n'ont pas de compte : leur espace s'ouvre par le lien personnel et le code reçu par e-mail, avec ou sans fournisseur.

## Déclarer l'application chez le fournisseur

Pour Keycloak, le pas-à-pas est dans [Brancher Keycloak](keycloak.md).

Créez un client confidentiel (flux « code d'autorisation » avec PKCE), puis déclarez ces deux adresses, exactes et sans joker : le `redirect_uri` envoyé suit l'en-tête `Host` de la requête, et un joker laisserait un hôte inattendu recevoir le code.

| Adresse | Valeur |
| --- | --- |
| Redirection après connexion | `<PUBLIC_URL>/api/auth/oidc/login` (la route de connexion fait aussi office de retour du code d'autorisation) |
| Redirection après déconnexion | `<PUBLIC_URL>/login` |

Le fournisseur doit pouvoir annoncer l'adresse de l'utilisateur : les portées demandées par défaut sont `profile` et `email`.

## Renseigner les variables

| Variable | Rôle | Défaut |
| --- | --- | --- |
| `OIDC_ENABLED` | Branche le fournisseur | `false` |
| `OIDC_AUTH_SERVER_URL` | L'émetteur (issuer), **la même URL** vue du navigateur et du conteneur | (vide) |
| `OIDC_CLIENT_ID` | Identifiant du client | `planning-app` |
| `OIDC_CLIENT_SECRET` | Secret du client confidentiel, **32 caractères au moins** : le démarrage est refusé sinon, Quarkus chiffrant l'état PKCE avec lui | (vide) |
| `OIDC_REQUIRE_EMAIL_VERIFIED` | Ne rattache un compte par l'adresse que si le fournisseur la déclare vérifiée. `false` pour un annuaire (ADFS) qui n'envoie jamais `email_verified` ; jamais pour un fournisseur où n'importe qui enregistre une adresse | `true` |
| `OIDC_SCOPES` | Portées demandées | `profile,email` |
| `OIDC_PRINCIPAL_CLAIM` | Revendication qui porte l'adresse de la personne | `email` |
| `OIDC_FORCE_HTTPS` | Redirections en `https` derrière un proxy qui termine le TLS | `false` |
| `OIDC_POST_LOGOUT_PATH` | Page où retombe la personne après la déconnexion | `/login` |
| `OIDC_SESSION_AGE_EXTENSION` | Prolongation de session quand le jeton est rafraîchi | `30M` |
| `OIDC_CONNECTION_DELAY` | Durée pendant laquelle la découverte du fournisseur est retentée au démarrage, l'application pouvant être prête avant lui | `30S` |
| `OIDC_MCP_AUDIENCE` | Audience exigée d'un jeton OAuth présenté au serveur MCP (voir [`mcp-connexion.md`](../comprendre/mcp-connexion.md)) | `planning-mcp` |
| `ADMIN_LOCAL_SECOURS` | Ouvre (`true`) ou ferme (`false`) le compte de secours. Absent : fermé derrière un fournisseur, ouvert sans | (vide) |

Derrière un proxy qui termine le TLS, posez `OIDC_FORCE_HTTPS=true` ; si le proxy ne transmet pas le schéma, ajoutez `QUARKUS_OIDC_AUTHENTICATION_COOKIE_FORCE_SECURE=true` pour que le cookie de session ne parte jamais en clair.

## Créer le premier compte et se connecter

Faites-le avant de laisser le compte de secours fermé : sinon plus personne n'entre le jour où le fournisseur vous oublie.

1. Connectez-vous en `admin` (mot de passe `ADMIN_PASSWORD`), ouvrez Paramètres › **Comptes**, créez votre compte (votre adresse) et accordez-lui le droit `ADMIN`.
2. Connectez-vous **par le fournisseur** avec cette adresse. Si le fournisseur la déclare vérifiée, le compte se lie alors au couple **émetteur + sujet** du fournisseur. Ensuite seul ce sujet le désigne : une autre personne qui reprendrait la même adresse chez le fournisseur n'obtient rien, et un changement d'adresse ne bloque pas le titulaire.
3. Constatez que l'administration s'ouvre, puis laissez `ADMIN_LOCAL_SECOURS` absent (ou `false`).

Pour les personnes suivantes, un administrateur crée le **compte** (adresse, nom) et accorde un droit ; la personne se rattache à sa première connexion de la même façon. **Désactiver** un compte coupe son accès à la requête suivante, sans toucher au fournisseur.

L'écran Paramètres › **Comptes**, visible des seuls administrateurs, liste les comptes ; il permet d'en ajouter, de les désactiver et de les réactiver, d'accorder et de retirer un droit par rôle et par édition. Le refus de désactiver le dernier administrateur, ou de se désactiver soi-même, s'affiche tel que le serveur le dit. Un organisateur ne voit ni cet onglet ni ceux de l'instance, et le serveur les lui refuse de toute façon (403). L'historique, la validation d'une journée et le mode jour J nomment l'auteur par son nom (à défaut son adresse), résolu à la lecture. Ce que chaque rôle ouvre est détaillé dans [Savoir ce que chaque rôle peut appeler](securite-roles-et-droits.md).

## Changer l'URL du fournisseur

Le compte est lié au couple **émetteur + sujet** : si l'URL de l'émetteur change (migration du fournisseur, nouveau domaine), plus aucun sujet ne correspond et personne ne peut se connecter, administrateurs compris. La procédure :

1. Rouvrez le secours : `ADMIN_LOCAL_SECOURS=true`, puis redémarrez (voir [le compte de secours](securite-acces-administration.md#le-compte-de-secours--ne-jamais-senfermer-dehors)).
2. Déliez les comptes en base, pour qu'ils se relient à leur prochaine connexion (adresse vérifiée) :

   ```sql
   UPDATE compte SET oidc_sujet = NULL;
   ```

3. Changez `OIDC_AUTH_SERVER_URL`, redémarrez, reconnectez-vous par le fournisseur.
4. Refermez le secours : retirez la variable et redémarrez.

## Garder une porte de dépannage

Le compte `admin` de la configuration et son mot de passe (`ADMIN_PASSWORD`) sont la porte de dépannage, fermée par défaut derrière un fournisseur (`ADMIN_LOCAL_SECOURS` l'ouvre). La menace, la préparation et la conduite à tenir le jour où le fournisseur ne répond plus sont dans [Choisir et protéger la connexion à l'administration](securite-acces-administration.md#le-compte-de-secours--ne-jamais-senfermer-dehors).

Les comptes locaux avec mot de passe restent ouverts même derrière un fournisseur ; ils sont décrits dans [les comptes locaux](comptes-locaux.md) et dans [les cinq portes de l'administration](securite-acces-administration.md#les-cinq-portes-de-ladministration).

## Ce que le fournisseur ne couvre pas

Le serveur MCP accepte aussi un jeton OAuth du fournisseur, sous les mêmes droits (voir [`mcp-connexion.md`](../comprendre/mcp-connexion.md)) ; sa clé d'API reste utilisable. Le rôle `responsable_stand` n'ouvre que `/api/responsable/*`, et le rôle `organisateur` ne couvre que les éditions que ses droits nomment : la liste des routes d'instance réservées aux administrateurs est dans [Savoir ce que chaque rôle peut appeler](securite-roles-et-droits.md#ce-que-le-rôle-organisateur-nouvre-pas).
