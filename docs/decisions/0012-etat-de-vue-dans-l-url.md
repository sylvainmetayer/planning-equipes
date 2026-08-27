# 0012 — L'état de vue vit dans l'URL

- **Statut** : accepté, implémenté
- **Date** : août 2026
- **Portée** : frontend

## Contexte

Chaque écran de consultation porte un petit état qui n'est pas de la donnée :
la vue choisie sur la heatmap, la colonne de tri d'un tableau d'heures, le
filtre rapide d'une liste d'animateurs. Cet état ne vivait que dans les signaux
du composant. Une actualisation de la page — un F5, un retour depuis un autre
onglet, un redéploiement — le ramenait à zéro, et l'organisateur qui triait
cent cinquante lignes recommençait.

Le calendrier mensuel et la timeline avaient déjà résolu le problème chacun de
leur côté, en écrivant leur état dans les paramètres de l'URL. La question
posée ici est celle du troisième écran : reproduire, ou choisir autre chose et
migrer les deux premiers.

## Options envisagées

**`localStorage`.** Le plus court à écrire, et le précédent existe : la langue
de l'interface y est déjà. Mais une vue devient invisible depuis un autre poste,
ne se transmet pas, et le contenu stocké doit être versionné : il survit aux
déploiements, donc à la disparition de la colonne ou de l'écran qu'il nomme.

**Côté serveur.** C'est le choix fait pour les réglages du solveur, et pour une
bonne raison : ce sont des paramètres de calcul, la même valeur doit être vue
depuis n'importe quel navigateur. Le transposer à une vue suppose une table, une
API et un propriétaire — donc une notion d'utilisateur que l'application n'a pas.
On paierait un schéma pour une préférence d'affichage.

**L'URL.** Une vue *est* déjà une adresse. Elle se copie dans un message, se met
en favori, se restaure au rechargement, et ne demande ni schéma, ni stockage, ni
migration : un paramètre devenu obsolète est ignoré à la lecture.

## Décision

**L'état de vue est porté par les paramètres de l'URL.** Quatre règles, tenues
par `core/view-query-params.ts` :

1. **La valeur par défaut est l'absence du paramètre.** Un tableau non trié, un
   filtre vide, la vue d'ouverture n'écrivent rien — l'URL nue reste l'URL nue.
2. **La lecture est tolérante.** Une valeur qu'un écran ne connaît pas est
   ignorée au profit du défaut, jamais appliquée ni signalée : une adresse mise
   en favori doit survivre à la colonne qu'elle nommait.
3. **L'écriture remplace l'entrée d'historique** (`replaceUrl`). Trier, filtrer
   puis retrier est une exploration, pas trois pages : le bouton Retour doit
   quitter l'écran, pas rejouer les gestes.
4. **Une vue se remet à zéro en une action**, puisque revenir à l'état nu en
   effaçant les paramètres à la main n'est pas une action offerte à l'utilisateur.

La frontière avec les deux autres emplacements se lit ainsi : **ce que l'écran
montre va dans l'URL** ; ce que la personne préfère partout (la langue) reste
dans le navigateur ; ce que le système calcule (les réglages du solveur) reste
sur le serveur.

## Conséquences

Les **vues nommées** — « ma vue du samedi », rappelée par son nom — ne sont pas
offertes. Le favori du navigateur en tient lieu, avec son propre nom et sans
rien à administrer. Si le besoin d'une bibliothèque de vues partagées se
confirme un jour, il rouvrira la question du stockage serveur, et la présente
décision devra être révisée plutôt que contournée.

Un écran qui gagne un état de vue gagne aussi le test qui prouve qu'une URL
périmée ou trafiquée le laisse fonctionner. C'est la contrepartie directe de la
règle 2 : sans ce test, la tolérance n'est qu'une intention.

Enfin, l'état de vue devient **public par construction** : il part dans un lien
collé, un signet, un journal de serveur. Les paramètres ne portent donc que ce
qui est déjà à l'écran de celui qui reçoit le lien — un identifiant de ligne, une
recherche — et rien qu'une personne n'aurait pas déjà le droit de voir.
