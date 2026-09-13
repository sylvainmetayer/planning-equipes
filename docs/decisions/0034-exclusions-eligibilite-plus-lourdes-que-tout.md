# 0034 — Un siège qui enfreint une exclusion d'éligibilité coûte plus que tout autre écart

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : solveur (score), analyse de faisabilité, saisie des ajustements manuels

## Contexte

Six règles dures disent qui ne peut **jamais** tenir un siège, quel que soit le
reste du plan : un animateur le jour qu'il a déclaré indisponible
(`animateurDisponible`), un mineur la nuit, sur un stand réservé aux majeurs,
un jour férié, au-delà de son plafond quotidien ou de travail continu. Ce sont
exactement les motifs de `EligibleAnimateurMoveFilter`, qui écarte ces
mouvements avant tout calcul de score.

Le filtre n'est pas un mur : la reconstruction du *ruin and recreate* lance sa
propre heuristique de construction, que Timefold 2.5 ne laisse pas filtrer
(voir [0025](0025-stabilite-du-plan-publie.md)). Et ces règles pesaient **un
point dur par siège** — autant qu'un siège vide ou qu'une affectation forcée
non tenue, et moins que quelques minutes de repos manquant, que d'autres règles
dures comptent à la minute. Sur un plan qui ne pouvait pas tout tenir, le
solveur échangeait donc l'un contre l'autre. Mesuré sur la gamme de scénarios :

- une soirée de deux places pour un seul majeur (`gamme-29`) : le second siège
  allait à un animateur de 15 ans, pendant sa nuit légale, plutôt que de rester
  vide ;
- une affectation forcée tombant le jour où l'animatrice s'était déclarée
  indisponible (`gamme-27`) : elle était placée ce jour-là, plutôt que de
  laisser l'exception non tenue.

Les deux plans avaient le même score dur que l'alternative légale. Rien ne
permettait à l'organisateur de voir, sur la page Problèmes, qu'un écart était
une affectation illégale plutôt qu'un siège à pourvoir.

## Options envisagées

**(A) Garder le poids égal, et signaler en amont.** Écarté comme réponse seule :
la détection ne couvre que les conflits qui se lisent avant le solve, pas un
arbitrage que le solveur fait en cours de recherche contre un siège vide.

**(B) Filtrer aussi la reconstruction.** Impossible sur Timefold 2.5 sans
retirer le *ruin and recreate*, qui porte la convergence des plans tendus.

**(C) Un quatrième niveau de score.** Un `BendableScore` rangerait ces règles
au-dessus du dur. Écarté : tout le produit lit un `HardMediumSoftScore`
(diagnostic, instantanés, KPI, MCP, écrans), pour un gain que (D) obtient.

**(D) Un forfait lourd par écart.** Retenu.

## Décision

Chaque écart à l'une des six règles coûte `ExclusionEligibilite.FORFAIT` —
10 000 points durs —, plus les minutes de dépassement pour les deux plafonds
comptés à la minute, qui gardent ainsi leur pente.

Le montant est choisi au-dessus de ce qu'un seul siège peut faire gagner
ailleurs : un siège déplace au plus un déficit de repos hebdomadaire de 35 h
(2 100 minutes), un repos quotidien, une durée quotidienne ou hebdomadaire, une
coupure repas ou une pause entre vacations de moins — ensemble, moins de la
moitié du forfait.

Le forfait vit dans la **fonction de pondération des correspondances**, pas dans
le poids de la contrainte : une surcharge de poids (`ConstraintWeightOverrides`,
déploiement, édition ou scénario) **remplace** le poids de la contrainte, et un
forfait écrit là serait ramené à 1 par le défaut du déploiement. Dans la
pondération, un poids d'édition le multiplie.

La liste des règles concernées n'est écrite qu'une fois : `ExclusionEligibilite`
la dérive des motifs du filtre.

En amont, le cas qui se lit avant tout solve — une affectation forcée dont tous
les animateurs sont indisponibles à chaque date de son périmètre — devient une
**cause bloquante** de l'analyse de faisabilité
(`AFFECTATION_FORCEE_JOUR_INDISPONIBLE`) et un **avertissement** à l'écriture
de l'ajustement. Pas un refus : l'indisponibilité arrive le plus souvent après,
par la déclaration de l'animateur, qu'on ne refuse pas.

## Conséquences

- Un plan infaisable laisse un siège vide ou une exception non tenue, jamais un
  siège illégal quand une alternative existe : ce que la page Problèmes montre
  est ce qu'il faut réellement traiter.
- Le score dur change d'unité pour ces six règles : `-10000hard` pour un
  mineur la nuit au lieu de `-1hard`. Le **nombre** d'écarts reste celui que
  lisent le diagnostic et les écrans (`matchCount`) ; seul le montant du score
  est plus grand.
- La conversion du score dur en `int` du diagnostic (`Math.toIntExact`) tient
  jusqu'à 214 000 écarts de ce type, hors de portée d'un plan réel.
