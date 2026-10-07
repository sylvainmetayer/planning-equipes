# 0081 — Le plateau se juge sur son gain de medium, à l'échelle du plan ; les rendements décroissants de Timefold sont écartés

- **Statut** : accepté, implémenté
- **Date** : octobre 2026
- **Portée** : solveur (terminaison), propriétés de déploiement
- **Prolonge** : [0051](0051-budget-de-calcul-par-edition-sous-plafond-d-exploitant.md) (le budget par édition : la durée et la fenêtre de plateau gardent leur sens)
- **Complète** : [0079](0079-la-phase-de-faisabilite-tire-sur-une-liste-par-pas-et-reconstruit-rarement.md)

## Contexte

Une résolution s'arrête au premier des deux : le budget (900 s), ou un plan
**déjà faisable** sans la moindre amélioration depuis `unimproved-seconds-limit`
(300 s). Ce second critère était bien dessiné — il ne coupe jamais la recherche
de la faisabilité — mais il attendait un silence **strict** de cinq minutes. Or
la seconde phase trouve toujours un point de medium de temps en temps : sur
une édition réelle le plateau ne s'est jamais déclenché, et chaque calcul a
consommé ses quinze minutes, même quand le gain par minute était devenu
négligeable au regard du plan déjà tenu.

Timefold propose depuis la 1.17 une terminaison par rendements décroissants
(`diminishedReturns`) qui compare le taux d'amélioration courant à celui d'une
période de grâce initiale. Il fallait mesurer avant d'adopter.

## Mesures

Le banc (`SolverBenchTest`, `docs/developpement.md` § *Le banc du solveur*) a
enregistré la courbe complète de 900 s, graine fixe, sur deux grilles
versionnées : `festival-hivernal` (3 438 sièges) et
`festival-realiste-canicule` (1 994). `TerminationReplayTest` a ensuite rejoué
chaque règle d'arrêt sur ces courbes : une règle qui ne lit que le meilleur
score au fil du temps s'arrête en un point de la courbe, et le score gardé est
celui de la courbe à cet instant.

Le medium après la faisabilité, puis aux repères du budget :

| Grille | faisable à | medium à 300 s | 450 s | 600 s | 900 s |
| --- | --- | --- | --- | --- | --- |
| `festival-hivernal` | 38 s | −27 545 | −26 720 | −26 210 | −25 495 |
| `canicule` | 13 s | −17 775 | −17 570 | −17 440 | −17 265 |

Ce que chaque famille de règle aurait fait, perte de medium contre le plan de
900 s :

| Règle | hivernal | canicule |
| --- | --- | --- |
| plateau strict, 60 à 300 s | jamais | jamais |
| rendements décroissants (fenêtres 30 à 120 s, ratios 10⁻⁴ à 0,2) | jamais | jamais |
| gain < 500 medium sur 180 s | 11 min 28 s, −1,9 % | 5 min 41 s, −2,6 % |
| gain < 1 000 sur 180 s | 7 min 39 s, −4,7 % | 4 min 09 s, −3,8 % |
| arrêt à 600 s | −2,8 % | −1,0 % |
| arrêt à 450 s | −4,8 % | −1,8 % |

Trois enseignements :

- **Le plateau strict est mort** : entre la dixième et la quinzième minute,
  `festival-hivernal` gagne encore plus de 140 points de medium par minute. Le
  critère tel qu'il était écrit ne pouvait pas servir.
- **Les rendements décroissants de Timefold ne se déclenchent pas non plus**,
  et pour une raison de construction : ils ne lisent que le niveau le plus
  fin du score — le soft ici — et se réarment à chaque changement d'un niveau
  plus haut. Le medium bouge sans cesse ; la période de grâce repart donc sans
  fin. Ils conviennent à un score dont le dernier niveau porte l'objectif, pas
  à trois niveaux où le medium fait l'essentiel.
- **Un gain minimal sur une fenêtre fait ce qu'on attendait du plateau**, à
  condition d'être à l'échelle du plan : 1 000 points sur trois minutes
  arrêtent la grille de 3 438 sièges à 7 min 39 s, la grille de 1 994 sièges
  dès 4 min 09 s — sur un plan plus petit, le même seuil est plus exigeant.
  Le medium par siège, lui, est du même ordre d'une grille à l'autre (7,4 et
  8,7 points par siège à 900 s) : c'est le siège qui donne l'échelle.

## Décision

1. **Le plateau se juge sur ce que la fenêtre a gagné en medium**, par la
   terminaison `unimprovedScoreDifferenceThreshold` de Timefold (API publique) :
   une amélioration ne repousse le plateau que si elle vaut au moins le seuil
   de mieux qu'un meilleur score atteint dans la fenêtre. Le hard est nul une
   fois faisable, le soft est laissé libre (−∞ sur ce niveau) : il cède aux deux
   autres par construction.
2. **Le seuil est proportionnel au nombre de sièges** du problème résolu :
   `planning.solver.plateau-gain-medium-per-seat`, 0,25 point de medium par
   siège et par fenêtre par défaut — un quart de point par siège, soit environ
   3 % du medium de ces plans. Les sièges comptés sont ceux que la recherche
   peut déplacer : le passé figé et les verrous ne peuvent plus rien gagner, et
   les compter demanderait à une résolution du dixième jour de l'événement un
   gain que seul le plan entier pourrait fournir. La fenêtre reste `unimproved-seconds-limit`,
   que chaque édition règle (0051) ; son défaut passe de 300 à **180 s**, la
   durée sur laquelle les courbes ont été lues.
3. **Le budget par défaut reste 900 s**, plafond d'un calcul qui progresse
   encore ; c'est la règle de gain qui l'écourte quand il ne progresse plus
   assez, et l'édition qui plafonne tôt s'arrête tôt.
4. **Les rendements décroissants ne sont pas adoptés**, pour la raison de
   construction ci-dessus ; `TerminationReplayTest` les garde dans sa grille,
   pour qu'un changement de niveaux du score rouvre la question avec des
   chiffres.

Sous ce réglage, rejoué sur les courbes enregistrées (`-Dreplay.seats`),
perte contre le plan de 900 s de la même version :

| Grille | seuil (0,25 × sièges) | s'arrête à | medium perdu |
| --- | --- | --- | --- |
| `festival-hivernal` | 860 | 8 min 14 s | −4,1 % |
| `canicule` | 499 | 5 min 41 s | −2,6 % |

Sous les 5 % de medium que l'organisation a acceptés contre le temps gagné.
Le même seuil sur une fenêtre de 120 s dépasse les 5 % sur `festival-hivernal`
(6,2 %), et sur 300 s ne gagne plus que deux minutes : 180 s est la fenêtre qui
tient les deux bouts.

## Alternatives écartées

- **Abaisser le budget à 600 s.** Perd 2,8 % sur `festival-hivernal`, 1 % sur
  `canicule`, et cinq minutes partout — mais coupe de la même façon une
  édition qui progresse encore et une qui plafonne depuis dix minutes. La règle
  de gain fait la différence entre les deux ; le budget reste ce qu'il est, un
  plafond.
- **Un seuil absolu** (500 ou 1 000 points). Juste sur une taille de grille,
  trop exigeant sur une plus petite, inerte sur une plus grande ; c'est ce que
  le tableau montre entre 3 438 et 1 994 sièges.
- **Un seuil relatif au medium atteint à la faisabilité.** Plus juste encore,
  mais la terminaison se construit avant la résolution, et le lire pendant
  demanderait une terminaison maison sur l'API interne de Timefold — la
  dépendance que `TimefoldInternalApiStructuralTest` tient à l'inventaire près.
  Le siège est le substitut disponible avant de résoudre.

## Conséquences

- `application.properties` : `unimproved-seconds-limit` 300 → 180,
  `plateau-gain-medium-per-seat` 0,25 ; le profil de test garde ses deux
  secondes et un seuil nul (le plateau strict), pour que ses scénarios
  s'arrêtent où ils s'arrêtaient.
- Une édition qui avait réglé son plateau garde sa fenêtre ; une édition qui
  suivait le défaut voit son calcul s'arrêter plus tôt dès qu'il ne gagne plus
  assez — c'est la rupture que le journal de version annonce.
- La fabrique de solveur se construit par problème, le seuil dépendant du
  nombre de sièges ; la fabrique partagée ne sert plus qu'au gestionnaire de
  solution et au diagnostic.
