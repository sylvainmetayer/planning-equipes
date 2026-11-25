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

`StaffingBoundEquivalenceTest`, joué le 25 novembre 2026 sur une machine louée
pour l'occasion (12 cœurs partagés, moteur direct, 300 s par équipe, 60 s pour
`B − 1`), sur les vingt-cinq rangs réalisables de la gamme. `besoin` est la
première taille de la grille `B`, `B+1`, `B+2`, `B+3`, `B+6`, `B+12`, `B+24`… qui
tient tous les sièges : une borne haute du besoin réel.

| Rang | Borne `B` | Besoin | Écart |
| --- | --- | --- | --- |
| 1 à 9 | 2, 4, 3, 4, 6, 5, 7, 9, 8 | `B` | 0 |
| 10 (journées types multiples) | 13 | 14 | 1 |
| 11 (semaine à cheval, nuit) | 7 | 9 | 2 |
| 12 à 16 | 14, 14, 45, 32, 41 | `B` | 0 |
| 17 (règles désactivées) | 35 | 78 | 43 |
| 18 (effectifs par fenêtre) | 58 | 82 | 24 |
| 19 | 53 | `B` | 0 |
| 20 (festival type) | 100 | 112 | 12 |
| 21 à 25 | 70, 123, 100, 168, 200 | `B` | 0 |

Ce que cela dit :

- **La borne n'est jamais trop haute** : aucune équipe de `B − 1` n'a tenu les
  sièges, sur aucun rang. Un `ne suffit pas` de la borne est donc fiable.
- **Sur vingt rangs sur vingt-cinq, la borne est le besoin**, à froid comme à
  partir du plan en place. Sur les rangs 10 et 11 le solve demande une à deux
  personnes de plus (le repos quotidien après une soirée, que la borne ignore).
- **Sur trois rangs, la borne est loin du besoin** : 18 et 20 enfreignent, à `B`,
  le non-chevauchement des postes d'une personne (18 y ajoute le travail continu
  maximal) : le compte jour par jour de la borne ne voit pas que deux sièges
  décalés d'une heure ne se tiennent pas à une seule personne. Sur le rang 17,
  qui éteint la coupure repas et la limite de typologies pour devenir
  réalisable, la recherche ne trouve aucun plan sous l'équipe entière (78) : à
  `B` il ne reste que des sièges vides, et la mesure ne dit pas si c'est la
  limite de la recherche ou une règle que la borne ignore.
- Un `suffit` de la borne est donc une **condition nécessaire**, pas une
  preuve : c'est pourquoi l'écran le formule ainsi et renvoie au calcul, et
  pourquoi le calcul reste une option plutôt qu'une réserve supprimée.

Le test fixe l'écart maximal de chaque rang (`ALLOWED_GAPS`) : une règle qui
l'élargit casse le test, pas l'écran.

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
