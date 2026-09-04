# Modèle de domaine

Pattern Timefold de *shift rostering*, à respecter tel quel pour rester
compatible avec `HardMediumSoftScore`. Les noms de classes et de champs restent
en **vocabulaire métier français** ; commentaires et identifiants non métier en
anglais (glossaire dans [`AGENTS.md`](../AGENTS.md)).

Les classes et leurs champs se lisent dans `domain/`. Ce document porte les
règles de calcul et les arbitrages.

## Deux choses jamais stockées

**Le statut mineur / majeur / moins de 16 ans** est dérivé de `dateNaissance`
**à la date du créneau**, jamais d'un booléen — trois régimes légaux distincts
en dépendent, et un booléen se désynchronise.

**Le drapeau polyvalent** est dérivé au chargement du problème : un animateur
est polyvalent parce que ses compétences contiennent la typologie marquée
`ninja`, pas parce qu'une colonne le dit.

Les typologies elles-mêmes ne sont pas un enum : ce sont des lignes CRUD
référencées par clé étrangère, donc on en ajoute ou en renomme sans toucher au
code.

## Les bornes de l'édition se dérivent

Une `Edition` ne porte **ni dates ni drapeau « en cours »** : c'est un
cloisonnement, pas un calendrier. L'événement d'une édition court donc du
**premier au dernier créneau** qu'elle contient, bornes comprises, et rien
d'autre ne fait autorité — ni un paramètre, ni la date du jour.

Trois conséquences, et aucune n'est un défaut à corriger plus tard :

- Un jour **creux** entre deux dates de créneaux (un lundi de relâche au milieu
  de deux week-ends) est *dans* l'événement, même s'il ne porte aucun créneau.
  L'intervalle est la borne ; l'ensemble des dates de créneaux, lui, sert à la
  collecte des disponibilités, qui n'a pas la même question à trancher.

  **Être dans l'événement ne veut pas dire être utilisable**, et l'écart se
  paie sur les indisponibilités. Le formulaire de déclaration ne propose que
  les **dates de créneaux**, et appliquer une déclaration remplace
  `joursIndisponibles` en bloc : une indisponibilité posée sur un jour creux
  est donc effacée à la première déclaration acceptée, sans que l'animateur
  l'ait jamais vue dans son espace. C'est la même donnée condamnée que l'import
  CSV **refuse** en rejetant la ligne ; la saisie manuelle, elle, avertit sans
  bloquer (`INDISPONIBILITE_JOUR_SANS_CRENEAU`, voir
  [`api.md`](api.md#avertissements-de-saisie)) — deux traitements différents
  pour deux doctrines assumées, jamais un silence.
- Une édition **sans aucun créneau** n'a **pas** de bornes. Rien ne peut y être
  déclaré « hors événement », et les avertissements de saisie se taisent : la
  seule alternative serait un faux positif sur l'écran par lequel une édition
  commence.
- Ajouter ou supprimer un créneau **déplace** les bornes. C'est voulu — la
  grille *est* l'événement — mais un avertissement déjà affiché ne se
  réévalue pas tout seul : il décrit l'instant de l'écriture.

Cette dérivation est écrite une fois (`service/JoursEvenement`) et lue à la
fois par la collecte des disponibilités et par les avertissements de saisie
(voir [`api.md`](api.md#avertissements-de-saisie)).

## Un poste = une place

Un `PosteAffectation` est créé **par place à pourvoir**, jamais un par couple
stand × créneau. Un poste non pourvu garde `animateur = null`.

`allowsUnassigned = true` : une place peut rester vide pendant la recherche, et
c'est `posteDoitEtrePourvu` qui en fait une exigence dure. **Conséquence
pratique** : `forEach(...)` *exclut* les postes non pourvus, seul
`forEachIncludingUnassigned(...)` les voit — d'où son usage dans
`posteDoitEtrePourvu`, et l'inutilité d'un test `animateur != null` après un
`forEach`.

## Trois états par jour pour un stand

Décidés indépendamment jour par jour :

- **ni indisponibilité ni ouverture ce jour-là** → ouvert sans restriction, cas
  largement majoritaire ;
- **au moins une indisponibilité** → ouvert par défaut, fermé sur les seules
  fenêtres listées. Le calcul soustrait l'union des fermetures qui chevauchent
  le créneau : une fermeture en plein milieu laisse deux segments ouverts ;
- **au moins une ouverture** → **fermé par défaut**, ouvert sur les seules
  fenêtres listées. Pensé pour un stand normalement fermé n'ouvrant que sur
  quelques créneaux : une seule ouverture au lieu d'une fermeture par créneau.

Un jour ne peut pas structurellement porter les deux : l'écriture le refuse, ce
qui évite d'avoir à arbitrer un conflit au calcul.

**`heureFin` nullable vaut « jusqu'à la fermeture »** : la fenêtre court jusqu'à
la fin du créneau évalué, quelle que soit l'heure de fermeture du jour. C'est ce
qui remplace le contournement `23:59` — une fenêtre ne peut pas chevaucher
minuit, contrairement à un créneau.

Une fenêtre datée d'un jour J+1 n'est lue que par un créneau qui **traverse
réellement minuit**.

### L'effectif se porte sur la fenêtre, pas sur le stand

Un stand dont la charge varie dans la journée — 4 personnes le matin, 4
l'après-midi, 5 le soir — reste **un seul stand** : chaque fenêtre d'ouverture
porte son `effectif`. `null`, le cas très majoritaire, veut dire « hériter de
`effectifMin` », donc une fenêtre qui n'en nomme aucun génère exactement ce
qu'elle générait avant que le champ existe.

Un créneau à cheval sur deux fenêtres d'effectifs différents produit **deux
groupes de sièges**, chacun portant la [fenêtre effective](#fenêtre-effective)
de son segment. Là où deux fenêtres se recouvrent, le recouvrement prend le
**plus haut** des deux effectifs : deux fenêtres qui se chevauchent énoncent
deux fois le besoin sur les mêmes minutes, et satisfaire le plus grand satisfait
les deux. Les tranches contiguës de même effectif sont refusionnées.

`segmentsOuverts` est le calcul primaire ; `segmentsOuvertsMinutes`
en est la projection et reste la **géométrie d'ouverture pure** — deux segments
que seul l'effectif sépare y redeviennent une ouverture continue, pour que tout
appelant qui demande « quand ce stand est-il ouvert » garde sa réponse.

> Pourquoi ce champ existe : appliquer `effectifMin` à toutes les tranches est
> ce qui faisait couvrir 7 155 h à un planning réel là où son classeur source en
> demandait 10 986. Le minimum est ce qu'un stand exige à son heure la plus
> creuse ; l'appliquer au pic sous-dote d'un tiers.

### Horaires récurrents : trois couches, un seul mode par jour

Les fenêtres datées sont des **exceptions** ; le motif qui se répète se saisit
au-dessus, en règles portant un mode et un sélecteur de jours de spécificité
croissante — `TOUS` (0), `JOURS_SEMAINE` (1), `PLAGE` (2), `DATES` (3).

Sur la fixture de 63 stands, 714 fenêtres datées deviennent 120 règles et 19
exceptions résiduelles.

La résolution se fait jour calendaire par jour calendaire :

1. **une fenêtre datée ce jour-là** gagne seule, et remplace *entièrement* ce
   que les règles disaient de ce jour ;
2. **sinon les règles couvrant ce jour**, à spécificité maximale, union de leurs
   fenêtres. Deux règles de même spécificité et de modes opposés sur des jours
   qui se croisent sont refusées à l'écriture ; le résolveur garde malgré tout
   un arbitrage déterministe pour une donnée arrivée autrement (un scénario
   écrit à la main) : `OUVERTURE` l'emporte, c'est la lecture la plus
   restrictive ;
3. **sinon** rien, donc ouvert toute la journée.

Le résultat est toujours **un seul mode par jour** : c'est ce qui préserve
l'invariant des trois états, et ce qui fait que ni le calcul de segments, ni les
contraintes, ni les exports n'ont à connaître les règles.

**Si un seul jour de l'événement reste non énoncé, aucune règle ne peut prendre
`TOUS`** — elle gouvernerait un jour laissé volontairement ouvert par défaut.

> **Limite assumée** : une exception *remplace* la journée au lieu de se
> soustraire aux règles. « Ouvert 10 h-12 h / 14 h-fermeture tous les jours,
> sauf le 14 juillet après-midi » demande de ressaisir la journée entière. C'est
> le prix de l'invariant à un mode par jour ; l'alternative serait de mélanger
> deux modes sur une journée, précisément l'ambiguïté que cet invariant écarte.

L'expansion n'est **jamais écrite** sur les listes datées : elle vit à côté, si
bien qu'un stand résolu peut repasser par une sauvegarde sans figer son
expansion en centaines de lignes. La distinction est explicite côté service :
une vue CRUD brute, et une vue effective résolue sur les jours de l'édition —
c'est celle-là que prennent le solveur, la génération de postes et l'analyse de
faisabilité.

Le **compactage** dérive les règles à la demande et ne réécrit un stand que si
elles reproduisent ses propres segments ouverts. L'écart se mesure en **minutes
d'ouverture** en désaccord, jamais en appariant les segments : un « fermé
10 h-23 h 59 » réécrit en « fermé jusqu'à la fermeture » fait passer le nombre
de segments de 1 à 0, alors que le désaccord réel est la seule minute d'un poste
qui n'aurait jamais dû être à pourvoir. Compter les segments ferait refuser
exactement les stands que la réécriture aide le plus.

### Fenêtre effective

Un poste issu d'un segment partiel porte une fenêtre plus étroite que son
créneau. **Ce n'est pas un sous-créneau** : la clé étrangère vise un créneau
réel et persisté, la fenêtre vit sur le poste, et les accesseurs retombent sur
le créneau quand l'override est absent — un poste non réduit se comporte donc
exactement comme son créneau. Impact sur les contraintes dans
[`contraintes.md`](contraintes.md#fenêtre-effective--ce-qui-la-lit-et-ce-qui-ne-la-lit-pas-délibérément).

### Verrouillage partiel du planning

`@PlanningPin` marque une place **validée et figée** : aucun move ne peut en
changer l'animateur. Le champ n'est ni positionné par le solveur ni persisté sur
le poste — il est recalculé à chaque construction du problème depuis la table
des verrous.

Un verrou porte sur un animateur, un stand, une journée, un créneau, ou un
couple animateur × créneau (posé automatiquement quand un échange est validé).

**Une cible, jamais deux.** En base, un verrou est une ligne plate — cinq
colonnes cibles nullables et un discriminant — que la contrainte `CHECK` garde
cohérente. Dans le code, la cible est une hiérarchie scellée : une variante ne
porte que ses propres champs, elle ne *peut* pas en désigner deux. Le `switch`
qui écrit les colonnes est exhaustif sans `default` — **ajouter une façon de
verrouiller ne compile pas** tant que personne n'a dit où elle atterrit.

Deux règles :

- **une place non pourvue n'est jamais figée.** Les places couvertes sont
  réamorcées avec l'animateur de la dernière résolution ; celles qui étaient
  vides restent mobiles, sinon geler un trou le rendrait définitivement non
  pourvu ;
- **une place figée est scorée normalement.** Un verrou peut donc laisser une
  violation visible — il ne désactive silencieusement aucune règle.

**Épingler ne suffit pas pour verrouiller un animateur** : cela fige les places
qu'il tient, mais le solveur pourrait lui en attribuer d'autres ailleurs. Ce
sont deux contraintes dures qui l'interdisent, à partir des verrous transmis
comme faits du problème.

### Replanification incrémentale

Répond à « il est 9 h, untel se désiste, que fait-on ? ». Elle **repart du
planning persisté**, épingle tout ce qui reste valable et ne rouvre que le
reste — d'où un budget par défaut de 60 s.

Le rapprochement est **positionnel**, sur stand × créneau, comme celui des
verrous : les places d'un même stand sur un même créneau sont interchangeables,
aucun identifiant de poste n'a donc besoin de survivre à un changement de
référentiel.

| Situation d'une place | Ce qui est fait |
| --- | --- |
| Désignée par le périmètre demandé | Libérée quoi qu'il arrive : l'utilisateur dit « refais ça » |
| Titulaire encore valable | **Épinglée** |
| Titulaire invalidé par un changement tardif — supprimé, indisponible, ou couvert par une indisponibilité forcée | Libérée. L'épingler figerait une violation dure que plus personne ne pourrait corriger. Le test de validité **réutilise le prédicat du solveur**, pour que les deux ne puissent pas diverger |
| Jamais pourvue, ou nouvelle | Laissée libre |

Deux choix à connaître :

- **le gel est volatil** : il vit le temps du job et n'écrit rien. Un verrou
  reste ce que l'utilisateur pose délibérément, jamais un artefact laissé par
  une replanification ;
- **tout ce qui est valable est épinglé**, y compris hors verrou explicite. Une
  replanification existe pour *stabiliser* le plan, pas pour le ré-optimiser.
  Rouvrir une zone est un acte explicite : la nommer dans le périmètre, ou
  lancer une résolution complète.

Le résultat porte les statistiques et le **diff des équipes** par stand ×
créneau — replanifier partiellement n'a d'intérêt que si l'on peut dire qui est
impacté. Une permutation entre places interchangeables d'un même stand n'y
figure pas : le planning de personne n'a changé.

### Demandes d'échange

Un animateur propose un échange depuis son espace ; **rien n'est appliqué sans
validation admin explicite**.

Le poste cédé est référencé par son couple **(créneau, stand)**, jamais par
l'id du `poste_affectation` : ces ids sont renumérotés à chaque résolution, le
couple est ce qui survit.

**L'identité.** Chaque animateur porte un jeton opaque, unique **globalement**,
qui résout à lui seul le couple (édition, animateur) — c'est le lien imprimé sur
son PDF. Le lien seul ne suffit pas : un **code à 6 chiffres envoyé à l'adresse
de la fiche** ouvre une session de 30 jours. Pas de compte ni de mot de passe,
**la boîte mail est le second facteur** — un animateur sans adresse doit la faire
ajouter. Une personne = un lien **par édition** ; dupliquer une édition frappe
des jetons neufs.

**L'accord du collègue.** Une demande naît `EN_ATTENTE_CIBLE`. Le collègue
l'accepte — elle devient `PROPOSEE`, l'admin est notifié, et n'a plus à demander
leur accord aux deux — ou la décline, ce qui est terminal. L'admin peut refuser
une demande encore en attente, mais **ne peut l'accepter qu'après** l'accord du
collègue.

**La simulation** juge sur le **score dur global**, pas sur les seuls postes
touchés : un échange peut casser une contrainte ailleurs — heures
hebdomadaires, repos. Échange croisé si la cible tient aussi un poste sur le
créneau, reprise simple sinon. Un échange **dirigé** troque deux créneaux
distincts (« je te laisse mon lundi, je prends ton mardi »).

**L'application** met à jour chirurgicalement les places concernées, **sans
re-résolution**, puis pose deux verrous sur le créneau que chacun **reçoit** :
la régénération suivante ne défera pas l'échange, sans geler le reste du
planning des deux animateurs.

**L'annonce attend la publication.** Trancher une demande ne mail personne sur
le champ : l'échange a changé le planning de travail, pas celui que le demandeur
a reçu, et son espace montre encore le précédent. La décision — acceptée ou
refusée — voyage avec la publication qui la porte, aux côtés des demandes encore
en attente. Voir *Planning publié* ci-dessous.

**Foire fermée** : soumissions et annulations refusées côté serveur, l'espace
passe en consultation seule — planning visible et téléchargeable, historique
conservé.

### Planning publié

Deux plannings coexistent par édition, et il faut les nommer.

Le **planning de travail** est celui que la résolution écrit et que
l'administration manipule : `poste_affectation`, un seul par édition. Le
**planning publié** est celui que les animateurs ont reçu — le dernier
instantané marqué publié (`plan_snapshot.publie_le`). C'est un instantané et
rien d'autre : le contenu dénormalisé de l'ADR 0007 était déjà exactement « un
planning complet, indépendant de ce qui bouge après ».

**L'espace animateur lit le planning publié.** Ce qu'une personne voit est ce
qu'on lui a envoyé — un échange validé, un remplacement appliqué, une nouvelle
résolution ne déplacent son espace qu'une fois publiés. Tant que rien ne l'a été
sur l'édition, l'espace est **vide** et le dit : replier sur le planning de
travail recréerait l'incohérence que la distinction supprime.

**Publier n'écrit qu'aux personnes concernées.** L'unité de comparaison est la
**vacation** — jour, heures effectives, stand — et l'identité comparée est celle
de la personne, pas celle du siège : un `poste_affectation` renuméroté par une
résolution ne déplace personne. Renommer un stand ne réveille donc personne ;
échanger deux vacations réveille exactement deux personnes. Un écart se dit en
ajout, retrait, ou **déplacement** quand un ajout et un retrait se répondent le
même jour — « Ninja 14h-18h remplace Cirque 14h-18h » est un changement, pas
deux.

Le calcul est volontairement **hors de la comparaison d'instantanés**, qui est
non nominative par choix (l'équité est une dispersion d'heures, jamais un
classement de personnes nommées). Celui-ci ne classe personne : il répond
« votre emploi du temps a-t-il changé depuis ce qu'on vous a envoyé ? », une
personne à la fois, et seulement pour celles à qui il va écrire.

## Découpage automatique en vacations

Un scénario « continu » ne définit qu'une **amplitude** par jour. Affecter
quelqu'un à un poste couvrant l'amplitude entière l'imposerait nominalement en
poste 10 à 14 h d'affilée.

Le découpage résout ça **à la génération, pas au solve** : chaque amplitude
devient plusieurs vacations plus courtes et chevauchantes. Tant qu'une vacation
reste sous `dureeVacationMaxMinutes` (6 h par défaut, seuil de l'art. [L3121-16])
**et** ne recouvre pas entièrement une fenêtre repas, elle n'a besoin d'aucune
pause interne : **la pause est le trou entre deux vacations**, pas un attribut
de créneau — sauf si l'organisateur déclare `pauseSurPoste` (voir plus bas).

Une amplitude est un créneau ordinaire, en base le temps de l'import ; le
découpage la remplace **en place**. Le solveur ne voit donc jamais d'amplitude.

### Le cas que le plafond de durée ne détecte pas

`dureeVacationMinMinutes` peut à lui seul empêcher un relais de tomber *avant*
une fenêtre repas — le premier relais d'une journée ouvrant à 10 h avec un
minimum de 3 h ne peut pas se produire avant 13 h, alors que le déjeuner
commence à midi. La seule coupe possible tombe alors *après* la fenêtre, et
l'animateur travaille seul pendant tout le repas. **Le plafond de durée ne voit
rien** : la vacation reste sous la limite.

Le découpeur détecte donc aussi ce cas — une vacation qui engloutit une fenêtre
repas de son début à sa fin — et y insère une vraie coupure, quelle que soit sa
durée. **Exception** : quand la fenêtre n'est englobée que parce qu'elle est
tronquée par la fermeture, aucune coupure n'est forcée — la journée se termine
simplement dans la fenêtre, plutôt que de créer une vacation résiduelle de
quelques minutes.

### Le chevauchement est un pic de demande

Pendant le chevauchement de deux vacations consécutives, deux postes existent
sur le même stand : l'effectif est garanti **en excédent** temporaire, jamais en
déficit.

Mais c'est aussi la principale source de pic. **Si tous les stands relèvent au
même instant, le nombre de sièges à pourvoir double à cet instant précis.** Sur
le scénario de référence, 86 sièges réellement ouverts devenaient 172 à pourvoir
à 18 h 30, pour 153 animateurs : infaisable par construction, sans la moindre
pénurie d'animateurs.

Deux leviers désamorcent le pic : `nombreFamillesDecalage` (grilles décalées,
chaque stand n'en suivant qu'une, donc les relèves s'étalent) et
`dureeChevauchementMinutes`.

> **L'analyse de faisabilité ne voit pas ce pic** : c'est une estimation
> optimiste, agrégée par jour. Elle peut répondre « réalisable » alors que le
> solveur ne ramène pas le score dur à zéro, précisément parce que trop de
> stands réclament un animateur au même quart d'heure. Le signal fiable après
> résolution est le **score dur réel**, pas `feasible` seul.

### Couverture pendant une pause

| Valeur | Effectif pendant la pause | Vacation générée |
| --- | --- | --- |
| `FERMETURE` (défaut) | aucun, le stand ferme | non |
| `RELEVE` | effectif plein, en plus des deux vacations encadrantes | oui |
| `EFFECTIF_REDUIT` | moitié de l'effectif du segment ouvert, **arrondie au supérieur** | oui |

`RELEVE` est à manier avec prudence sur un scénario déjà tendu : une vacation de
relève de plus par pause, sur *chaque* stand concerné, concentrée sur la même
fenêtre, est exactement le pic décrit ci-dessus.

`EFFECTIF_REDUIT` reproduit ce que fait réellement le classeur source : diviser
l'effectif par deux sur les créneaux de repas plutôt que d'ajouter une équipe ou
de fermer. **L'arrondi est au supérieur** pour qu'un stand tenu par une seule
personne la garde au lieu de fermer — fermer reste une décision explicite, pas
l'effet de bord d'une division entière.

### Sections de scénario

Un fichier peut fixer ses propres `parametresLegaux`, `parametresDecoupage` et
`parametresSolveur`, avec uniquement les champs à surcharger — les autres
gardent le défaut **de la classe Java, jamais la valeur en base**. C'est ce qui
permet à un gros scénario d'auto-configurer sa durée de résolution plutôt que de
dépendre de ce qu'un scénario précédent a laissé.

`decoupageAuto: {}` déclenche le découpage à l'import. Formats complets dans
[`import-export.md`](import-export.md).

## Contraintes ad hoc

Exceptions ponctuelles posées par l'administrateur, stockées en base et évaluées
dynamiquement — **jamais de contrainte codée en dur pour un cas particulier**.
Quatre types : indisponibilité forcée, incompatibilité, affectation forcée,
affinité.

## Paramètres légaux

`ParametresLegaux` est un fait de problème (`@ProblemFactCollectionProperty` sur
`PlanningEvenement`, même mécanisme que `ContrainteAdHoc`) qui porte les **deux
durées hebdomadaires de travail maximales**, paramétrables depuis la page
« Constraints » et persistées en base (`ReferenceDataService.getParametresLegaux()` /
`updateParametresLegaux(...)`).

| Champ | Défaut | Base légale | Contrainte qui le consomme |
| --- | --- | --- | --- |
| `dureeHebdomadaireMaxMinutes` | 48 h (2880 min) | Code du travail art. [L3121-20] (ordre public) ; CCN Animation ÉCLAT [IDCC 1518] art. 5.2 *[non vérifié]* | `dureeHebdomadaireMax` (**majeurs uniquement**) |
| `dureeHebdomadaireMaxMineurMinutes` | 35 h (2100 min) | Code du travail art. [L3162-1] ; art. [D4153-3] pour les 14 à moins de 16 ans | `dureeHebdomadaireMaxMineur` |

Les deux contraintes regroupent les `PosteAffectation` par animateur et semaine
ISO (`Creneau.semaineIso()`) et pénalisent le dépassement au prorata des minutes
excédentaires (gradient, pas simple booléen).

Les autres seuils légaux (repos quotidien, durée quotidienne, pauses, repos
hebdomadaire, jours fériés) sont **des constantes du code**, pas des paramètres :
ce sont des minima/maxima d'ordre public qu'un administrateur n'a aucune raison
légitime d'assouplir. Ils sont déclarés dans `LegalConstraints`, chacun avec son
article.


### Convention de rattachement à la semaine

Un créneau est rattaché **entièrement** à la semaine ISO de sa date de début. Un
créneau du dimanche 20 h → 00 h compte donc dans la semaine qui s'achève.
Convention assumée : elle simplifie le décompte et reste conservatrice tant que
les créneaux de nuit sont courts.

### Amplitude vs travail effectif

La durée d'un créneau mesure une **amplitude**, alors que les articles cités
portent sur le **travail effectif**. Par défaut le modèle ne représente aucune
pause à l'intérieur d'un créneau : les deux grandeurs coïncident, ce qui revient
à supposer qu'aucune pause n'y est prise. C'est précisément pourquoi les pauses
sont modélisées comme des **trous entre deux créneaux**.

### Un seul compte de sièges

Le nombre de sièges qu'un segment ouvert génère — au moins un, la moitié
arrondie au supérieur sur une vacation de couverture de pause — est une méthode
du créneau, `Creneau.siegesSegment`, et le plus chargé des segments d'un stand
sur un créneau est `Creneau.siegesSimultanes`. La génération des postes,
l'analyse de faisabilité (`GET /api/feasibility`), l'analyse de fragilité et la
règle `eviterRoulementStandsPremium` lisent tous cette même méthode : ce que le
solveur doit remplir et ce que les écrans annoncent ne peuvent pas être deux
nombres différents, et un effectif porté par une fenêtre est vu partout.

### Pause prise sur le poste

Le Code exige que la pause soit **réelle**, pas qu'elle soit planifiée : un
organisateur qui relève chaque animateur vingt minutes à l'intérieur d'une
vacation 13 h-20 h est en règle avec l'art. [L3121-16]. Le paramètre légal
`pauseSurPoste` (faux par défaut, `PUT /api/parametres-legaux`, section
`parametresLegaux` d'un scénario) déclare cette organisation. Quand il est vrai :

- `travailContinuMaxMajeur` et `travailContinuMaxMineur` considèrent la pause
  comme prise à la sixième heure (à 4 h 30 pour un mineur) et ne pénalisent plus
  une séquence longue ;
- `dureeQuotidienneMaxMajeur` et `dureeQuotidienneMaxMineur` déduisent de
  l'amplitude les pauses ainsi organisées — 20 minutes par tranche de 6 h
  entamée au-delà de la première (30 minutes par 4 h 30 pour un mineur) — pour
  ne compter que le travail effectif, comme les art. L3121-18 et L3162-1.

Une journée 14 h-minuit vaut ainsi 9 h 40 de travail effectif ; 13 h-minuit en
vaut 10 h 40 et reste refusée. Le paramètre est **déclaratif** : l'outil ne
vérifie pas que le stand peut relayer, c'est l'organisateur qui l'affirme.

## Paramètres de qualité

`ParametresQualite` est un fait de problème du même type que `ParametresLegaux`,
pour les seuils qui règlent le **confort** d'un planning et non la loi. C'est un
`record`, contrairement à `ParametresLegaux` : un fait de problème est immuable
par nature — le solveur le lit des milliers de fois par seconde et ne l'écrit
jamais. Il ne porte aujourd'hui qu'un champ :

| Champ | Défaut | Réglage | Contrainte qui le consomme |
| --- | --- | --- | --- |
| `maxEmplacementsDistinctsParJour` | 3 | `planning.contraintes.max-emplacements-par-jour` | `limiterEmplacementsParJour` |

Différence assumée avec `ParametresLegaux` : ces seuils ne sont **pas stockés
par édition** en base, ils viennent de la configuration de l'application. Ils
n'engagent aucune obligation, seulement un arbitrage d'organisation, et
`PlanningService.prepareProblem` les écrase systématiquement — un appelant ne
peut donc pas desserrer un seuil de qualité en l'envoyant dans son payload.

## Invariants à ne pas casser

- Un poste = une place, jamais un couple stand × créneau.
- Le statut mineur/majeur est calculé, jamais stocké.
- `souhaits` est un `Set` sans ordre ni priorité — ne pas le transformer en
  liste ordonnée.
- Les contraintes ad hoc prescriptives restent **dures** ; seule l'affinité est
  une récompense soft, par conception.
- Un créneau reste toujours l'unité de travail réellement assignable — jamais
  une amplitude d'ouverture brute.
- L'espace animateur ne montre jamais le planning de travail : ce qu'une
  personne voit est ce qu'on lui a envoyé.
- Les bornes d'une édition se dérivent de ses créneaux — une `Edition` ne
  stocke pas de dates, et une édition sans créneau n'a pas de bornes.
- Les noms de domaine restent en français métier.

<!-- Liens vers Légifrance. Chaque référence pointe vers la recherche par
     numéro d'article, qui résout toujours la version en vigueur : un
     identifiant LEGIARTI désigne une version datée, et vieillit en silence. -->
[D4153-3]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=D4153-3
[IDCC 1518]: https://www.legifrance.gouv.fr/conv_coll/id/KALICONT000005635177
[L3121-16]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3121-16
[L3121-20]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3121-20
[L3162-1]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3162-1
