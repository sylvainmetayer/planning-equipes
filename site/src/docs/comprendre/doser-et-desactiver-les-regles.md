# Doser et désactiver les règles

Vous savez comment une règle s'éteint ou se pondère, ce que la modale de confirmation garantit et ne garantit pas, comment l'écran dit à quoi une règle est réglée et quel geste proposer quand elle est en défaut, et comment lire un plancher, cette règle qui pénalise tout faute de donnée.

## L'écran dit à quoi la règle est réglée

Une description répond au *pourquoi*, jamais au *combien*. « Pas plus de jours consécutifs que le plafond réglé sur la page Paramètres » laissait le lecteur chercher le bon onglet et parier que le champ trouvé était celui que la phrase désignait. Chaque règle porte donc, **sur sa propre ligne**, les réglages qu'elle lit : le libellé du champ mot pour mot, et le champ lui-même à la valeur de l'édition. Un réglage lu par plusieurs règles (la pause, la fenêtre repas) apparaît sur chacune de leurs lignes et se modifie à n'importe laquelle : c'est une seule valeur. Le serveur envoie la clé du réglage (`cle`) avec son libellé, l'écran n'a pas à reconnaître un libellé pour savoir quel champ il édite. Rien ne s'écrit avant « Enregistrer ».

Une règle liste **tous** les réglages qu'elle lit, pas seulement son seuil vedette. `dureeHebdomadaireMax` le justifie : son plafond est hebdomadaire, mais la semaine se mesure en travail effectif, qui dépend de la durée de la pause. Trois choses n'y figurent pas, aucune par oubli :

- le **poids**, qui a sa propre colonne (l'importance) et arbitre entre règles d'un même niveau plutôt que de dire ce qu'une règle mesure ;
- une **constante du Code du travail** (les 10 h quotidiennes, les 11 h de repos, les six jours de repos hebdomadaire) : aucun formulaire ne la change, la description est l'endroit où elle est nommée ;
- une **donnée de référentiel** (le plafond d'une typologie, le caractère premium d'un stand, les appréciations d'un animateur) : ce sont des lignes, pas des réglages, et l'écran y renvoie par le plancher quand leur absence aplatit une règle.

L'inventaire est [`ConstraintParameters`](repo:src/main/java/dev/sylvain/planning/solver/ConstraintParameters.java), à côté de `ConstraintCatalog` et pour la même raison : une liste écrite à la main pourrit en silence dès qu'une règle lit un paramètre de plus. `ConstraintParametersStructuralTest` lit les sources des règles, résout ce que chacune lit à travers ses méthodes d'appui et échoue sur toute lecture non déclarée. Deux formes lui échappent et sont nommées avec leur raison : un paramètre qui arrive en fait du problème (les fenêtres de repas, montées par `ReferenceDataService`) et un accesseur à argument lu hors du dossier balayé (`dureePauseMinutes(mineur)` et `PauseSurPoste.dues`, que les cinq règles de durée déclarent toutes).

## Quand une règle est en défaut : le geste, pas le poids

« 554 correspondances » est une mesure, pas une suite. L'écran Problèmes proposait sur douze règles en défaut douze fois le même bouton, baisser le poids, alors que les explications nommaient le vrai geste sans lien : monter le niveau d'un animateur, retirer le drapeau premium, relever le plafond. Un écran qui ne propose que de s'en soucier moins n'aide pas à corriger.

**Le catalogue dit le geste, l'écran le rend** ([ADR 0064](repo:docs/decisions/0064-le-geste-d-une-regle-en-defaut-vient-du-catalogue.md)). `ConstraintCatalog` porte, pour chaque règle medium et soft (et pour les règles dures dont le levier est précis), la liste ordonnée de ses **leviers** (`Lever`, un type, jamais une phrase), et `BlockerPlaybook` traduit chaque levier en une navigation positionnée sur le premier écart. La liste règle par règle est dans le catalogue et dans `GET /api/constraints` (champ `actions`) : elle y est vérifiée par test, elle pourrirait ici.

| Levier | Bouton | Ce qu'il ouvre |
|---|---|---|
| `SEAT` | « Qui peut tenir ce siège ? » | le banc du siège, dans le Diagnostic, et son « Placer » |
| `SKILL` | « Saisir la compétence » | la grille des compétences filtrée sur la typologie du stand |
| `STAND_PROFILE` | « Ouvrir la fiche du stand » | effectif, drapeau premium ou épuisant, référent |
| `STAFFING` | « Baisser l'effectif demandé » | les horaires du stand, ce jour-là |
| `CAP` | « Régler le plafond » | la ligne de la règle sur Règles du planning |
| `THRESHOLD` | « Régler les seuils » | la même ligne, pour une règle qui mesure contre des seuils |
| `REPAIR` | « Proposer une réparation » | la page Planning, sur le jour et le stand de l'écart |
| `GAME_CATEGORY_CAP` | « Régler le plafond de la typologie » | l'écran Typologies |
| `ANIMATEUR_PROFILES` | « Revoir les fiches » | la fiche de la personne : indisponibilités, souhaits |
| `WORKLOAD` | « Voir la charge par personne » | la page Planning, par personne |
| `LOCK` | « Verrouiller ce qui doit tenir » | les verrouillages |
| `ADJUSTMENTS` | « Revoir l'ajustement » | les ajustements manuels en cause |
| `MEAL_WINDOW`, `SHIFT` | « Régler la fenêtre repas », « Raccourcir la vacation » | la ligne de la coupure repas, les créneaux |
| `RULE` | « Voir la règle » | la ligne de la règle |

Quatre invariants tiennent l'ensemble, chacun testé par `BlockerPlaybookTest` :

- aucune règle medium ou soft n'a le poids pour seul geste : chacune nomme au moins un levier qui change le plan ou le référentiel, et ce levier vient en premier ;
- « Baisser l'importance » vient toujours en dernier, ajouté par le playbook à toute règle pesée, jamais à une règle dure, et plus du tout quand le poids est déjà au plus bas : un bouton qui ne peut rien baisser ment ;
- seul ce bouton parle du poids : le premier geste reprend mot pour mot le conseil de la règle, sauf quand ce conseil propose de baisser l'importance ;
- « Régler le plafond » ou « les seuils » n'existe que sur une règle qui lit un réglage (`ConstraintParameters`) : sans champ à remplir, la ligne n'a rien à régler.

Le « où » vient de la même analyse. Le pivot dit sur quels jours, stands et personnes les écarts se concentrent, mais pas quel créneau de quel stand, la seule chose qu'on ouvre pour corriger. `BreachHotspots` croise donc stand et créneau pour les trois endroits qui rassemblent le plus d'écarts de chaque règle (`hotspots`, des identifiants seulement), et chacun ouvre la page Planning sur ce siège. Le passé figé en est exclu : un siège déjà commencé ne se corrige plus. Une règle agrégée qui ne nomme aucun lieu (l'équilibre de la charge) se rabat sur les stands, puis les jours, les plus touchés. Quand l'écart tombe sur un siège tenu par une seule personne, « Qui peut tenir ce siège ? » devient « Qui peut remplacer ? » et s'ouvre sur sa place à elle.

## Activer et désactiver

La désactivation vaut pour le prochain solve : elle empêche le flux de produire des correspondances, elle ne touche ni au poids ni à la logique.

Une ligne de `constraint_toggle` porte l'**état explicite** d'une règle (`nom`, `actif`) ; son absence vaut ce que dit le catalogue. Presque toutes les règles sont actives par défaut et la table ne porte alors que les désactivations. Celles que le catalogue livre **éteintes** sont dans `ConstraintCatalog.DESACTIVEES_PAR_DEFAUT` : `mineurNecessiteEncadrementMajeur` ([ADR 0041](repo:docs/decisions/0041-encadrement-des-mineurs-eteint-par-defaut.md)) et `maxJoursConsecutifsTravaillesDur`. Pour celles-là, c'est l'allumage qui écrit une ligne. Demander l'état que le catalogue donne déjà **efface la ligne** : la table ne porte que les décisions que quelqu'un a prises, comme une pondération remise à son défaut.

Un scénario dit les deux directions : `contraintes.desactivees` pour ce qu'il éteint, `contraintes.activees` pour ce qu'il allume alors que le catalogue le livre éteint. Une règle qu'il ne cite pas revient au défaut du catalogue.

L'état est injecté comme fait de planification et consulté par `ConstraintToggleSupport.actif(...)` **en queue de flux, sur les écarts**, juste avant la pénalité. Les écarts d'une règle se comptent en dizaines là où ses sièges se comptent en milliers : un gardien posé en tête de flux rendrait la tête impartageable entre règles (`SeatStreams`). Une règle pilotée par `ContrainteAdHoc` garde son gardien sur les quelques faits ad hoc. C'est là, et pas seulement dans le service, que le défaut du catalogue est lu : un harnais Java qui construit son propre problème obtient exactement ce qu'obtient une édition à laquelle personne n'a touché.

> **Une règle dont le flux n'est pas enrobé par `ConstraintToggleSupport.actif` affiche un interrupteur sans effet.** `ConstraintToggleStructurelleTest` échoue sur toute règle du catalogue dans ce cas, et `ConstraintToggleTest` vérifie le mécanisme sur une règle représentative par famille : le même jeu de données doit être pénalisé sans le toggle et valoir exactement zéro avec.

### Qui est présent sur une ligne stand × créneau

Trois règles lisent qui tient un stand à une heure : un référent présent (`standComplexeAvecReferent`), pas plus de mineurs que de majeurs (`repartitionMineursParCreneau`), un débutant à côté du référent (`favoriserMixiteDesNiveaux`). Toutes trois lisent la même ligne (`SeatStreams`) : les places **encore tenues** sur le reste du créneau. La place d'origine d'une place scindée dans la journée ([ADR 0066](repo:docs/decisions/0066-le-passe-est-fige-a-la-minute.md)) s'arrête là où sa suite commence : celui qui est parti à 9 h 20 n'est ni référent, ni majeur, ni débutant pour l'après-midi. Hors scission (tout plan avant le jour J), rien ne change.

### Désactiver une règle légale

Le solveur peut alors produire un planning **contraire au Code du travail tout en affichant un score dur à zéro**, sans que rien dans le score le signale. Quatre catégories sont protégées (`CATEGORIES_PROTEGEES`, champ `protegee` de l'API) : « Légal (mineurs) », « Légal (temps de travail) », « Sécurité (mineurs) » et « Organisation (repas) ». Les autres se décochent sans friction : elles arbitrent du confort.

**Deux seulement sont fondées en droit** (`CATEGORIES_LEGALES`, champ `legale`) : les deux « Légal (…) ». « Sécurité (mineurs) » et « Organisation (repas) » sont des règles que l'organisateur s'est données : les lever l'engage autant, mais le planning n'en devient pas illégal, et la modale ne doit pas le prétendre. Un avertissement qui en dit trop est un avertissement qu'on apprend à passer : la modale choisit sa formulation sur `legale`, le serveur décide, jamais un test sur le libellé de catégorie côté client.

L'écran demande une confirmation qui nomme la règle, rappelle ce qui la fonde et reprend la formule des conditions d'utilisation : **l'organisateur reste l'employeur et le responsable** du planning produit. Réactiver ne demande rien. **L'API, elle, ne demande rien** : un client MCP ou un `curl` désactive sans passer par la modale, la garde est ergonomique, pas contractuelle.

**Confirmation seule, décision assumée** : pas de motif, pas de journal, pas de colonne d'auteur. Sans notion d'utilisateur, une trace ne serait pas imputable. Reste l'état : l'écran Règles du planning montre en permanence ce qui est désactivé, et le bandeau partagé le répète partout où un solve se lance ou se juge. Une vraie piste d'audit suppose d'abord de décider qui a le droit de lever une règle légale.

## Pondérer une règle

Chaque règle pénalise d'un poids littéral dans son code ; ce défaut est surchargeable par `ConstraintWeightOverrides` sans toucher au Java. Trois couches, la plus proche l'emportant : le déploiement (`planning.constraint-weights.*`, **1 pour une règle dure, 5 pour une règle medium ou soft**, 25 pour la stabilité du plan publié), l'édition (`ponderation_contrainte`), et le scénario quand le fichier épingle son dosage. Le poids d'une règle va de 1 à 500 ; `0` est refusé. Il se pose par `PUT /api/constraints/{name}/poids` ou l'outil `modifier_poids_contrainte` ([API des règles](api-regles-et-diagnostic.md)).

### L'importance en trois positions

L'écran ne demande pas un nombre : le bloc « Politique de l'organisation » de Règles du planning offre à chaque règle un niveau (obligatoire, ou une préférence **forte, normale ou légère**, qui écrivent un poids de 25, 5 et 1) et le bloc « Avancé » trois positions aux préférences (`core/importance.ts`, `ConstraintCatalog.POIDS_FAIBLE/NORMAL/FORT`). Le défaut livré est la position du milieu, pour que « baisser l'importance » ait toujours un sens : un défaut à 1 était déjà le minimum. Un facteur cinq entre deux positions voisines suffit pour qu'une règle forte passe avant une normale, sans écraser les autres de son niveau ([ADR 0057](repo:docs/decisions/0057-importance-d-une-regle-en-trois-positions.md)).

Un poids qui n'est pas l'une des trois valeurs reste permis, le panneau de la règle porte le champ numérique, et s'affiche « personnalisée ». Un dosage mémorisé par une résolution passée et son score disent sous quels poids ce plan a été calculé : deux plans calculés sous des dosages différents ne se comparent pas à poids égaux, et la comparaison de Versions du plan le dit.

### Le niveau : obligatoire ou préférence

Une règle d'organisation a un **niveau choisi par édition** ([ADR 0111](repo:docs/decisions/0111-le-niveau-d-une-regle-est-le-niveau-de-son-poids.md), qui applique [0106](repo:docs/decisions/0106-obligatoire-est-un-niveau-reglable-par-edition.md)). **Obligatoire** : la règle compte au niveau dur du score, au même rang que les règles légales ; un plan qui la viole est infaisable et le diagnostic la nomme. **Préférence** : la règle compte au niveau medium, au poids de sa position (légère 1, normale 5, forte 25). C'est la **même règle** dans les deux cas, écrite une fois : seul change l'endroit du score où va son écart, et le poids numérique reste réglable en avancé.

Le niveau se choisit pour les règles moyennes de « Qualité d'organisation », la coupure repas et l'encadrement des mineurs par un majeur (`niveauReglable` dans `GET /api/constraints`). **Les règles légales, d'affectation, ad hoc et de verrou restent obligatoires, et les préférences souples restent des préférences** : un niveau demandé pour elles est refusé. Une règle obligatoire compte un point dur par écart ; le poids stocké pour elle est gardé, sans s'appliquer, pour le jour où elle redevient une préférence. Rendre obligatoire une règle qu'une édition ne peut pas tenir donne un plan infaisable : l'analyse de faisabilité le dit avant le calcul. Le dosage mémorisé par chaque résolution garde les niveaux déplacés, et un plan calculé sous d'autres niveaux ne se compare pas à poids égaux.

### Ce qui se dose, ce qui ne se dose pas

Les règles qui varient réellement d'un organisateur à l'autre sont les **medium de « Qualité d'organisation »** (`dosable()`) : elles arbitrent du confort contre du confort, la continuité sur un stand premium contre l'équilibre des charges, les souhaits contre l'expérience. La règle dont on se moque passe en importance faible et se fait battre, celle qui compte passe en forte.

Une règle dure est respectée ou le planning est invalide : son poids ne change que la vitesse de convergence, et **repondérer une règle légale ne la rend ni plus ni moins obligatoire**. Les règles dures n'ont donc que leur interrupteur, l'offrir en trois positions laisserait croire le contraire. Un poids ne change jamais le niveau d'une règle : c'est pourquoi les jours d'affilée existent sous deux formes ([Repos et jours d'affilée](repos-et-jours-d-affilee.md)).

### Garder la trace d'un dosage

Un dosage se règle à tâtons, et le tâtonnement ne vaut que si l'on peut relire ce qu'il a donné. Chaque changement effectif de poids ou d'activation est historisé par édition, valeurs comprises (`GET /api/constraints/historique`, `GET /api/constraints/{name}/historique`, outil `consulter_historique_ponderation`), et chaque résolution mémorise le dosage sous lequel elle a été **lancée**. Le panneau d'une règle pose les changements et les scores qui ont suivi, et la comparaison de Versions du plan signale deux plans que des poids différents rendent incomparables. C'est une juxtaposition, pas une mesure d'effet.

### Les règles d'équité

`equilibrerCharge` met `loadBalance().unfairness()`, un `BigDecimal` typiquement inférieur à 1, à l'échelle ×10 avant de tronquer en `int`. Sans cela, tout déséquilibre modéré tronquait à 0 et le solveur n'avait aucun gradient à suivre. Ce facteur est lui-même un choix de pondération : pour « plus ou moins d'importance », passez par `planning.constraint-weights.equilibrerCharge` ; ne touchez à `UNFAIRNESS_SCALE` que pour « plus ou moins de granularité ». `equilibrerCreneauxPenibles` applique la même technique, sur le seul sous-ensemble des postes épuisants ou premium.

## Le plancher : une règle qui pénalise tout faute de donnée

Sur une édition réelle mesurée avant la grille d'appréciations, la majeure partie du score medium était une constante : deux règles matchaient l'intégralité de ce qu'elles évaluent parce que la donnée mesurée n'existait pas au référentiel (aucun souhait déclaré, aucun animateur au niveau référent). Les règles étaient correctes ; mais un organisateur qui lit un gros score medium ne peut pas savoir qu'une grande part ne bougera jamais. Le défaut se reproduit sur toute édition dont une donnée n'est pas saisie, ce qu'est n'importe quelle édition au premier jour.

Chaque analyse lit donc chaque règle non dure comme un **ratio** : `écarts ÷ éléments évalués`, où le dénominateur est le grain de la règle (sièges pourvus pour `souhaitsIncompatibles`, groupes stand × créneau pour `standComplexeAvecReferent`, paires consécutives pour `eviterChangementEmplacementEloigne`, sièges publiés pour `stabiliteDuPlanPublie`). La table est [`ConstraintFloorRules`](repo:src/main/java/dev/sylvain/planning/solver/ConstraintFloorRules.java), exhaustive par test : une règle medium ou soft ajoutée sans ligne fait échouer `ConstraintFloorRulesTest`.

Onze règles n'ont pas de lecture par élément et ne sont jamais un plancher : celles qui n'ont qu'une correspondance agrégée (`equilibrerCharge`, `equilibrerCreneauxPenibles`), une récompense (`affiniteAdHoc`), et les huit qui pénalisent par une **fonction de poids** (`repartitionMineursParCreneau`, `eviterRoulementStandsPremium`, `limiterEmplacementsParJour`, `limiterTypologiesDistinctesParAnimateur`, `maxJoursConsecutifsTravailles`, `eviterFermeturePuisOuverture`, `coupureRepasPlacementPrefere`, `preserverBufferPolyvalents`). Pour celles-là, le nombre de correspondances est un écart et non un booléen par élément : un ratio de 1,0 veut dire « tout le monde est en écart d'une quantité que le solveur peut réduire », l'inverse d'un plancher.

**À partir de 95 %** (`FLOOR_THRESHOLD`, pas 100 % : une poignée de sièges échappe toujours), la règle est signalée : une marque dans la colonne Écarts de Règles du planning, et dans son panneau le ratio et la donnée nommée, avec un lien vers l'écran de saisie quand le référentiel n'en contient aucune (souhaits, appréciations, référents). Un plancher que rien de tel n'explique est signalé quand même, sans lien : un stand premium que personne ne peut légalement tenir seul est tout aussi constant. Celui-là demande un échantillon : sous dix éléments évalués (`FLOOR_MIN_SAMPLE`), il n'est pas dit, car une correspondance sur une fait 100 % de rien. Un plancher que le référentiel explique est signalé quelle que soit la taille : « aucun souhait déclaré » est aussi vrai sur trois sièges que sur trois mille.

Ne sont **pas** des planchers : les cas où la donnée absente rend la règle *inerte* (zéro stand premium, zéro emplacement, zéro mineur, zéro stand épuisant). La règle ne matche rien, ne coûte rien, et « satisfaite » est exact.

Le **score hors plancher**, le score brut moins, niveau par niveau, ce que coûtent les règles signalées, est la part qu'une résolution peut faire bouger. Il s'affiche à côté du score brut sur le récapitulatif du Solveur et dans la comparaison de Versions du plan (`kpi` d'un instantané ; absent, jamais zéro, sur un instantané pris avant la mesure). **Rien n'est désactivé** : une règle au plancher reste active, c'est à l'organisateur de saisir la donnée, baisser le poids ou éteindre la règle en connaissance de cause ([ADR 0031](repo:docs/decisions/0031-signaler-le-plancher-sans-le-decider.md)).

Ce que ces règles mesurent se lit sur la page Planning, par personne (`GET /api/planning/equite`), qui dit colonne par colonne si une règle du solveur la pèse et si elle est active : les heures de soirée, de week-end ou de jour férié n'y sont mesurées par aucune règle, et l'écran le dit. Les heures de week-end et de jour férié sont attribuées en entier à la date du créneau, comme la semaine ISO l'est par `Creneau.semaineIso()` ; seules les heures de soirée sont au prorata, parce que la soirée est une heure de la journée et non une journée.
