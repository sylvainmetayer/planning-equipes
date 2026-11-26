# 0117 — Une exception ad hoc n'impose plus rien : les forcées sont converties

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : domaine (`TypeContrainteAdHoc`), solveur (catalogue, contraintes, moteur direct), schéma (migration `V156`), lecture des scénarios, mode jour J, MCP, écran Ajustements manuels, guide de migration 4.0
- **Révise** : [0010](0010-contraintes-ad-hoc-contradiction-plutot-que-budget.md) (la liste des types dont on détecte les contradictions)
- **Prolonge** : [0091](0091-refonte-fonctionnelle-4-0.md) (une seule façon de dire une chose), [0109](0109-trois-types-de-verrou.md) (le verrou animateur + créneau)

## Contexte

Une exception ad hoc avait cinq types, dont deux disaient « cette personne,
ici ». L'indisponibilité forcée écartait quelqu'un d'un périmètre (un créneau,
un stand, tout l'événement) ; l'affectation forcée en exigeait au moins un
parmi plusieurs. Chacune était une règle dure du solveur, écrite deux fois
(flux de contraintes et moteur direct), avec ses lectures d'intenabilité
(trois classes, trois avertissements, trois causes de faisabilité) et ses
contradictions entre elles.

Or l'organisateur dispose déjà des deux gestes équivalents, plus simples : le
**verrou animateur + créneau** (ADR 0109) garde quelqu'un sur un créneau, et le
**jour indisponible de la fiche** écarte quelqu'un d'un jour. Deux façons de
dire la même chose, dont l'une est une règle à part qui peut contredire l'autre,
sont une de trop.

## Décision

`INCOMPATIBILITE`, `AFFINITE` et `ARRIVEE_GROUPEE` restent : elles disent un
rapport entre deux personnes, ce qu'un verrou ni la fiche ne savent dire.
`AFFECTATION_FORCEE` et `INDISPONIBILITE_FORCEE` disparaissent, avec leurs deux
règles (`affectationForcee`, `indisponibiliteForcee`, nommées dans
`ConstraintCatalog.ANCIENS_NOMS` comme retirées : le décompte passe de 42 à 40),
leurs trois lectures d'intenabilité et leurs contradictions.

**Elles sont converties, pas perdues** — à la migration `V156`, et à la lecture
d'un fichier ou d'une copie de la 3.x (`ScenarioMigrations`), avec le même
résultat et un avertissement par cas :

| Avant | Après |
| --- | --- |
| affectation forcée sur un créneau | un verrou animateur + créneau par animateur nommé qui tient une place du périmètre sur le plan enregistré |
| indisponibilité forcée sur un créneau | le **jour entier** ajouté aux jours indisponibles de la fiche |
| indisponibilité forcée sans créneau | tous les jours qui portent un créneau |
| indisponibilité forcée sur un stand ; affectation sans créneau ou que personne ne tient | rien : signalé, gardé dans `contrainte_ad_hoc_avant_4_0` jusqu'en 5.0 |

Le plan enregistré ne change pas : la conversion n'écrit que des verrous et des
jours, jamais une place. Un test le vérifie sur une base remplie.

Le mode jour J marquait une absence par une indisponibilité forcée par créneau
restant. Il écrit désormais le jour entier sur la fiche : un solveur qui
n'a pas de règle par créneau n'a pas à en inventer une pour un événement de
midi. Une absence n'est « listée » que si le plan **publié** attendait la
personne ce jour-là.

## Options envisagées

**Garder l'indisponibilité forcée pour le seul jour J.** Évite la perte de
granularité sur un cas fréquent, mais garde la règle dure, ses deux écritures et
ses contradictions pour un seul écran. Écartée ; reprise possible si l'usage
montre qu'une absence sur un seul créneau est courante (voir ci-dessous).

**Convertir une indisponibilité de créneau en verrou « interdit ».** Un verrou
dit ce qu'il fige, jamais ce qu'il interdit (ADR 0109) : le faire parler de
l'absence casserait la phrase qui le définit.

## Conséquences

- La perte est nommée : une indisponibilité forcée sur **un seul créneau**
  devient un jour entier. Un plan enregistré où la personne tient un autre
  créneau ce jour-là porte désormais un écart dur (`animateurDisponible`) à lire
  sur la page Problèmes ; la migration le signale dans son journal, le guide de
  passage à la 4.0 le décrit.
- Le détecteur de contradictions ne lit plus que deux combinaisons : une même
  paire incompatible et en affinité, une arrivée groupée dont deux membres sont
  incompatibles. L'ADR 0010 reste vraie pour ce qui reste.
- L'aide intégrée et les guides portent « Comment faire sans affectation
  forcée » : verrouiller le créneau, ou marquer le jour indisponible sur la fiche.
- Le décompte des règles du moteur direct baisse de deux ; le test différentiel
  et le test de couverture tiennent les deux écritures.
- Une absence du jour J sur un seul créneau libère le reste de la journée
  (`creneauId` ne la réduit plus). À rouvrir si le retour d'usage le demande.
