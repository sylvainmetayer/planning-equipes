# 0089 — La levée d'une consigne avertit quand les journées figées vident la semaine

- **Statut** : accepté
- **Date** : novembre 2026
- **Portée** : aperçu de la levée d'une consigne (`service/consigne`), écran et outils MCP qui le lisent ; rien du solveur
- **Voisine de** : [0043](0043-consigne-d-edition-fermer-une-bande-sans-rien-detruire.md) (la consigne), [0044](0044-le-passe-est-fige.md) (le passé figé)

## Contexte

Le geste de l'organisateur autour d'une consigne : poser la consigne, figer
les autres journées par des verrous `JOUR`, recalculer ; puis lever la
consigne sur un jour et recalculer ce jour-là seul, les autres journées
restant verrouillées. La levée rend à la journée sa grille nominale — un
après-midi entier là où la consigne n'ouvrait qu'une soirée.

Les journées figées fixent qui travaille quand. Une personne qui tient un
siège figé sur chacun des six autres jours de la semaine ISO de la journée
levée ne peut plus y travailler (`maxJoursTravaillesParSemaine`, L3132-1),
quels que soient les besoins du jour. Le calcul fait sous consigne a pu, en
toute légalité, employer pour les journées voisines des personnes dont la
journée levée aura besoin : la levée laisse alors la journée sans assez de
monde, à un ou deux sièges près, et **aucun moteur** n'y peut rien tant que
les verrous tiennent. Sur `festival-hivernal`, une chaîne poser → lever ainsi
verrouillée finit à −1 dur selon le plan qu'avait trouvé le calcul sous
consigne, avec le moteur direct comme avec Timefold, et à zéro dès qu'on
rouvre la journée voisine.

## Décision

L'aperçu de la levée (`POST /api/consignes/levee/apercu`,
`simuler_levee_consigne`, et le résultat de `lever_consigne`) porte, par
date, un **avertissement** — deux champs, que l'écran met en phrase — quand deux conditions tiennent ensemble :

1. la levée **ajoute des minutes-siège** à la journée (sous consigne contre
   grille nominale, mêmes stands résolus) ;
2. au moins un animateur **disponible** ce jour-là tient, dans le plan
   enregistré, un siège **figé** — couvert par un verrou, ou sur un jour
   passé — sur chacun des six autres jours de la semaine ISO.

Il compte ces personnes (`animateursSansJourLibre`) sans en nommer aucune, et
nomme les journées verrouillées `JOUR` à rouvrir pour leur rendre un jour
(`joursADeverrouiller`) : la plus petite liste trouvée gloutonnement, la
journée qui en libère le plus d'abord, la plus proche de la journée levée à
égalité. Il n'est rien vérifié si la règle hebdomadaire est désactivée.

C'est un **risque**, pas une preuve, et jamais un refus : la journée peut
tenir sans ces personnes, et la levée est faite de toute façon. La phrase dit
quoi faire si le calcul échoue.

## Alternatives écartées

- **Une borne de capacité certaine** (minutes-siège du jour contre
  disponibles × durée quotidienne maximale, sièges simultanés contre
  disponibles). Saine, mais muette sur le cas réel : le manque vient de la
  forme des sièges, des pauses et des durées croisées, pas du total, et la
  borne passe large.
- **Lancer le solveur dans l'aperçu.** Des dizaines de secondes pour un
  aperçu, et un « non » d'une recherche locale ne prouve rien.
- **Refuser la levée.** Le jour peut tenir ; et lever une consigne est ce que
  l'autorité demande, la question des verrous vient après.
- **Déverrouiller d'office.** Les verrous sont l'acte délibéré de
  l'organisateur ; l'application dit lesquels gênent, elle ne les retire pas.
- **Compter aussi le repos hebdomadaire, la durée quotidienne ou les jours
  consécutifs.** Plus fin, mais chaque règle ajoutée rend l'avertissement
  plus bavard sans le rendre plus sûr ; la semaine pleine est la cause que
  le cas réel a montrée, et la seule qu'un déverrouillage nommé corrige.
