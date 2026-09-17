# 0041 — L'encadrement des mineurs est une règle éteinte par défaut

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : solveur (catalogue), référentiel (états de contraintes), scénarios
- **Révise** : [0035](0035-mineur-seul-au-forfait.md)
- **Prolonge** : [0006](0006-obligation-legale-ou-politique-organisateur.md)

## Contexte

`mineurNecessiteEncadrementMajeur` impose au solveur qu'un majeur tienne le même
stand, sur le même créneau, que tout mineur affecté. Elle n'a **aucune base dans
le Code du travail** : le code le disait déjà, en la cataloguant « Sécurité
(mineurs) » plutôt que « Légal (mineurs) » et en portant le marqueur *JUR-2 non
vérifié*. C'est une politique d'organisateur, au sens de
[0006](0006-obligation-legale-ou-politique-organisateur.md).

Or l'organisation qui exploite l'application a des **managers hors planning**,
présents en permanence à côté des stands. L'encadrement que le solveur réclamait
est assuré par eux, et la règle lui coûtait des affectations qu'elle voulait
faire : un mineur ne pouvait pas tenir un stand seul, même de jour, même avec un
manager à trois mètres.

Le cadre légal des mineurs, lui, ne bouge pas : `travailDeNuitInterditPourMineur`
(art. L3163-1), `standReserveAuxMajeurs`, `travailInterditJourFerieMineur`, les
plafonds de durée et les repos restent actifs et inchangés.

## Options envisagées

**A. Retirer la contrainte.** Moins de code, mais aucune autre organisation ne
pourrait plus réclamer l'encadrement au solveur — et le produit vise d'autres
organisations que celle-ci.

**B. La désactiver par défaut.** Elle reste au catalogue, décrite, activable.
Il n'existait cependant **aucun mécanisme** pour cela : `ConstraintToggle`
signifiait « désactivée », et l'absence de ligne valait « active », pour toutes
les contraintes sans exception.

## Décision

**B**, en créant le mécanisme qui manquait.

1. Le catalogue porte `ConstraintCatalog.DESACTIVEES_PAR_DEFAUT`. Une contrainte
   qui y figure n'est pas appliquée tant que personne ne la demande.
2. `ConstraintToggle` porte l'**état explicite** d'une contrainte (`nom`,
   `actif`) ; son absence vaut « ce que dit le catalogue ». La table
   `constraint_toggle` gagne la colonne `actif` (migration V82) ; les lignes déjà
   écrites signifiaient « désactivée » et prennent `actif = FALSE`.
3. `ConstraintToggleSupport` lit le défaut du catalogue. Une règle active par
   défaut est affamée par un état `false` ; une règle éteinte par défaut n'est
   nourrie que par un état `true`. Le défaut vaut donc **aussi dans les harnais
   Java qui construisent leur propre problème**, et pas seulement pour les
   appelants qui interrogent le service.
4. Un état égal au défaut du catalogue **efface la ligne** au lieu de l'épingler :
   la table ne porte que les décisions que quelqu'un a prises.
5. La section `contraintes:` d'un scénario gagne `activees:`, symétrique de
   `desactivees:`. Une règle qu'un fichier ne cite nulle part revient au défaut
   du catalogue — et non plus « à actif », ce qui rallumerait à chaque import
   précisément la règle que le déploiement a décidé de ne pas appliquer.
6. `FeasibilityAnalyzer` reçoit l'état de la règle : son estimation de capacité
   appariait chaque mineur à un majeur, et aurait déclaré infaisables des
   plannings parfaitement pourvus.

[0035](0035-mineur-seul-au-forfait.md) reste vraie **quand la règle est
allumée** : un mineur seul coûte alors toujours `ExclusionEligibilite.FORFAIT`.
Elle n'est pas remplacée, elle est conditionnée.

## Conséquences

- Par défaut, un mineur peut tenir un stand seul **de jour**. Il ne peut
  toujours pas tenir un créneau de nuit, ni un jour férié, ni un stand réservé
  aux majeurs.
- L'écran Contraintes affiche la règle comme désactivée, et l'allumer est ce qui
  écrit une ligne — l'image inversée de ce qu'un interrupteur fait ailleurs.
- `extreme-12-uniquement-des-mineurs` demande explicitement la règle : le
  fichier décrit un problème vérifié avec elle, et sans cette ligne le cas
  dégénéré ne dégénérerait plus.
- La responsabilité de l'encadrement est **hors planning**. Ce n'est pas un avis
  juridique : c'est un choix d'organisation, consigné dans
  `docs/audit-conformite-rh.md`.
