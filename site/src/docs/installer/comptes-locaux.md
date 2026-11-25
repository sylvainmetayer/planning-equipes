# Créer les comptes de l'administration

Vous donnez à chaque administrateur son compte nominatif, protégé par un mot de passe et un code reçu par e-mail, et vous gardez une porte de secours. Les comptes locaux fonctionnent avec ou sans fournisseur d'identité ; pour brancher un annuaire, voir [la connexion par fournisseur d'identité](oidc.md).

## Ajouter un compte

1. Ouvrez *Paramètres › Comptes* et ajoutez le compte, en laissant cochée l'invitation.
2. La personne reçoit un lien à usage unique, valable 72 heures, pour choisir son mot de passe (douze caractères au moins).

**L'application doit donc pouvoir envoyer du mail** (`MAIL_*`, et `PUBLIC_URL` pour écrire le lien : sans `PUBLIC_URL` joignable, le lien pointe vers `localhost`). Voir [Faire arriver vos e-mails](courriels.md).

« Réinitialiser le mot de passe » (même onglet) et « Mot de passe oublié » (page de connexion) envoient un lien valable une heure. L'administrateur ne voit jamais le lien ni le mot de passe.

## La seconde étape de connexion

À la connexion, un **code à six chiffres reçu par mail** suit le mot de passe (dix minutes, cinq essais). Il est actif par défaut. `AUTH_LOCAL_SECONDE_ETAPE=false` le coupe : réservez-le à une instance sans messagerie, ou déjà derrière un proxy d'accès qui impose son propre second facteur.

Les comptes locaux restent ouverts derrière un fournisseur OIDC. Chaque connexion, réussie ou non, est dans *Paramètres › Connexions* (adresse IP et heure, jamais ce qui a été saisi).

## Ne jamais s'enfermer dehors

Derrière un fournisseur OIDC, le compte `admin` de la configuration est **fermé par défaut**. Avant de brancher le fournisseur, créez votre propre compte administrateur et vérifiez qu'il ouvre l'administration : sinon, le jour où le fournisseur ne répond plus, il ne reste que la base. La procédure complète (préparation, réouverture par `ADMIN_LOCAL_SECOURS=true`, relecture du journal des connexions) et son raisonnement sont dans [le compte de secours](securite-acces-administration.md#le-compte-de-secours--ne-jamais-senfermer-dehors).

Mot de passe perdu par le dernier administrateur : même procédure.
