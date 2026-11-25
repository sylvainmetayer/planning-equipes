# Les outils de l'organisateur

Vous branchez l'application à vos autres outils, vous savez ce qui est sauvegardé, vous retrouvez l'aide et les pages légales, et vous travaillez plus vite au clavier.

## Brancher un assistant IA

Un assistant IA peut consulter et piloter l'application en langage naturel, sans jamais voir les données personnelles des animateurs. Le branchement (clés, outils exposés, confidentialité) est décrit dans [Le serveur MCP](../comprendre/mcp.md). Les clés se gèrent dans Paramètres, onglet *MCP*.

## Prévenir un autre outil de l'équipe (webhooks)

L'application peut annoncer ce qui se passe à l'outil où l'équipe travaille : Slack, Discord, Matrix, Telegram, ou un outil d'automatisation comme n8n, par un message signé. Les événements :

- planning publié ;
- demande d'échange à trancher ;
- échanges qui dorment ;
- disponibilités déclarées ;
- résolution terminée ;
- sauvegarde en échec ;
- alerte météo.

Les messages portent des comptes, jamais un nom.

1. Ouvrez Paramètres, onglet *Instance*.
2. Créez le webhook (adresse et format).
3. Testez-le d'un bouton.
4. Suivez-le dans son journal de livraisons.

Un récepteur en panne est relancé plusieurs fois et ne retarde jamais l'opération qu'il annonce. L'application refuse d'appeler une adresse interne.

## Savoir si la sauvegarde de nuit a réussi

Chaque nuit, toute la base est copiée sur le disque de l'hébergeur, et seules les dernières copies sont gardées. Paramètres, onglet *Instance*, dit où elles vont, lesquelles existent et si la dernière nuit s'est bien passée.

- Une nuit en échec prévient l'administrateur par e-mail : la raison, la dernière copie réussie, le nombre de nuits d'affilée.
- Un second message annonce la première nuit qui réussit de nouveau.
- La restauration est une opération de l'exploitant sur la base : voir [Installer chez vous](../installer/index.md).

## Retrouver le mode d'emploi dans l'application

- **Aide** : le mode d'emploi complet de l'application, avec une recherche, consultable pendant qu'une résolution tourne ou sur une édition vide (icône « ? » de la barre d'outils).
- **Nouveautés** : ce que la version installée a apporté, et depuis quelle version. Les évolutions sont regroupées par version ; ce qui n'est pas encore publié apparaît sous « À venir », et la liste est triée comme les notes de version, « À surveiller » d'abord. Rien n'est à tenir à jour à la main.

## Travailler plus vite

- **Palette de commandes** : Ctrl+K ouvre une zone de saisie unique qui mène à n'importe quel écran (ou directement à l'un de ses onglets ou de ses vues) et retrouve un animateur, un stand ou un créneau. « g » suivi d'une lettre va droit à un écran, « / » saisit le filtre de la page et « ? » liste les raccourcis. Les raccourcis à une touche se coupent.
- **Tableaux au clavier** : voir [Travailler dans les tableaux](preparer-edition.md#travailler-dans-les-tableaux).
- **Thème clair ou sombre** : un bouton de la barre d'outils fait tourner l'affichage entre « automatique », « clair » et « sombre ». « Automatique » suit le réglage du système d'exploitation, en direct. Un choix explicite est retenu par le navigateur et survit au rechargement.

## Les pages publiques et leur contenu

Quatre pages sont lisibles sans être connecté, et sans lien valide.

| Page | Ce qu'elle dit |
| --- | --- |
| Mentions légales | Éditeur, hébergeur, contact, propriété intellectuelle |
| Politique de confidentialité | Quelles données, pourquoi, combien de temps, qui y accède (y compris les outils de mesure d'audience et de suivi d'erreurs) et comment exercer ses droits. Elle s'adresse explicitement aux animateurs mineurs |
| Conditions d'utilisation | La ligne de partage, écrite noir sur blanc : l'application calcule des propositions, l'organisation décide. Elle reste l'employeur, le responsable des données et du respect de la réglementation ; le logiciel est fourni en l'état |
| Déclaration d'accessibilité | Liée depuis chaque écran : l'état de conformité au RGAA que l'organisation déclare, les contenus non accessibles, où signaler un défaut. Vide, elle le dit plutôt que d'annoncer une conformité |

Les écrans se parcourent au clavier, déplacer une affectation compris.

Ces pages sont à renseigner par l'hébergeur de l'instance : voir [Installer chez vous](../installer/index.md).
