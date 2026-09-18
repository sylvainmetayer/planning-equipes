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
n'étant gardée que si **deux** choses reviennent identiques — tous les segments
ouverts, effectif compris, et toutes les fenêtres d'ouverture que la résolution
produit. Les deux, parce que chacune seule laisse passer quelque chose : une
fermeture peut être la seule à fermer un jour qu'une règle d'ouverture couvre à
une heure qu'aucun créneau ne touche, et la retirer ne change aucun segment
tout en déclarant une ouverture que personne n'a écrite — un test de bout en
bout l'a attrapée ; et une fermeture qui creuse un trou dans une journée peut
faire toute la différence entre un jour à demi ouvert et un jour clos, sans
fenêtre d'ouverture d'un côté ni de l'autre. Deux limites de plus, et les deux
consistent à ne pas décider à la place de l'opérateur :

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
- **Rejoué de bout en bout** sur une copie de cette édition, par les vrais
  endpoints : duplication, puis simplification. 1 307 cases ouvertes avant et
  après, zéro écart, 3 330 sièges, zéro anomalie de part et d'autre.
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

## Complément ([0043](0043-consigne-d-edition-fermer-une-bande-sans-rien-detruire.md))

Une **quatrième couche** s'applique désormais au-dessus des trois : la
**consigne d'édition**, ce qu'une autorité impose à tout l'événement sur une
date — une bande horaire fermée pour tous les stands, et les fenêtres où
certains rouvrent en compensation. Elle n'est pas une règle du stand ni une
exception datée : elle vient d'en dehors du stand, s'applique à tous sans
qu'aucun l'ait déclarée, et se lève sans rien réécrire.

`StandService.resolve` est le point d'entrée : `HoraireStandResolver.apply`
pour les couches 1 à 3, puis `ConsigneResolver.apply` par-dessus, sur les
seules fenêtres effectives. L'invariant **un seul mode par jour** est
préservé, parce que la couche 4 ne mélange rien : elle repart de la journée
que les trois couches ont produite, lue en segments ouverts ; en retire la
bande, puis les créneaux que la consigne a ajoutés à la grille ; y ajoute les
fenêtres choisies pour ce stand, hors bande ; et **réénonce la journée
entière** — en ouvertures explicites (fermé par défaut) quand il reste
quelque chose, en fermeture de journée entière quand il ne reste rien. Rien
en aval — segments, contraintes, exports — n'a donc à connaître la consigne,
comme rien n'avait à connaître les règles.

La limite de la couche 1 — une exception *remplace* la journée — est
précisément ce qui rendait la consigne nécessaire : rouvrir 18 h-22 h par une
exception datée faisait ressaisir toute la journée du stand, sur tous les
stands, pour chaque jour d'alerte. La couche 4 se **soustrait et s'ajoute** à
ce que les trois premières disent, mais elle est la seule à le faire, et
seulement pour une décision qui n'appartient pas au stand.

Un test structurel, `ConsigneCoucheStructurelleTest`, tient ce que le nom du
point d'entrée promet : tout appelant de `HoraireStandResolver.apply` dans
le code de production est soit `StandService.resolve`, soit inscrit avec sa
raison dans une liste — compactage, élagage, dérivation de grille, cohérence,
scénario, et la journée nominale que la consigne elle-même calcule, tous
raisonnant sur ce qu'un stand *déclare*. Un appelant oublié doterait en
silence une bande qu'un arrêté a fermée ; c'est la même classe de défaut
qu'un prédicat d'édition oublié, et le même genre de filet.
