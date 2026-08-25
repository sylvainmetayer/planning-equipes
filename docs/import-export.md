# Import / export de données

La structure d'un fichier de scénario est décrite par
[`schema/scenario-schema.json`](schema/scenario-schema.json), **généré** depuis
les DTO de `dev.sylvain.planning.scenario.dto` — il ne peut donc pas diverger de
ce que le code accepte. Ce document porte les règles que le schéma ne peut pas
exprimer.

Sauf mention contraire, tout est lu et écrit dans l'édition désignée par
`X-Edition-Id`.

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
- affectations et contraintes ad hoc de l'édition sont supprimées.

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

`contraintesAdHoc` **remplace** les contraintes ad hoc de l'édition quand elle
est présente ; absente, elles sont conservées. Elle désigne animateurs, stands
et créneaux par les ids **du fichier**.

## Typologies

Un id référencé sans être déclaré est créé avec un libellé identique à son id.
La section `typologies` permet de fixer un vrai libellé — elle est appliquée
**après** l'import, pour ne pas être écrasée par cette création automatique. Au
plus une typologie porte `ninja: true` ; la déclarer retire le drapeau de la
précédente. L'export réécrit la section entière, drapeau compris.

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

`event-avec-erreur-planning.yaml` est la seule fixture livrée qui **ne doit pas
converger** : six stands, cinq animateurs, dix créneaux sur trois jours, et un
défaut d'ouverture par stand (fenêtre hors créneau, effectif minimum au-dessus
de l'effectif total, règle récurrente hors amplitude, exception datée qui
remplace la règle du jour, heure de fin mal saisie, typologie que personne ne
maîtrise). Elle sert à montrer ce que disent les pages Problèmes, Besoin en
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
