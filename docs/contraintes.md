# Référentiel de contraintes

Trois niveaux, alignés sur le `HardMediumSoftScore` de Timefold : **dur**
(bloquant), **medium** (fortement pénalisé, signalé à l'organisateur) et
**soft** (préférence, qui départage deux plannings valides).

> **Ne jamais reclasser une contrainte dure en medium ou soft sans validation
> explicite**, en particulier tout ce qui touche au cadre légal des mineurs.

**La liste des 39 contraintes, leur niveau, leur catégorie, leur description
métier et l'article de loi qui les fonde vivent dans `ConstraintCatalog`** — et
sont servies par `GET /api/constraints`, affichées sur la page Contraintes. Ce
document ne les recopie pas : il porte les mécanismes et les arbitrages.

## Fenêtre effective : ce qui la lit, et ce qui ne la lit pas délibérément

Un stand fermé pour **une partie** d'un créneau — ou normalement fermé et
n'ouvrant que sur des fenêtres précises — génère un poste par segment encore
ouvert, chacun portant une fenêtre horaire *effective* plus étroite que le
créneau. `posteDoitEtrePourvu` s'applique à ces postes réduits : personne n'est
exigé sur la plage fermée.

Ce mécanisme **ne crée jamais de créneau supplémentaire** : la clé étrangère
reste le créneau réel, la fenêtre effective vit sur le poste.

Deux familles lisent cette fenêtre plutôt que celle du créneau :

- **`pasDeChevauchementHoraire`** — deux postes du même animateur sur deux
  segments qui ne se recouvrent pas réellement ne sont plus signalés à tort ;
- **les cumuls d'heures et les repos** — un poste réduit ne compte que le temps
  réellement couvert, et le repos qui suit démarre à la fin de ce temps réel.

Une famille reste **délibérément calée sur le créneau entier** :

- **`travailDeNuitInterditPourMineur`**. Une règle de sécurité pour mineurs ne
  doit jamais devenir *plus permissive* comme effet de bord d'une
  fonctionnalité de disponibilité de stand.

## Typologie ninja et buffer de polyvalents

Au plus une typologie porte le drapeau `ninja` (index unique partiel, V30). Un
animateur qui la possède dans ses compétences est **polyvalent** :

- `hasCompetenceFor(stand)` renvoie vrai pour **n'importe quel** stand — il
  n'est jamais pénalisé par `appreciationIncompatible` ;
- il est exclu de `limiterTypologiesDistinctesParAnimateur` : le disperser est
  précisément sa raison d'être ;
- `preserverBufferPolyvalents` pénalise chaque créneau où il ne reste aucun
  polyvalent libre.

Cette dernière est **volontairement en tension** avec `equilibrerCharge` : un
polyvalent laissé en réserve déséquilibre mécaniquement la charge. L'arbitrage
est porté par les niveaux — soft contre medium, donc l'équilibrage l'emporte
sauf à égalité. Tant qu'aucune typologie n'est ninja, personne n'est polyvalent
et la contrainte ne coûte rien.

> **Retirée : `favoriserRotationDesStands`.** Elle pénalisait chaque paire de
> postes du même animateur sur le même stand. Supprimée parce qu'elle entrait
> en conflit avec deux règles de niveau supérieur qui poussent à la stabilité —
> `eviterRoulementStandsPremium` et `limiterTypologiesDistinctesParAnimateur` —
> et ne faisait donc que du bruit dans le score. **Plus rien n'exprime
> aujourd'hui de préférence pour la variété des stands** : rétablir ce besoin
> suppose d'abord d'arbitrer contre ces deux règles.

## Activer / désactiver

Pour le prochain solve uniquement : la désactivation empêche le stream de
produire des matches, elle ne touche ni au poids ni à la logique. Toutes les
règles sont actives par défaut ; `constraint_toggle` ne porte que les
désactivations (présence d'une ligne = désactivée).

L'état est injecté comme fait de planification et consulté par
`ConstraintToggleSupport.actif(...)` **juste après le `forEach` initial, sur le
flux le plus étroit possible** : une contrainte pilotée par `ContrainteAdHoc`
branche le toggle sur les quelques faits ad hoc, pas sur les milliers de postes.

> **Une contrainte oubliée par `ConstraintToggleSupport.actif` affiche un
> interrupteur sans effet**, sans que rien ne le signale. `ConstraintToggleTest`
> couvre une contrainte représentative par famille : le même jeu de données doit
> être pénalisé sans le toggle et valoir exactement zéro avec.

### Désactiver une règle légale : confirmation, et rien d'autre

Le solveur peut alors produire un planning **contraire au Code du travail tout
en affichant un score dur à zéro**, et rien dans le score ne le signale.

Trois catégories sont protégées (`CATEGORIES_PROTEGEES`, champ `protegee` de
l'API) : « Légal (mineurs) », « Légal (temps de travail) » et « Sécurité
(mineurs) » — cette dernière n'est pas une obligation du Code du travail mais
une règle posée par l'organisateur, et la lever engage tout autant. Les autres
se décochent sans friction : elles arbitrent du confort.

L'IHM demande une confirmation qui nomme la règle, rappelle l'article qui la
fonde et reprend la formule des conditions d'utilisation — **l'organisateur
reste l'employeur et le responsable** du planning produit. Réactiver ne demande
rien : remettre une règle légale ne mérite aucune cérémonie.

**L'API, elle, ne demande rien** : un client MCP ou un `curl` désactive sans
passer par la modale. La garde est ergonomique, pas contractuelle.

**Confirmation seule, décision assumée** : pas de motif, pas de journal, pas de
colonne d'auteur. Sans notion d'utilisateur, une trace ne serait **pas
imputable** — l'auteur ne vaudrait que la constante `ui` ou `mcp`. Ce qui reste,
c'est **l'état** : l'écran Contraintes montre en permanence ce qui est
désactivé, et le bandeau partagé le répète partout où un solve se lance ou se
juge.

Une vraie piste d'audit suppose d'abord une notion d'utilisateur : un journal
(une ligne par changement, jamais supprimée) plutôt qu'une table d'état, et le
cas échéant un blocage — qui suppose de décider **qui** a le droit de lever une
règle légale.

## Pondérer une contrainte

Chaque contrainte pénalise d'un poids littéral dans son code ; ce défaut est
surchargeable par `ConstraintWeightOverrides` sans toucher au Java. Trois
couches, la plus proche l'emportant : le déploiement
(`planning.constraint-weights.*`, **1 partout**), l'édition
(`ponderation_contrainte`), et le scénario quand le fichier épingle son dosage.

Valeurs de 1 à 100 ; `0` est refusé — voir [`api.md`](api.md#contraintes).

### Ce qui se dose, et ce qui ne se dose pas

Les règles qui varient réellement d'un organisateur à l'autre sont les
**MEDIUM de « Qualité d'organisation »** (`dosable()`) : elles arbitrent du
confort contre du confort — la continuité sur un stand premium contre
l'équilibre des charges, les souhaits contre l'expérience. La règle dont on se
moque descend à 1 et se fait battre, celle qui compte monte.

Les autres ne se dosent pas dans le même sens : une contrainte dure est
respectée ou le planning est invalide, son poids ne change que la vitesse de
convergence. **Repondérer une règle légale ne la rend ni plus ni moins
obligatoire.**

Le contrôle, lui, est le **même pour les 39 règles** : un champ « Poids » de 1
à 100. Deux contrôles différents selon la famille laissaient croire à deux
mécanismes ; il n'y en a qu'un, seul le sens de la valeur change.

### Le cas des contraintes d'équité

`equilibrerCharge` met `loadBalance().unfairness()` — un `BigDecimal`
typiquement inférieur à 1 — à l'échelle ×10 avant de tronquer en `int`. Sans
ça, tout déséquilibre modéré tronquait à 0 et **le solveur n'avait aucun
gradient à suivre** pour améliorer l'équité.

Ce facteur est lui-même un choix de pondération. Pour « plus ou moins
d'importance », passer par `planning.constraint-weights.equilibrerCharge` ;
ne toucher à `UNFAIRNESS_SCALE` que si le besoin est « plus ou moins de
granularité ».

`equilibrerCreneauxPenibles` applique la même technique, mais sur le seul
sous-ensemble des postes épuisants ou premium.

## Hors périmètre assumé

Ces obligations sont réelles et **volontairement non implémentées**. Elles sont
listées pour que leur absence soit un choix écrit, pas un oubli.

**Durée hebdomadaire moyenne de 44 h sur 12 semaines (L3121-22).** Le festival
dure 15 jours, soit 2 à 3 semaines ISO : **le solveur ne peut pas calculer cette
moyenne**, il ne connaît ni les 9 semaines précédentes ni les suivantes. Limite
structurelle du périmètre, pas manque d'implémentation. Le contrôle relève du
service RH, à partir du cumul par semaine que la page Heures expose déjà.

**Travail de nuit des majeurs (L3122-1 et suivants).** `chevaucheNuit()` n'est
consulté que par les contraintes mineurs, alors que les scénarios livrés
comportent un créneau nocturne quotidien. La qualification de « travailleur de
nuit » dépend d'un seuil et d'une régularité, mais aussi du contrat, d'un accord
collectif ou d'une autorisation de l'inspection — des faits que l'application ne
détient pas. La numérotation exacte des articles reste **non vérifiée**. Sujet à
instruire avec un juriste ; d'ici là, contrôle manuel.

| Autre sujet | Pourquoi hors périmètre |
| --- | --- |
| Heures supplémentaires, contingent annuel (L3121-30) | Le planning produit est l'assiette du décompte, pas le décompte |
| Nature des indisponibilités | `joursIndisponibles` est un `Set<LocalDate>` non typé : impossible de distinguer un repos légal, un congé payé et une convenance |
| Autorisation d'inspection pour les moins de 16 ans (L4153-3, D4153-2) | Donnée administrative absente du modèle, à vérifier manuellement |
| Dérogation sectorielle aux jours fériés (R3164-2) | Non instruite ; le défaut le plus protecteur s'applique |

## Ajouter une contrainte

1. Implémenter dans la classe de famille, en enrobant le stream initial de
   `ConstraintToggleSupport.actif(...)`.
2. L'enregistrer dans `PlanningConstraintProvider.defineConstraints`.
3. Ajouter sa description métier dans `ConstraintCatalog` — niveau, catégorie,
   libellé, et l'article s'il y en a un. `ConstraintCatalogTest` échoue si le
   catalogue et le provider divergent, **dans un sens comme dans l'autre**.
4. Écrire un cas pénalisé **et** un cas valide dans le `*ConstraintsTest` de la
   famille. Pour une contrainte dure, vérifier que `PlanningHardConstraintsTest`
   passe toujours.

**Une contrainte n'est pas terminée tant que ces tests ne passent pas.**

### Une contrainte qui ne coûte pas cher

Le coût se mesure au nombre de tuples que le stream construit et maintient à
chaque mouvement, pas à la longueur du code.

- **Restreindre avant de joindre** : un `filter` placé après un
  `forEachUniquePair` a déjà payé la construction de toutes les paires.
- **Préférer `Joiners.equal` / `lessThan` / `overlapping` à
  `Joiners.filtering`** : un joiner indexé est un accès par hachage, un
  `filtering` un prédicat évalué sur chaque combinaison. `lessThan` sur le
  `@PlanningId` reproduit la sémantique « chaque paire une seule fois » tout en
  autorisant un flux d'entrée déjà filtré.

Chiffres mesurés et protocole dans
[`developpement.md`](developpement.md#réglage-du-solveur).
