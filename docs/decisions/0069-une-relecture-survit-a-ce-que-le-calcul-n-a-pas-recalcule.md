# 0069 — Une relecture survit à ce que le calcul n'a pas recalculé, et à rien d'autre

- **Statut** : accepté, implémenté
- **Date** : octobre 2026
- **Portée** : relecture du plan, résolution, IHM (Solveur)
- **Précise** : [0039](0039-validation-de-relecture-distincte-du-verrou.md)

## Contexte

[0039](0039-validation-de-relecture-distincte-du-verrou.md) retire la
relecture d'une journée quand une résolution y **déplace un siège**. Le retrait
est donc conditionné au mouvement, et trois choses empêchent une journée de
bouger : le passé figé ([0044](0044-le-passe-est-fige.md)), un verrou `JOUR`,
et un plan qui retombe à l'identique. Rien de cela n'était faux. Deux choses
l'étaient :

- **« Recommencer de zéro » gardait des relectures sans le dire.** Le calcul
  jette le plan en place et repart de rien ; l'écran l'annonce. Le lecteur en
  déduit que tout est refait, relectures comprises. Or une petite journée sans
  marge retombe souvent sur le même équipage, et sa relecture survivait. Le
  récapitulatif comptait les relectures retirées, jamais celles gardées. Une
  relecture qu'on croit effacée et qui ne l'est pas est pire qu'une relecture
  visiblement conservée : elle fait passer pour relu un plan que personne n'a
  rouvert.
- **Une cellule disparue n'était jamais comparée.** La comparaison parcourait
  les cellules (stand × créneau) du plan résolu. Une cellule tenue avant et
  absente après — ouverture retirée, stand fermé par une consigne — ne faisait
  pas bouger sa journée, alors que plus rien de ce qui y avait été relu n'est
  travaillé.

## Options envisagées

**(A) Garder la règle du mouvement et se contenter de le dire.** Écarté comme
réponse complète : « relu et accepté » dit *j'ai relu ce plan*. Un calcul à
froid jette ce plan ; qu'il retombe par hasard sur le même équipage est une
coïncidence que personne n'a regardée.

**(B) Un calcul à froid retire toutes les relectures, verrous compris** — ce que
fait déjà une consigne. Écarté : un verrou `JOUR` empêche le solveur de toucher
la journée. Le plan relu est alors exactement celui qui reste en place ; le
retirer ferait relire ce qui n'a pas changé.

**(C) Une relecture survit si sa journée n'a pas été recalculée.** Retenu.

## Décision

Une relecture **survit à une résolution quand sa journée n'a pas été
recalculée**, et seulement alors.

- Deux sortes de journées ne sont **jamais recalculées**, quel que soit le
  mode : une journée **entièrement passée**, dont chaque siège a commencé
  (réamorcée et épinglée, [0044](0044-le-passe-est-fige.md)), et une journée
  sous **verrou `JOUR`**. Leur relecture reste.
- **Repartir du plan en place** (le mode par défaut, la replanification
  incrémentale) : la journée est recalculée *à partir de ce qui a été relu*.
  Rendue à l'identique, elle garde sa relecture ; un siège déplacé la retire,
  comme avant.
- **Repartir de zéro** : toute journée recalculée perd sa relecture, même rendue
  à l'identique.
- Une cellule **tenue avant la résolution et absente après** fait bouger sa
  journée, dans les trois modes.

Le **récapitulatif** de fin de calcul dit ce qui a été retiré **et** ce qui a été
gardé, un nombre par motif : déjà travaillées, verrouillées, inchangées. La
**confirmation** de « Recommencer de zéro » liste, avant le lancement, les
journées relues qui survivront et pourquoi. C'est le moment où la question se
pose.

## Conséquences

- Le grain reste la journée entière : un verrou plus petit qu'une journée ne
  sauve pas sa relecture, comme dans 0039.
- La journée en cours n'est pas « passée » tant qu'un de ses sièges reste à
  venir. Un calcul à froid l'a donc recalculée en partie, et sa relecture part.
- La suppression d'un stand ou d'un créneau efface ses sièges du plan au moment
  de l'écriture, avant toute résolution. Ce cas échappe donc à la comparaison,
  qui ne voit plus rien à comparer. Seules les cellules qu'une résolution cesse
  de produire sont couvertes ici.
