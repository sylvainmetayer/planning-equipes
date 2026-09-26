# 0054 — « Placer » sur un siège vide écrit le siège, puis le verrou

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : API (`POST /api/postes/{id}/placement`), panneau Siège de la Journée
- **Voisine de** : [0044](0044-le-passe-est-fige.md) (le passé est figé), [0046](0046-un-placement-intenable-est-dit-avant-le-calcul.md) (un placement intenable est dit, et refusé à l'écriture)

## Contexte

Un siège vide n'avait aucun geste : le banc de touche disait qui aurait pu le
tenir, en lecture seule, et renvoyait vers un assistant de réparation qui
n'existe que sur un siège tenu. Boucher un trou demandait un ajustement manuel
puis une nouvelle résolution, ou le mode jour J le jour même.

Le panneau Siège de la Journée donne au siège vide « Qui peut tenir ce
siège ? » et, sur chaque personne disponible, « Placer ». Deux questions
restaient : par où passe l'écriture, et comment la prochaine résolution la
traite.

## Options envisagées

| Option | Ce qu'elle coûte |
| --- | --- |
| **A.** Étendre le déplacement (`/deplacement?animateur=`) à un siège de départ vide | Son verdict est celui du planning entier : remplir un siège vide rend un point dur (`posteDoitEtrePourvu`) et peut en dépenser un autre, si bien qu'un mineur posé sur une nuit laisse le total inchangé et passerait |
| **B.** Réutiliser `/affectation?animateurId=` tel quel | Les bons contrôles, mais aucune précondition : un panneau ouvert sur un plan plus ancien délogerait sans un mot la personne placée entre-temps ; `204`, rien pour dire ce que le geste coûte en qualité |
| **C.** Un point `placement` sur le service de `/affectation` et d'`affecter_poste`, avec les gardes du déplacement | Un endpoint de plus |
| **D.** Placer et verrouiller dans le même appel | Un verrou posé par un endpoint de siège : deux écritures sous une seule ligne de journal, et un paramètre qui double `POST /api/verrouillages` |

Pour le verrou : une affectation forcée (ajustement manuel) plutôt qu'un
verrou aurait survécu à tout, y compris à une régénération des créneaux, mais
aurait fait d'un geste de correction une règle dure permanente — exactement ce
que les ajustements servent à dire, et que le panneau propose à part (« Poser un
ajustement »).

## Décision

**Option C**, verrou posé par l'écran.

- `POST /api/postes/{id}/placement?animateur=A` passe par les contrôles de
  l'écriture directe (`PlanningWhatIf.placeOnFreeSeat`, le service
  d'`affecter_poste`) : résolution en cours, créneau commencé, siège
  verrouillé, règle dure lue sur le planning entier **et** sur le siège. Il y
  ajoute les deux gardes du déplacement : le siège doit être encore vide
  (`409`), et la personne ne doit pas être sous un verrou qui lui interdit un
  nouveau siège. Il répond comme un déplacement, scores compris, pour que
  l'écran dise quand le placement coûte en qualité.
- « La garder au prochain calcul », **cochée par défaut**, pose ensuite un
  verrou `ANIMATEUR_CRENEAU` — cette personne sur ce créneau — par
  `POST /api/verrouillages`. C'est le verrou que pose déjà un échange accepté :
  la résolution suivante fige le siège que la personne tient sur ce créneau.
  « Déverrouiller » le lève depuis le même panneau.
- Deux appels, deux lignes de journal (poste attribué, verrou posé). Un verrou
  refusé laisse le placement en place et le panneau le dit ; rien n'est
  défait.
- « Encore vide » est tenu par l'`UPDATE` lui-même (`animateur_id IS NULL`
  dans sa clause), pas seulement par la lecture qui le précède : entre les
  deux, quelqu'un peut avoir été assis. « Libérer » et « Remplacer ›
  Appliquer » gardent `/affectation`, avec une précondition facultative
  `occupant` — la personne affichée — tenue de la même façon ; sans elle,
  l'endpoint écrit comme avant.
- « Libérer » pose le verrou symétrique, case « La tenir à l'écart de ce
  créneau au prochain calcul » cochée par défaut : un `ANIMATEUR_CRENEAU` sur
  la personne libérée, celui qu'un échange accepté pose sur qui il libère.
  Sans lui, « Corriger le reste » remplissait le trou avec la personne qu'on
  venait d'en retirer.

## Conséquences

- Un placement fait à la main survit à la prochaine résolution tant que la
  case reste cochée ; décochée, il n'est qu'un point de départ que le solveur
  peut défaire.
- Le panneau ne lève que ce verrou-là. Un verrou de stand, de créneau, de
  journée ou de personne tient plus que le siège montré : il se lève sur
  l'écran Verrouillages, où toute sa portée est visible.
