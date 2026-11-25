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

`StaffingBoundEquivalenceTest`, joué le 25 novembre 2026 sur des machines louées
pour l'occasion (12 cœurs partagés, moteur direct, 300 à 600 s par équipe, 60 à
120 s pour `B − 1`), sur les vingt-cinq rangs réalisables de la gamme. `besoin`
est la première taille de la grille `B`, `B+1`, `B+2`, `B+3`, `B+6`, `B+12`,
`B+24`, `B+36`… qui tient tous les sièges : une borne haute du besoin réel.

| Rang | Borne `B` | Besoin | Écart |
| --- | --- | --- | --- |
| 1 à 9 | 2, 4, 3, 4, 6, 5, 7, 9, 8 | `B` | 0 |
| 10 (journées types multiples) | 13 | 14 | 1 |
| 11 (semaine à cheval, nuit) | 7 | 9 | 2 |
| 12 à 16 | 14, 14, 45, 32, 41 | `B` | 0 |
| 17 (règles désactivées) | 60 (35 avant l'ajout ci-dessous) | `B` | 0 |
| 18 (effectifs par fenêtre) | 58 | 82 | 24 |
| 19 | 53 | `B` | 0 |
| 20 (festival type) | 100 | 112 | 12 |
| 21 à 25 | 70, 123, 100, 168, 200 | `B` | 0 |

Ce que cela dit :

- **La borne n'est jamais trop haute** : aucune équipe de `B − 1` n'a tenu les
  sièges, sur aucun rang. Un « ne suffit pas » de la borne est fiable.
- **Elle égale le besoin sur 21 rangs sur 25.** Les rangs 10 et 11 demandent une
  à deux personnes de plus (le repos quotidien après une soirée, que la borne
  ignore). Les rangs 18 et 20 demandent 24 et 12 de plus ; à `B`, le meilleur
  plan enfreint le non-chevauchement (et, au rang 18, le travail continu
  maximal), sans que la mesure dise si c'est un fait de la grille que la borne
  ne voit pas ou la limite de la recherche : les deux moteurs, essayés sur le
  rang 17 avant l'ajout, s'arrêtent au même score, ce qui plaide pour un fait
  de la grille et non de la recherche.

### Ce que la borne gagne

**Les sièges trop longs pour se partager un jour** (`grosSieges`). La borne d'un
jour comptait le pic simultané, le volume d'heures sur le plafond quotidien et
les fenêtres de repas. Elle ignorait l'empaquetage : deux sièges de plus de la
moitié du plafond quotidien (travail effectif plus la pause, comme pour le
volume) dépassent ensemble ce plafond, donc nul ne tient deux d'entre eux le même
jour, et chacun veut sa personne. C'est une minoration prouvée du même type que
le volume (le plafond est le même) ; elle ne peut que monter le besoin d'un jour.
Au rang 17, dont les sièges font plus de cinq heures, le minimum passe de 35 à 60
et devient exact ; aucun autre rang ne bouge (le test le dit : leur borne est
inchangée).

Les rangs 10, 11, 18 et 20 restent ouverts : le repos quotidien entre deux jours
(onze heures) et le travail continu ne se laissent pas ajouter au flot des jours
de repos sans le réécrire, et ce n'est pas l'objet d'ici.

### Conséquence sur le défaut

L'équivalence n'est pas totale (21 rangs sur 25) : **le défaut reste le calcul**.
Le test fixe l'écart de chaque rang (`ALLOWED_GAPS`) : une règle qui l'élargit
casse le test. Le défaut passera à la borne par la propriété quand l'écart sera
nul partout, ou prouvé dû à la recherche seule.

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
