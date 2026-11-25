# 0094 — Comptes locaux : mot de passe haché, lien à usage unique, code reçu par mail

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : authentification de l'administration, comptes
- **Prolonge** : [0093](0093-connexion-par-un-fournisseur-oidc-et-secours-ferme.md) (les comptes et la porte de secours)

## Contexte

Une instance sans fournisseur d'identité n'avait qu'une porte : le compte `admin`
de la configuration, un mot de passe partagé sans nom. Les comptes nominatifs
n'y servaient à rien. Beaucoup d'organisations n'ont pas de fournisseur OIDC et
veulent pourtant que chaque organisateur se connecte sous son nom.

## Décision

1. **Un compte local est un compte comme un autre** : même table `compte`, mêmes
   droits, même désactivation. Il se connecte par adresse et mot de passe. Les
   comptes locaux **restent ouverts derrière un fournisseur OIDC** : seul le
   secours de la configuration (ADR 0093) est fermé par défaut, parce que c'est
   lui, le mot de passe partagé et anonyme, qui est dangereux — pas un mot de
   passe qui appartient à une personne et que son propriétaire a choisi.
2. **Personne d'autre que son propriétaire ne connaît un mot de passe.** Un
   administrateur ajoute un compte : celui-ci reçoit par mail un **lien à usage
   unique valable 72 h** pour choisir le sien. Le mot de passe oublié et la
   réinitialisation par un administrateur sont le même mécanisme, avec un lien
   valable **une heure**. L'administrateur déclenche l'envoi et ne voit jamais le
   lien.
3. **Rien d'utilisable n'est stocké.** Le mot de passe est un hachage bcrypt
   (déjà fourni par Quarkus, aucune dépendance de plus), coût 12. Le lien et le
   défi de connexion sont des SHA-256 de valeurs aléatoires de 256 bits. Un lien
   se consomme en supprimant sa ligne ; il en existe un seul par compte, le
   suivant remplace le précédent.
4. **Politique : douze caractères au moins, et rien d'autre.** Pas de chiffre ni
   de majuscule exigés, qui ne produisent que `Motdepasse1!`. Le plafond est celui
   de bcrypt (72 octets), refusé plutôt que tronqué en silence.
5. **Seconde étape par défaut : un code à six chiffres reçu par mail**, comme
   l'espace animateur (dix minutes, cinq essais, un seul à la fois par compte,
   même limiteur de demandes). L'opérateur la coupe par `AUTH_LOCAL_SECONDE_ETAPE=false`
   (pas de messagerie, ou un proxy d'accès qui l'impose déjà).
6. **Deux temps, une seule session.** Le mot de passe ne s'échange pas contre une
   session mais contre un **défi** à usage unique (`POST /api/auth/connexion`),
   que le formulaire de Quarkus échange, avec le code, contre le cookie
   (`/api/auth/connexion/j_security_check`). On garde ainsi, sans les réécrire,
   le verrou par adresse, le journal des connexions et le cookie chiffré du
   formulaire du compte `admin`. Le chemin propre au compte local est ce qui
   permet de fermer le secours sans fermer les comptes.
7. **Ce qui se répond identiquement.** Une adresse inconnue, un mauvais mot de
   passe, un compte désactivé et un compte sans mot de passe donnent la même
   réponse, après le même calcul bcrypt. « Mot de passe oublié » répond `204`
   que l'adresse existe ou non ; un envoi qui échoue est journalisé par
   identifiant de compte, jamais par adresse.

## Conséquences

- Une session locale est relue à chaque requête : désactiver un compte coupe sa
  session au coup suivant, sans attendre l'expiration du cookie.
- Tant que le secours est fermé, `admin` saisi sur le chemin des comptes locaux
  ouvre bien une session, mais **sans aucun rôle** (l'augmenteur d'identité la
  dépouille) : le chemin n'est pas une porte dérobée.
- Le jeton rejoint l'adresse du lien dans le **fragment** (`/mot-de-passe#…`),
  qu'aucun serveur, journal de proxy ou en-tête `Referer` ne voit.
- Un mot de passe perdu ne se récupère pas : il se remplace. Rien ne le permet
  de façon différente, volontairement.
