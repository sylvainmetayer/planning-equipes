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
| [0002](0002-diagnostic-decouple-du-solveur.md) | Découpler le diagnostic de contraintes de l'édition du solveur | **Remplacé** par 0013 |
| [0003](0003-verrouillage-par-pin-natif.md) | Figer les affectations validées par le mécanisme natif du solveur | Accepté |
| [0004](0004-competence-transverse-dans-les-typologies.md) | Une compétence transverse réutilise l'énumération des typologies | Accepté |
| [0005](0005-polyvalence-reserve-plutot-que-reservation.md) | Polyvalence : garantir une réserve, plutôt que réserver les personnes | Accepté |
| [0006](0006-obligation-legale-ou-politique-organisateur.md) | Distinguer l'obligation légale de la politique de l'organisateur | Accepté · principe transverse |
| [0007](0007-instantanes-contenu-denormalise.md) | Instantanés de plan : un contenu dénormalisé, pas une copie de lignes | Accepté |
| [0008](0008-file-sequentielle-avant-parallelisme.md) | Résoudre les variantes en file séquentielle avant d'envisager le parallélisme | **Retiré** · remplacé par 0009 |
| [0009](0009-edition-unique-porteur-de-variantes.md) | L'édition est l'unique porteur de variantes | Accepté · révise 0001, remplace 0008 |
| [0010](0010-contraintes-ad-hoc-contradiction-plutot-que-budget.md) | Contraintes ad hoc : détecter la contradiction, pas plafonner le nombre | Accepté |
| [0011](0011-publier-plutot-qu-envoyer-a-tous.md) | Publier, plutôt qu'envoyer à tous : le planning publié est distinct du planning de travail | Accepté |
| [0012](0012-etat-de-vue-dans-l-url.md) | L'état de vue d'un écran est porté par l'URL | Accepté · précisé par 0018 |
| [0013](0013-diagnostic-par-le-score-director.md) | Diagnostiquer par le score director, sans mode dégradé | Accepté · remplace 0002 |
| [0014](0014-analyser-le-plan-persiste.md) | Analyser le plan persisté, plutôt que résoudre pour jeter | Accepté · prolonge 0013 |
| [0015](0015-sauvegarde-par-pg-dump-restauration-hors-application.md) | Sauvegarder par `pg_dump`, restaurer hors de l'application | Accepté |
| [0016](0016-purge-manuelle-avant-automatisation.md) | Purger à la main d'abord, automatiser quand la charge le justifie | Accepté |
| [0017](0017-fragilite-le-ninja-est-un-renfort-pas-un-specialiste.md) | Fragilité : le ninja est un renfort, jamais un spécialiste | Accepté · prolonge 0005 |
| [0018](0018-ecrire-l-url-de-vue-sans-naviguer.md) | Écrire l'URL de vue sans naviguer | Accepté · précise 0012 |
| [0019](0019-jeton-et-chemin-dedies-pour-l-abonnement-ics.md) | Un jeton et un chemin dédiés pour l'abonnement au calendrier | Accepté |
| [0020](0020-avertir-dans-la-reponse-d-ecriture.md) | Avertir dans la réponse d'écriture, pas dans un contrôle à part | Accepté · prolonge 0010 |
| [0021](0021-import-tabulaire-partiel-et-previsualise.md) | L'import tabulaire d'animateurs est partiel, prévisualisé et rejoué | Accepté |
| [0022](0022-import-de-la-grille-des-stands.md) | La matrice des stands s'importe sous le contrat de 0021 | Accepté · transpose 0021 |
| [0023](0023-modification-concurrente-par-horodatage.md) | Détecter une modification concurrente par horodatage, sans verrou | Accepté |

**0002** et **0013** se lisent ensemble : la première pose le blocage du
diagnostic par l'édition du solveur et retient deux modes de qualité inégale,
la seconde montre que le verrou ne portait que sur une façade et n'en garde
qu'un seul. **0014** en tire la conséquence : l'analyse ne coûtant plus qu'un
calcul de score, elle se dérive du plan persisté au lieu d'une résolution dont
on jette le résultat.

Deux décisions se lisent ensemble : **0001** pose le cloisonnement par édition,
**0009** le révise en supprimant le second niveau qu'il avait retenu. **0008**
documente une orchestration livrée puis retirée par 0009 — son raisonnement est
conservé parce que ses mesures gardent leur valeur pour toute réflexion future
sur le parallélisme.
