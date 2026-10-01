# 0071 — Le besoin en animateurs : un minimum exact par flot, vérifié par une équipe fictive

- **Statut** : accepté, implémenté
- **Date** : octobre 2026
- **Portée** : écran Besoin en animateurs, `GET /api/staffing`, `analyser_effectifs`
- **Voisine de** : [0048](0048-une-seule-regle-de-pause.md), [0068](0068-sous-la-regle-dure-la-construction-suit-le-calendrier.md)

## Contexte

L'écran Besoin sert à décider **combien de personnes recruter, et lesquelles**.
L'organisateur saisit d'abord les stands, les créneaux et les horaires. Les
animateurs viennent en dernier. L'écran donnait la plus haute de cinq bornes,
toutes prouvées, mais trois lacunes le rendaient trompeur pour cet usage :

- **La rotation était calculée semaine par semaine**, en divisant les
  jours-personnes d'une semaine ISO par six. Cette division ignore la règle des
  jours d'affilée tenue en dur ([0068](0068-sous-la-regle-dure-la-construction-suit-le-calendrier.md)).
  Prenons une semaine pleine à six personnes par jour, suivie de deux jours à
  sept. La division donne sept. Or celui qui se repose le premier lundi
  travaillerait sept jours d'affilée pour finir l'événement : il en faut huit.
- **Le plafond de créneaux d'une typologie** (`maxCreneauxParAnimateur`) n'entrait
  dans aucune borne. Une typologie de neuf créneaux plafonnée à quatre par
  personne demande trois personnes, quand son pic en annonce une.
- **La part de majeurs** était une proportion : « la moitié des places de chaque
  stand ». C'est une règle de qualité, lue comme si c'était la loi. Elle ignorait
  les jours fériés, la nuit des mineurs et leurs deux jours de repos consécutifs.

Il fallait enfin un moyen de passer de « au moins N » à « N suffisent ».

## Options envisagées

**(A) Garder la division, avec un correctif pour les jours d'affilée.** Écarté.
On ne corrige pas un raisonnement semaine par semaine d'une règle glissante sans
réécrire le raisonnement. La fenêtre de faisabilité (`ConsecutiveDaysCapacity`)
reste une condition nécessaire. Elle ne donne pas de minimum.

**(B) Un programme linéaire en nombres entiers**, avec un calendrier de jours
travaillés par variable, confié à une bibliothèque d'optimisation. Écarté. C'est
une dépendance native de plus dans l'image, à inventorier et à suivre, pour un
problème qu'un algorithme de quelques dizaines de lignes résout exactement.
L'énumération des calendriers explose aussi au-delà d'un mois d'événement.

**(C) Un flot maximal sur les jours de repos.** Retenu. Le calendrier d'une
personne se décrit par ses jours de repos, lus de gauche à droite. Chaque pas
d'un jour de repos au suivant ne saute ni une semaine complète ni plus de jours
que le plafond d'affilée. Le jour *j* accueille au plus N − besoin(*j*) jours de
repos. N personnes tiennent si et seulement si N tels chemins tiennent ensemble
dans ces capacités. Un flot entier se décompose en chemins : le résultat est
exact pour cette relaxation, et non une borne de plus.

Pour la vérification, deux formes ont été écartées :

- **Résoudre avec les animateurs saisis.** Un échec mêlerait alors le manque de
  monde, une compétence absente et une indisponibilité déclarée, que les lignes
  par compétence et le budget d'indisponibilités mesurent déjà à part.
- **Passer par la file du solveur.** La vérification y prendrait la place d'une
  vraie résolution. Elle serait aussi persistée et rejouée au démarrage, pour un
  chiffre que la prochaine modification d'un stand rend caduc.

## Décision

1. **Borne `ENCHAINEMENT_JOURS`** (`DaySequenceFloor`) : le flot ci-dessus, en
   Java pur et sans nouvelle dépendance. Le besoin du jour est le minimum déjà
   prouvé pour ce jour. Le plafond d'affilée ne compte que si
   `maxJoursConsecutifsTravaillesDur` est allumée, car une règle moyenne peut
   être enfreinte et ne doit pas faire recruter. La borne n'est créditée que
   lorsqu'elle dépasse strictement les autres.
2. **Par typologie**, le même flot, plus ⌈sièges ÷ plafond⌉ quand la typologie
   porte un plafond (borne `PLAFOND_TYPOLOGIE`). La ligne s'affiche **avant toute
   saisie d'animateur** : c'est la fiche de recrutement. La somme des lignes
   donne l'effectif nécessaire si personne ne cumulait deux typologies.
3. **Majeurs et mineurs.** `majeursMin` est le plus grand nombre de majeurs
   qu'une journée exige : toute la journée un jour férié, sinon le pic des sièges
   qu'aucun mineur ne peut tenir. `mineursMax` est un **plafond** pour l'effectif
   de référence, tiré de trois conditions nécessaires : les majeurs restants, les
   jours-personnes d'une semaine (cinq jours pour un mineur, jamais un jour
   férié), les heures de la semaine. L'écran le présente comme un plafond, jamais
   comme une cible.
4. **Indisponibilités.** Chaque jour dit combien de personnes de l'effectif de
   référence peuvent manquer. Chaque semaine dit combien de jours-personnes
   d'indisponibilité elle absorbe **au-delà** du jour de repos qu'une semaine
   complète doit déjà à chacun. Un jour d'absence posé sur ce repos ne coûte
   rien.
5. **Vérification par un solve.** Une équipe fictive de N majeurs, disponibles
   tous les jours et compétents sur toutes les typologies, est construite en
   mémoire. Elle est résolue sous les règles de l'édition, sans exceptions ad
   hoc ni plan publié, et le solve s'arrête dès qu'un plan est réalisable.
   `posteDoitEtrePourvu` y est toujours tenue : la question est de savoir si
   l'équipe pourvoit chaque siège. Un échec ne prouve donc pas qu'il manque du
   monde, seulement qu'aucun plan complet n'a été trouvé dans le temps imparti.
   Une seule vérification tourne à la fois dans toute l'application, et aucune
   ne démarre pendant une résolution. Le dernier
   résultat de chaque édition reste en mémoire. Rien de l'édition n'est écrit, et
   le geste n'est pas journalisé.

## Conséquences

- Le chiffre retenu peut monter d'une unité ou plus sur les éditions qui tiennent
  les jours d'affilée en dur. C'est la correction attendue, pas une régression :
  le nombre précédent n'était pas atteignable.
- Le plafond de mineurs ne tient compte ni des jours d'affilée ni des 4 h 30 de
  travail continu. Un solve peut donc en tenir moins. Le calcul exact
  demanderait un flot à deux classes, qui n'est plus un flot simple.
- Les sièges d'un stand qui propose plusieurs typologies restent hors des lignes
  par typologie. Une ligne « union » est possible plus tard.
- La vérification prend les cœurs du serveur pendant quelques minutes, au plus
  `planning.staffing.verification.seconds-limit`. Une seconde demande reçoit un
  409 au lieu d'attendre son tour.
