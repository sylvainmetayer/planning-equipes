# 0098 — La borne exacte s'affiche d'abord, le calcul par équipe fictive reste le défaut

- **Statut** : accepté, implémenté (le défaut reste le calcul ; la borne s'affiche d'abord)
- **Date** : novembre 2026
- **Portée** : écran Besoin en animateurs, `POST /api/staffing/verification`, table `verification_besoin`
- **Remplace en partie** : [0071](0071-le-besoin-en-animateurs-un-minimum-exact-verifie-par-une-equipe-fictive.md) — son point 5 (la vérification par un solve) gagne une alternative, la borne seule, que le défaut adoptera à l'équivalence ; ses points 1 à 4 et 6 restent
- **Voisine de** : [0080](0080-un-solveur-exact-pour-la-seule-verification-du-besoin-etude.md) (étude close, inchangée), [0087](0087-un-second-moteur-de-recherche-arbitre-par-timefold.md), [0090](0090-les-reglages-avances-replies-par-ecran.md)

## Contexte

0071 donne deux réponses à « cette équipe tient-elle les sièges ? ». Le minimum
exact, par flot maximal, se lit sans calcul. La vérification pose une équipe
fictive de cette taille et la fait résoudre jusqu'au premier plan réalisable :
une minute à dix, les cœurs du serveur pris, le quota horaire de l'instance
entamé, et un échec qui ne prouve rien (le temps a manqué, ou l'équipe est
trop petite). L'organisateur qui dimensionne son équipe essaie plusieurs
tailles ; avec le solve, chaque essai est une attente et une incertitude, alors
que la question n'a pas besoin de plus que le minimum.

Reste à savoir si le minimum **est** la réponse du solve. Le minimum ignore ce
que seule la résolution voit : le repos quotidien après une soirée, les pauses
d'un long enchaînement, une fenêtre de repas que la grille ne laisse pas tenir.
La mesure qui tranche est `StaffingBoundEquivalenceTest`, sur toute la gamme
de scénarios réalisables (`gamme-01` à `gamme-25`) : pour chaque rang, la borne
`B`, puis le solve d'une équipe de `B` majeurs fictifs, de `B − 1`, et au
besoin de `B + 1`, `B + 2`… jusqu'au premier réalisable.

## Décision

1. **La borne répond à la demande qui la choisit, et s'affiche d'abord.** `POST
   /api/staffing/verification` avec `calcul: false` compare l'effectif au
   minimum exact (`minimumTotal`) et aux majeurs que la journée la plus
   exigeante demande (`majeursMin`) : `TERMINEE` d'emblée (`methode: BORNE`),
   sans solve, sans file, sans quota. L'écran montre ce minimum, « au moins N »,
   avant tout calcul, et la réponse de la borne se lit comme une condition
   nécessaire, jamais comme « un plan existe ». Elle est conservée et
   journalisée comme tout essai (`verification_besoin.methode`, `.minimum`).
   Aucun algorithme n'est réécrit : c'est le minimum déjà calculé pour l'écran.
2. **Le défaut reste le calcul tant que la borne n'égale pas le solve sur toute
   la gamme.** Une requête qui ne choisit pas obtient la résolution de
   l'équipe fictive de 0071 point 5, sur **le moteur de l'instance** : le moteur
   direct (ADR 0087, le défaut) s'arrête une seconde après son premier plan
   réalisable, et Timefold rejoue le plan et refuse tout écart de score, comme
   partout ; sous `SOLVER_ENGINE=timefold`, c'est l'ancienne recherche. L'écran
   coche « Confirmer par un calcul » (réglages avancés, ADR 0090) ; décochée,
   seule la borne répond.
3. **Le basculement du défaut est une propriété** :
   `planning.staffing.verification-default=borne` en fait la réponse d'une
   requête qui ne choisit pas. Il se décide quand `StaffingBoundEquivalenceTest`
   donne un écart nul sur les vingt-cinq rangs, ou un écart prouvé dû à la
   recherche seule. Une requête qui envoie `calcul` n'est jamais affectée.
4. **Ce que la borne ne dit pas reste dit.** Un effectif de mineurs au-delà de ce
   qu'une journée peut encadrer n'est jugé que par le calcul (le plafond
   `mineursMax` de 0071 point 3 n'entre pas dans la réponse).
5. **0080 reste une étude close** : aucun solveur exact n'entre dans l'application.

## Mesure d'équivalence

`StaffingBoundEquivalenceTest` compare, sur les vingt-cinq rangs réalisables de la
gamme, la borne `B` au **besoin** : le plus petit effectif d'une équipe fictive qui
tient tous les sièges. Le besoin vient d'abord de la recherche : la première taille de la
grille `B`, `B+1`, `B+2`, `B+3`, `B+6`, `B+12`, `B+24`… qui tient (moteur direct,
300 à 600 s par équipe, joué sur des machines louées pour l'occasion, détruites
ensuite). Une recherche qui échoue dans son temps ne prouve rien : pour les quatre
rangs où elle et la borne divergeaient (10, 11, 18, 20), le besoin est donc
**prouvé hors application**, par un modèle exact (voir plus bas).

| Rang | Borne `B` | Besoin | Écart | Besoin établi par |
| --- | --- | --- | --- | --- |
| 1 à 9 | 2, 4, 3, 4, 6, 5, 7, 9, 8 | `B` | 0 | recherche (et aucune équipe de `B − 1` ne tient) |
| 10 (journées types multiples) | 13 | 14 | 1 | preuve exacte |
| 11 (semaine à cheval, nuit) | 9 | 9 | 0 | preuve exacte |
| 12 à 16 | 14, 14, 45, 32, 41 | `B` | 0 | recherche |
| 17 (règles désactivées) | 60 | 60 | 0 | recherche |
| 18 (effectifs par fenêtre) | 63 | 72 | 9 | preuve exacte |
| 19 | 53 | `B` | 0 | recherche |
| 20 (festival type) | 105 | 105 | 0 | preuve exacte |
| 21 à 25 | 70, 123, 100, 168, 200 | `B` | 0 | recherche |

La borne égale le besoin sur **23 rangs sur 25**. Elle n'est jamais trop haute : sur
les rangs de la recherche, aucune équipe de `B − 1` n'a tenu ; sur les quatre autres,
la preuve exacte donne le besoin lui-même.

### La preuve exacte, hors application

`scripts/besoin-exact/modele.py` (OR-Tools CP-SAT, **jamais dans l'image ni dans
l'application** : le Dockerfile ne copie que `pom.xml`, `src` et `.git`) pose les
règles dures d'un rang en une **relaxation** : chaque règle est exacte ou plus faible
que la vraie, jamais plus forte. Un « infaisable » à `N` prouve donc que `N` personnes
ne tiennent pas les sièges sous les vraies règles. Dans l'autre sens, un plan que le
modèle trouve est noté par les vraies contraintes (le scoreur de l'application) : zéro
point dur le prouve réalisable. Les deux ensemble donnent le besoin exact.

- **Rang 11 : 9.** `{chevauchement, repos quotidien}` suffit. La borne passe de 7 à 9
  par le plancher du repos quotidien (ci-dessous), qui égale le besoin.
- **Rang 20 : 105.** Le modèle est optimal à 105 en vingt secondes ; le plan, noté
  avec les vraies règles, ne casse rien. Le plancher du repos quotidien donne 105.
- **Rang 10 : 14.** 13 est infaisable dès que la coupure repas, le travail continu
  relayé et les dix heures effectives (la pause d'un étirement de plus de six heures
  est déduite) sont posés ensemble ; aucun couple de ces trois règles ne suffit. La
  borne, qui lit chacune séparément, reste à 13 : **l'écart de 1 est prouvé**.
- **Rang 18 : 72.** Infaisable à 63, 64, 66, 67, 70 et 71, faisable à 72 (plan noté à
  zéro dur). La borne (63) vient du travail continu seul ; les 9 de plus viennent de
  sa composition avec le repos quotidien et la pause, que la borne ne sait pas
  combiner : **l'écart de 9 est prouvé**, la recherche de la grille s'arrêtait à 82.

### Ce que la borne gagne

Chaque ajout est une minoration prouvée (la preuve est dans le Javadoc de la méthode)
et couverte par un test unitaire. Aucun autre rang ne bouge : leur borne est celle de
la mesure ci-dessus.

1. **Les sièges trop longs pour se partager un jour** (`grosSieges`). Deux sièges de
   plus de la moitié du plafond quotidien (travail effectif plus pause, comme pour le
   volume d'heures) dépassent ensemble ce plafond : nul n'en tient deux le même jour.
   Rang 17 : 35 → 60, exact.
2. **Le repos quotidien** (`reposQuotidienPlancher`). Un siège qui finit à `e` un jour
   et un siège qui commence à `s` le lendemain ne se tiennent pas ensemble quand
   `e − s > 1440 − repos` ; les sièges du soir qui se chevauchent, plus ceux du matin
   suivant qui se chevauchent, forment une clique : leur somme minore l'effectif.
   Rang 11 : 7 → 9, rang 20 : 100 → 105, tous deux exacts.
3. **Le travail continu relayé** (`continuousWorkFloor`). Un enchaînement de plus
   de six heures doit être relayé par un siège du même stand ; une « tête » (un siège
   que rien ne précède) et les sièges qui le suivent, que l'absence de relais
   empêche de lui succéder, forment une clique. Rang 18 : 58 → 63.

### Ce qui reste

Les rangs 10 et 18 : la borne lit chaque règle à part, le besoin vient de leur
composition. Une borne qui les combine est un petit modèle exact, non un compte : elle
n'entre pas dans l'application (0080 reste une étude close). C'est l'écart prouvé, et
c'est ce qui garde le calcul comme défaut.

### Conséquence sur le défaut

L'équivalence n'est pas totale (23 rangs sur 25) : **le défaut reste le calcul**. Le
test fixe le besoin des rangs prouvés (`PROVEN_NEED`) et l'écart de chaque rang
(`ALLOWED_GAPS`) : une règle qui l'élargit casse le test. Le défaut passera à la
borne par la propriété quand l'écart sera nul partout.

## Conséquences

- Tant que le défaut est le calcul, rien ne change pour l'organisateur, sinon
  qu'il lit « au moins N » tout de suite, et peut ne demander que la borne.
- La borne seule ne prend ni les cœurs, ni le quota, ni les verrous (une
  vérification à la fois, une résolution en cours) : ceux-ci ne concernent que le
  calcul.
- Le test d'équivalence fixe l'écart de chaque rang (`ALLOWED_GAPS`) : une règle
  qui l'élargit casse le test.
- Les lignes `verification_besoin` écrites avant ce changement sont toutes des
  calculs (`methode` vaut `CALCUL` par défaut, `minimum` est absent).
- Le contrat de l'API change : `calcul` en entrée, `methode` et `minimum` en
  sortie.
