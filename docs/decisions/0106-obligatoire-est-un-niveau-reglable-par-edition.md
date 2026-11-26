# 0106 — « Obligatoire » pour une règle d'organisation : un niveau réglable par édition

- **Statut** : accepté
- **Date** : novembre 2026
- **Portée** : solveur (catalogue, contraintes, moteur direct), configuration des règles par édition
- **Prolonge** : [0006](0006-obligation-legale-ou-politique-organisateur.md), [0045](0045-le-niveau-de-la-regle-des-jours-d-affilee.md), [0057](0057-importance-d-une-regle-en-trois-positions.md), [0087](0087-un-second-moteur-de-recherche-arbitre-par-timefold.md)

## Contexte

Une organisation veut pouvoir dire d'une règle d'organisation, édition par
édition, « celle-ci est obligatoire » ou « celle-ci est une préférence ». Le
mot appelle une garantie : un plan qui la viole n'est pas livrable. Dans le
solveur, la seule chose qui porte cette garantie est le niveau **dur** du
score : un plan dont le score dur n'est pas nul est déclaré infaisable, la
recherche cherche d'abord à l'annuler, et le diagnostic dit quelle règle
bloque. Les règles légales y sont déjà, et ne se règlent pas.

Pour les règles d'organisation, la forme actuelle est celle de
[0045](0045-le-niveau-de-la-regle-des-jours-d-affilee.md) : **deux
contraintes** pour une même règle, l'une medium dosée, l'autre dure éteinte par
défaut. Elle marche, mais elle montre à l'utilisateur deux règles là où il
en pense une, et elle ne se généralise pas : aucune autre règle d'organisation
n'a sa forme dure.

Un banc a mesuré ce que coûterait de fusionner des règles d'organisation
(`RuleBenchTest`, qui sait déplacer une règle d'un niveau à l'autre par
`bench.level`). Son constat utile ici est qualitatif : la règle des jours
d'affilée n'est tenue que par sa forme dure, et la retirer laisse le plafond
violé sans que le score medium perdu le montre. Le banc n'a pas mesuré un
poids très élevé au niveau medium : le choix entre les options
ci-dessous repose donc sur la sémantique du niveau, pas sur un chiffre. Le
scénario joué n'est pas versionné : le constat se rejoue sur la gamme, à la
PR qui fusionnera les règles de jours d'affilée.

## Options envisagées

**A. Une règle, un niveau réglable par édition.** Obligatoire est le niveau
dur ; préférence est un niveau pondéré (medium, avec les poids du réglage
d'importance de [0057](0057-importance-d-une-regle-en-trois-positions.md)).
L'écran montre une règle ; le catalogue lui donne un niveau par défaut.

**B. Obligatoire par un poids très élevé au niveau medium.** Aucun niveau
dynamique à écrire. Mais un poids, si grand soit-il, reste un échange : le
solveur peut payer une violation pour gagner ailleurs, et un plan « faisable »
peut violer une règle dite obligatoire. Le mot cesse de vouloir dire ce qu'il
dit, et le diagnostic de faisabilité ne peut plus s'appuyer sur le score dur.

**C. Garder deux contraintes par règle (le modèle de 0045).** Aucun niveau
dynamique, mais deux règles à écrire, à tester et à expliquer par règle
concernée, et l'écran continue de montrer ce que l'organisation ne pense pas.

## Décision

**Option A.** Une règle d'organisation est **une seule règle** dont le niveau
dépend du réglage de l'édition :

- **obligatoire** : la règle contribue au score dur, au même rang que les
  règles légales et les exceptions prescriptives ;
- **préférence** : la règle contribue au score medium, avec son poids ;
- les règles légales restent obligatoires et ne se règlent pas ;
- le niveau est stocké par édition, comme les poids, et le catalogue donne le
  niveau par défaut de chaque règle. Une édition existante garde le niveau
  qu'elle a aujourd'hui : celle dont la forme dure d'une règle est active
  passe en « obligatoire », les autres en « préférence » au poids stocké. Aucun
  plan ne change de score à la migration des réglages ; la fusion des deux
  formes des jours d'affilée en une règle, elle, se mesure (voir plus bas).

Côté Timefold, la règle reste écrite une fois, et le niveau vient du poids qui
lui est donné : la PR qui construit le mécanisme vérifie que
`ConstraintWeightOverrides` accepte un score complet (dur ou medium) pour une
même contrainte configurable. Si ce n'est pas le cas, le repli est deux
déclarations partageant le même corps, dont une seule pèse par édition, sans
changer ce que l'utilisateur voit.

## Conséquences

- **Deux écritures de la règle, et un niveau lu par chacune.** Le flux de
  contraintes et le moteur direct (`IncrementalScore`, `FlatProblem`) comptent
  la même violation ; ce qui change est le composant du score où elle va. Le
  niveau devient une entrée du problème aplati (`FlatProblem`), lue par les
  deux : un niveau lu par une seule des deux écritures est un score faux, que
  le recalcul par Timefold refuse (`HandSearchSolver` échoue plutôt que de
  croire un score différent).
- **Le test différentiel joue chaque règle à niveau réglable aux deux
  niveaux.** Il compare déjà règle par règle et exige qu'une règle ait été
  comparée sur un score non nul ; il doit l'exiger **par niveau**, sans quoi un
  niveau reste vérifié par personne. Les tests unitaires de contrainte
  (`ConstraintVerifier`) et la mutation (PIT) suivent la même exigence : un cas
  pénalisé à chaque niveau.
- **La convergence change avec le niveau.** Une règle obligatoire entre dans la
  phase qui cherche d'abord la faisabilité dure ; les scénarios lents
  (`scenario-lent`) doivent se rejouer avec les règles concernées au niveau
  obligatoire. Une règle rendue obligatoire sur une édition où elle ne peut
  pas être tenue donne un plan infaisable : l'écran doit le dire avant le
  calcul, et le diagnostic des échecs durs doit la nommer.
- **Le nom des règles ne porte plus le niveau.** Les anciens noms des formes
  dures restent lus comme alias pendant toute la 4.x.

### Ce que ça change pour la suite

- **Mécanisme (niveau par règle et par édition)** : c'est lui qui construit le
  niveau dans le catalogue, les paramètres, le calcul des poids et le moteur
  direct, la migration des réglages existants vers des niveaux, et la lecture
  du même niveau par les deux moteurs. Ses tests : le différentiel par
  niveau, la garantie que le légal reste obligatoire, le catalogue.
- **Mesure de la fusion des jours d'affilée** : elle se joue à **chaque niveau**
  de la règle fusionnée (`bench.level=<règle>:HARD` puis `:MEDIUM`), sur la
  gamme et non sur le seul scénario réel, et elle ne peut commencer qu'après
  le mécanisme : sans niveau réglable, il n'y a rien à mesurer. Elle chiffre
  aussi le retrait de l'ordre de construction par calendrier
  ([0068](0068-sous-la-regle-dure-la-construction-suit-le-calendrier.md)),
  qui n'est pas une règle.
- **Fusion des jours d'affilée** : la règle unique est obligatoire par défaut
  pour une édition qui active aujourd'hui la forme dure, préférence sinon ; la
  recherche par jours entiers de
  [0049](0049-la-regle-dure-des-jours-d-affilee-se-cherche-par-jours-entiers.md)
  s'applique quand le niveau est obligatoire. Elle remplace 0045, 0049 et 0068,
  dont les chaînes de révision restent lisibles.
- **Autres fusions de règles d'organisation** : elles héritent du même
  mécanisme. Aucune ne crée une seconde forme dure.
