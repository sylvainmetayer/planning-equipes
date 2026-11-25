# Connexion par un fournisseur d'identité (OIDC)

Facultatif : sans configuration, l'application se comporte comme avant (le compte
`admin` de la configuration ouvre l'administration). Le raisonnement est dans
l'[ADR 0093](decisions/0093-connexion-par-un-fournisseur-oidc-et-secours-ferme.md).

## Ce que fait le fournisseur, ce que fait l'application

- Le **fournisseur** dit qui se connecte (un sujet stable, une adresse) et porte
  le second facteur. Tout fournisseur OpenID Connect convient : un ADFS 2016+, un
  Keycloak ou un Zitadel intermédiaire.
- **L'application** décide ce que la personne peut faire : table `compte` (la
  personne) et `habilitation` (ses droits : `ADMIN`, `ORGANISATEUR` ou
  `RESPONSABLE_STAND`). Sans compte actif portant un droit en vigueur, la session
  n'ouvre **rien** — quels que soient les rôles que le fournisseur mettrait dans
  son jeton. Se connecter ne crée aucun compte.
- Les **animateurs** n'ont pas de compte : leur espace s'ouvre par le lien
  personnel et le code reçu par e-mail, comme sans fournisseur.

## Variables

| Variable | Rôle | Défaut |
| --- | --- | --- |
| `OIDC_ENABLED` | Branche le fournisseur | `false` |
| `OIDC_AUTH_SERVER_URL` | L'émetteur (issuer), **la même URL** vue du navigateur et du conteneur | — |
| `OIDC_CLIENT_ID` | Identifiant du client | `planning-app` |
| `OIDC_CLIENT_SECRET` | Secret du client confidentiel, **32 caractères au moins** (le démarrage est refusé sinon : Quarkus chiffre l'état PKCE avec lui) | — |
| `OIDC_REQUIRE_EMAIL_VERIFIED` | Ne rattache un compte par l'adresse que si le fournisseur la déclare vérifiée. `false` pour un annuaire (ADFS) qui n'envoie jamais `email_verified` — jamais pour un fournisseur où n'importe qui enregistre une adresse | `true` |
| `OIDC_SCOPES`, `OIDC_FORCE_HTTPS`, `OIDC_POST_LOGOUT_PATH`, `OIDC_SESSION_AGE_EXTENSION`, `OIDC_CONNECTION_DELAY` | Réglages fins | voir `application.properties` |
| `ADMIN_LOCAL_SECOURS` | Ouvre (`true`) ou ferme (`false`) le compte de secours. Absent : fermé derrière un fournisseur, ouvert sans | — |

L'URI de redirection à déclarer chez le fournisseur est
`<PUBLIC_URL>/api/auth/oidc/login` (la route de connexion fait aussi office de
retour du code d'autorisation) ; celle de retour après déconnexion,
`<PUBLIC_URL>/login`. Déclarez-les **exactes, sans joker** : le `redirect_uri`
envoyé suit l'en-tête `Host` de la requête, un joker laisserait un hôte
inattendu recevoir le code. Le client est confidentiel, avec PKCE.

## Changer l'URL du fournisseur

Le compte est lié au couple **émetteur + sujet** : si l'URL de l'émetteur
change (migration du fournisseur, nouveau domaine), plus aucun sujet ne
correspond et personne ne peut se connecter, administrateurs compris. Procédure :
rouvrez le secours (`ADMIN_LOCAL_SECOURS=true`, voir [`securite.md`](securite.md#le-compte-de-secours--ne-jamais-senfermer-dehors)), puis
déliez les comptes en base pour qu'ils se relient à leur prochaine connexion
(adresse vérifiée) :

```sql
UPDATE compte SET oidc_sujet = NULL;
```

Changez ensuite `OIDC_AUTH_SERVER_URL`, redémarrez, reconnectez-vous par le
fournisseur, puis refermez le secours.

## Rattacher une personne

1. Un administrateur crée le **compte** de la personne (son adresse) et lui
   accorde un droit (`ADMIN`, `ORGANISATEUR` ou `RESPONSABLE_STAND`).
2. À sa première connexion, si le fournisseur déclare son adresse vérifiée, le
   compte est lié au couple **émetteur + sujet** du fournisseur. Ensuite seul ce
   sujet le désigne : une autre personne qui reprendrait la même adresse chez le
   fournisseur n'obtient rien, et un changement d'adresse ne bloque pas le titulaire.
3. **Désactiver** le compte coupe l'accès à la requête suivante, sans toucher
   au fournisseur.

## Le compte de secours

Le compte `admin` de la configuration et son mot de passe (`ADMIN_PASSWORD`) sont
la porte de dépannage, **fermée par défaut** derrière un fournisseur
(`ADMIN_LOCAL_SECOURS` l'ouvre). La menace, la procédure de préparation et
celle du jour où le fournisseur ne répond plus sont dans
[`securite.md`](securite.md#le-compte-de-secours--ne-jamais-senfermer-dehors).

## L'écran des comptes

Paramètres › **Comptes**, visible des seuls administrateurs : la liste des
comptes, l'ajout (adresse, nom), la désactivation et la réactivation, l'octroi
et le retrait d'un droit par rôle et par édition. Le refus du dernier
administrateur et de sa propre désactivation s'affiche tel que le serveur le
dit. Un organisateur ne voit ni cet onglet ni ceux de l'instance ; le serveur
les lui refuse de toute façon (403). L'historique, la validation d'une journée
et le mode jour J nomment l'auteur par son nom (à défaut son adresse), résolu à
la lecture.

## Ce qui n'est pas là

Le rôle `organisateur` ouvre `/api` dans les seules éditions que ses droits en
vigueur nomment (un droit « toutes éditions » les couvre toutes) : une édition
hors de ses droits est refusée comme une édition inexistante
(`EDITION_INCONNUE`, 400) et absente de la liste des éditions. Il n'ouvre pas
les routes d'instance, réservées à `admin` (403) : comptes, webhooks, clé MCP,
sauvegardes, export et import de toute la base, écran Débogage, machine de
calcul, journal des connexions, horloge simulée, création, renommage,
duplication, activation et suppression d'une édition, comparaison de deux
éditions. L'import de scénario reste ouvert à l'organisateur, dans son édition
courante seulement : une section `edition:` qui en désigne une autre, ou une à
créer, est refusée (400). L'inventaire est `InstanceRoutesStructuralTest` (ressources) et
`RoutesOuvertesStructuralTest` (route par route, voir `securite.md`) ; une
nouvelle ressource ou route non classée fait échouer le build. Le rôle `responsable_stand` n'ouvre que `/api/responsable/*`
(ADR [0095](decisions/0095-le-responsable-de-stand-lit-son-stand.md)). Le rôle
`lecteur` (une édition) voit comme un organisateur mais le serveur refuse toute
écriture, tout calcul et tout export de données brutes ; seuls les PDF et ICS
passent (ADR [0099](decisions/0099-le-lecteur-voit-sans-rien-ecrire.md),
inventaire : `RoutesLecteurStructuralTest`). Le `/mcp`
accepte aussi un jeton OAuth du fournisseur, sous les mêmes droits (voir
[`mcp.md`](mcp.md)) ; sa clé d'API reste. Les comptes locaux avec mot de passe, ouverts
même derrière un fournisseur, sont décrits dans
[`exploitation.md`](exploitation.md#les-comptes-locaux-mot-de-passe) et
[`securite.md`](securite.md#qui-ouvre-ladministration).
