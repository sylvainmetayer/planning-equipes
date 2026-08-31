# Import / export de données

La structure d'un fichier de scénario est décrite par
[`schema/scenario-schema.json`](schema/scenario-schema.json), **généré** depuis
les DTO de `dev.sylvain.planning.scenario.dto` — il ne peut donc pas diverger de
ce que le code accepte. Ce document porte les règles que le schéma ne peut pas
exprimer.

Sauf mention contraire, tout est lu et écrit dans l'édition désignée par
`X-Edition-Id`.

Trois voies d'entrée coexistent, et elles ne font pas le même travail : le
**scénario YAML** décrit un événement entier et remplace le référentiel de
l'édition, le **dump SQL** rejoue une base complète, et l'**import CSV des
animateurs** ne touche qu'un seul référentiel, ligne par ligne, sans rien
effacer par défaut. C'est le dernier qu'on utilise quand un organisateur
arrive avec son tableur de bénévoles.

## Dump SQL

C'est la seule opération **globale à l'instance** : une sauvegarde de la base,
toutes éditions comprises. L'import rejoue le script en une transaction et
n'accepte que `INSERT` / `DELETE` / `TRUNCATE` sur les tables métier.

L'export cloisonné par édition existe sous une autre forme : l'export de
scénario YAML.

## Ce que l'import d'un scénario remplace

- **rien ne sort de l'édition courante** ;
- animateurs et stands sont remplacés en totalité ;
- les créneaux reçoivent de **nouveaux ids en base** — les contraintes ad hoc
  qui les référençaient par leur id de fichier sont réassociées ;
- affectations et contraintes ad hoc de l'édition sont supprimées — ces
  dernières ne sont réécrites que si le fichier porte la section
  `contraintesAdHoc`.

Une section `edition: { id, nom? }` route l'import vers une autre édition, créée
vide au besoin. La réponse dit toujours où les données ont atterri : l'opérateur
peut consulter une édition différente de celle qui vient d'être écrite.

## Ce que l'export garantit

« Exporter les données actuelles en scénario » écrit **toutes** les sections que
l'import sait relire — pas seulement les entités, mais aussi `typologies`,
`emplacements`, `parametresLegaux`, `parametresDecoupage`, `parametresSolveur`,
`contraintes`, `contraintesAdHoc`, et `decoupageAuto` le cas échéant.

C'est la raison d'être de l'export : **réimporter le fichier reproduit
exactement le même problème**. Un fichier sans ces sections retombait
silencieusement sur les réglages de l'instance qui l'importe — sa durée de
résolution, ses durées de vacation, ses plafonds légaux — et le « même »
scénario rejoué ailleurs résolvait un autre problème.

Deux formes en sortent :

- une grille saisie à la main exporte ses `creneaux` **et** les `postes` qu'ils
  impliquent ;
- une grille issue d'un découpage exporte les **amplitudes sources** et
  `decoupageAuto`, **sans** `postes` : l'import rejoue le découpage et régénère
  les sièges, seule façon de garder des ids cohérents.

## Horaires d'un stand

Le sélecteur de jours est **à plat** : `jours` nomme lequel des champs voisins
s'applique (`TOUS` par défaut, `JOURS_SEMAINE`, `PLAGE`, `DATES`), et seul
celui-là est lu.

**`heureFin` omise signifie « jusqu'à la fermeture »** : la fenêtre court
jusqu'à la fin du créneau évalué. C'est ce qui permet à une même règle de
couvrir un jour fermant à 20 h et un jour fermant à minuit, et ce qui remplace
le contournement `23:59` qu'imposait une heure de fin concrète — une fenêtre ne
peut pas chevaucher minuit.

Une portée plus précise prime sur une portée plus large ; une exception datée
prime sur toutes les règles, pour le seul jour qu'elle nomme. Arbitrage complet
dans [`domaine.md`](domaine.md#horaires-récurrents--trois-couches-un-seul-mode-par-jour).

## `postes` : absente ≠ vide

**Absente**, la section est générée à partir des stands et créneaux importés —
un poste par place (`effectifMin`, pas `effectifMax`) sur chaque créneau ×
segment réellement ouvert, exactement comme « Lancer le solveur » depuis le
référentiel.

**Présente**, elle est reprise telle quelle, y compris vide. C'est ce qui permet
un staffing s'écartant de la règle.

Aucun scénario livré ne s'en sert : tous laissent l'import générer leurs sièges.
Une liste écrite à la main répète ce que `effectifMin` dit déjà, et le jour où
elle dérive des stands, le fichier décrit un problème qu'il ne dit plus.

## `contraintes` : en bloc, pas en fusion

Une règle que la section ne cite pas **redevient active, à son poids par
défaut**. Un scénario qui épingle son réglage décrit le problème contre lequel
il a été vérifié : une fusion laisserait en place les restes de l'édition qui
importe, et le « même » scénario continuerait de résoudre un problème différent
selon l'endroit où il atterrit. Absente, elle ne touche à rien.

Un nom de contrainte absent du catalogue est **refusé (400)**, pas ignoré :
c'est une faute de frappe ou un fichier écrit contre une autre version du
catalogue, et l'ignorer laisserait l'opérateur convaincu qu'une règle est
éteinte alors qu'elle ne l'est pas.

Les scénarios livrés portent tous cette section, avec les deux seuls poids non
neutres du produit — `appreciationIncompatible: 3` et
`maxJoursConsecutifsTravailles: 5`. Le défaut du déploiement est 1 partout : le
dosage voyage avec le scénario.

`contraintesAdHoc` **remplace** les contraintes ad hoc de l'édition ; absente,
le fichier n'en installe aucune. Elle désigne animateurs, stands et créneaux par
les ids **du fichier**.

Elles n'étaient auparavant pas conservées mais **héritées de l'édition
courante** — celle de l'appelant, résolue avant même que l'édition cible soit
connue. Une exception ainsi recopiée dans une autre édition y désigne un stand
et un créneau qui n'existent pas : l'import échouait alors sur une clé
étrangère. Et l'héritage n'était pas plus sain dans sa propre édition, puisque
l'import remplace tous les créneaux : les ids visés venaient d'être supprimés.
Ce qui préserve réellement les exceptions d'un aller-retour, c'est l'export, qui
écrit la section dès que l'édition en porte une.

Une exception qui ne peut pas tenir en même temps qu'une autre du même fichier
fait **refuser l'import entier**, avant toute écriture, avec un message qui les
nomme — le même contrôle qu'à la saisie
([`contraintes.md`](contraintes.md#contraintes-ad-hoc--les-contradictions-refusées-à-la-saisie)).

## Typologies

Un id référencé sans être déclaré est créé avec un libellé identique à son id.
La section `typologies` permet de fixer un vrai libellé — elle est appliquée
**après** l'import, pour ne pas être écrasée par cette création automatique. Au
plus une typologie porte `ninja: true` ; la déclarer retire le drapeau de la
précédente. L'export réécrit la section entière, drapeau compris.

## Import CSV des animateurs

Le seul import **partiel** du produit : il ne touche que les animateurs, une
ligne à la fois, et il ne supprime rien tant qu'on ne le lui demande pas. Écran
dédié, `/import-animateurs` ; endpoints dans [`api.md`](api.md#import-csv-des-animateurs).

### Un fichier d'exemple est livré avec l'application

`src/main/resources/scenarios/festival-realiste-animateurs.csv` — à côté du
scénario dont il dérive, et téléchargeable depuis l'écran d'import (bouton
« Télécharger un fichier d'exemple », servi par
`GET /api/animateurs/import-csv/exemple`). Il porte les **153 animateurs** du
scénario anonymisé `festival-realiste.yaml`, sur les **neuf colonnes** que
l'import lit, séparateur `;`, en-têtes reconnus tout seuls par la
correspondance proposée. Il montre ce qu'une cellule multi-valeurs contient :
compétences avec et sans niveau (`DIV:REFERENT|LOGISTIQUE|NINJA:DEBUTANT`),
souhaits, jours d'indisponibilité.

Il n'existe **qu'à cet endroit** : l'écran le récupère par l'API plutôt que
par une copie dans le bundle, et `AnimateurCsvExempleTest` réimporte cette
ressource-là par le vrai lecteur CSV. Le test vérifie qu'elle passe avec
**zéro ligne rejetée**, que son en-tête se mappe seul sur les neuf champs, et
que fiche par fiche elle décrit bien les animateurs du YAML — régénérer la
fixture anonymisée sans régénérer le CSV rend le test rouge, au lieu de
laisser dériver un exemple que personne ne relit.

Ses jours d'indisponibilité et ses typologies sont ceux de `festival-realiste`
(dates du 1<sup>er</sup> au 16 septembre 2026, typologies `DIV`, `ENF`,
`LOGISTIQUE`…). Déposé dans une édition qui a d'autres dates ou d'autres
typologies, il se fait donc rejeter des lignes : c'est un modèle de **forme**,
pas un jeu de données à reprendre. Ses dates de naissance, toutes identiques,
et ses noms « Animateur A1 » viennent de l'anonymisation de la fixture.

### CSV, et seulement CSV

Le lecteur est écrit à la main, sans dépendance. Il suit la RFC 4180 et
reconnaît tout seul ce qu'un tableur produit : séparateur `,`, `;` ou
tabulation (le plus fréquent hors guillemets l'emporte), guillemets et
guillemets doublés, fins de ligne CRLF ou LF, marque d'ordre d'octets d'un
« CSV UTF-8 », retour à la ligne à l'intérieur d'une cellule. Un `.xlsx` ou un
`.ods` déposé est reconnu et **refusé avec la marche à suivre** — pas avec une
erreur technique.

Le fichier doit être en **UTF-8**, et un fichier qui ne l'est pas se le voit
dire *comme tel*. Excel en français enregistre encore en Windows-1252 par
défaut : le navigateur lit alors chaque accent comme un caractère illisible,
bien avant que le serveur reçoive un octet, et plus rien ne permet de le
retrouver. L'import est **refusé en nommant l'encodage** et en citant un
extrait du fichier — pas en répondant « ce n'est pas du CSV » à quelqu'un qui
vient justement d'en enregistrer un. Un fichier réellement binaire (une NUL, ou
plus d'un dixième d'illisible) reste, lui, un refus de format.

Les lignes entièrement vides sont ignorées. Chaque ligne du rapport porte son
**numéro de ligne physique dans le fichier**, retours à la ligne des cellules
compris : c'est le seul repère sur lequel l'organisateur peut agir.

### Le mapping est par index de colonne

L'écran propose une correspondance à partir des en-têtes, en français comme en
anglais, puis **laisse tout modifier** — y compris tout effacer : une
correspondance vide envoyée explicitement est respectée telle quelle, elle ne
se voit pas redessiner par la proposition automatique. La correspondance désigne des colonnes
par leur **rang**, jamais par leur nom : un fichier peut porter deux fois le
même en-tête, ou n'en porter aucun, sans qu'il faille arbitrer à la place de
l'utilisateur. Une colonne déjà prise par un autre champ est déplacée, jamais
partagée.

Une colonne **non associée** n'est pas une colonne vide : le champ
correspondant n'est pas touché sur les fiches déjà en base. C'est ce qui rend
un fichier de rattrapage à trois colonnes inoffensif.

`competences`, `souhaits` et `joursIndisponibles` acceptent plusieurs valeurs
dans une même cellule, séparées par `|`, `;`, `,` ou un retour à la ligne —
**jamais `/`**, qu'une date `18/07/2030` utilise. Une compétence s'écrit
`typologie` ou `typologie:REFERENT`. Les dates se lisent en `JJ/MM/AAAA` comme
en `AAAA-MM-JJ`.

### Sur quoi une ligne reconnaît une fiche existante

Trois clés, dans cet ordre : la colonne `id` quand le fichier en porte une,
puis l'adresse e-mail, puis « prénom + nom » comparés sans casse ni accents.

Le nom est le dernier recours **et il peut être ambigu** : sur 150 bénévoles,
les homonymes existent. Une ligne dont le nom désigne deux fiches est
**rejetée** en nommant les identifiants concernés, plutôt que d'en choisir une.
Deux lignes du même fichier qui désignent la même personne : la seconde est
rejetée, en citant le numéro de la première — **la première effectivement
retenue**. Une ligne rejetée pour une autre raison n'occupe pas l'identité :
sinon la bonne ligne serait refusée au profit d'une mauvaise, et l'opérateur
renvoyé vers une ligne qui n'a rien importé.

### Ce qu'une ligne doit porter

Un nom (ou un identifiant), et une **date de naissance**. La seconde n'est pas
négociable : tout le régime mineur / majeur s'en déduit à la date de chaque
créneau, et la colonne est `NOT NULL`. Une ligne qui met à jour une fiche
existante peut l'omettre — la fiche en a déjà une.

Les cellules sont aussi bornées par la **largeur des colonnes de la base** :
64 caractères pour l'identifiant, 128 pour le prénom et pour le nom, 255 pour
l'adresse. Une valeur plus longue **rejette la ligne** en donnant sa longueur —
c'est la signature d'une colonne associée au mauvais champ (une colonne
« Commentaires » posée sur `nom`), et sans ce contrôle l'aperçu serait tout
vert avant que l'écriture n'échoue sur le fichier entier. L'identifiant
*engendré* pour une nouvelle fiche est tronqué à la même largeur, suffixe
compris.

Les typologies citées sont vérifiées contre le référentiel par **le même
contrôle que la saisie d'une fiche** (`TypologieService.validerIds`), appelé
ligne par ligne : une typologie inconnue coûte une ligne, pas le fichier.

### Jours d'indisponibilité : quatre situations, quatre réponses

| Situation | Ce que fait l'import |
| --- | --- |
| Jour hors des dates de créneaux | **Ligne rejetée**, motif au rapport. L'espace animateur ne dessine que les jours de l'événement : un tel jour serait stocké, invisible, puis effacé par la première déclaration acceptée |
| Aucun créneau dans l'édition | **Import refusé en bloc** (`409`), avant toute lecture. Sans dates de référence, rien n'est vérifiable ni affichable |
| L'animateur a une déclaration en attente | Ligne **acceptée**, avec un avertissement nominatif : la décision de l'administrateur remplacera les jours importés |
| L'animateur existe déjà | **Ajout par défaut** : les jours du fichier complètent ceux de la fiche. Une case à cocher de l'écran bascule en remplacement |

L'ajout est le défaut parce qu'un import de rattrapage ne doit pas effacer ce
que les animateurs ont déclaré eux-mêmes pendant la collecte. Les compétences
et les souhaits, eux, sont **toujours** ajoutés : retirer une compétence reste
un geste de l'écran de référentiel.

### Ajout ou remplacement complet

Le YAML remplace tout ; **le CSV, non, sauf demande explicite**. Décoché
(défaut), l'import ajoute et met à jour, et personne ne disparaît. Coché, les
animateurs absents du fichier sont supprimés — et leurs postes du planning
persisté sont **libérés**, pas détruits, comme une suppression de fiche.

Le remplacement complet est **refusé tant qu'une ligne est rejetée** : sinon il
supprimerait justement les personnes que ces lignes n'ont pas su décrire. Le
bouton « Importer » est d'ailleurs désactivé dans ce cas, avec le motif écrit
sous la case.

L'aperçu chiffre ce que cette suppression emporte, comme le fait la suppression
d'une fiche à l'unité : places libérées dans le planning persisté,
verrouillages retirés, contraintes ad hoc concernées. Et il nomme le reste,
qui part **en cascade et sans retour** avec la fiche : déclaration de
disponibilités, accusé de réception du planning publié, échanges de la foire au
planning, code d'accès à l'espace animateur, compétences et souhaits. Un
avertissement qui sous-estime ce qu'il détruit vaut moins qu'aucun
avertissement.

### Prévisualiser puis écrire, en deux appels

L'analyse et l'écriture sont deux endpoints, et **le second reçoit le fichier,
pas le rapport**. Il le relit et rejoue toutes les vérifications avant
d'écrire : un rechargement de page, un rejeu de requête ou un corps fabriqué à
la main ne peut pas faire passer une ligne devant un contrôle.

L'écriture est **atomique** : les lignes acceptées et les suppressions
éventuelles partent dans une seule transaction. Un échec en cours de route
annule tout et remonte, donc le rapport affiché n'annonce jamais 148 lignes
écrites après un retour arrière.

Le fichier est traité **en mémoire de bout en bout** et n'est jamais écrit sur
disque : il porte des noms, des dates de naissance et parfois des mineurs (voir
[`rgpd.md`](rgpd.md)). Deux plafonds explicites le bornent, 1 000 000 de
caractères et 5 000 lignes de données, en plus du plafond de corps HTTP de
[`securite.md`](securite.md).

## Fixtures réalistes anonymisées

`festival-realiste.yaml` et `festival-realiste-canicule.yaml` sont **dérivés
d'une édition réelle**. Ils servent à une démo grandeur nature et à la
régression de convergence de `PlanningServiceScenarioFestivalRealisteTest`.

Réécrits : prénoms, noms, ids et noms d'emplacements et de stands, nom de
l'édition. **Pas** réécrits : heures, effectifs, compétences, horaires,
typologies, paramètres — une fixture qui ne converge pas comme sa source ne
teste pas ce qu'elle prétend tester.

Le calendrier fait exception, et c'est la seule : `--date-debut` translate tout
l'événement en bloc pour le détacher des dates réelles de sa source (ces
fixtures ouvrent le **01/09/2026**, 57 jours après l'original). Les dates de
naissance ne bougent jamais — les décaler changerait qui est mineur, donc le
problème lui-même. Un décalage multiple de 7 conserverait les jours de la
semaine et l'ancrage des semaines ISO ; celui-ci ne l'est pas, il déplace donc
les bornes de semaine et les jours fériés traversés. **La convergence est à
revérifier après tout changement de cette option**, jamais à supposer.

Trois détails avant de les régénérer :

- **les coordonnées sont translatées en longitude, à latitude constante**, pas
  supprimées. Une contrainte de qualité pénalise deux emplacements distants :
  les retirer changerait le score. À latitude et delta inchangés, la haversine
  rend les mêmes distances — vérifié au mètre près ;
- **les ids anonymes sont numérotés dans l'ordre d'apparition**, et l'ordre des
  sections préservé. Timefold épingle `randomSeed=0`, mais un tri ou un hachage
  s'appuyant sur les ids ferait diverger la trajectoire de recherche ;
- **le décalage de calendrier n'est pas mémorisé dans le fichier** autrement que
  par l'en-tête que le script y écrit : régénérer sans repasser `--date-debut`
  ramène les dates de la source.

`festival-realiste-animateurs.csv` **dérive de `festival-realiste.yaml`** (voir
plus haut). Une régénération de la fixture qui change un animateur, une
compétence ou les dates de l'événement fait échouer `AnimateurCsvExempleTest` :
c'est voulu, et le CSV est alors à régénérer avec elle. La règle est
mécanique : une ligne par animateur du YAML **dans l'ordre du fichier**,
`identifiant;prénom;nom;date de naissance;email;manager;compétences;souhaits;jours indisponibles`,
dates en `JJ/MM/AAAA`, `manager` en `oui`/`non`, compétences jointes par `|`
avec le niveau suffixé (`DIV:REFERENT`) sauf `AUTONOME` qui s'écrit nu. Seules
les colonnes `souhaits` et `jours indisponibles` n'existent pas dans la fixture
— elles y sont ajoutées pour montrer la cellule multi-valeurs, sur une
minorité de lignes (33 et 25 sur 153), avec des typologies déclarées par le
scénario et des dates prises dans ses créneaux. Le test n'en vérifie que ces
propriétés-là, pas les valeurs : elles sont libres, et il suffit qu'au moins
une ligne porte deux compétences, deux souhaits et deux jours pour que
l'exemple illustre encore le séparateur `|`.

Les sources restent hors dépôt : `docs/reel-*.yaml` et
`scenarios/reel-*.yaml` sont dans `.gitignore`, elles portent des données
personnelles réelles. **Le préfixe `reel-` est précisément ce qui les rend
invisibles à git** — d'où le nom `festival-realiste`, sans quoi la fixture ne
serait pas versionnée et le test casserait en CI.

Régénération :

```bash
python3 src/main/resources/anonymiser-scenario.py \
    ~/…/reel-2026.yaml src/main/resources/scenarios/festival-realiste.yaml \
    --edition-id festival-realiste --edition-nom "Festival réaliste" \
    --date-debut 2026-09-01
```

## Fixture volontairement insoluble

`scenario-avec-erreur-planning.yaml` est la seule fixture livrée qui **ne doit
pas converger** : six stands, cinq animateurs, dix créneaux sur trois jours, et
un défaut d'ouverture par stand (fenêtre hors créneau, effectif minimum
au-dessus de l'effectif total, règle récurrente hors amplitude, exception datée
qui remplace la règle du jour, heure de fin mal saisie, typologie que personne
ne maîtrise). Elle sert à montrer ce que disent les pages Problèmes, Besoin en
animateurs, Ouvertures et l'analyse de faisabilité quand la saisie est fausse.
`PlanningServiceUnsolvableScenarioTest` fige l'intention — le fichier est
accepté à l'import et le solveur ne peut pas atteindre un score dur nul — mais
aucun score.

## Deux notes pour qui touche au format

**Régénérer le schéma** après avoir modifié un DTO :
`./mvnw process-classes -Pgenerate-schema`. Le schéma ne peut pas exprimer les
règles conditionnelles d'un sélecteur d'horaires — celles-là sont vérifiées par
`StandValidator` — ni les références croisées, vérifiées à l'import réel.

**Le fichier est lu en une seule passe**, par un point d'entrée qui rend le
planning et toutes les sections optionnelles ensemble. Ajouter une section
optionnelle = un champ dans `ScenarioSections` et une ligne dans `sectionsOf`,
**pas une méthode publique de plus** : une méthode par section reparserait le
fichier entier à chaque appel, soit sept parses complets pour un seul clic.

## Exports PDF / ICS

Générés côté serveur. Les deux affichent l'horaire **effectif** du poste, pas
celui, plus large, de son créneau : un poste réduit par une fermeture partielle
montre à l'animateur les heures qu'il couvre réellement, pas la plage fermée.

Quand le stand a un emplacement géocodé, le PDF porte un lien OpenStreetMap et
l'ICS les champs `LOCATION` et `GEO`.

Le même document ICS se sert de deux façons, et la différence est de mode, pas
de format : en **téléchargement** (une photo à l'instant du clic) ou en
**abonnement**, sur une adresse permanente que l'agenda rappelle tout seul —
`GET /api/abonnements/{token}/planning.ics`, voir [`api.md`](api.md). Les
`UID` des événements sont stables d'un appel à l'autre, ce qui est la condition
pour qu'un client d'agenda mette à jour ses rendez-vous au lieu d'en créer des
doublons à chaque synchronisation.
