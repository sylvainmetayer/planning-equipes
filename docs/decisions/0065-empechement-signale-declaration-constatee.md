# 0065 — Un empêchement signalé depuis l'espace est une déclaration constatée, pas une écriture

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : domaine, schéma (migration `V111`), espace animateur, écran du jour, notifications
- **Voisine de** : [0047](0047-differer-le-message-d-une-personne-sans-la-perdre-de-vue.md) (rien ne se perd, rien ne s'applique seul)

## Contexte

Une fois la collecte des disponibilités et la foire fermées, l'espace d'un
animateur n'avait plus aucun geste pour dire « je ne pourrai pas être là ».
L'aide le renvoyait à une collecte fermée, et chaque empêchement finissait en
appel téléphonique à l'organisation — la première source d'appels pendant
l'événement. L'écran du jour savait déjà marquer une absence et chercher un
remplaçant ; il manquait la file qui y amène l'information.

## Options envisagées

| Option | Ce qu'elle coûte |
| --- | --- |
| **A.** Le signalement écrit l'indisponibilité et libère les sièges | Un lien public — l'espace — écrirait dans le planning sans que personne le lise. Un faux clic, ou un jeton qui a fuité, viderait des sièges la veille au soir sans qu'aucun humain ne l'ait décidé |
| **B.** Déclaration constatée : le signalement attend l'organisation | Une table, deux gestes d'administration (« Marquer absent et remplacer », « Classer ») et une annulation côté animateur |
| **C.** Un simple courriel à l'organisation | Rien ne se relit ni ne se suit : l'animateur ne sait pas si c'est arrivé, l'organisation n'a pas de file et oublie |

## Décision

**Option B.** Le signalement est une **information**, jamais une écriture du
référentiel ni du planning.

- Portée : **toute la journée ou un poste**, désigné par sa clé naturelle
  (créneau et stand) — jamais l'identifiant du siège, qu'une résolution
  renumérote.
- **Absent seulement** : pas de retard, pas d'arrivée tardive. Un retard se
  règle au téléphone ; l'ajouter ferait d'un signal simple un formulaire.
- Motif **facultatif, en liste fermée** — raison personnelle, transport, autre.
  **Aucun texte libre** : c'est le champ où l'on écrit une raison de santé que
  personne n'a demandée (`docs/rgpd.md`).
- L'organisation est **prévenue tout de suite**, sans attendre l'ouverture d'un
  écran, par `service/notification/` — donc au mieux : un serveur de messagerie
  en panne ne refuse pas le signalement.
- L'organisation **constate** (« Marquer absent et remplacer » : le geste du jour
  J, sur la journée ou sur le seul créneau du poste) ou **classe**. Tant que rien
  n'est décidé, l'animateur **annule** le sien.
- Bornes calquées sur la déclaration de disponibilités : une journée de
  l'événement où il tient un poste **dans le planning communiqué**, pas encore
  terminée ; **un seul signalement ouvert** par personne et par objet (index
  unique partiel) ; un **plafond d'envois** par fenêtre, `429` + `Retry-After`.

## Conséquences

- La foire et la collecte fermées ne ferment pas ce geste : c'est le cas
  principal.
- Un signalement ne s'applique jamais seul. Un empêchement qu'on ne constate pas
  reste dans la file de l'écran du jour, de ce jour-là jusqu'à la date qu'il
  vise.
- Le remplacement automatique reste hors périmètre : le remplaçant se choisit
  sur l'écran du jour, avec les suggestions existantes.
