# 0109 — Un verrou a trois types, et les anciens se convertissent en ce qu'ils figeaient

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : domaine (`TypeVerrouillage`), solveur (catalogue, contraintes, moteur direct), schéma (migration `V144`), MCP, écran Verrouillages, guide de migration 4.0
- **Remplace en partie** : [0003](0003-verrouillage-par-pin-natif.md) (la liste des cibles d'un verrou)
- **Prolonge** : [0091](0091-refonte-fonctionnelle-4-0.md) (trois mécanismes de gel)

## Contexte

Le verrou avait cinq types : un animateur sur tout l'événement, un stand sur
tout l'événement, une journée, un créneau pour tous les stands, et un animateur
sur un créneau (celui que pose un échange accepté). Trois mécanismes de gel
cohabitent en 4.0 (le verrou, la phase de l'édition, l'état de la journée) et
le verrou est celui qu'on explique en une phrase : *ce que le calcul ne doit
plus déplacer*. Cinq types, dont deux qui ne se distinguaient d'un autre que
par l'étendue, ne tenaient pas dans cette phrase.

Un seul des cinq portait en plus une règle : l'animateur verrouillé
(`animateurVerrouilleFige`) ne devait recevoir aucune place nouvelle, sinon
l'épinglage des places tenues n'aurait gelé que la moitié de son planning.

## Décision

Trois types, les trois grains auxquels on fige : **animateur + créneau**,
**stand + journée**, **journée**. Un verrou « tout un animateur » ou « tout un
stand » est un verrou par créneau ou par journée.

- Seul animateur + créneau garde sa règle dure (`animateurVerrouilleCreneauFige`).
  `animateurVerrouilleFige` est retirée : son nom est dans
  `ConstraintCatalog.ANCIENS_NOMS` comme retiré, et le moteur direct perd la
  même règle, ce qui ramène le décompte de 47 à 46.
- Stand + journée et journée n'ont pas de règle : elles épinglent les places
  tenues, comme avant.
- La migration `V144` convertit chaque ancien verrou **en ce qu'il figeait sur le
  plan enregistré**, de sorte que le plan ne bouge pas à la prochaine résolution :
  animateur → un verrou animateur + créneau par créneau où il tient une place ;
  stand → un verrou stand + journée par jour où le stand a des places ; créneau →
  un verrou animateur + créneau par animateur qui y tient une place. Un verrou
  dont le plan ne tient aucune place figeait déjà rien et disparaît.
- Les verrous n'étant pas dans le fichier YAML d'un scénario, aucun maillon de
  conversion de scénario n'est nécessaire.

## Options écartées

- **Convertir en l'étendue la plus proche** (créneau → journée, stand →
  stand + journée sur chaque jour de l'édition, animateur → un verrou par créneau
  de l'édition) : la conversion fige plus, ou bien ajoute des lignes qui ne
  figent rien, et un plan peut changer à la première résolution.
- **Garder l'animateur sur tout l'événement** : c'est le type qui coûtait une
  règle et un second sens à « verrouiller », au profit d'un geste que la fiche
  de l'animateur reproduit d'un clic.

## Conséquences

- Perte documentée : l'interdiction de donner à un animateur verrouillé une
  place sur un créneau où il n'en tenait pas. Les places vides restent, comme
  avant, non verrouillables.
- Une sauvegarde d'avant le vidage d'une édition (ADR 0084) prise en 3.x peut
  porter un type retiré : la restauration le dit dans son compte rendu et ne le
  pose pas.
- L'outil MCP `verrouiller` garde son nom (déjà conforme à l'ADR 0100) ; seules
  les valeurs de son argument `type` changent.
