# 0093 — Connexion par un fournisseur OIDC, compte de secours fermé par défaut

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : authentification de l'administration, comptes
- **Prolonge** : [0076](0076-journal-des-connexions-admin-par-instance.md) (le journal des connexions)

## Contexte

Les comptes nominatifs (`compte`, `habilitation`) existent, mais rien ne les
relie encore à une connexion : un seul mot de passe, celui du compte `admin` de
la configuration, ouvre toute l'administration, sans nom derrière lui. Une
instance exposée à plusieurs organisateurs veut qu'une personne se connecte
comme elle-même, avec le second facteur et le cycle de vie (départ, congé) que
son organisation gère déjà chez son fournisseur d'identité.

## Décision

1. **OIDC facultatif**, éteint par défaut (`OIDC_ENABLED`) : tout fournisseur
   OpenID Connect, le code d'autorisation avec PKCE conduit par Quarkus. Rien ne
   change tant qu'il n'est pas configuré.
2. **Le fournisseur dit qui, l'application dit quoi.** Un jeton n'ouvre rien par
   lui-même : seul un compte **actif** de l'application, avec un droit `ADMIN`
   en vigueur, reçoit le rôle `admin`. Un compte inconnu ou désactivé n'a aucun
   rôle, quoi que le jeton contienne. **Personne n'est créé en se connectant** :
   un administrateur crée le compte (adresse) puis le fournisseur l'atteste.
3. **Rattachement par le couple émetteur + sujet.** Un compte sans sujet est
   réclamé par la première session dont l'adresse correspond **et que le
   fournisseur déclare vérifiée** (`email_verified`) ; il appartient ensuite à ce
   sujet. La même adresse reprise plus tard par une autre personne chez le
   fournisseur n'ouvre rien, et un changement d'adresse n'enferme pas son
   titulaire. `OIDC_REQUIRE_EMAIL_VERIFIED=false` est l'exception d'un annuaire
   (ADFS) dont les adresses sont celles de l'organisation.
4. **Le compte `admin` de la configuration devient le secours** : derrière un
   fournisseur, son formulaire est **fermé par défaut** (`409` sur
   `/j_security_check`, y compris sous un préfixe, et une session ouverte avant
   la fermeture perd son rôle). `ADMIN_LOCAL_SECOURS=true` le rouvre. Chaque
   ouverture au démarrage et chaque connexion par ce compte sont écrites dans le
   journal des connexions admin (`SECOURS_OUVERT`, `CONNEXION_SECOURS`). Sans
   fournisseur, le formulaire reste la porte, comme avant.
5. **Le mode en-tête de proxy est déprécié** : il fonctionne, un avertissement le
   dit à chaque démarrage, il sera retiré en 4.0.
6. Les rôles `organisateur` (droit non limité à une édition) sont portés par la
   session mais **n'ouvrent rien** : `/api/*` exige `admin`. Leur périmètre par
   édition vient avec l'écran des comptes.

## Alternatives écartées

- *Créer le compte à la première connexion* (reprise de la proposition initiale) :
  tout titulaire d'un compte chez le fournisseur entrerait dans la table ; un
  organisateur est choisi, pas auto-déclaré.
- *Faire confiance aux rôles du jeton* : le droit d'administrer vivrait dans
  l'annuaire, hors du journal de l'application.
- *Supprimer le compte `admin`* : une panne du fournisseur enfermerait
  l'opérateur dehors. Fermé par défaut et journalisé, il est le moindre mal.

## Conséquences

- Premier démarrage derrière un fournisseur : l'opérateur crée son compte
  administrateur **avant** de fermer le secours (procédure dans
  `site/src/docs/installer/exploitation.md`). Un avertissement au démarrage signale l'absence
  d'administrateur actif.
- Chaque requête authentifiée lit son compte en base (deux requêtes brèves) : le
  prix d'une désactivation qui prend effet à la requête suivante, sans cache.
- Le MCP garde sa clé d'API ; l'OAuth du `/mcp` n'est pas traité ici.
