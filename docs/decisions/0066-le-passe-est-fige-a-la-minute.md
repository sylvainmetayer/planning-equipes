# 0066 — Le passé est figé à la minute, pas au créneau

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : écran Aujourd'hui (ex-« Mode jour J »), assistant de
  réparation, panneau Siège, construction du problème (réamorçage,
  replanification incrémentale), persistance du plan et des instantanés,
  affichage mural, export de scénario
- **Assouplit** : [0044](0044-le-passe-est-fige.md), pour le créneau en cours

## Contexte

L'ADR 0044 fige les places des créneaux **déjà commencés** : elles sont reprises
du plan enregistré et épinglées, et toute écriture y est refusée
(« Ce créneau est déjà commencé : le passé ne se modifie plus »). La règle est
juste pour le passé, et fausse pour le cas le plus fréquent du jour J :
quelqu'un manque à 9 h sur le créneau 9 h – 12 h, on le constate à 9 h 20.
Son siège appartient à un créneau commencé ; il reste figé à son nom jusqu'à
midi, et les « Trouver un remplaçant » de l'écran du jour répondent tous 400.
Observé en édition réelle : 23 places à pourvoir, 23 refus.

## Décision

**Le passé est figé à la minute.** Un siège dont le créneau est en cours est
**scindé à « maintenant »** — la minute courante de l'horloge du jour J
(`PastHorizon`, `SeatSplit.minute`) — en **deux sièges réels** :

- l'**origine** est écourtée à cette minute (`heure_fin_effective`) et garde
  qui la tenait : l'historique dit toujours qui a tenu 9 h 00 – 9 h 20 ;
- la **suite** couvre le reste (`heure_debut_effective`), nomme son origine
  (`poste_affectation.suite_de`) et s'écrit comme n'importe quel siège à venir :
  « Remplacer sur le reste du créneau ».

La scission est faite par l'écriture elle-même (`PlanningWhatIf.writeSeating`,
donc la réparation, « Placer », « Marquer absent » et l'écriture MCP qui passe
par le même service), jamais par un écran : il n'y a qu'une façon de couper un
siège. Un siège déjà **terminé** reste refusé, comme le veut 0044 ; un siège
dont le début *est* la minute courante s'écrit tel quel (personne n'en a rien
tenu). Un siège vide en cours est scindé lui aussi : l'origine vide dit que
personne ne l'a tenu avant l'arrivée du remplaçant.

La suite a un identifiant dérivé, jamais tiré : `<origine>~HHmm`. Une
reconstruction du problème (réamorçage, incrémental) régénère les sièges depuis
le référentiel et ne sait rien de la scission : `SeatSplit.restore` rejoue alors,
pour chaque cellule stand × créneau scindée, les sièges persistés — fenêtres et
titulaires — sur les sièges générés, et ajoute chaque suite après son origine.
Le solveur incrémental ne rouvre ainsi que la partie future.

## Ce que cela coûte

- **Deux sièges pour une place.** Toute règle qui compte les présents par
  stand × créneau doit ne compter la place qu'une fois : l'équipage d'un stand
  premium (`QualiteConstraints.crewByStand`) ignore les suites, et un test
  `ConstraintVerifier` le tient. L'effectif min/max, lui, reste porté par le
  nombre de sièges générés : une suite n'en ajoute pas au référentiel.
- **L'export de scénario omet les suites.** Un scénario décrit les sièges à
  pourvoir, pas qui a tenu quelle part : écrit, le reste reviendrait comme un
  second siège entier et doublerait l'équipage du créneau.
- **« Nouveau depuis ce matin »** se lit cellule par cellule contre le plan
  publié (clé naturelle stand, jour, heures — 0025) : la suite est une cellule
  que le plan publié n'avait pas, sa place vide est nouvelle.

## Options écartées

- **Libérer le siège entier du créneau commencé** : l'historique perdrait qui a
  tenu 9 h – 9 h 20, et les heures travaillées de la personne absente
  mentiraient dans l'autre sens.
- **Figer au créneau et ouvrir un siège supplémentaire** : un siège de plus que
  le référentiel n'en prévoit, que l'effectif maximal du stand refuserait.
- **Scinder à l'heure pleine ou au quart d'heure** : une règle d'arrondi que
  personne ne devine, pour une minute gagnée sur l'affichage.
