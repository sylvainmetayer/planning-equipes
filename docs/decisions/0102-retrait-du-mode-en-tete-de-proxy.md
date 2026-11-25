# 0102 — Retrait du mode en-tête de proxy

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : authentification de l'administration et de l'espace animateur
- **Remplace en partie** : [0093](0093-connexion-par-un-fournisseur-oidc-et-secours-ferme.md) (le point 5, qui ne faisait que déprécier ce mode)

## Contexte

Le mode en-tête de proxy (`REMOTE_USER_*`) laissait un proxy d'accès
authentifier à la place de l'application : une adresse dans un en-tête, un
secret partagé dans un second, et l'administrateur ou l'animateur était reconnu
sans formulaire ni code. Il datait d'avant les comptes nominatifs. Depuis la
connexion par un fournisseur OIDC et les comptes locaux, il double une porte
qui a maintenant un nom dans le journal, un second facteur et un verrou, et il
reste le seul chemin où une identité naît d'une affirmation du client que seul
un secret partagé rend crédible : une origine joignable sans passer par le
proxy suffit à tout ouvrir si ce secret fuit.

## Décision

Le mode est retiré en 4.0 : plus de mécanisme d'authentification par en-tête,
plus d'ouverture de l'espace animateur sans code sur l'attestation d'un proxy.
Les cinq variables `REMOTE_USER_*` sont lues au démarrage pour être nommées dans
un avertissement (« n'a plus aucun effet ») ; l'application démarre quand même,
parce qu'une montée de version ne doit pas échouer sur une variable inerte.
L'avertissement disparaît en 5.0.

L'opérateur derrière un proxy d'accès garde son proxy comme filtre réseau ;
c'est l'application qui authentifie, par OIDC ou par un compte local.

## Alternatives écartées

- **Le garder déprécié** : chaque version porterait un chemin d'authentification
  que plus rien ne teste de bout en bout contre les comptes.
- **Refuser de démarrer si une variable est renseignée** : une variable oubliée
  dans un fichier d'environnement ne justifie pas une instance à l'arrêt.

## Conséquences

- Une instance qui s'appuyait sur ce mode perd l'accès tant qu'elle n'a pas
  configuré OIDC ou créé un compte : le guide de passage à la 4.0 le dit en
  premier.
- Le mécanisme n'existe plus : `CompteIdentityAugmentor` n'a plus d'exception
  pour une identité qui ne serait pas une session de formulaire.
