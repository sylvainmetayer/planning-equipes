# Gérer les comptes et les droits

Vous donnez accès à l'application à d'autres personnes (un responsable de stand, une personne des ressources humaines), vous contrôlez ce qu'elles voient, et vous retrouvez qui a fait quoi.

## Qui se connecte et comment

La connexion se fait au choix par :

- un **mot de passe** d'administrateur ;
- des **comptes nominatifs** invités par e-mail (lien à usage unique, code reçu par e-mail en seconde étape) ;
- la connexion déléguée au portail d'accès de l'hébergeur.

![Paramètres, onglet Comptes : la liste des comptes et de leurs droits](/images/fr/docs/comptes.webp "Les comptes de l'administration et leurs droits")

Les espaces animateurs restent joignables par leur lien : voir [L'espace des animateurs](espace-animateur.md). La mise en place de la connexion déléguée et des comptes de secours incombe à l'hébergeur : voir [Installer chez vous](../installer/index.md).

Les comptes se gèrent dans Paramètres, onglet *Comptes*.

## Donner accès à un responsable de stand

Le **responsable de stand** voit, en lecture seule, les horaires et les places à pourvoir de son stand : le planning **publié**, de l'édition active seulement. Il dispose d'un écran à lui (« Mon stand ») et d'une affichette à imprimer.

![L'écran Mon stand d'un responsable de stand](/images/fr/docs/responsable.webp "Ce que voit un responsable de stand : son stand, ses créneaux, ses places vides")

1. Ouvrez Paramètres, onglet *Comptes*.
2. Accordez le droit de responsable de stand au compte, pour une édition et pour les stands qu'il doit voir (stand par stand).
3. Réglez les noms : un réglage par défaut de l'édition et une exception par stand, chacun avec une date d'ouverture facultative. Tant que vous ne le décidez pas, les prénoms et noms ne s'affichent pas ; les places vides restent sans nom.

Il ne voit jamais de coordonnées ni d'âge.

## Donner accès à une personne des ressources humaines

Le droit **RH** donne accès au **rapport de conformité** d'une édition, et à rien d'autre. Il s'accorde dans Paramètres, onglet *Comptes*. Les administrateurs y ont accès aussi. Chaque consultation et chaque export est inscrit au journal.

![La page Conformité RH vue par un compte ressources humaines](/images/fr/docs/rh.webp "Ce que voit une personne des ressources humaines")

Un compte RH s'ouvre directement sur sa page *Conformité RH*, hors de l'administration ; un administrateur lit le même rapport dans *Diagnostic*, onglet *Conformité*. Il s'exporte en PDF ou en CSV. Pour l'inspection du travail, à partir du planning publié, il donne par personne :

- les heures de travail effectif par semaine, face au plafond qui s'applique (majeur ou mineur) ;
- les heures de dimanche, de jours fériés et de nuit ;
- le repos le plus court observé ;
- les écarts aux règles légales ;
- une section dédiée aux mineurs.

![Diagnostic, onglet Conformité : le rapport par personne](/images/fr/docs/conformite.webp "Le rapport de conformité, onglet Conformité du Diagnostic")

Il ne change aucune règle du planning. Les contacts d'urgence et représentants légaux saisis par les animateurs sont lisibles par les administrateurs et par le droit RH (voir [L'espace des animateurs](espace-animateur.md#contact-durgence-et-représentant-légal)).

## Lire l'historique des actions

L'**historique** (Paramètres, onglet *Historique*, derrière « Réglages avancés ») garde ce qui a été fait dans l'édition et par qui : chaque écriture, chaque export, chaque envoi, qu'il vienne d'un écran, d'un assistant ou d'une tâche de nuit, avec les champs qu'une modification a réellement changés.

![L'historique des actions, filtré par auteur](/images/fr/docs/historique-auteur.webp "L'historique filtré sur un auteur")

Quelques filtres :

- « Exports » : qui a sorti quel fichier et quand, planning téléchargé par un animateur depuis son espace compris, sur toute la durée conservée.
- « Données » : les modifications que le prochain calcul verra. Une période se choisit à la minute près, et l'onglet *Calculer* y mène sur exactement les modifications qu'il compte depuis la dernière résolution.
- « Règles » : les changements d'activation, de poids ou de niveau d'une règle, avec son nom, la date et l'auteur, sans la valeur. La page Règles du planning y renvoie depuis le panneau d'une règle.
- Les vérifications du besoin en animateurs y figurent avec leur équipe, leur durée et leur résultat, et un filtre les isole.

Aucun nom n'y est conservé : les identités sont retrouvées à l'affichage, et les lignes trop anciennes sortent d'elles-mêmes.

Un onglet *Connexions*, le même quelle que soit l'édition, liste chaque connexion à l'administration, chaque échec et chaque adresse verrouillée après trop d'échecs, avec l'heure et l'adresse, jamais ce qui a été saisi.
