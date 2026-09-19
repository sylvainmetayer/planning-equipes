# Référentiel de contraintes

Trois niveaux, alignés sur le `HardMediumSoftScore` de Timefold : **dur**
(bloquant), **medium** (fortement pénalisé, signalé à l'organisateur) et
**soft** (préférence, qui départage deux plannings valides).

> **Ne jamais reclasser une contrainte dure en medium ou soft sans validation
> explicite**, en particulier tout ce qui touche au cadre légal des mineurs.

**La liste des 43 contraintes, leur niveau, leur catégorie, leur description
métier et l'article de loi qui les fonde vivent dans `ConstraintCatalog`** — et
sont servies par `GET /api/constraints`, affichées sur la page Contraintes. Ce
document ne les recopie pas : il porte les mécanismes et les arbitrages.

L'écran les range une par ligne, par catégorie, et **le nom d'une règle est son
ancre** : `/constraints#coupureRepasObligatoire` ouvre la page sur cette
règle-là, `/constraints#categorie-legal-mineurs` sur sa catégorie. C'est le nom
servi par l'API, donc l'adresse d'une règle ne bouge que si la règle est
renommée — auquel cas le lien partagé cesse de désigner quoi que ce soit, et
c'est la même rupture que pour tout client de l'API.

Les descriptions restent du **texte brut** côté serveur : la même chaîne part
vers les outils MCP et se range dans une demande d'échange. C'est le frontend
qui, au moment d'afficher, reconnaît les numéros d'article qu'elles citent et
les lie vers Légifrance (`webui/src/app/core/legifrance.ts`, rendu par
`app-legal-text`). Écrire `art. L3162-1` dans une description suffit donc à
obtenir le lien : rien d'autre n'est à déclarer.

## « Écart » à l'écran, `violation` dans le code

Un cas où une contrainte n'est pas satisfaite se nomme un **écart** partout où
l'organisateur le lit : les libellés, l'aide en ligne et cette documentation. Le
mot vaut pour les trois niveaux, ce qu'« infraction » ou « manquement » ne
feraient pas sans accuser une préférence de confort d'un délit.

Le code, lui, garde `violation` : identifiants Java et TypeScript, clés JSON de
l'API (`violations`, `violationsParContrainte`, `violationsIntroduites`),
`ViolationFormatter`. Le renommer romprait le contrat JSON et donc tout client
MCP existant, pour un gain de lecture seulement. **Le décalage est délibéré** :
ne pas « corriger » un libellé vers `violation` ni un identifiant vers `écart`.
Pour la même raison, `docs/rgpd.md` et `docs/securite.md` parlent bien de
violation — de données et de CSP —, deux termes juridiques et techniques qui
n'ont rien à voir avec celui-ci, et les documents datés (audit RH, ADR, revues)
gardent les mots de leur époque.

## Fenêtre effective : ce qui la lit, et ce qui ne la lit pas délibérément

Un stand fermé pour **une partie** d'un créneau — ou normalement fermé et
n'ouvrant que sur des fenêtres précises — génère un poste par segment encore
ouvert, chacun portant une fenêtre horaire *effective* plus étroite que le
créneau. `posteDoitEtrePourvu` s'applique à ces postes réduits : personne n'est
exigé sur la plage fermée.

Ce mécanisme **ne crée jamais de créneau supplémentaire** : la clé étrangère
reste le créneau réel, la fenêtre effective vit sur le poste.

Une consigne d'édition — la bande qu'un arrêté ferme pour tous les stands, et
les fenêtres rouvertes en compensation
([`domaine.md`](domaine.md#consigne-dédition--la-quatrième-couche)) — se lit
par ces mêmes fenêtres effectives : aucune contrainte n'a à la connaître, et
ce qui suit vaut tel quel pour une journée sous consigne, la règle de nuit
des mineurs comprise.

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

## Un quota par typologie

Une typologie peut porter un plafond : `maxCreneauxParAnimateur`, saisi sur sa
fiche, nullable — vide signifie « pas de plafond », et une édition qui n'y
touche pas ne change pas de comportement. La contrainte dure
`plafondCreneauxParTypologie` le fait respecter (issue #594,
[0042](decisions/0042-quota-par-typologie-sur-la-typologie.md)).

Deux choix à connaître avant de s'en servir :

- **L'unité est le créneau**, pas l'heure. Quatre créneaux d'une heure et
  quatre créneaux de six heures pèsent pareil.
- **Un poste compte pour chaque typologie que son stand propose**, pas pour
  celles que son animateur maîtrise. On tient le jeu auquel on est assis, que
  sa fiche le mentionne ou non. C'est le choix inverse de
  `limiterTypologiesDistinctesParAnimateur`, qui lit l'intersection : cette
  règle-là parle de ce qu'une personne doit apprendre, celle-ci de ce qu'un jeu
  consomme.

La portée est l'**édition entière**, jamais la journée ni la semaine. Comme la
règle est dure, un plafond saisi trop bas rend l'édition infaisable plutôt que
de le dépasser : le diagnostic nomme alors qui, quelle typologie, combien de
créneaux tenus pour quel plafond.

La page Typologies porte la lecture correspondante (issue #590) : par
typologie, les animateurs réellement affectés, les postes, les heures, et
l'écart avec ceux que le référentiel apprécie. Un plafond qu'on ne voit nulle
part est un plafond que personne ne peut régler.

## Deux semaines pleines d'affilée

`dureeHebdomadaireMax` plafonne **chaque semaine ISO indépendamment** : rien
n'empêchait donc deux semaines pleines dos à dos, ce qui est exactement la
forme vers laquelle un solveur converge sur un événement à cheval sur deux
semaines avec un besoin tendu.

`dureeHebdomadaireMaxDeuxSemaines` (contrainte dure, issue #593) l'interdit
pour les majeurs. Le seuil est celui de
`ParametresLegaux.dureeHebdomadaireMaxMinutes` — jamais une seconde constante à
48 h qui pourrait diverger — et la pénalité compte les **paires** de semaines
pleines consécutives : trois semaines de suite coûtent deux fois plus, pour que
le solveur ait une pente à descendre plutôt qu'un mur.

C'est la forme courte de l'art. **L3121-22** (44 h en moyenne sur douze
semaines consécutives), la seule qui ait un sens sur quinze jours : le solveur
ne connaît ni les neuf semaines d'avant ni celles d'après. La moyenne glissante
reste donc **hors périmètre, assumé comme tel**.

> **Ce que la règle ne rattrape pas** : 47 h 59 puis 48 h, permis par
> construction. C'est la formulation demandée par l'organisateur, mot pour mot ;
> un plan qui a besoin d'une minute la trouvera là plutôt qu'ailleurs.

Une semaine se juge **pleine en travail effectif**, pas en amplitude : quand
l'organisateur déclare la pause prise sur le poste, elle en est déduite, comme
au plafond quotidien — voir « La durée des pauses » ci-dessous. Les créneaux
étant toujours en heures entières, « strictement sous 48 h » vaut « 47 h au
plus », et une marge paramétrable n'aurait de sens que le jour où des
demi-heures apparaîtraient : il n'y en a pas.

## Le niveau de la règle des jours d'affilée

Deux règles dures garantissent le **jour libre par semaine civile** :
`maxJoursTravaillesParSemaine` (L3132-1) et `reposHebdomadaireMinimal`
(L3132-2, 35 h). Elles laissent pourtant passer mardi→dimanche puis
lundi→samedi — **douze jours d'affilée à zéro écart dur**, chaque semaine
civile ayant bien son jour libre.

Le plafond de six jours **glissants** est donc une règle à part, et c'est une
**politique de l'organisateur**, pas une obligation : L3132-1 se lit sur la
semaine civile (L3121-35), et la Cour de cassation juge que le repos
hebdomadaire n'a pas à tomber au plus tard après six jours consécutifs
(Cass. soc. 13 nov. 2025, n° 24-10.733 ; même lecture CJUE C-306/16).

Elle existe sous deux formes, parce qu'un poids ne change jamais le niveau
d'une règle (`SolverConfiguration.constraintWeightOverrides` mappe sur le
niveau du catalogue, et `ConstraintToggle` ne porte que `actif`) :

| Règle | Niveau | Par défaut |
|---|---|---|
| `maxJoursConsecutifsTravailles` | MEDIUM, dosable | **active**, poids 1 |
| `maxJoursConsecutifsTravaillesDur` | HARD | **éteinte** |

Les deux partagent leur corps et le seuil de six, donc ne peuvent pas diverger
sur ce qu'elles comptent ; les deux peuvent être actives en même temps, ce qui
dit « jamais plus de six, et de préférence moins ». La forme dure s'allume par
l'écran Contraintes, par `activer_contrainte` ou par `contraintes.activees`
d'un scénario — aucune migration, même mécanisme que
[0041](decisions/0041-encadrement-des-mineurs-eteint-par-defaut.md).

Le niveau par défaut sort d'un banc de comparaison, pas d'une intuition. Sur
`festival-hivernal` — la grille de l'organisateur, 153 animateurs, 600 s — la
forme dure tient parfaitement sa promesse, personne au-delà de six jours, et
laisse **23 sièges vides** : la règle est respectée, le festival ne l'est pas.
Au dosage livré, la même grille pourvoit tout mais place **48 personnes
au-delà de six jours, jusqu'à onze d'affilée** — ce n'est pas un défaut du
solveur, c'est ce que l'événement demande à effectif constant, et l'écran
Équité le montre colonne « plus longue série » avant la publication. Monter le
poids à 100 en retire un tiers, sans rien coûter aux autres règles de qualité,
et ne règle pas le fond. Le protocole complet, la fixture à l'aise (`festival-realiste-canicule`, où la
forme dure ne coûte rien) et la décision sont dans
[0045](decisions/0045-le-niveau-de-la-regle-des-jours-d-affilee.md).

## La durée des pauses

La pause qui coupe une période de travail continu se règle sur la page
Paramètres, carte « Paramètres légaux » : `dureePauseMajeurMinutes` et
`dureePauseMineurMinutes` (issue #592). Elles sont lues par
`travailContinuMaxMajeur`, `travailContinuMaxMineur`, `PauseSurPoste` et
l'analyse des pauses — donc par l'écran Pauses et par la carte
« Pause de 18:20 à 18:40 » du PDF animateur.

C'est un **plancher**, à l'inverse des deux durées hebdomadaires qui sont des
plafonds : 20 minutes pour un majeur (art. L3121-16), 30 pour un mineur
(art. L3162-3), refusées en dessous par le service, libres au-dessus. Donner
plus que ce que le Code doit est un choix d'organisation — une relève de 30
minutes s'organise plus simplement qu'une de 20 — et la seule direction
qu'une application ne doit pas laisser prendre est l'autre.

Les constantes `PlafondsLegauxMajeurs.PAUSE_MINIMALE_MINUTES` et
`PlafondsLegauxMineurs.PAUSE_MINIMALE_MINUTES` restent : elles sont la valeur
par défaut et la référence de l'article.

Elles se règlent aussi depuis un assistant : `modifier_parametres_legaux`
(MCP) porte `dureePauseMajeurMinutes` et `dureePauseMineurMinutes`, avec les
mêmes planchers — voir `docs/mcp.md`.

### Ce qui déduit la pause, et ce qui compte l'amplitude

La pause est du **repos**, pas du travail effectif (art. L3121-1) : quand
l'organisateur déclare `pauseSurPoste`, elle est **déduite** des plafonds,
qu'elle soit payée ou non. « Déduite » vaut pour les quatre règles de durée :

| Règle | Ce qu'elle mesure |
|---|---|
| `dureeQuotidienneMaxMajeur`, `dureeQuotidienneMaxMineur` | travail effectif : amplitude moins les pauses dues sur le poste |
| `dureeHebdomadaireMax`, `dureeHebdomadaireMaxMineur` | idem, sommé sur la semaine ISO |
| `dureeHebdomadaireMaxDeuxSemaines` | idem : une semaine est pleine en effectif |

Les trois règles hebdomadaires sommaient l'amplitude jusqu'à l'issue #31, alors
que le plafond quotidien déduisait déjà. L'écart allait dans le sens
protecteur, mais il refusait des plans licites — cinq jours de 10 h d'amplitude
font 48 h 20 de travail effectif, qui étaient comptées 50 h — et surtout il
donnait **deux lectures contradictoires du même planning**, dont aucun
organisateur ne peut rien faire. Le calcul des pauses étant journalier, les
règles hebdomadaires regroupent désormais les sièges par jour avant de
regrouper les jours par semaine (`LegalConstraints.joursAvecParametres`).

Un vrai trou entre deux vacations ne fait rien déduire, au niveau hebdomadaire
comme au quotidien : aucune séquence n'y dépasse six heures, donc aucune pause
n'y est due sur le poste. C'est le trou lui-même qui est la pause.

**Ce que le niveau intermédiaire coûte**, mesuré contre la même résolution sans
lui, même machine, même graine : `gamme-25` 14 303 contre 15 175 mouvements
évalués par seconde (−5,7 %), `extreme-02` 24 988 contre 25 985 (−3,8 %),
`festival-hivernal` 5 313 contre 5 540 (−4,1 %). Les deux premiers finissent
sur le même score après le même nombre de pas, le troisième atteint zéro dur
des deux côtés. Quelques pour cent, pour que le plafond hebdomadaire cesse de
contredire le quotidien.

**Ailleurs, on compte l'amplitude planifiée, et c'est voulu.** L'écran Heures
(`PlanningHoursService`, outil `heures_travaillees`), le tableau d'équité
(`EquiteService`), les KPI (`PlanningKpiService`) et l'analyse d'effectifs
(`StaffingAnalyzer`) somment la durée des vacations sans retrancher les pauses
sur le poste. Ce ne sont pas des plafonds légaux : ce sont des heures
**planifiées**, ce que l'organisateur demande à quelqu'un d'être présent, et
c'est la grandeur qu'on répartit équitablement et qu'on compare d'une édition à
l'autre. Heures planifiées et travail effectif sont donc deux nombres
différents, et il est normal que l'écran Heures affiche un peu plus que ce que
`dureeHebdomadaireMax` mesure. `StaffingAnalyzer` borne par les heures : il est
de ce fait pessimiste, donc sans danger.

## Les seuils de qualité

Les règles de « Qualité d'organisation » lisent leurs seuils dans
`ParametresQualite`, fait de problème comme `ParametresLegaux` :

| Seuil | Défaut | Règle qui le lit |
|---|---|---|
| Emplacements distincts par jour et par animateur | 3 | `limiterEmplacementsParJour` |
| Typologies distinctes par animateur, **sur l'édition entière** | 2 | `limiterTypologiesDistinctesParAnimateur` |
| Heure d'un service tardif | 22:00 | `eviterFermeturePuisOuverture` |
| Heure d'un service matinal | 10:00 | `eviterFermeturePuisOuverture` |
| Repos souhaité après un service tardif | 12 h | `eviterFermeturePuisOuverture` |

Ils sont **persistés par édition** depuis l'issue #591 et se règlent sur la
page Paramètres, carte « Qualité d'organisation » ; le bloc
`planning.contraintes.*` de la configuration donne les valeurs d'une édition
qui n'a jamais ouvert cette carte. Un scénario les emporte dans sa section
`parametresQualite:`, comme il emporte déjà `parametresLegaux:`.

Aucun n'a de plancher d'ordre public : ce sont des conforts que l'organisateur
arbitre. Laisser les deux heures de service vides est d'ailleurs une réponse
légitime — c'est ainsi qu'`eviterFermeturePuisOuverture` se tait
([0031](decisions/0031-signaler-le-plancher-sans-le-decider.md)).

La portée du plafond de typologies est l'**édition**, jamais la journée : le
`groupBy` de la contrainte ne porte que l'animateur, sans clé de date, et un
test le verrouille pour qu'un futur refactor ne le fasse pas glisser en
silence.

## Activer / désactiver

Pour le prochain solve uniquement : la désactivation empêche le stream de
produire des matches, elle ne touche ni au poids ni à la logique.

Une ligne de `constraint_toggle` porte l'**état explicite** d'une contrainte
(`nom`, `actif`) ; son absence vaut *ce que dit le catalogue*. Presque toutes
les règles y sont actives, et la table ne porte alors que les désactivations.
Celles que le catalogue livre **éteintes** sont listées dans
`ConstraintCatalog.DESACTIVEES_PAR_DEFAUT` : pour elles, c'est l'allumage qui
écrit une ligne. Une seule y figure aujourd'hui,
`mineurNecessiteEncadrementMajeur`
([0041](decisions/0041-encadrement-des-mineurs-eteint-par-defaut.md)).

Demander l'état que le catalogue donne déjà **efface la ligne** au lieu de
l'épingler : la table ne porte que les décisions que quelqu'un a prises, comme
une pondération remise à son défaut.

L'état est injecté comme fait de planification et consulté par
`ConstraintToggleSupport.actif(...)` **juste après le `forEach` initial, sur le
flux le plus étroit possible** : une contrainte pilotée par `ContrainteAdHoc`
branche le toggle sur les quelques faits ad hoc, pas sur les milliers de postes.
C'est là, et pas seulement dans le service, que le défaut du catalogue est lu —
un harnais Java qui construit son propre problème obtient donc exactement ce
qu'obtient une édition à laquelle personne n'a touché.

Un scénario dit les deux directions : `contraintes.desactivees` pour ce qu'il
éteint, `contraintes.activees` pour ce qu'il allume alors que le catalogue le
livre éteint. Une règle qu'il ne cite nulle part revient au défaut du catalogue.

> **Une contrainte oubliée par `ConstraintToggleSupport.actif` affiche un
> interrupteur sans effet**, sans que rien ne le signale. `ConstraintToggleTest`
> couvre une contrainte représentative par famille : le même jeu de données doit
> être pénalisé sans le toggle et valoir exactement zéro avec.

### Désactiver une règle légale : confirmation, et rien d'autre

Le solveur peut alors produire un planning **contraire au Code du travail tout
en affichant un score dur à zéro**, et rien dans le score ne le signale.

Quatre catégories sont protégées (`CATEGORIES_PROTEGEES`, champ `protegee` de
l'API) : « Légal (mineurs) », « Légal (temps de travail) », « Sécurité
(mineurs) » et « Organisation (repas) ». Les autres se décochent sans friction :
elles arbitrent du confort.

**Deux d'entre elles seulement sont fondées en droit** (`CATEGORIES_LEGALES`,
champ `legale`) : les deux « Légal (…) ». « Sécurité (mineurs) » et
« Organisation (repas) » sont des règles que l'organisateur s'est données — la
lever l'engage tout autant, mais le planning n'en devient pas illégal, et la
modale ne doit pas le prétendre. Un avertissement qui en dit trop est un
avertissement qu'on apprend à passer : la modale choisit donc sa formulation
sur `legale`, et le serveur décide, jamais un test sur le libellé de
catégorie côté client.

L'IHM demande une confirmation qui nomme la règle, rappelle ce qui la fonde et
reprend la formule des conditions d'utilisation — **l'organisateur reste
l'employeur et le responsable** du planning produit. Réactiver ne demande
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

Le contrôle, lui, est le **même pour les 43 règles** : un champ « Poids » de 1
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

### Le plancher : une règle qui pénalise tout faute de donnée

Mesuré sur l'édition 2026 réelle, avant la grille d'appréciations
(`docs/memoire-du-projet.md`, §2.g) : **68 % du score medium était une
constante**, deux règles matchant l'intégralité de ce qu'elles évaluent parce
que la donnée qu'elles mesurent n'existait pas au référentiel — aucun souhait
déclaré, aucun animateur au niveau référent. Les règles étaient correctes ; un
organisateur qui lit `-6 675 medium` ne peut pas savoir que `-5 000` ne
bougeront jamais.

Le chiffre est daté, et c'est voulu. Mesuré plus tard sur `festival-realiste`
— la fixture qui portait alors des référents et des appréciations, retirée
depuis avec les familles de relais ([0029](decisions/0029-retrait-des-familles-de-relais.md))
—, il ne restait qu'une règle signalée : `souhaitsIncompatibles`, 3 503 sièges
sur 3 503, soit 39 % du medium. Le défaut n'a pas disparu du monde pour autant :
il se reproduit sur toute édition dont une donnée n'est pas saisie, ce qu'est
n'importe quelle édition au premier jour.

Depuis, chaque analyse lit chaque règle non dure comme un **ratio** :
`écarts ÷ éléments évalués`, où le dénominateur est le grain de la règle —
sièges pourvus pour `souhaitsIncompatibles`, groupes stand × créneau pour
`standComplexeAvecReferent`, paires consécutives pour
`eviterChangementEmplacementEloigne`, sièges publiés pour
`stabiliteDuPlanPublie`… La table est `ConstraintFloorRules`, à côté du
catalogue, et elle est **exhaustive par test** : une règle medium ou soft
ajoutée au catalogue sans ligne dans cette table fait échouer
`ConstraintFloorRulesTest`. Onze règles n'ont pas de lecture par élément et ne
sont donc jamais un plancher : une seule correspondance agrégée
(`equilibrerCharge`, `equilibrerCreneauxPenibles`), une récompense
(`affiniteAdHoc`), et les huit règles qui pénalisent par une **fonction de
poids** — `repartitionMineursParCreneau`, `eviterRoulementStandsPremium`,
`limiterEmplacementsParJour`, `limiterTypologiesDistinctesParAnimateur`,
`maxJoursConsecutifsTravailles`, `eviterFermeturePuisOuverture`,
`coupureRepasPlacementPrefere`, `preserverBufferPolyvalents`. Pour celles-là le nombre de correspondances est
un *écart*, pas un booléen par élément : un ratio de 1,0 veut dire « tout le
monde est en écart d'une quantité que le solveur peut réduire », soit
l'inverse d'un plancher.

**À partir de 95 %** (`FLOOR_THRESHOLD`, pas 100 % : une poignée de sièges
échappe toujours, et une règle qui en pénalise 98 % est tout aussi
constante), la règle est signalée : badge « mesure une donnée absente » sur
la page Contraintes, ratio, et la donnée nommée avec un lien vers l'écran de
saisie quand le référentiel n'en contient effectivement aucune — souhaits,
appréciations, référents, ou aucune appréciation au-dessus de débutant. Un
plancher que rien de tel n'explique est signalé quand même, sans lien : une
règle qui matche tout pour une autre raison — un stand premium que personne
ne peut légalement tenir seul — est tout aussi constante. Celui-là demande
toutefois un échantillon : au-dessous de dix éléments évalués
(`FLOOR_MIN_SAMPLE`) il n'est pas dit, parce qu'une correspondance sur une
fait 100 % de rien et qu'une édition en cours de saisie signalerait presque
toutes ses règles. Un plancher que le référentiel explique, lui, est signalé
quelle que soit la taille : « aucun souhait déclaré » est un fait, aussi vrai
sur trois sièges que sur trois mille.

Ne sont **pas** des planchers, et ne sont pas signalés : les cas où la
donnée absente rend la règle *inerte* — zéro stand premium, zéro
emplacement, zéro mineur, zéro stand épuisant. La règle ne matche alors
rien, elle ne coûte rien, et « satisfaite » est exact.

Le **score hors plancher** — le score brut moins, niveau par niveau, ce que
coûtent les règles signalées — est la part qu'une résolution peut faire
bouger : il s'affiche à côté du score brut sur Contraintes, sur le
récapitulatif du Solveur, dans le Comparateur A/B et l'Autopsie (`kpi`
d'un instantané ; absent, jamais zéro, sur un instantané pris avant la
mesure). Le plancher est signé comme le score dont il vient.

**Rien n'est désactivé.** Une règle au plancher reste active : c'est
l'organisateur qui saisit la donnée, baisse le poids, ou éteint la règle en
connaissance de cause — voir
[`decisions/0031`](decisions/0031-signaler-le-plancher-sans-le-decider.md).

Ce que ces règles mesurent se lit sur l'écran Équité (`GET /api/planning/equite`),
qui dit colonne par colonne si une règle du solveur la pèse et si elle est
active : les heures de soirée, de week-end ou de jour férié n'y sont mesurées
par aucune règle, et l'écran le dit plutôt que de le laisser croire. Les deux
dernières sont attribuées **en entier à la date du créneau**, comme la semaine
ISO l'est par `Creneau.semaineIso()` ; seules les heures de soirée sont au
prorata, parce que la soirée est une heure de la journée et non une journée.

## Stabilité du plan publié

`stabiliteDuPlanPublie` (MEDIUM, « Qualité d'organisation », dosable) répond à
un constat mesuré sur l'édition réelle : un calcul relancé après publication
déplaçait des dizaines de personnes déjà prévenues pour un gain d'équité
marginal, parce que rien dans le score ne disait que déplacer une personne
informée a un coût. C'est ce coût.

**Ce qu'elle compte.** Un point medium par siège dont le titulaire n'est pas
celui que le plan publié avait sur ce stand et ce créneau — un point par
personne à qui la publication dirait « votre emploi du temps a changé ». Les
faits sont les sièges du dernier instantané publié (`AffectationPubliee`),
chargés par `SolveRunner.prepareProblem` avant chaque résolution et
chaque diagnostic, jamais envoyés par un client.

**Ce qu'elle compte aussi : un siège publié laissé vide**, au même prix
qu'un remplacement. Son titulaire a été prévenu qu'il y travaillait, le trou
est le sien — et c'est ce qui empêche le solveur d'attirer gratuitement un
titulaire sur une ligne neuve en laissant derrière lui un trou que personne
ne peut combler sans payer (mesuré, ADR 0025).

**Ce qu'elle ne compte pas, délibérément.**

- Rien tant que rien n'a été publié : la liste de faits est vide, la règle
  est muette, le solveur reste libre de tout remanier en amont.
- Un stand ou un créneau créé après la publication : personne n'y a été
  prévenu de rien, le siège est libre.
- Un siège ajouté sur une ligne publiée (l'effectif a grandi) compte son
  nouvel occupant : c'est bien une personne à prévenir.

**Ce qu'elle ne peut pas faire.** Elle est medium : une règle dure ou une
indisponibilité l'emporte toujours (`PlanningHardConstraintsTest`
`publishedSeatsNeverOutweighAHardRule`). Elle ne fige rien non plus — pour
figer, il y a les verrouillages et la replanification incrémentale ; elle
arbitre, au poids près, entre le dérangement et le gain.

**Le poids : 5 par défaut.** Un point par siège déplacé, contre les autres
règles medium à leur poids : `equilibrerCharge` produit des milliers de
points sur une édition réelle, donc à poids 1 la stabilité pèse peu (74
personnes bougent encore pour trois absences sur la fixture, 10 à poids 5).
Le défaut `planning.constraint-weights.stabiliteDuPlanPublie=5` sort du banc
décrit ci-dessous ; il se dose par édition sur l'écran Contraintes comme les
autres règles de qualité, et se désactive par l'interrupteur.

**Le mur des heures pleines, et comment le solveur le franchit.** Quand le
changement ajoute des sièges (un stand nouveau ouvert toute la journée),
pourvoir un siège neuf aux heures pleines demande une chaîne de
déplacements que chaque point de stabilité rend infranchissable pas à pas :
le banc laissait 4 à 5 sièges non pourvus après 600 s. La phase de
faisabilité du solveur porte pour cela un sélecteur *ruin and recreate*
restreint aux sièges de l'heure d'un trou (`HoleNeighbourPosteFilter`,
`solverConfig.xml`), qui évalue la chaîne comme un seul mouvement : 0 écart
dur en moins de trois minutes sur le même cas, une dizaine de personnes de
plus dérangées que le plan à trous, moitié moins que sans la règle. Si un
calcul finit malgré tout avec des écarts durs, les recours sont, dans
l'ordre, « Corriger après un changement », un second « Calculer le
planning », et la désactivation de la règle pour ce calcul.

**Le mur des six jours, et la chaîne qui le franchit.** Un autre trou résiste
au *ruin and recreate* parce que sa chaîne ne passe pas par l'heure du trou
mais par un autre jour de la semaine. Sur l'édition 2026, 26 sièges du
montage du mardi restaient vides après 1800 s alors que 61 personnes étaient
libres ce jour-là : toutes à six jours dans la semaine. En prendre un mardi,
c'est en rendre un autre — le lundi, dont les deux demi-journées doivent
alors aller à deux collègues qui n'en tenaient qu'une. Un mouvement de
changement ne voit que le premier maillon, neutre en dur ; le second est un
changement précis parmi un demi-million. `WeekRelocationMoveIteratorFactory`
joue la chaîne entière comme un seul mouvement : le siège vide à quelqu'un de
libre à cette heure, et les sièges qu'il tenait un autre jour de la même
semaine à des collègues libres à ces heures, éligibles au sens de
`EligibleAnimateurMoveFilter`. Le score juge le reste — heures, repos,
coupures — et le mouvement se dégrade en simple changement quand il n'y a
ni trou ni chaîne. Treize de ces chaînes, construites à la main, avaient
rempli les 26 sièges sans enfreindre une règle ; le solveur les trouve
désormais seul.

**Ce qu'elle ne retient pas : une grille qui change.** La règle tient des
lignes stand × créneau. Tout ce qui rebat ces lignes lui retire sa prise : une
dérivation ou un calendrier de journées types qui remplace la grille (le plan
enregistré part avec). Un stand ajouté, lui, ne déplace pas les autres : c'était le cas du
temps des familles de relais, où un identifiant classé avant les autres
faisait glisser toutes les familles, et le banc avait mesuré 153 personnes sur
153 quel que soit le poids (ADR 0026, puis retrait des familles en ADR 0029).

**Le compte à côté du score.** Chaque résolution rend aussi
`impactPublication.personnes`, le nombre de personnes que la publication
préviendrait — calculé comme la publication le calcule, sur les vacations par
personne, et non par la contrainte. Les deux ne coïncident pas exactement (une
personne qui change deux sièges compte deux points et une personne), et c'est
le compte de personnes que l'écran montre.

**Banc de comparaison** (fixture `festival-realiste`, 153 animateurs, 65
stands, 600 s par résolution, réamorçage depuis le plan publié) — voir la
décision [0025](decisions/0025-stabilite-du-plan-publie.md) pour les
chiffres.

## Contraintes ad hoc : les contradictions refusées à la saisie

`ContrainteAdHoc` dans le domaine et sur le fil, **« Ajustements manuels »** à
l'écran (route `/ad-hoc-constraints`, groupe *Planning* du menu) : ce qu'un
organisateur saisit là est une exception au plan, pas une règle du catalogue, et
les deux se lisaient comme la même chose à côté de l'écran Contraintes. Les cas
limites côté utilisateur — sémantique « l'un de ces animateurs », périmètre
vide, créneau supprimé — sont détaillés dans l'aide en ligne (`/aide`, section
« Ajustements manuels »). L'autre confusion possible est avec le
**verrouillage** : un ajustement est une règle posée *avant* le calcul (placer
ou écarter quelqu'un), un verrou fige *après coup* ce qu'une résolution a
produit sur une partie du plan — l'aide porte un encart « Ajustement manuel ou
verrouillage ? », et [`domaine.md`](domaine.md#verrouillage-partiel-du-planning)
la distinction côté modèle.

Les exceptions saisies à la main s'appliquent en **dur**, à la même priorité que
les règles légales. Rien n'empêche donc, en principe, d'en saisir deux qui ne
peuvent pas être satisfaites ensemble — et le seul symptôme serait un score dur
négatif au solve suivant, sans que rien ne désigne la cause.

`ContrainteAdHocContradictions` refuse ces combinaisons à l'écriture, avec un
message qui **nomme les exceptions en cause**. Quatre familles sont détectées :

| Combinaison | Pourquoi elle est impossible |
| --- | --- |
| Même paire déclarée `INCOMPATIBILITE` **et** `AFFINITE` | La règle dure gagnerait toujours sur la récompense soft, sans que personne ne soit prévenu |
| `AFFECTATION_FORCEE` dont tout le périmètre est couvert par une `INDISPONIBILITE_FORCEE` visant chacun des animateurs qu'elle nomme | Tout poste qui satisferait la première est pénalisé par la seconde |
| Deux `AFFECTATION_FORCEE` fixant **le même animateur** sur des périmètres qui se chevauchent dans le temps | `pasDeChevauchementHoraire` interdit de tenir les deux postes, et aucun poste unique ne satisfait les deux |
| Deux `AFFECTATION_FORCEE` plaçant une paire `INCOMPATIBILITE` sur **le même créneau** | `incompatibiliteAdHoc` joint sur le créneau seul : deux postes sur deux stands de ce créneau sont tout aussi infaisables |

La quatrième ligne se lit **au créneau, pas au stand** : c'est ce que la règle
applique réellement. Restreindre la détection au même stand laisserait passer
des combinaisons que le solveur déclarerait pourtant infaisables.

### Ce qui passe délibérément

La validation ne refuse que ce qui est **certainement** insatisfiable : une
exception refusée à tort coûte à l'utilisateur une saisie légitime, sans
contournement, là où une exception litigieuse laissée passer ne coûte qu'un
solve. Passent donc :

- une `AFFECTATION_FORCEE` nommant deux animateurs, qu'**un seul** d'entre eux
  suffit à satisfaire — elle ne contredit qu'une indisponibilité qui les couvre
  tous les deux ;
- une indisponibilité **plus étroite** que le périmètre forcé : le poste peut
  se poser ailleurs dans ce périmètre ;
- deux affectations forcées du même animateur sur le même créneau quand au plus
  une des deux précise un stand : un seul poste les satisfait toutes les deux.

Les chevauchements se calculent sur la fenêtre **nominale** du créneau. Une
fermeture de stand peut rétrécir la fenêtre réellement couverte par un poste :
deux affectations forcées sur des créneaux qui se recouvrent mais sur deux
stands fermés à des heures complémentaires sont donc refusées alors que le
solveur aurait pu les poser. Le périmètre d'une exception est ce que
l'utilisateur a saisi, et refuser cette combinaison — en nommant les deux — est
la réponse honnête.

### Ce qui est refusé, et où

Le contrôle vaut pour **toute** écriture : la saisie unitaire
(`POST /api/contraintes-ad-hoc`, outil MCP `creer_contrainte_ad_hoc`) comme
l'import d'un scénario, qui écrit tout le jeu d'un coup — un fichier ne peut pas
installer une combinaison que le formulaire refuse. Réenregistrer une contrainte
**sous son propre id** reste possible : la version sauvegardée remplace la
précédente au lieu de coexister avec elle.

### Ce qui subsiste, et comment on le voit

Une contradiction saisie **avant** ce contrôle reste en base et n'apparaîtrait
nulle part. `FeasibilityAnalyzer` la remonte donc comme cause bloquante
`CONTRAINTES_AD_HOC_CONTRADICTOIRES`, avant tout solve : elle s'affiche sur la
page Problèmes, et badge les lignes concernées sur la page Contraintes ad hoc.

Et quand le solve termine **quand même** en dur négatif, le diagnostic répond à
la question suivante — *lesquelles de mes exceptions* ? — avec
`contraintesAdHocEnCause` : une ligne par exception encore en écart, son id, sa
raison et le nombre d'écarts qu'elle porte. Les lignes d'écart
nomment elles aussi l'exception par son id.

**Le budget d'exceptions a été explicitement écarté** : voir
[0010](decisions/0010-contraintes-ad-hoc-contradiction-plutot-que-budget.md).

### L'affectation forcée un jour d'indisponibilité

Une `AFFECTATION_FORCEE` dont **tous** les animateurs sont indisponibles à
**chaque** date de son périmètre ne peut pas être tenue. Ce n'est pas une
contradiction entre exceptions — elle oppose une exception à un jour déclaré —
et elle n'est pas refusée : l'indisponibilité arrive le plus souvent *après*,
par la déclaration de l'animateur, qu'on ne refuse pas. Elle est donc dite deux
fois, sans bloquer : un avertissement `AFFECTATION_FORCEE_JOUR_INDISPONIBLE` à
l'écriture de l'ajustement, et une cause bloquante du même nom dans l'analyse de
faisabilité tant qu'elle tient (`ForcedAssignmentOnDayOff`). Et le solve garde
le jour d'indisponibilité : c'est l'exception qui n'est pas tenue (voir
ci-dessous).

## Les exclusions d'éligibilité pèsent plus lourd que tout

Six règles disent qui ne peut **jamais** tenir un siège : `animateurDisponible`,
`standReserveAuxMajeurs`, `travailDeNuitInterditPourMineur`,
`travailInterditJourFerieMineur`, `dureeQuotidienneMaxMineur`,
`travailContinuMaxMineur` — les motifs de `EligibleAnimateurMoveFilter`. Une
septième porte le même forfait sans être un motif du filtre, parce qu'elle
dépend de qui d'autre tient le stand : `mineurNecessiteEncadrementMajeur`. Un
mineur seul coûtait sinon exactement un siège vide, et sur une équipe de mineurs
seul l'ordre de la recherche départageait
([0035](decisions/0035-mineur-seul-au-forfait.md)). Cette septième règle est
**éteinte par défaut** depuis
[0041](decisions/0041-encadrement-des-mineurs-eteint-par-defaut.md) : le forfait
n'est dû que si l'organisation l'a allumée.

Le filtre les écarte de la recherche, mais la reconstruction du *ruin and
recreate* ne le lit pas, et elles ne pesaient qu'un point dur : autant qu'un
siège vide, moins que quelques minutes de repos. Un plan qui ne pouvait pas
tout tenir plaçait alors un mineur de 15 ans la nuit plutôt que de laisser un
siège vide (`gamme-29`), ou une animatrice le jour de son indisponibilité plutôt
que de laisser une affectation forcée non tenue (`gamme-27`).

Chaque écart coûte désormais un forfait de **10 000 points durs**
(`ExclusionEligibilite.FORFAIT`), plus les minutes de dépassement pour les deux
plafonds comptés à la minute. Un siège vide ou une exception non tenue se lisent
sur la page Problèmes et se traitent ; un siège illégal, lui, ne doit pas sortir
d'un solve quand une alternative existe. Le forfait est dans la pondération des
correspondances, si bien qu'un poids d'édition le multiplie au lieu de le
remplacer. Décision et options écartées :
[0034](decisions/0034-exclusions-eligibilite-plus-lourdes-que-tout.md).

## La frontière de semaine

Les deux repos hebdomadaires se lisent sur la **semaine civile**, du lundi
0 h au dimanche 24 h ([L3121-35], définition supplétive qu'un accord d'entreprise
peut déplacer — donnée que l'application ne détient pas), la même fenêtre que
`Creneau.semaineIso()` et les plafonds hebdomadaires. La question qui revient
est ce que vaut un repos **à cheval sur le lundi**.

**Majeurs, `reposHebdomadaireMinimal` ([L3132-2]).** Le texte est « vingt-quatre
heures consécutives auxquelles s'ajoutent les heures consécutives de repos
quotidien » : les 24 h sont *dans* la semaine, seul le repos quotidien qui s'y
ajoute peut déborder. C'est ce que `creditReposMinutes` calcule —
`min(durée réelle, part dans la semaine + 11 h)`. Un repos à cheval est donc
crédité aux deux semaines, mais il n'atteint le plancher de 35 h que dans celle
où il passe au moins 24 h. Un repos du samedi 20 h au lundi 10 h (38 h) satisfait
la semaine du samedi et laisse la suivante à 21 h, comme la Cour de cassation le
lit (Cass. soc. 13 nov. 2025, n° 24-10.733, au visa des articles L3132-1 et
L3132-2 : « toute semaine civile doit comporter » ce repos).

**Mineurs, `reposHebdomadaireMineur` ([L3164-2]).** « Deux jours de repos
consécutifs par semaine » se compte en **jours civils à l'intérieur de la
semaine** : un dimanche et le lundi qui le suit sont un jour libre dans chacune
de deux semaines, pas deux jours consécutifs de l'une d'elles, et aucune des
deux n'est satisfaite. La lecture inverse — créditer la paire aux deux semaines
— a été proposée puis écartée : elle ramenait un mineur à un seul jour libre par
semaine, soit un résultat voisin de la dérogation à 36 h de l'alinéa 2 sans
l'accord qu'il exige. Le test
`mineurAvecDimancheEtLundiLibresAChevalSurDeuxSemainesEstPenaliseSurChacune`
verrouille ce choix. Les jours que l'événement ne couvre pas restent des jours
libres, ce qui ne met pas pour autant une semaine partiellement couverte hors de
la règle : les deux jours doivent se suivre, et une semaine ouverte en pointillé
— lundi, mercredi, vendredi, dimanche — reste pénalisée si le mineur les tient
tous.

## La coupure repas

`coupureRepasObligatoire` (dure) et `coupureRepasPlacementPrefere` (souple) portent la
coupure repas. Avant elles, **aucune contrainte ne la modélisait** : une journée
de dix heures d'affilée sortait à zéro dur, sans que rien ne le signale
(issue #438).

### Ce n'est pas le Code du travail

La seule pause que le Code impose est celle de [L3121-16] — vingt minutes
consécutives dès que le temps de travail quotidien atteint six heures — et
`travailContinuMaxMajeur` la porte déjà. La coupure repas est la règle
d'organisation de l'événement, celle pour laquelle la grille du classeur
source taille ses vacations de midi. Elle est tenue **en dur par choix**, sous une
catégorie « Organisation (repas) » protégée : le catalogue n'avait pas de case
pour une règle d'organisation dure, il en a une maintenant.

### La règle, exactement

Pour un couple (animateur, date) et chaque fenêtre repas déclarée :

- **due** si la personne travaille **de part et d'autre** : un poste commence
  avant l'ouverture de la fenêtre, un poste finit après sa fermeture. Qui
  commence son service à 19 h a mangé avant ; qui termine à 14 h mangera
  après ; ni l'un ni l'autre ne doit quoi que ce soit.
- **satisfaite** si un trou libre d'au moins la durée paramétrée tient
  **entièrement dans la fenêtre**. Sur une fenêtre 12 h-14 h à 60 minutes,
  c'est 12-13 ou 13-14 — les deux créneaux que la grille taille pour la
  rotation — et tout ce qui se place entre, 12 h 30-13 h 30 compris. Une
  coupure 13 h 45-14 h 45 ne compte que pour ses quinze premières minutes : la
  fenêtre est le service de restauration, pas une indication vague.
- **pénalité** : les minutes manquantes, jamais un tout-ou-rien. Une journée à
  un quart d'heure près et une journée qui ne s'arrête jamais ne sont pas le
  même problème, et un palier ne laisserait au solveur aucune pente à
  descendre.
- **une coupure par fenêtre traversée** : une journée 10 h-23 h en doit deux.

### Une heure, et pourquoi

La coupure vaut **une heure** par défaut (`coupureRepasMinutes`), et non les
45 minutes d'avant. Une fenêtre de deux heures se divise alors en deux
créneaux entiers — 12-13 ou 13-14 le midi, 19-20 ou 20-21 le soir — qui sont
l'organisation réelle de l'événement. À 45 minutes, la coupure ne tombait sur
aucun créneau entier : elle flottait dans la fenêtre.

C'était sans importance tant que cette durée n'était qu'une indication pour le
découpage, qui a depuis été retiré. Depuis que `coupureRepasObligatoire` la
lit, c'est la règle que les gens doivent pouvoir planifier. La migration V71 remonte les éditions restées
à 45 — celles qui n'ont jamais choisi ; une édition ayant saisi une autre
valeur n'est pas touchée.

### Ce qu'elle remet à zéro

**Une coupure repas est une pause au sens de [L3121-16]** : elle rompt la
séquence de travail continu, donc le compteur des six heures repart de zéro.
Ce n'est pas une règle ajoutée, c'est une conséquence du modèle —
`longestSequenceMinutes` rompt une séquence sur tout trou d'au moins vingt
minutes, et une coupure d'une heure en est un. Douze heures d'un bloc coûtent
six heures de dépassement ; les mêmes coupées de 13 h à 14 h n'en coûtent
aucune. `RepasConstraintsTest` le verrouille, parce que rien dans le code ne
l'écrivait et que les deux règles pourraient dériver l'une de l'autre sans
qu'on le voie.

### La rotation, et ce qu'elle coûte

Offrir le choix entre 12-13 et 13-14 suppose **deux créneaux distincts** à
midi : c'est la rotation, et elle demande que quelqu'un tienne le stand
pendant que les autres mangent. Le classeur source y répond par un **effectif
réduit** : `couverturePause` sur un créneau divise par deux les sièges qu'un
stand y ouvre, l'arrondi au supérieur. Le drapeau se pose à la main sur le
créneau, ou sur la vacation d'une journée type qui le projette ; le format de
scénario sait le lire (issue #438). Sans lui, une rotation réclame deux
équipages complets.

Deux réglages conditionnent qu'une telle grille tienne, et ils se mesurent :

- la **pause minimale entre vacations** doit être à 0 quand les blocs se
  touchent, sinon on ne peut pas enchaîner 11 h-12 h puis 12 h-13 h et la
  relève de midi impose une seconde équipe entière ;
- le **repos quotidien** de 11 h reste dû entre la fin de soirée et le matin
  suivant. Le plancher de l'écran Besoin **ne le voit pas** — ses bornes
  l'ignorent, sa javadoc le dit — donc il peut annoncer une marge
  confortable pendant qu'une partie de l'effectif est en réalité
  inutilisable.

### La pause sur le poste demande un relais

Déclarer la pause légale « prise sur le poste » éteint `travailContinuMax*` :
les vingt minutes se prennent par relais, un collègue du même stand tenant
le poste pendant ce temps. Rien ne vérifiait que ce collègue existe. Sur
l'édition 2026, un plan à zéro écart dur portait **18 pauses dues sans
personne pour relayer** : une relève de midi ou de soir enchaînée à un
après-midi entier sur un stand à une place — sept heures seul, une pause due
à 19 h que personne ne peut couvrir. L'écran Pauses et la page Problèmes le
signalaient après coup ; le solveur ne l'évitait jamais.

`pauseSurPosteSansRelais` (**HARD**, « Légal (temps de travail) ») coûte un
point par pause due sans relais à son heure limite : le siège tenu à cet
instant n'a, sur son stand, aucun autre animateur couvrant toute la pause. Les
pauses dues sortent de `PauseSurPoste`, que l'écran Pauses lit aussi : les deux
ne peuvent pas diverger sur ce qui est dû. Muette quand la pause n'est pas
déclarée sur le poste : la règle légale exige alors un vrai trou, et le juge.

**Pourquoi en dur, et plus dosée.** Elle a été MEDIUM, et la réponse la moins
chère — donner la relève à quelqu'un d'autre — était une réponse que le score
trouvait souvent. Mais un relais qui n'existe pas n'est pas un confort perdu :
sans personne pour tenir le stand, la personne ne peut pas le quitter, sa
« pause » reste du travail effectif (art. L3121-1 et L3121-2, et rémunérer la
pause ne change rien à sa qualification — Cass. soc. 22 mai 2019,
n° 17-26.914), et les vingt minutes de l'art. L3121-16 ne sont tout simplement
pas données. Pour un mineur, ce sont les trente minutes et les 4 h 30 de
l'art. L3162-3, qui sont d'ordre public.

Sous `pauseSurPoste`, cette règle est la **seule** qui les vérifie encore :
`travailContinuMaxMajeur` et `travailContinuMaxMineur` se taisent, et les
plafonds quotidien et hebdomadaires déduisent la pause de l'amplitude. La doser
reviendrait à mettre un prix sur la déduction d'une pause que personne n'a
prise. La déduction reste inconditionnelle — elle ne regarde pas si un relais
existe — précisément parce que cette règle-ci garantit qu'il existe : les deux
décisions ne tiennent qu'ensemble.

Mesuré sur `festival-hivernal` (153 animateurs, 65 stands, `pauseSurPoste:
true`) : la fixture atteint toujours **zéro écart dur** avec la règle en dur.
Le durcissement ne rend donc pas infaisable l'édition réelle dont il vient.

Là où il mord, c'est sur un **stand à une seule place tenu plus de six heures
d'affilée** : personne ne peut relayer, et aucune affectation n'atteint zéro
dur. C'est le barreau `gamme-30` de la gamme, et c'est le cas que
l'organisation affirme ne jamais produire. La réponse est de retailler la
grille ou d'ouvrir une place de plus — pas de baisser un poids, qui n'existe
plus ici.

**Une pause due dans une séquence déjà écoulée n'est reprochée à personne.**
Le passé est figé ([0044](decisions/0044-le-passe-est-fige.md)) : ses sièges
sont épinglés, donc un écart dur posé là ne serait réparable par aucun
mouvement, et un calcul relancé en cours d'événement n'atteindrait plus jamais
zéro. La règle regroupe par animateur **et par jour**, et le garde-fou de
journée ne suffit pas — une journée dont la matinée est derrière nous et qui
garde un siège le soir répond « oui, il reste quelque chose devant ». C'est
donc le siège que le relais aurait dû couvrir qui décide.

### Le dosage et la faisabilité

Un poids fort sur une règle MEDIUM se paie sur la phase de faisabilité, qui
accepte ses mouvements sur le score entier. Mesuré sur `festival-hivernal`, du
temps où `pauseSurPosteSansRelais` était dosable : au poids 1, zéro écart dur
en 67 s ; au poids 5, la même grille finissait ses 900 s à **−67 dur**. Le
poids 5 était pourtant celui qui effaçait tout relais manquant — mais depuis un
plan déjà faisable, à chaud. La règle d'usage vaut pour toutes les règles
MEDIUM : atteindre zéro dur au dosage par défaut, puis doser et relancer à
chaud ; jamais un poids fort dans un départ à froid.

`coupureRepasPlacementPrefere` départage ensuite 12-13 de 13-14 en pénalisant
la distance au bout de la fenêtre vers lequel elle penche. **Le midi, c'est le
plus tard** : les stands viennent d'ouvrir, on ne mange pas à midi pile — donc
13-14 plutôt que 12-13. **Le soir, c'est le plus tôt** : on mange tôt pour
rouvrir ensuite. La règle préférait le plus tôt dans les deux fenêtres, ce qui
était juste pour le soir et inversé pour le midi (issue #596) ; son ancien nom,
`coupureRepasAuPlusTot`, ne la décrivait plus, et la migration V86 reporte les
désactivations et les poids déjà enregistrés sous ce nom.

La règle lit ce que la personne **pourrait** choisir, pas la première coupure
venue : une journée qui laisse tout le midi libre n'est pas « mange à midi
pile », c'est une journée où elle a le choix, et elle ne coûte rien. La
couverture des stands étant dure, c'est son arbitrage avec cette préférence qui
répartit la rotation.

### Deux indépendances, qui sont le fond du sujet

**Indépendante de `pauseSurPoste`.** Déclarer la pause légale prise sur le
poste, par relais entre collègues, dit que les vingt minutes ont lieu à
l'intérieur de la vacation. Cela ne dit rien du déjeuner. Le paramètre qui
neutralise `travailContinuMaxMajeur` ne doit pas emporter la coupure repas —
c'est exactement ainsi qu'une journée 10 h-20 h passait inaperçue.

**Indépendante du `modeGrille`.** Les fenêtres repas n'étaient lues que
lorsqu'une grille AMPLITUDES était découpée en vacations ; une grille saisie
en VACATIONS n'est jamais découpée, donc personne ne les regardait. Elles
voyagent désormais en faits de problème (`FenetreRepas`, projeté depuis
`ParametresLegaux`), sur toute grille. La coupure — durée et fenêtres — se
règle avec les paramètres légaux, où l'organisateur la cherche, à côté du
plafond de durée d'une vacation qui les y a rejointes.

### Ce que ça peut rendre infaisable

Sur une grille dont **un seul créneau couvre toute la fenêtre** — une vacation
10 h-20 h d'un bloc — son titulaire ne peut pas s'absenter, et le siège doit
être pourvu (`posteDoitEtrePourvu`, dure aussi). Aucune affectation n'atteint
alors zéro dur. La réponse est de retailler la grille, ou d'éteindre la règle
depuis l'écran Contraintes (ses valeurs se règlent sur l'écran Paramètres). Une fenêtre **plus courte que la coupure qu'elle
exige** est en revanche écartée d'office (`FenetreRepas.depuis`) : personne ne
pourrait la satisfaire, et sanctionner une saisie n'est pas le rôle du score.

### Les autres écrans

L'écran **Pauses** liste la coupure due, le plus grand trou libre et ce qui
manque, à côté des pauses légales. Il appelle le **même `CoupureRepas`** que la
contrainte : les deux ne peuvent pas diverger, parce qu'il n'y en a qu'un.

L'écran **Besoin** compte la coupure dans son plancher — c'est la cinquième
borne de `StaffingAnalyzer`, démontrée dans sa javadoc. `StaffingResource` ne
lui transmet les fenêtres que si la règle est active : une règle que le solveur
n'a pas à honorer ne doit pas relever le nombre d'animateurs à recruter.

Cette borne compte deux choses, et la seconde est ce qu'une grille écrite à la
main lui oppose le plus souvent (issue #482). Un **siège qui couvre toute la
fenêtre** ne laisse à son titulaire aucun trou pour manger : il ne peut donc
pas travailler des deux côtés. Un siège du soir 20 h-minuit contre une fenêtre
20 h-21 h interdit ainsi l'après-midi à ses titulaires, et une journée de 138
sièges 14 h-20 h suivie de 27 sièges 20 h-minuit demande **165** personnes
distinctes, pas les 96 que la seule grille d'instants démontre. Le cas
symétrique se lit pareil : un siège 14 h-20 h 30 ne laisse que trente minutes
d'une fenêtre qui en exige soixante, et ferme la soirée à ses titulaires. Un
siège qui déborde des **deux** côtés, lui, ne relève rien : c'est la grille
infaisable ci-dessus, qu'aucun effectif ne sauve.

L'écran **Problèmes** n'a rien de spécifique à faire : la règle étant dure, ses
écarts remontent déjà par `ConstraintCatalog.NOMS_DURS`.

## Fermer tard puis ouvrir tôt

`reposQuotidienMinimal` tient le repos quotidien **en dur** : 11 h pour un
majeur ([L3131-1]), 12 h pour un mineur et 14 h avant 16 ans ([L3164-1]). Un
animateur qui ferme à 23 h et reprend à 10 h le lendemain respecte les 11 h à la
minute près. Légalement irréprochable ; sur un festival de quinze jours,
discutable.

`eviterFermeturePuisOuverture` (MEDIUM, « Qualité d'organisation ») exprime
cette préférence, et **rien d'autre** : ce n'est pas une obligation du Code du
travail, et c'est pour cela qu'elle n'est ni dure ni rangée sous « Légal ».

### Ce qu'elle compte, et ce qu'elle refuse de compter

Trois seuils, portés par `ParametresQualite` (configuration de l'application,
pas la base — voir `docs/domaine.md`) :

| Seuil | Défaut | Réglage |
| --- | --- | --- |
| Service tardif — la journée **finit** à cette heure ou après | 22 h | `planning.contraintes.heure-service-tardif` |
| Service matinal — la journée du lendemain **commence** à cette heure ou avant | 10 h | `planning.contraintes.heure-service-matinal` |
| Repos souhaité dans ce cas | 12 h | `planning.contraintes.repos-souhaite-apres-service-tardif-minutes` |

La pénalité est le nombre de minutes manquant au repos souhaité, **comptées à
partir du plancher légal** : `souhaité − max(repos réel, plancher légal)`. La
formule n'est pas une précaution de style, c'est ce qui empêche la règle de
devenir un second plancher déguisé :

- un planning déjà illégal n'est **pas facturé deux fois** pour les mêmes
  minutes — celles sous le plancher restent l'affaire de `reposQuotidienMinimal`,
  qui les tient en dur ;
- là où la loi exige déjà autant que le souhait — un mineur, 12 h ; avant 16 ans,
  14 h —, la règle est **entièrement muette** : il ne lui reste rien à demander ;
- un repos souhaité réglé au niveau du plancher, ou en dessous, rend la règle
  inerte. C'est voulu : une préférence qui ne dépasse pas la loi ne dit rien.

Les deux bornes sont comparées **en instant**, pas en heure d'horloge. Une
vacation 20 h → 00 h finit le lendemain à 0 h : lue comme une heure, elle
passerait pour matinale alors qu'elle est précisément la fermeture que la règle
cherche. Le cas est couvert par un test dédié.

### Une nuit, une pénalité

La règle raisonne sur des **journées**, pas sur des paires de vacations, et ce
n'est pas un détail d'implémentation. Le repos d'une nuit est une quantité
unique — dernière fin de J, premier début de J+1 — alors qu'une jointure par
paires la facture une fois par couple (vacation tardive, vacation matinale) :
un animateur qui reprend à 8 h puis de nouveau à 10 h payait deux fois la même
nuit, et **supprimer le siège de 10 h divisait la pénalité par deux sans lui
rendre une minute de sommeil**. Le gradient que descend le solveur doit être le
déficit lui-même : la journée est donc agrégée d'abord (`max(fin)` d'un côté,
`min(début)` de l'autre), puis les deux journées jointes sur la clé
d'adjacence. Un soir coupé en deux est couvert par le même geste — seule la
dernière vacation de la soirée décide si la journée a fermé tard.

`reposQuotidienMinimal` peut se permettre la forme par paires, elle : c'est un
plancher binaire, pas une magnitude, et toute paire non contraignante a un
écart plus grand qui la fait sortir du filtre.

### Pourquoi 22 h et 10 h

10 h et non 9 h, et c'est l'arbitrage le moins évident de la règle. Avec un seuil
matinal à 9 h, une vacation finissant à 22 h ou après et une reprise à 9 h ou
avant sont **au plus 11 h** l'une de l'autre — soit jamais au-delà du plancher
légal d'un majeur, que la règle refuse par construction de facturer. Le seul
couple qu'elle aurait attrapé sur un planning licite est le point exact
22 h → 9 h : une règle inerte, livrée pour rien. À 10 h, la bande utile est
`[11 h, 12 h[` : exactement le motif de l'issue #78, et rien d'autre.

### Ce qu'elle coûte, mesuré

Deux résolutions par scénario, même graine (0) et même budget (480 s), la règle
active puis désactivée par `ConstraintToggle` — le plan obtenu sans elle étant
ensuite **re-noté avec**, pour savoir ce qu'il aurait coûté.

Sur **`festival-realiste-canicule`** (la fixture réelle anonymisée : 153
animateurs, 65 stands, 16 jours, grille 9 h-0 h), la règle a de quoi mordre :

| Règle (medium) | Avec | Sans (re-noté avec) | Écart |
| --- | ---: | ---: | ---: |
| `eviterFermeturePuisOuverture` | −600 | −8 700 | **−8 100** |
| `eviterRoulementStandsPremium` | −264 | −238 | +26 |
| `standComplexeAvecReferent` | −626 | −585 | +41 |
| `appreciationIncompatible` | −115 | −74 | +41 |
| `limiterTypologiesDistinctesParAnimateur` | −11 | −9 | +2 |
| *Tout le reste* (charge, souhaits, mineurs, premium) | | | *inchangé* |

**8 100 points de clopening supprimés — 93 % — pour 110 points payés aux neuf
autres règles.** Les 8 700 points du plan libre sont 8 700 minutes de repos
manquant, soit 145 heures étalées sur seize jours ; il en reste 10 heures. La
tension redoutée avec la continuité sur stand premium est réelle et **chiffrée
à 26 points**, l'essentiel du prix allant en fait au référent et à
l'appréciation. Le débit du solveur baisse de 1,4 % (5 844 contre 5 925
évaluations/s).

Sur **`scenario-complet`**, la règle vaut **0 des deux côtés** : aucun créneau
du fichier ne commence avant 11 h, donc aucune journée n'y ouvre tôt et la règle
ne peut structurellement pas se déclencher, quelle que soit l'affectation. Les
31 points d'écart en medium entre les deux résolutions (−4 402 contre −4 371,
0,7 %) ne sont pas son arbitrage mais le bruit de 3,4 % de débit en moins. À
retenir pour lire une prochaine mesure : ce scénario ne peut pas répondre à la
question, et un chiffre nul y est un artefact de grille, pas un verdict.

### La tension avec la continuité sur stand premium

`eviterRoulementStandsPremium` (MEDIUM) pousse à garder les mêmes têtes sur un
stand premium ; sur un stand qui ouvre et ferme chaque jour, éviter le
fermeture → ouverture revient à les faire tourner. Les deux règles sont MEDIUM,
les deux sont `dosable()` : l'arbitrage se règle **par édition** dans
`ponderation_contrainte`, pas dans le code. Une édition qui tient à la
continuité descend le poids de celle-ci ; une édition tendue peut la mettre à
zéro sans toucher au catalogue, ou vider un des deux seuils dans la
configuration.

## Compté, non reproché : le passé

Pendant l'événement, une résolution reçoit les places des créneaux déjà
commencés **reprises du plan enregistré et épinglées** — « le passé est
figé », [ADR 0044](decisions/0044-le-passe-est-fige.md), mécanique dans
[`domaine.md`](domaine.md#le-passé-est-figé). Ces places
portent un drapeau `passe` que chaque contrainte lit selon une seule règle :

- une place passée **compte** — elle reste dans chaque flux, groupe et
  jointure : ce que quelqu'un a travaillé hier conditionne le repos qui lui
  est dû cette nuit, les heures qui lui restent dans la semaine, les jours
  d'affilée qu'il atteint ;
- une place passée n'est **jamais reprochée** — une correspondance dont
  toutes les places sont passées (un trou d'hier, un mineur la nuit hier, une
  coupure repas manquée hier) est de l'histoire que le solveur ne peut pas
  réparer, et la facturer empêcherait un avenir faisable d'atteindre le zéro
  dur. Une correspondance n'est facturée que si **au moins une** des places
  qu'elle implique n'est pas passée.

Concrètement : une règle par place ignore la place passée ; une règle par
paire ne facture pas une paire dont les deux places sont passées ; une règle
groupée par animateur et jour ou semaine compte tout mais ne facture le groupe
que s'il tient encore une place à venir ; la récompense d'affinité suit la
même lecture. Le tableau règle par règle est dans l'ADR. Les diagnostics qui
rejouent le score sur le plan enregistré — écran Contraintes,
`expliquer_echec_contraintes_dures`, planchers, simulations — passent par le
même fournisseur de contraintes et lisent donc la même chose. Les analyses
qui comptent hors du solveur (Pauses, Besoin, contrôle de grille, Heures)
**décrivent** toujours le passé, y compris ce qui s'y est mal passé : elles
disent ce qui a été fait, elles ne le reprochent pas non plus.

**Un plancher medium pendant l'événement, assumé.** `equilibrerCharge` et
`equilibrerCreneauxPenibles` mesurent l'édition entière — places passées
comprises, puisque ce que chacun a déjà travaillé est ce qui rend l'équité
lisible — et ne sont facturées que tant qu'une place est à venir. Mais la
résolution ne peut plus bouger que l'avenir : un écart déjà creusé avant
aujourd'hui ne se rattrape pas, et le score medium garde un plancher
qu'aucune relance ne ramène à zéro. Il n'est ni signalé comme les
[planchers par règle](#le-plancher--une-règle-qui-pénalise-tout-faute-de-donnée)
ni soustrait : le score d'un solve pendant l'événement se compare au
précédent, pas au zéro, et l'aide de l'écran Solveur le dit. Le lever
demanderait de ne mesurer l'équilibre que sur l'avenir, ce qui inverserait
la règle — l'animateur déjà chargé hier redeviendrait « à charger » demain.

## La convention collective de l'Animation (ÉCLAT, IDCC 1518)

**Elle ne s'applique pas.** L'organisation a confirmé, lors du cadrage du cadre
de temps de travail, qu'elle n'en relève pas. Tout ce que cette documentation
et le code citent est donc le **Code du travail seul**, et la mention « CCN
Animation ÉCLAT art. 5.2 » qui accompagnait le plafond de 48 h a été retirée :
elle était fausse deux fois, l'art. 5.2 traitant des jours de repos et la
semaine haute figurant à l'art. 5.7.2.3 (modulation). Elle subsiste dans le
commentaire de `V7__parametres_legaux.sql`, et c'est délibéré : une migration
appliquée ne se modifie pas, son empreinte est gelée
(`FlywayMigrationsFrozenTest`).

C'est un **fait déclaré, pas une vérification juridique**, et il porte à
conséquence. Si la convention s'appliquait, deux de ses articles changeraient
des décisions déjà prises : l'art. 5.3 impose 45 minutes de coupure à toute
journée de travail quelle que soit sa durée (les 30 minutes retenues sont sous
ce plancher) et plafonne l'amplitude à 12 h ; l'art. 5.2 donne deux jours de
repos consécutifs à **tout** salarié, ce qui interdirait mécaniquement les douze
jours d'affilée et donnerait une base textuelle à la règle ci-dessous. Une
réserve reste à lever de toute façon : une page Légifrance rendait ces articles
comme « non en vigueur » alors que les textes consolidés de 2024 les portent
« en vigueur, étendu ». Le suivi de cette confirmation est tenu hors du dépôt
public, avec le reste de ce qui touche à l'organisation elle-même.

## Hors périmètre assumé

Ces obligations sont réelles et **volontairement non implémentées**. Elles sont
listées pour que leur absence soit un choix écrit, pas un oubli.

**Durée hebdomadaire moyenne de 44 h sur 12 semaines ([L3121-22]).** Un événement
couvre quelques semaines ISO au plus : **le solveur ne peut pas calculer cette
moyenne**, il ne connaît ni les semaines précédentes ni les suivantes. Limite
structurelle du périmètre, pas manque d'implémentation. Le contrôle relève du
service RH, à partir du cumul par semaine que la page Heures expose déjà.

**Travail de nuit des majeurs ([L3122-1] et suivants).** `chevaucheNuit()` n'est
consulté que par les contraintes mineurs, alors que les scénarios livrés
comportent un créneau nocturne quotidien. La qualification de « travailleur de
nuit » dépend d'un seuil et d'une régularité, mais aussi du contrat, d'un accord
collectif ou d'une autorisation de l'inspection — des faits que l'application ne
détient pas. La numérotation exacte des articles reste **non vérifiée**. Sujet à
instruire avec un juriste ; d'ici là, contrôle manuel.

| Autre sujet | Pourquoi hors périmètre |
| --- | --- |
| Heures supplémentaires, contingent annuel ([L3121-30]) | Le planning produit est l'assiette du décompte, pas le décompte |
| Nature des indisponibilités | `joursIndisponibles` est un `Set<LocalDate>` non typé : impossible de distinguer un repos légal, un congé payé et une convenance |
| Autorisation d'inspection pour les moins de 16 ans ([L4153-3], [D4153-2]) | Donnée administrative absente du modèle, à vérifier manuellement |
| Dérogation sectorielle aux jours fériés ([R3164-2]) | Non instruite ; le défaut le plus protecteur s'applique |
| Repos dominical des mineurs ([L3132-3], [L3164-3] à [L3164-5], [R3164-1]) | Le repos hebdomadaire est donné le dimanche, et les dérogations de L3132-4 et L3132-8 ne s'appliquent pas aux moins de 18 ans ; seuls des apprentis de douze secteurs énumérés peuvent travailler le dimanche. Savoir si l'organisateur relève d'une dérogation et sous quel statut chaque animateur est engagé sont des faits que le dépôt ne contient pas : un mineur placé un dimanche est aujourd'hui accepté, à contrôler manuellement |

## Les contraintes, telles que le catalogue les déclare

Rendu de `ConstraintCatalog`, tenu à jour par `DocumentationStructuralTest` :
quand le catalogue change, le test échoue et imprime le tableau à coller entre
les deux repères. Le *pourquoi* de chaque règle reste dans les sections
ci-dessus ; ceci est la liste, complète par construction.

<!-- catalogue:debut -->
| Contrainte | Niveau | Catégorie | Ce qu'elle dit |
|---|---|---|---|
| `posteDoitEtrePourvu` | HARD | Affectation | Chaque place ouverte sur un stand doit être pourvue par un animateur. |
| `animateurDisponible` | HARD | Affectation | Un animateur ne peut pas être affecté un jour qu'il a déclaré indisponible. |
| `pasDeChevauchementHoraire` | HARD | Affectation | Un animateur ne peut pas tenir deux postes dont les créneaux se chevauchent dans le temps (y compris deux créneaux distincts qui se recouvrent, et pas seulement deux postes sur le même créneau). |
| `plafondCreneauxParTypologie` | HARD | Affectation | Sur une typologie qui porte un plafond, un animateur ne tient pas plus que ce nombre de créneaux sur l'ensemble de l'édition. Un poste compte pour chaque typologie que son stand propose. Une typologie sans plafond n'impose rien. |
| `standReserveAuxMajeurs` | HARD | Légal (mineurs) | Les stands réservés aux majeurs ne peuvent accueillir aucun mineur. |
| `mineurNecessiteEncadrementMajeur` | HARD | Sécurité (mineurs) | Éteinte par défaut. Un mineur doit toujours être accompagné d'au moins un majeur sur le même stand et le même créneau. Règle de sécurité posée par l'organisateur, pas une obligation du Code du travail : l'organisateur de l'évènement la remplit par ses managers, qui ne sont pas planifiés, et ne la demande donc pas au solveur. Une organisation sans encadrant hors planning l'allume depuis l'écran Contraintes. |
| `travailDeNuitInterditPourMineur` | HARD | Légal (mineurs) | Un mineur ne peut pas être affecté sur un créneau qui empiète sur sa nuit légale : 20 h-6 h avant 16 ans, 22 h-6 h de 16 à 18 ans (Code du travail art. L3163-1). |
| `dureeQuotidienneMaxMineur` | HARD | Légal (mineurs) | Un mineur ne peut pas dépasser 8 heures de travail effectif sur une même journée (Code du travail art. L3162-1), ramenées à 7 heures avant 16 ans (art. D4153-3). Les pauses prises sur le poste, si l'organisateur les déclare, sont déduites. |
| `travailInterditJourFerieMineur` | HARD | Légal (mineurs) | Un mineur ne peut pas travailler un jour férié légal (Code du travail art. L3164-6, liste de l'art. L3133-1). Aucune dérogation sectorielle n'est appliquée : celle de l'art. R3164-2 reste à instruire. |
| `reposHebdomadaireMineur` | HARD | Légal (mineurs) | Un mineur bénéficie de deux jours de repos consécutifs à l'intérieur de chaque semaine civile, du lundi 0 h au dimanche 24 h (Code du travail art. L3164-2 et L3121-35) : un dimanche et le lundi qui le suit sont chacun un jour de repos de leur semaine, mais ne forment la paire d'aucune des deux. Les dérogations conventionnelles supposent un accord étendu ou une autorisation de l'inspection du travail : elles ne sont pas présumées. |
| `travailContinuMaxMineur` | HARD | Légal (mineurs) | Aucune période de travail ininterrompue de plus de 4 h 30 pour un mineur : au-delà, une pause consécutive de la durée paramétrée est obligatoire, au minimum 30 minutes (Code du travail art. L3162-3). Inerte quand l'organisateur déclare la pause prise sur le poste, par relais. |
| `dureeHebdomadaireMax` | HARD | Légal (temps de travail) | Aucun animateur majeur (tous payés, manager ou non) ne peut dépasser la durée hebdomadaire de travail effectif maximale paramétrée (48 h par défaut, Code du travail art. L3121-20, d'ordre public). Les pauses prises sur le poste, si l'organisateur les déclare, sont déduites, comme au plafond quotidien. |
| `dureeHebdomadaireMaxDeuxSemaines` | HARD | Légal (temps de travail) | Un animateur majeur ne peut pas atteindre la durée hebdomadaire maximale sur deux semaines ISO consécutives : 48 h une semaine puis 48 h la suivante est refusé, 47 h puis 48 h reste permis. Forme courte et opérationnelle de la moyenne de 44 h sur douze semaines (Code du travail art. L3121-22) — la seule qui ait un sens sur un événement de quinze jours. Le seuil est celui du paramètre de durée hebdomadaire maximale, jamais une seconde constante. Une semaine se juge pleine en travail effectif : les pauses prises sur le poste, si l'organisateur les déclare, en sont déduites. |
| `dureeHebdomadaireMaxMineur` | HARD | Légal (mineurs) | Un mineur ne peut pas dépasser 35 heures de travail effectif par semaine (Code du travail art. L3162-1 ; art. D4153-3 pour les 14 à moins de 16 ans employés pendant les vacances scolaires). Les pauses prises sur le poste, si l'organisateur les déclare, sont déduites, comme au plafond quotidien. |
| `dureeQuotidienneMaxMajeur` | HARD | Légal (temps de travail) | Un animateur majeur ne peut pas dépasser 10 heures de travail effectif sur une même journée (Code du travail art. L3121-18). Les pauses prises sur le poste, si l'organisateur les déclare, sont déduites. |
| `reposQuotidienMinimal` | HARD | Légal (temps de travail) | Entre deux journées travaillées, tout animateur bénéficie d'un repos quotidien minimal : 11 h pour un majeur (art. L3131-1), 12 h pour un mineur et 14 h avant 16 ans (art. L3164-1). |
| `maxJoursTravaillesParSemaine` | HARD | Légal (temps de travail) | Aucun animateur ne peut travailler plus de six jours dans la même semaine (Code du travail art. L3132-1). |
| `reposHebdomadaireMinimal` | HARD | Légal (temps de travail) | Chaque animateur bénéficie, dans chaque semaine, d'un repos hebdomadaire de 35 heures consécutives : 24 heures (art. L3132-2) auxquelles s'ajoutent les 11 heures de repos quotidien (art. L3131-1). Un repos à cheval sur le lundi compte en entier pour la semaine où il tombe. |
| `travailContinuMaxMajeur` | HARD | Légal (temps de travail) | Aucune période de travail ininterrompue de plus de 6 heures pour un majeur : au-delà, une pause consécutive de la durée paramétrée est obligatoire, au minimum 20 minutes (Code du travail art. L3121-16). Inerte quand l'organisateur déclare la pause prise sur le poste, par relais. |
| `pauseMinimaleEntreVacations` | HARD | Légal (temps de travail) | Entre deux vacations d'un même animateur le même jour, l'écart doit être d'au moins la pause minimale paramétrée (30 min par défaut). |
| `coupureRepasObligatoire` | HARD | Organisation (repas) | Qui travaille de part et d'autre d'une fenêtre repas doit disposer, entièrement dans cette fenêtre, d'une coupure libre de la durée paramétrée (60 min par défaut, midi 12 h-14 h et soir 19 h-21 h). Commencer sa journée à l'ouverture de la fenêtre, ou la terminer à sa fermeture, ne doit rien : on a mangé avant, ou on mangera après. Une journée à cheval sur les deux fenêtres doit deux coupures. Ce n'est pas une obligation du Code du travail — la seule pause qu'il impose est celle de 20 minutes à la sixième heure (art. L3121-16), portée par travailContinuMaxMajeur — mais la règle d'organisation de l'événement, tenue en dur par choix. Elle reste active quand l'organisateur déclare la pause prise sur le poste : la pause légale par relais et la coupure repas sont deux choses distinctes. |
| `coupureRepasPlacementPrefere` | SOFT | Préférences | Entre deux coupures repas possibles dans la même fenêtre, préférer celle vers laquelle la fenêtre penche : le midi la plus tard — 13 h-14 h plutôt que 12 h-13 h, les stands viennent d'ouvrir — et le soir la plus tôt, pour rouvrir ensuite. La couverture des stands, elle, est dure : c'est son arbitrage avec cette préférence qui répartit la rotation. |
| `indisponibiliteForcee` | HARD | Contraintes ad hoc | Indisponibilité posée manuellement par l'administrateur : l'animateur ne doit jamais être affecté sur le périmètre visé. |
| `incompatibiliteAdHoc` | HARD | Contraintes ad hoc | Deux animateurs déclarés incompatibles ne doivent jamais travailler sur le même créneau. |
| `affectationForcee` | HARD | Contraintes ad hoc | Affectation imposée par l'administrateur : l'animateur doit être présent sur le créneau ou le stand visé. |
| `affiniteAdHoc` | SOFT | Contraintes ad hoc | Paire d'animateurs à privilégier : chaque créneau où les deux sont affectés au même stand est récompensé. Contrainte souple : elle favorise la co-affectation quand c'est possible, sans jamais la forcer. |
| `animateurVerrouilleFige` | HARD | Verrouillage du planning | Le planning d'un animateur verrouillé ne bouge plus : ses postes validés sont figés et le solveur ne peut plus lui en attribuer de nouveaux. |
| `animateurVerrouilleCreneauFige` | HARD | Verrouillage du planning | Un échange validé est figé sur son créneau : ce que chacun des deux animateurs y tient après l'échange ne bouge plus, sans geler le reste de leur planning. |
| `standComplexeAvecReferent` | MEDIUM | Qualité d'organisation | Chaque stand devrait compter au moins un référent sur chaque créneau. |
| `equilibrerCharge` | MEDIUM | Qualité d'organisation | La charge de travail doit être répartie équitablement entre les animateurs. |
| `stabiliteDuPlanPublie` | MEDIUM | Qualité d'organisation | Une fois un planning publié, chaque personne déplacée d'un siège qu'elle tenait dans le plan publié coûte : le solveur ne bouscule les gens déjà prévenus que si le gain vaut le dérangement. Muette tant que rien n'a été publié ; une vacation que le plan publié n'avait pas — autre jour, autres heures ou autre stand — reste libre. |
| `repartitionMineursParCreneau` | MEDIUM | Qualité d'organisation | Sur un créneau, un stand ne devrait pas compter plus de mineurs que de majeurs. |
| `experienceRequisePourStandsPremium` | MEDIUM | Qualité d'organisation | Un stand premium ne devrait pas être tenu par un animateur débutant sur sa typologie. |
| `eviterRoulementStandsPremium` | MEDIUM | Qualité d'organisation | Sur un stand premium, limiter le nombre d'animateurs différents qui s'y relaient au-delà d'un équipage : on privilégie la continuité. |
| `eviterChangementEmplacementEloigne` | MEDIUM | Qualité d'organisation | Entre deux créneaux consécutifs, éviter de faire basculer un animateur vers un stand dont l'emplacement est éloigné (> 300 m à vol d'oiseau) de celui du créneau précédent. |
| `limiterEmplacementsParJour` | MEDIUM | Qualité d'organisation | Sur une même journée, limiter le nombre d'emplacements distincts visités par un animateur (plafond réglable, 3 par défaut) : au-delà, la journée est dispersée quelles que soient les distances. |
| `eviterEnchainementStandsEpuisants` | MEDIUM | Qualité d'organisation | Entre deux créneaux consécutifs, éviter d'enchaîner un animateur sur deux stands physiquement épuisants sans repos ni stand plus facile entre les deux. |
| `eviterFermeturePuisOuverture` | MEDIUM | Qualité d'organisation | Après une vacation qui finit tard (22 h par défaut), éviter une reprise matinale le lendemain (10 h par défaut) : on souhaite alors 12 h de repos plutôt que le minimum légal. Préférence d'organisation, pas une obligation du Code du travail : seules les minutes au-dessus du repos quotidien légal sont comptées ici, celles en dessous restent l'affaire de reposQuotidienMinimal, qui les tient en dur. La règle est donc muette quand la loi exige déjà autant (un mineur, 12 h ; avant 16 ans, 14 h). |
| `appreciationIncompatible` | MEDIUM | Qualité d'organisation | L'appréciation de l'administrateur ne couvre aucune typologie de jeu proposée par le stand. |
| `souhaitsIncompatibles` | MEDIUM | Qualité d'organisation | Aucune des typologies de jeu proposées par le stand ne figure dans les souhaits déclarés de l'animateur. |
| `limiterTypologiesDistinctesParAnimateur` | MEDIUM | Qualité d'organisation | Un animateur devrait intervenir sur un petit nombre de typologies de jeu (plafond réglable, 2 par défaut) sur l'ensemble de l'édition, et pas seulement sur une journée : deux typologies le même après-midi et deux à une semaine d'écart comptent pareil. |
| `maxJoursConsecutifsTravailles` | MEDIUM | Qualité d'organisation | Un animateur ne devrait pas travailler plus de six jours consécutifs sans au moins un jour de repos : moins est possible, plus ne devrait pas l'être. Règle d'organisation, dosable : aucun article du Code du travail n'impose un décompte glissant de six jours (L3132-1 se lit sur la semaine civile, Cass. soc. 13 nov. 2025, n° 24-10.733). |
| `maxJoursConsecutifsTravaillesDur` | HARD | Qualité d'organisation | Éteinte par défaut. Le même plafond de six jours consécutifs, tenu en dur : au-delà, le plan est refusé au lieu d'être pénalisé. Un poids ne change jamais le niveau d'une règle, d'où une contrainte séparée, qu'une édition allume depuis l'écran Contraintes, par activer_contrainte ou par contraintes.activees d'un scénario. Reste rangée en « Qualité d'organisation » et non en « Légal » : c'est une politique de l'organisateur, pas une obligation du Code du travail. |
| `pauseSurPosteSansRelais` | HARD | Légal (temps de travail) | Quand la pause légale est déclarée prise sur le poste, quelqu'un doit tenir le stand pendant qu'elle est prise. Chaque pause due à la sixième heure (quatre heures et demie pour un mineur) qui tombe sur un stand où personne d'autre n'est présent est un écart dur : sans relais, la personne ne peut pas quitter son poste, la pause reste du travail effectif (art. L3121-1 et L3121-2) et l'obligation de l'art. L3121-16 — L3162-3 pour un mineur — n'est pas remplie. C'est aussi ce qui autorise la déduction de la pause des plafonds quotidien et hebdomadaire : sans relais, on déduirait une pause que personne n'a prise. Muette quand la pause n'est pas déclarée sur le poste : travailContinuMaxMajeur et travailContinuMaxMineur exigent alors un vrai trou. |
| `favoriserMixiteDesNiveaux` | SOFT | Préférences | Quand un référent est présent sur un créneau, y associer un débutant pour favoriser la montée en compétence. |
| `equilibrerCreneauxPenibles` | SOFT | Préférences | Répartir équitablement entre animateurs les créneaux pénibles (stands épuisants ou premium). |
| `preserverBufferPolyvalents` | SOFT | Préférences | Garder au moins un animateur polyvalent (typologie ninja) libre sur chaque créneau, pour pouvoir réparer le planning en cas d'absence de dernière minute. |
<!-- catalogue:fin -->

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

<!-- Liens vers Légifrance. Chaque référence pointe vers la recherche par
     numéro d'article, qui résout toujours la version en vigueur : un
     identifiant LEGIARTI désigne une version datée, et vieillit en silence. -->
[D4153-2]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=D4153-2
[L3121-16]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3121-16
[L3121-22]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3121-22
[L3121-30]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3121-30
[L3122-1]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3122-1
[L4153-3]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L4153-3
[R3164-2]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=R3164-2
[L3121-35]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3121-35
[L3132-2]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3132-2
[L3132-3]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3132-3
[L3164-2]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3164-2
[L3164-3]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3164-3
[L3164-5]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3164-5
[R3164-1]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=R3164-1
[L3131-1]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3131-1
[L3164-1]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3164-1
