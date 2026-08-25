# Décisions d'architecture

Ce dossier consigne les **décisions structurantes** du projet : ce qui a été
choisi, contre quelles alternatives, et ce que ça engage. Il ne remplace pas la
documentation de référence de [`docs/`](../README.md), qui décrit *ce que fait*
le système — ici on trouve le **pourquoi**.

## Convention

Un fichier par décision, nommé `NNNN-titre-court.md`, numéroté dans l'ordre où
la décision a été prise. Chaque document ouvre sur son statut, sa date et sa
portée, puis suit le même plan : contexte, options envisagées, décision,
conséquences.

Une décision n'est jamais réécrite après coup. Quand elle est révisée, son
statut le dit et renvoie vers celle qui la remplace — c'est la chaîne des
révisions qui a de la valeur, pas la dernière version seule.

Le format reste **court**, une page. Les décisions étayées par des mesures
portent celles-ci en annexe, à condition qu'elles soient reproductibles sur un
scénario versionné de `src/main/resources/scenarios/`.

## Index

| # | Décision | Statut |
| --- | --- | --- |
| [0001](0001-cloisonnement-par-edition.md) | Cloisonner le référentiel et les résultats de solveur par édition | Accepté · révisé par 0009 |
| [0002](0002-diagnostic-decouple-du-solveur.md) | Découpler le diagnostic de contraintes de l'édition du solveur | **Proposé** · non implémenté |
| [0003](0003-verrouillage-par-pin-natif.md) | Figer les affectations validées par le mécanisme natif du solveur | Accepté |
| [0004](0004-competence-transverse-dans-les-typologies.md) | Une compétence transverse réutilise l'énumération des typologies | Accepté |
| [0005](0005-polyvalence-reserve-plutot-que-reservation.md) | Polyvalence : garantir une réserve, plutôt que réserver les personnes | Accepté |
| [0006](0006-obligation-legale-ou-politique-organisateur.md) | Distinguer l'obligation légale de la politique de l'organisateur | Accepté · principe transverse |
| [0007](0007-instantanes-contenu-denormalise.md) | Instantanés de plan : un contenu dénormalisé, pas une copie de lignes | Accepté |
| [0008](0008-file-sequentielle-avant-parallelisme.md) | Résoudre les variantes en file séquentielle avant d'envisager le parallélisme | **Retiré** · remplacé par 0009 |
| [0009](0009-edition-unique-porteur-de-variantes.md) | L'édition est l'unique porteur de variantes | Accepté · révise 0001, remplace 0008 |
| [0010](0010-contraintes-ad-hoc-contradiction-plutot-que-budget.md) | Contraintes ad hoc : détecter la contradiction, pas plafonner le nombre | Accepté |
| [0011](0011-publier-plutot-qu-envoyer-a-tous.md) | Publier, plutôt qu'envoyer à tous : le planning publié est distinct du planning de travail | Accepté |

Deux décisions se lisent ensemble : **0001** pose le cloisonnement par édition,
**0009** le révise en supprimant le second niveau qu'il avait retenu. **0008**
documente une orchestration livrée puis retirée par 0009 — son raisonnement est
conservé parce que ses mesures gardent leur valeur pour toute réflexion future
sur le parallélisme.
