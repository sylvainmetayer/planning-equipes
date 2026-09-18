# 0046 — Un placement intenable est dit avant le calcul

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : écriture d'une contrainte ad hoc, écriture d'un verrouillage,
  écriture directe d'un siège (REST et MCP), analyse de faisabilité, écran
  Solveur

## Contexte

Un placement posé à la main peut être intenable au regard des règles dures :
un mineur forcé sur un créneau de nuit, sur un jour férié ou sur un stand
réservé aux majeurs ; une personne dont l'emploi du temps est verrouillé et
qu'une affectation forcée nomme quand même ; un siège écrit directement à
quelqu'un que la règle exclut.

Rien ne le disait au moment du geste. L'organisateur lançait une résolution,
attendait le temps de calcul qu'il avait donné, et lisait à l'arrivée un score
dur négatif qu'il lui restait à décrypter — alors que la cause était connue
avant la première seconde. Le retour de l'organisation est net : une
résolution lancée sur un placement impossible est du temps machine perdu, et
la cause n'apparaît qu'après coup.

Le dépôt savait déjà faire, mais partiellement :

- le glisser-déposer des vues Journée et Rail est simulé sur le plan
  enregistré et refusé s'il dégrade le score dur, en nommant les règles
  cassées ;
- l'analyse de faisabilité sans solveur détecte le sous-effectif, les
  contraintes ad hoc contradictoires et une affectation forcée tombant sur un
  jour déclaré indisponible ;
- le banc de touche liste, pour un siège, les motifs d'exclusion durs d'un
  animateur.

Restaient cinq trous, tous sur le même thème : le geste passe, et c'est la
résolution qui paie.

## Options envisagées

**(A) Refuser à l'écriture.** Le geste intenable est rejeté, point final.
Écarté pour les affectations forcées : la donnée qui rend l'exception
intenable arrive presque toujours **après** elle. Un mineur le devient à la
saisie de sa date de naissance, un stand devient réservé aux majeurs, un
verrou est posé le lendemain. Refuser l'exception ne tiendrait que jusque-là,
et refuser la déclaration qui la casse n'est pas une option. C'est la
doctrine que `Avertissement` porte déjà : seul ce qui est **certainement**
insatisfiable est refusé, et aucun de ces cas ne l'est.

**(B) Ne rien dire et laisser le score parler.** L'état actuel. Écarté : le
score dur négatif est exactement ce que l'organisateur ne sait pas lire, et il
arrive après la dépense.

**(C) Avertir à l'écriture, reporter dans la faisabilité, demander
confirmation avant le calcul.** Retenu. Trois moments, trois intensités, une
seule lecture des règles.

**(D) Une nouvelle règle dure par cas.** Écarté : il n'y a rien de nouveau à
interdire. Les règles existent déjà (`travailDeNuitInterditPourMineur`,
`standReserveAuxMajeurs`, `animateurVerrouilleFige`…), et ce qui manquait
était de les **lire avant** le solveur, sur le couple place × animateur que
`EligibleAnimateurMoveFilter` sait déjà juger.

## Décision

### Ce qui est dit, et quand

| Geste | Ce qui est détecté | Réponse |
| --- | --- | --- |
| Écriture d'une affectation forcée | Aucune place de sa portée n'accepte les animateurs qu'elle nomme, au titre d'une règle dure du couple place × animateur | Avertissement `AFFECTATION_FORCEE_MOTIF_LEGAL`, l'exception est écrite |
| Écriture d'une affectation forcée | L'emploi du temps de chacun des animateurs nommés est verrouillé sur toute la portée, et aucun n'y tient déjà de place | Avertissement `AFFECTATION_FORCEE_SIEGE_VERROUILLE`, l'exception est écrite |
| Écriture d'un verrouillage | Les places figées cassent déjà une règle dure dans la dernière analyse | Avertissement `VERROUILLAGE_SUR_VIOLATION_DURE`, le verrou est écrit |
| Écriture directe d'un siège (`POST /api/postes/{id}/affectation`, `affecter_poste`) | Le geste dégraderait le score dur, ou introduirait une violation dure sur le siège lui-même | **Refusé**, en nommant les règles |
| « Calculer », « Corriger », « Recommencer de zéro » | La faisabilité porte au moins une cause CRITIQUE autre qu'un sous-effectif | Confirmation, le calcul part si l'organisateur le dit |

Les trois avertissements sur affectation forcée se lisent **une fois chacun**
et ne se doublent pas : le jour déclaré indisponible est aussi un motif
d'exclusion dur, et c'est la lecture en termes de déclaration qui garde le
cas — c'est elle que l'organisateur sait traiter, en parlant à la personne.

### Pourquoi le siège écrit directement, lui, est refusé

C'est le seul geste de la liste qui n'est pas une **règle posée pour plus
tard** mais une **écriture immédiate**. Rien n'arrivera après elle pour la
rendre tenable : la place, la personne et le créneau sont connus au moment du
clic. Il est donc jugé comme le glisser-déposer l'est déjà, sur le plan
enregistré préparé comme une résolution le prépare — les contraintes que
l'édition a éteintes, ses poids, ses paramètres.

Le verdict a deux moitiés, et chacune laisse passer ce que l'autre attrape :

- **le score dur global** attrape ce que le siège ne montre pas — un plafond
  hebdomadaire, un repos cassé sur une place que le geste ne touche pas ;
- **les violations introduites sur le siège lui-même** attrapent ce que le
  score cache : remplir une place vide solde un point dur
  (`posteDoitEtrePourvu`) et peut le dépenser ailleurs, si bien qu'un mineur
  posé sur une nuit laisse le total plat en cassant une règle en pleine vue.

C'est exactement l'invariant que l'assistant de réparation applique déjà pour
écarter un candidat ; l'écriture le partage désormais au lieu de le supposer.

**Libérer un siège n'est jamais scoré.** La place coûte son point
`posteDoitEtrePourvu` de toute façon, et marquer une absence en mode jour J
est le geste qui doit passer précisément quand le plan va mal.

### Pourquoi le verrou sur une violation n'est pas refusé

Un verrou fige, il n'exempte pas : les places verrouillées restent jugées par
les règles ([0003](0003-verrouillage-par-pin-natif.md)). Poser un verrou sur
une journée qui porte un écart est donc une décision légitime — c'est même le
sens de la décision 0003 que de la rendre possible sans mentir sur le score.
Ce qui manquait n'était pas un refus mais une phrase, au moment du geste :
sans elle, l'organisateur lance une résolution et lit un score dur négatif
que ce clic explique, sans aucun chemin de retour vers les places en cause.

### Ce que la confirmation avant le calcul ne couvre pas

Le **sous-effectif** ne demande rien, même classé CRITIQUE. Le solveur
l'atténue encore : il remplit ce qu'il peut, et le planning partiel a de la
valeur. Une confirmation qui se déclencherait sur chaque soirée tendue serait
cliquée sans être lue, et ne dirait plus rien le jour où une contradiction
certaine se présente.

La confirmation **demande, elle ne refuse pas**. Lancer quand même est une
chose légitime à vouloir : le reste du planning s'améliore, et la cause
bloquante peut être une que l'organisateur a décidé d'assumer.

### Ce que ça coûte

Les deux lectures sur affectation forcée sont du Java pur, sans solveur, et
s'arrêtent au premier couple place × animateur acceptable — c'est-à-dire
immédiatement dans le cas courant. La lecture des verrous ne descend chercher
les places du plan enregistré **que si un verrou existe** : sans verrou, rien
n'est lu, et l'analyse de faisabilité tourne à chaque ouverture des écrans
Solveur, Édition et Problèmes.

L'écriture directe d'un siège paie deux calculs de score du plan. C'est le
prix du glisser-déposer, sur un geste cent fois moins fréquent.

## Conséquences

- Trois valeurs de plus sur `TypeAvertissement`, deux sur
  `TypeCauseInfaisabilite` : le contrat OpenAPI les publie, et l'IHM les
  distingue. Aucune valeur existante ne change de sens.
- `POST /api/verrouillages` répond `{ verrouillage, avertissements }` au lieu
  du verrou seul, comme les autres référentiels qui préviennent. `verrouiller`
  en MCP rend les **codes**, jamais les phrases
  ([`mcp.md`](../mcp.md)).
- `affecter_poste` et `POST /api/postes/{id}/affectation` peuvent désormais
  répondre 400 sur un animateur que l'écran de réparation n'aurait jamais
  proposé. C'est le comportement voulu : ces deux portes acceptaient
  n'importe qui.
- Une organisation qui veut réellement poser un siège contre une règle dure
  passe par une contrainte ad hoc, qui est tracée avec sa raison — et
  avertie, elle, plutôt que refusée.
