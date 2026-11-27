---
titre: Installer chez vous
pour: Installez, sécurisez, sauvegardez et mettez à jour votre instance.
docs:
  - installation
  - mentions-legales
  - courriels
  - comptes-locaux
  - oidc
  - keycloak
  - securite
  - securite-navigateur
  - securite-acces-administration
  - securite-roles-et-droits
  - securite-jetons-publics
  - securite-limites-de-debit
  - securite-appels-sortants
  - securite-analyses-automatiques
  - sauvegarde-et-restauration
  - envois-automatiques
  - calcul-du-planning
  - calcul-sur-une-autre-machine
  - instance-de-demo
  - appels-sortants
  - surveillance
  - observabilite
  - exploitation
  - mettre-a-jour
  - versioning
  - publier-une-version
  - passer-a-la-4-0
  - conservation-des-donnees
  - rgpd
  - rgpd-sous-traitance
  - rgpd-limites-a-consigner
  - rgpd-atteinte-aux-donnees
  - accessibilite
  - variables-environnement
  - licences-tierces
---
# Installer chez vous

Vous hébergez Planning Équipes pour un organisateur, ou pour vous-même : une application et sa base PostgreSQL, derrière votre reverse proxy. Ces guides vous mènent de la première mise en ligne à la tenue de l'instance pendant l'événement, dans l'ordre où les questions se posent.

## Je veux mettre l'application en ligne

1. [Installer l'application sur votre serveur](installation.md) : la pile de production, les sept variables obligatoires, le contrôle de fin de déploiement.
2. [Renseigner les mentions légales](mentions-legales.md) : les cinq informations sans lesquelles une instance publique refuse de démarrer.
3. [Faire arriver vos e-mails](courriels.md) : relais SMTP, SPF, DKIM, DMARC, et le suivi des envois.
4. [Exposer l'instance sur Internet](securite.md) : ce que le reverse proxy doit faire, et le durcissement de la pile.

## Je veux ouvrir l'administration à mon équipe

- [Créer les comptes de l'administration](comptes-locaux.md) : mot de passe, code par e-mail, compte de secours.
- [Brancher votre annuaire d'entreprise](oidc.md) : connexion par un fournisseur OIDC.
- [Brancher Keycloak](keycloak.md) : le realm, le client, l'assistant IA en OAuth et l'ordre de bascule.
- [Savoir ce que chaque rôle peut appeler](securite-roles-et-droits.md) : administrateur, responsable de stand, ressources humaines, animateur.

## Je veux ne jamais perdre les données

- [Sauvegarder et restaurer](sauvegarde-et-restauration.md) : la sauvegarde de nuit, la sauvegarde à la main, la restauration guidée.
- [Surveiller l'instance](surveillance.md) : sondes, métriques, journaux.
- [Brancher le suivi d'erreurs et les métriques](observabilite.md).

## Je veux régler ce que l'application fait seule

- [Régler les envois automatiques](envois-automatiques.md) : rappel de la veille, relances, alertes.
- [Régler le calcul du planning](calcul-du-planning.md) et [Résoudre sur une autre machine](calcul-sur-une-autre-machine.md).
- [Héberger une instance de démonstration](instance-de-demo.md) : données fictives, remise à zéro toutes les trente minutes.
- [Autoriser les webhooks et l'alerte météo](appels-sortants.md).

## Je veux mettre à jour ou publier

- [Mettre à jour vers une nouvelle version](mettre-a-jour.md), et [ce que promet un numéro de version](versioning.md).
- [Publier une version et patcher la précédente](publier-une-version.md), pour qui fabrique l'image.
- [Passer de la 3.x à la 4.0](passer-a-la-4-0.md) : ce que la 4.0 casse et ce que vous faites en montant.

## Je dois tenir mes obligations

- [Purger et conserver les données](conservation-des-donnees.md) : la purge annuelle.
- [Tenir le registre RGPD de votre instance](rgpd.md), avec la [convention de sous-traitance](rgpd-sous-traitance.md).
- [Publier votre déclaration d'accessibilité](accessibilite.md).

## Je cherche un réglage précis

- [Retrouver une variable d'environnement](variables-environnement.md) : toutes les variables, leur défaut et leur effet.
- [Tenir l'instance pendant l'événement](exploitation.md) : la vérification de la semaine avant, et où regarder un samedi qui coince.
- [Les licences tierces](licences-tierces.md) : ce que l'image redistribue.
