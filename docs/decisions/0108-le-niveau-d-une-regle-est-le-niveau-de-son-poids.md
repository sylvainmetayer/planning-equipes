# 0108 — Le niveau d'une règle d'organisation est le niveau de son poids, stocké avec le poids

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : catalogue des règles, calcul des poids, moteur direct, schéma (migration `V145`), scénario YAML (`schemaVersion: 4`), API et outils MCP
- **Applique** : [0106](0106-obligatoire-est-un-niveau-reglable-par-edition.md) (option A, la vérification qu'elle laissait)
- **Prolonge** : [0057](0057-importance-d-une-regle-en-trois-positions.md)

## Contexte

[0106](0106-obligatoire-est-un-niveau-reglable-par-edition.md) décide qu'une règle
d'organisation est une règle unique dont le niveau (dur ou medium) dépend de
l'édition, et laisse à la PR du mécanisme une vérification : un même poids de
contrainte peut-il porter un score dur ou medium ? Sinon, le repli est deux
déclarations partageant un corps.

## Décision

1. **La vérification est positive, sans repli.** `ConstraintWeightOverrides` de
   Timefold prend un score complet : une règle écrite avec une pénalité d'un point
   medium, surchargée par `HardMediumSoftScore.ofHard(1)`, compte un point dur et
   aucun point medium (`PlanningServiceConstraintWeightOverridesTest`). Le moteur
   direct lisait déjà le niveau du poids surchargé (`FlatProblem.weights`) : le
   banc de règles s'en servait. Aucune règle n'est donc écrite deux fois, et
   `PlanningConstraintProvider` ne change pas.
2. **Le niveau est un champ du poids de l'édition**, colonne `obligatoire` de
   `ponderation_contrainte` (`NULL` : le niveau du catalogue, vrai : obligatoire,
   faux : préférence), et le poids devient facultatif pour qu'une ligne puisse ne
   porter que le niveau. Pas de nouvelle table : la copie d'une édition, la
   restauration et l'isolement par édition suivent la table existante.
3. **Quelles règles ont un niveau à choisir.** Celles que le catalogue marque
   `niveauReglable` : les règles moyennes de « Qualité d'organisation », la coupure
   repas et l'encadrement des mineurs par un majeur. Jamais les règles légales,
   ni les onze règles de base (affectation, exceptions ad hoc, verrous), ni les
   préférences souples (une préférence l'est par nature), ni, pour l'instant, les
   deux formes des jours d'affilée, qui fusionneront en une règle à niveau
   réglable (voir 0045). Un niveau demandé pour une autre règle est refusé en 400.
4. **Le poids suit le niveau.** Obligatoire compte un point dur par écart (le poids
   dur, 1) : le poids que l'édition avait stocké reste stocké pour le retour, sans
   s'appliquer. Une préférence pèse son poids stocké, ou la position « normale »
   (5) pour une règle que le catalogue livre dure. Les trois positions de [0057](0057-importance-d-une-regle-en-trois-positions.md)
   (forte 25, normale 5, légère 1) sont la lecture d'un poids de préférence : l'API
   écrit un poids en choisissant une position, et lit un poids quelconque comme
   la position la plus proche.
5. **Rien ne change à la migration.** La colonne naît `NULL` partout : chaque
   édition garde son niveau et son poids, donc ses scores. Aucune donnée n'est
   réécrite ni supprimée, il n'y a rien à archiver.
6. **Les lectures suivent le niveau appliqué.** Une règle est « dure » dans un
   diagnostic quand ses points sont au niveau dur (`ConstraintCatalog.estDur`),
   pas quand le catalogue la range là : le détail des écarts, la lecture du score
   et les listes de blocages suivent donc le choix de l'édition. Le dosage de
   chaque résolution garde les niveaux déplacés (`Dosage.levels`).

## Options écartées

- **Une table `niveau_regle`.** Plus propre sur le papier, mais six endroits à
  tenir en parallèle (copie d'édition, dump, sauvegarde avant vidage, isolement,
  empreinte, export) pour une colonne.
- **Réécrire les poids en niveaux à la migration.** Aurait détruit les poids
  numériques que l'écran avancé garde, sans rien gagner : le niveau et le poids
  sont deux réglages, pas un.
- **Un niveau pour les préférences souples.** « Obligatoire » sur une préférence
  d'agrément (mixité, répartition des créneaux pénibles) ne veut rien dire.

## Conséquences

- Le test différentiel joue chaque règle à niveau réglable aux deux niveaux et
  exige qu'elle ait mordu à chacun (`HandSearchDifferentialTest`), le fuzz déplace
  des niveaux au hasard.
- Une règle rendue obligatoire sur une édition qui ne peut pas la tenir donne un
  plan infaisable : le diagnostic des échecs durs la nomme ; l'avertissement avant
  le calcul est à l'écran des règles (PR suivante).
- Le changement de niveau est consigné au journal des actions et dans le dosage de
  chaque résolution ; il n'a pas de ligne dans l'historique des poids, qui ne
  décrit que des nombres.
