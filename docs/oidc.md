# Connexion par un fournisseur d'identité (OIDC)

Facultatif : sans configuration, l'application se comporte comme avant (le compte
`admin` de la configuration ouvre l'administration). Le raisonnement est dans
l'[ADR 0093](decisions/0093-connexion-par-un-fournisseur-oidc-et-secours-ferme.md).

## Ce que fait le fournisseur, ce que fait l'application

- Le **fournisseur** dit qui se connecte (un sujet stable, une adresse) et porte
  le second facteur. Tout fournisseur OpenID Connect convient : un ADFS 2016+, un
  Keycloak ou un Zitadel intermédiaire.
- **L'application** décide ce que la personne peut faire : table `compte` (la
  personne) et `habilitation` (son droit). Sans compte actif portant un droit
  `ADMIN` en vigueur, la session n'ouvre **rien** — quels que soient les rôles que
  le fournisseur mettrait dans son jeton. Se connecter ne crée aucun compte.
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
rouvrez le secours (`ADMIN_LOCAL_SECOURS=true`, voir `exploitation.md`), puis
déliez les comptes en base pour qu'ils se relient à leur prochaine connexion
(adresse vérifiée) :

```sql
UPDATE compte SET oidc_sujet = NULL;
```

Changez ensuite `OIDC_AUTH_SERVER_URL`, redémarrez, reconnectez-vous par le
fournisseur, puis refermez le secours.

## Rattacher une personne

1. Un administrateur crée le **compte** de la personne (son adresse) et lui
   accorde un droit (`ADMIN`).
2. À sa première connexion, si le fournisseur déclare son adresse vérifiée, le
   compte est lié au couple **émetteur + sujet** du fournisseur. Ensuite seul ce
   sujet le désigne : une autre personne qui reprendrait la même adresse chez le
   fournisseur n'obtient rien, et un changement d'adresse ne bloque pas le titulaire.
3. **Désactiver** le compte coupe l'accès à la requête suivante, sans toucher
   au fournisseur.

## Le compte de secours

Le compte `admin` de la configuration et son mot de passe (`ADMIN_PASSWORD`) sont
la porte de dépannage, **fermée par défaut** derrière un fournisseur. Fermée, la
connexion par mot de passe répond `409` et une session ouverte avant la
fermeture perd son rôle. La procédure d'ouverture est dans
[`exploitation.md`](exploitation.md), § « Le compte de secours » ; chaque ouverture et chaque connexion par ce
compte sont écrites dans le journal des connexions (écran Historique, onglet
Connexions).

## Ce qui n'est pas là

Le rôle `organisateur` ouvre `/api` dans les seules éditions que ses droits en
vigueur nomment (un droit « toutes éditions » les couvre toutes) : une édition
hors de ses droits est refusée comme une édition inexistante
(`EDITION_INCONNUE`, 400) et absente de la liste des éditions. Il n'ouvre pas
les routes d'instance, réservées à `admin` (403) : comptes, webhooks, clé MCP,
sauvegardes, export et import de toute la base, écran Débogage, machine de
calcul, journal des connexions, horloge simulée, création, renommage,
duplication, activation et suppression d'une édition, comparaison de deux
éditions, et l'import de scénario, dont le fichier peut désigner une autre
édition. L'inventaire est `InstanceRoutesStructuralTest` ; une nouvelle ressource
non classée fait échouer le build. L'OAuth du `/mcp` (qui garde sa clé d'API) et
les comptes locaux avec mot de passe viennent séparément.
