# 0049 — La règle dure des jours d'affilée se cherche par jours entiers

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : solveur (phase de faisabilité, mouvement de relocalisation à travers la semaine)
- **Prolonge** : [0045](0045-le-niveau-de-la-regle-des-jours-d-affilee.md)
- **Complète** : [0025](0025-stabilite-du-plan-publie.md) (le *ruin and recreate* de la phase de faisabilité)

## Contexte

[0045](0045-le-niveau-de-la-regle-des-jours-d-affilee.md) a laissé à chaque
édition le choix d'allumer `maxJoursConsecutifsTravaillesDur` et de régler son
seuil. Une édition qui le fait et le resserre à six pose deux questions qu'il
faut séparer, parce qu'elles n'ont pas la même réponse :

1. **Le besoin est-il tenable ?** C'est de l'arithmétique. La règle exige de
   chacun un jour de repos dans *chaque* fenêtre de sept jours ; sur une
   fenêtre, l'effectif offre donc au plus six fois son nombre de
   jours-personnes, et chaque journée en demande au moins son pic de sièges
   simultanés — un peu plus quand les règles de la journée (coupure repas,
   six heures de travail continu, dix heures par jour) interdisent d'enchaîner
   deux sièges. Tant que la somme des pics d'une fenêtre reste sous ce
   plafond, rien ne l'interdit a priori. La preuve qui tranche n'est pas une
   borne mais un **plan** : un plan construit hors de l'application, auquel le
   score de l'application donne zéro écart dur, établit que le besoin est
   tenable avec les règles telles qu'elles sont codées, relais et coupures
   compris.
2. **Le solveur le trouve-t-il ?** Sur une édition anonymisée de seize jours
   (non versionnée), tenue à six jours, un tel plan existe — construit par un
   modèle en nombres entiers, noté zéro dur par le score de l'application —
   et le solveur ne le trouvait pas : il finissait son budget avec des sièges
   vides, tous sur un lundi et un mardi de la deuxième semaine, à des heures où
   personne n'était libre sans dépasser six jours d'affilée.

Trois causes, mesurées sur cette édition :

- **Le jour-personne est la ressource rare, et la recherche le gaspille.** À
  six jours la marge est de quelques personnes par jour ; or l'heuristique de
  construction place siège par siège, sans rien qui coûte d'éclater une
  journée : une journée de montage tenue en deux demi-journées par deux
  personnes, un siège de nocturne tenu seul, dépensent chacun un jour de
  repos de plus. Le plan du solveur employait nettement plus de
  jours-personnes que le plan qui tient, surtout les jours de montage.
- **La chaîne de [la relocalisation à travers la semaine](../contraintes.md)
  ne libérait qu'un jour de la même semaine ISO.** C'est ce qu'il faut pour
  `maxJoursTravaillesParSemaine`, pas pour une série glissante : un lundi pris
  par quelqu'un qui a travaillé du mercredi au dimanche fait sept jours
  d'affilée, et seul un jour de cette série — de l'autre semaine — les casse.
- **Le *ruin and recreate* prenait presque tout le temps de la phase.** Il
  reconstruit ses sièges par une heuristique de construction qui évalue chaque
  animateur (0025 le note : Timefold 2.5 ne laisse pas filtrer cette
  reconstruction) — plus de cent millisecondes l'évaluation, quand les autres
  mouvements en coûtent moins d'une. Tiré une fois sur six, il occupe presque
  toute la phase : quelques dizaines d'évaluations par seconde au lieu de
  quelques milliers (tableau ci-dessous). Et il reconstruit les sièges *de
  l'heure du trou*, quand le manque est d'un jour.

## Options envisagées

**(A) Baisser le *ruin and recreate* partout.** Écarté : c'est lui qui franchit
le mur des heures pleines après publication ([0025](0025-stabilite-du-plan-publie.md)),
et rien n'a été mesuré sur ce cas avec un autre poids. Un réglage global
pour un besoin d'une édition qui a allumé une règle éteinte par défaut.

**(B) Planifier les jours de repos avant la construction.** Un modèle
au jour — qui travaille quel jour — puis la construction restreinte à ce
calendrier : c'est ainsi que le plan de preuve a été obtenu. Écarté comme
mécanisme du produit : un second solveur dans le solveur, soit une
dépendance de programmation linéaire, soit une heuristique écrite à la main,
pour une question que le score sait déjà juger.

**(C) Adapter la recherche quand la règle dure est allumée.** Retenu.

## Décision

**(C)**, sous une seule condition : `maxJoursConsecutifsTravaillesDur` allumée
pour l'édition. Le seuil lu est `joursConsecutifsMax`, celui de la règle.

1. **La chaîne libère un jour de la série.** Quand le trou ferait dépasser le
   seuil au candidat, le jour rendu est choisi parmi ceux dont le retrait
   ramène à la fois la série et la semaine sous leurs plafonds — dans la même
   semaine ISO ou non ; quand aucun jour ne répare à lui seul ce que le trou
   casse, la chaîne n'est pas proposée et le candidat suivant est essayé. Le
   plafond de la semaine est de six jours, cinq pour un mineur (ses deux jours
   de repos). Les candidats essayés passent d'une douzaine à trois : une chaîne
   ne coûte aucun calcul de score, mais chaque essai parcourt les collègues de
   chaque jour qu'il pourrait rendre, sur le fil du solveur.
2. **Un jour rendu ne va qu'à des gens déjà là ce jour-là.** Donner un siège à
   quelqu'un qui ne travaillait pas ce jour lui ajoute un jour, c'est-à-dire
   déplace le problème de série sur lui. Sous la règle, le jour est repris par
   des collègues déjà présents et libres à ces heures, ou la chaîne n'est pas
   proposée ; un même collègue peut en reprendre plusieurs sièges, s'ils ne se
   chevauchent pas.
3. **Deux mouvements nouveaux, dans la même fabrique.** Une série déjà trop
   longue, sans trou à remplir, voit un de ses jours rendu aux collègues
   présents — un jour *de cette série*, un autre la laisserait aussi longue. Et une journée quelconque peut être *regroupée* : ses sièges
   repris par des collègues présents ce jour-là, ce qui couvre la journée avec
   une personne de moins et libère le jour-personne qu'une chaîne dépensera
   plus tard. Le score juge le reste — heures, repos, coupures.
4. **Le *ruin and recreate* de la phase de faisabilité devient rare** :
   poids 0,02 contre 1 pour chacun des autres sélecteurs
   (`HardRunCapSearch`, appliqué à la résolution comme
   `LargeProblemConstruction`). Il reste tiré, pour les chaînes à l'heure du
   trou qu'il est seul à trouver ; le temps va aux chaînes par jours entiers.
   Le retirer tout à fait atteignait la faisabilité plus vite encore sur
   l'édition mesurée ; 0,02 garde le mouvement de 0025 à portée.

5. **Les sièges épinglés comptent.** Le passé figé ([0044](0044-le-passe-est-fige.md))
   et les verrous ne sont jamais rendus, mais la règle les compte dans une
   série : l'index des journées les compte donc aussi, et les tient pour
   occupés. Une journée qui en porte un ne peut être ni rendue ni regroupée —
   elle reste travaillée quoi qu'on déplace.

Rien des règles 1 à 4 ne joue quand la règle dure est éteinte — le défaut livré :
la configuration du solveur est alors celle du XML, et sur un plan sans siège
épinglé la fabrique rend, tirage pour tirage, les mêmes mouvements qu'avant. La
règle 5 vaut pour toutes les éditions : un collègue qui tient un siège verrouillé
à cette heure n'est plus proposé pour un siège rendu, ce que le score refusait
de toute façon.

## Mesures

Protocole : même graine (celle de `solverConfig.xml`), problème construit comme
en production (import dans une édition, horaires résolus), seuil à six,
`maxJoursConsecutifsTravaillesDur` allumée.

**`festival-hivernal`** (versionné, 153 animateurs, 3 438 sièges), 1 200 s :

| Recherche | Score à 1 200 s | Sièges vides | Évaluations par seconde (phase de faisabilité) |
|---|---|---|---|
| avant | `-23hard/-7060medium/-20272soft` | 18, sur trois jours | 29 |
| **après** | `-6hard/-5952medium/-20820soft` | **6**, tous le même jour | 1 047 |

Même ordre de grandeur que la mesure de [0045](0045-le-niveau-de-la-regle-des-jours-d-affilee.md)
(22 sièges vides à six, sur une version antérieure du solveur).

**L'édition anonymisée de seize jours** (non versionnée, chiffres hors du
dépôt) : le solveur n'y atteignait pas la faisabilité dans son budget ; il
l'atteint désormais bien avant la fin de ce budget, et plus tôt encore avec le
*ruin and recreate* retiré. Les leviers se cumulent : sans le *ruin and
recreate* mais avec la chaîne d'origine, la faisabilité venait nettement plus
tard ; avec la chaîne étendue mais le *ruin and recreate* au poids 1, jamais
dans le budget.

## Conséquences

- Une édition qui allume la règle dure à six jours et dont le besoin est
  tenable a désormais un solveur qui le trouve — sur l'édition mesurée, dans
  le budget de résolution qu'elle déclarait.
- **Le seuil par défaut reste huit** ([0045](0045-le-niveau-de-la-regle-des-jours-d-affilee.md)).
  `festival-hivernal` à six jours finit à six sièges vides au lieu de dix-huit,
  mais n'est pas faisable en vingt minutes, et rien n'établit qu'il le soit :
  aucun plan de preuve n'a été construit pour cette grille. Ce qu'une
  organisation doit vérifier avant de resserrer, c'est la question 1 du
  contexte, pas le poids du solveur.
- La question 1 se tranche hors du produit : un plan construit à part et
  importé, que l'écran Problèmes juge. Le produit ne calcule pas de borne de
  faisabilité propre à cette règle ; l'analyse de faisabilité existante ne la
  connaît pas.
- `WeekRelocationMoveIteratorFactoryTest` tient les trois formes du mouvement
  sous la règle et le simple remplissage d'origine sans elle ; `HardRunCapSearchTest`
  la condition et le poids.
