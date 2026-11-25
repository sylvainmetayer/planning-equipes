# 0097 — Le MCP se connecte par OAuth, la clé se renouvelle sans toucher au serveur

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : transport `/mcp`, écran MCP, clé d'API
- **Prolonge** : [0093](0093-connexion-par-un-fournisseur-oidc-et-secours-ferme.md) (le fournisseur OIDC et les comptes)

## Contexte

`/mcp` n'avait qu'une clé partagée : un secret sans nom derrière lui, sans
expiration, que seul un redémarrage du serveur permettait de changer. Les
clients MCP récents savent se connecter par OAuth, et l'instance a désormais un
fournisseur d'identité et des comptes (0093).

## Décision

1. **Les deux, sans rupture.** Quand le fournisseur est configuré, `/mcp` est
   aussi une ressource protégée OAuth : un second locataire OIDC, `mcptransport`
   (porteur seul, sans cookie), choisi par `McpTenantResolver` pour `/mcp` et
   rien d'autre — pas `/api/mcp/*`, la page d'administration, qui garde le
   locataire du navigateur. Le défi 401 porte alors `resource_metadata`
   (RFC 9728) et les métadonnées de ressource sont servies, de quoi un client
   découvre le fournisseur seul. La clé d'API reste, pour les clients qui ne
   savent pas faire OAuth ou qu'un proxy d'accès prive de l'en-tête
   `Authorization`.
2. **Le jeton prouve qui, l'application dit quoi.** Le jeton doit être émis pour
   l'audience du MCP (`OIDC_MCP_AUDIENCE`, `planning-mcp` par défaut), sans
   quoi le jeton d'un autre client du même fournisseur ouvrirait les outils.
   Il n'ouvre `/mcp` qu'à un compte **actif** portant un droit
   d'administrateur ou d'organisateur en vigueur (pas un responsable de stand) :
   `CompteIdentityAugmentor` lui donne les rôles de ses droits, plus `mcp`.
   Aucun rôle du jeton n'y compte.
3. **Un organisateur n'atteint que ses éditions.** Une édition hors de ses
   droits est refusée comme une édition inexistante (`McpEditions` ne la voit
   pas), la liste des éditions est filtrée, et les outils d'instance (créer,
   renommer, dupliquer, activer, supprimer une édition, comparer, sauvegardes)
   lui sont refusés par `@InstanceOnlyTool`, le pendant de `@InstanceOnly`.
   Les jobs du solveur, registre commun, sont filtrés comme côté REST.
4. **La clé se renouvelle depuis l'écran MCP.** Une clé générée n'est montrée
   qu'une fois et stockée hachée (SHA-256, table `mcp_cle`, hors édition et hors
   export). Le recouvrement de l'ancienne clé (0 minute à 7 jours) est choisi à
   la génération ; 0 la coupe tout de suite. La clé de configuration marche
   tant qu'aucune clé n'a été générée ; la première génération la remplace pour
   de bon, une valeur de configuration changée ensuite ne la ressuscite pas.
   Générer ou révéler une clé exige une **connexion récente** (10 minutes) :
   le `auth_time` du jeton d'identité pour une session OIDC, l'instant noté par
   la connexion elle-même pour un compte local ou le secours (en mémoire, par
   empreinte du cookie de session, reportée sur chaque cookie réémis) — le
   cookie de Quarkus est réémis chaque minute et ne dit pas quand la session
   s'est ouverte. Trop ancienne, le serveur refuse (`409 RECONNEXION_REQUISE_<mode>`)
   et l'écran propose de se reconnecter. Le mot de passe de la configuration,
   que seul le compte de secours possède, n'est plus demandé. Une ligne au
   journal (`CLE_MCP_ROTEE`), jamais la clé.
5. Le verrou sur les clés refusées ne compte pas un `Bearer` de forme JWT : un
   jeton OAuth expiré ne doit pas bloquer une adresse.

## Écarté

- **Remplacer la clé par OAuth** : casse les clients sans OAuth et les
  déploiements derrière un proxy qui consomme `Authorization`.
- **Les rôles du jeton** pour décider des droits : le fournisseur n'a pas à
  connaître ceux de l'application (0093).
- **Un locataire nommé `mcp`** : Quarkus le choisirait aussi pour `/api/mcp/*`
  et vérifierait la session de l'administrateur contre l'audience du MCP.
