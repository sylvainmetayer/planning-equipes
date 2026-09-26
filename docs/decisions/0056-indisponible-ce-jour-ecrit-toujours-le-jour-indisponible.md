# 0056 — « Indisponible ce jour » écrit toujours le jour indisponible, et libère ses sièges

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : API (`PUT`/`DELETE /api/animateurs/{id}/jours-indisponibles/{date}`), fiche animateur
- **Voisine de** : [0044](0044-le-passe-est-fige.md) (le passé est figé), [0054](0054-placer-sur-un-siege-vide-ecrit-le-siege-puis-le-verrou.md) (« Placer » sur un siège vide)

## Contexte

Dire qu'une personne ne viendra pas un jour coûtait quatre clics et deux
saisies dans le formulaire de la page Animateurs, et rien ne reliait l'absence
aux sièges qu'elle laisse : le planning enregistré la plaçait toujours ce
jour-là jusqu'à la résolution suivante. Trois notions coexistent pour la dire —
le jour indisponible de la fiche, la déclaration de disponibilités de l'espace,
l'indisponibilité forcée que pose le mode jour J —, et la fiche devait choisir
laquelle écrire depuis un clic sur la frise des jours.

## Options envisagées

| Option | Ce qu'elle coûte |
| --- | --- |
| **A.** Le jour indisponible avant la résolution, une indisponibilité forcée (et les sièges libérés) après | Deux effets pour un même geste selon un état que l'écran ne montre pas ; une exception ad hoc par créneau du jour, à nettoyer ensuite, et une absence qui disparaît de la frise dès qu'on la relit sur une autre fiche |
| **B.** Toujours le jour indisponible, rien d'autre | Le planning enregistré reste faux jusqu'au prochain calcul : la personne absente tient encore ses sièges, et personne ne cherche à les reprendre |
| **C.** Toujours le jour indisponible, et ses sièges de ce jour libérés dans le même appel | Un point d'API qui écrit à la fois la fiche et le plan |
| **C′.** Comme C, mais un siège verrouillé refuse tout le geste, comme l'absence du mode jour J | La personne reste disponible un jour où elle ne viendra pas, pour protéger un siège qu'on pouvait laisser en place |

## Décision

**Option C.**

- Le jour s'écrit dans `joursIndisponibles`, avant l'événement comme pendant :
  c'est la notion que lisent le solveur, la collecte des disponibilités et tous
  les écrans. Le mode jour J garde son geste, l'indisponibilité forcée « à
  partir de maintenant », pour l'absence qui commence au milieu d'une vacation.
- Si le planning enregistré place la personne ce jour-là, ses sièges de ce jour
  sont libérés dans le même appel, par l'écriture qu'emploient déjà la
  réparation et le mode jour J. Un siège déjà commencé reste tel quel (0044) ;
  une résolution en cours refuse le geste.
- **Le jour s'écrit toujours, même sous un verrou.** Un siège tenu sous un
  verrou (celui du siège, du créneau ou de tout son planning) n'est pas
  libéré : il reste en place, et seuls les autres sièges du jour le sont. Un
  verrou dit « celui-là ne bouge pas », et seul celui qui le lève en décide
  autrement ; mais refuser le geste entier, comme le fait l'absence du mode
  jour J, laissait la personne disponible un jour où elle ne viendra pas —
  l'information la plus sûre du geste perdue pour protéger un siège qu'on
  pouvait garder. Tant que le verrou tient, le plan place quelqu'un un jour
  où il est indisponible, violation dure que la prochaine résolution ne peut
  pas lever : la fiche le dit en nommant ces sièges, avec le lien vers les
  verrouillages.
- La réponse nomme les sièges libérés — la fiche propose sur chacun « Qui peut
  tenir ce siège ? », le banc du panneau Siège et son « Placer » (0054) —, les
  sièges gardés sous un verrou, et compte les sièges déjà commencés.
- « Annuler l'absence » rend le jour disponible et ne rend aucun siège : qui
  tient un siège est une décision, et le remplaçant placé entre-temps serait
  délogé sans un mot.
- Une ligne de journal par geste (`ANIMATEUR_JOUR_INDISPONIBLE`,
  `ANIMATEUR_JOUR_DISPONIBLE`), rangée parmi les changements de données.

## Conséquences

- Le motif facultatif qu'évoquait le ticket n'a nulle part où s'écrire : un
  jour indisponible ne porte pas de motif. Il n'est pas demandé.
- Un siège gardé sous un verrou est une violation dure assumée jusqu'à ce
  que le verrou soit levé : l'écran Problèmes et le score la montrent, et la
  fiche envoie aux verrouillages plutôt que de décider à la place de
  quelqu'un.
- Deux écritures sans transaction commune : si la libération échoue après
  l'écriture du jour, le jour reste indisponible et la fiche affiche l'erreur ;
  refaire le geste libère ce qui reste, le jour étant déjà posé.
