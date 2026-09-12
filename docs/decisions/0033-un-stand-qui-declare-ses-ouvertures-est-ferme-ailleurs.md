# 0033 — Un stand qui déclare ses ouvertures est fermé les jours qu'il ne déclare pas

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : résolution des horaires de stand, compactage, grille des
  ouvertures, import de scénario

## Contexte

Un stand énonce ses horaires en règles récurrentes portant un mode
(`OUVERTURE` ou `FERMETURE`) et un sélecteur de jours de spécificité
croissante. La résolution, jour calendaire par jour calendaire, prenait la
fenêtre datée si elle existait, sinon les règles les plus spécifiques couvrant
ce jour, **sinon rien : ouvert toute la journée** (voir
[`domaine.md`](../domaine.md)).

Cette troisième couche rendait « ouvert ces douze dates, et rien d'autre »
inexprimable en une règle. Il en fallait une seconde, dont le seul rôle était
de fermer les jours que la première ne nommait pas. Et comme une fermeture
porte des fenêtres horaires, pas des journées, elle s'écrivait comme une
fermeture partant d'une heure assez tôt pour couvrir tous les créneaux : sur
l'édition qui a servi de mesure, `09:00` jusqu'à la fermeture, **tous les
jours**, portée par soixante-cinq stands — l'un des deux tiers de leurs règles.

Deux défauts, pas un :

- **du remplissage** : une déclaration sur deux ne disait rien de l'horaire,
  seulement que le reste n'en était pas un ;
- **un piège** : l'heure `09:00` n'a de sens que tant qu'aucun créneau ne
  commence avant. Un créneau ajouté à `08:00` — une matinée de montage, une
  ouverture anticipée — rouvrait silencieusement les soixante-cinq stands, un
  dimanche de démontage compris.

## Options envisagées

**(A) Un drapeau « fermé par défaut » sur le stand.** Écarté : un troisième
état à saisir, à expliquer et à faire voyager dans l'import, l'export, le MCP
et la duplication d'édition, pour une information que les règles portent déjà.

**(B) Fermer tout jour que rien ne couvre.** Écarté : cela retourne le défaut
historique pour tout le monde. Un stand qui dit seulement « fermé le 14
juillet » décrit des exceptions à un stand ouvert ; le fermer les onze autres
jours lui fait dire le contraire de ce qui est écrit.

**(C) Fermer selon ce que le stand déclare.** Retenu. Un stand dont au moins
une règle **ouvre** décrit un horaire : les jours qu'il ne nomme pas sont des
jours où il n'ouvre pas. Un stand dont les règles ne font que **fermer**
décrit des exceptions : le défaut historique tient.

## Décision

La couche 3 se lit désormais selon le mode des règles du stand, comme en (C).
`HoraireStandResolver.declaresOpenings` porte ce test à un seul endroit, et la
résolution produit pour un jour non déclaré une fermeture de journée entière —
un seul mode par jour, l'invariant est intact et rien en aval ne change.

Les fermetures que cette lecture rend muettes sont retirées par
`HoraireElagage`, appelé après chaque compactage : chaque fermeture est ôtée à
tour de rôle et le stand re-résolu contre ses propres créneaux, la suppression
n'étant gardée que si **tous** les segments ouverts reviennent identiques,
effectif compris. Deux limites, et les deux consistent à ne pas décider à la
place de l'opérateur :

- **seules des fermetures partent.** Une ouverture n'est jamais retirée, même
  sans effet sur la grille du jour : « ouvert 08:00-09:00 » sur une grille qui
  commence à 10 h est un énoncé qui attend un créneau, pas du poids mort ;
- **seulement sur un stand qui déclare des ouvertures.** Ailleurs, chaque
  fermeture redevient porteuse dès qu'un créneau se déplace dedans.

Le compactage, lui, gagne l'obligation inverse : une journée dont un stand n'a
jamais rien dit était ouverte toute la journée, et factoriser les autres en
règle la fermerait. Il l'énonce donc explicitement avant de compacter, et reste
la réécriture à sens constant qu'il prétend être.

## Conséquences

- **Mesuré sur une édition réelle** (65 stands, 62 créneaux, 157 règles, 27
  exceptions datées) : 157 règles deviennent 104, sur 53 stands, **empreinte
  d'ouvertures identique** — 1 307 cases ouvertes, 3 330 sièges, à la minute et
  au siège près. `OuverturesEditionReelleTest` gèle cette empreinte.
- **Rejoué sur les seize éditions d'une base de travail** : aucune case
  d'ouverture ne bouge, et les règles baissent de 34 à 45 % partout.
- **Le piège disparaît** : un créneau ajouté tôt le matin ne rouvre plus rien,
  puisque plus rien ne dépend de l'heure choisie pour fermer.
- **Une donnée peut changer de sens** : un stand qui n'ouvre que le week-end
  par une règle `JOURS_SEMAINE` était ouvert toute la journée le mercredi ; il
  y est maintenant fermé. C'est l'intention lue, et la mesure ci-dessus dit que
  personne n'en dépendait — mais c'est une rupture, et elle est ici.
- **L'import de scénario en hérite sans un mot** : un fichier qui déclare des
  ouvertures dit désormais ce qu'il a l'air de dire.
