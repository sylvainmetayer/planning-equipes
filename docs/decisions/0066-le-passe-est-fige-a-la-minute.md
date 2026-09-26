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
tenu).

**Un siège vide en cours n'est pas scindé, il est rogné.** Personne ne l'a
tenu : il n'y a pas d'historique à garder, et une origine vide de 9 h à 9 h 20
ne servirait qu'à compter un siège « non pourvu » de plus — dans les places à
pourvoir comme dans l'indicateur des sièges —, un fragment de quelques minutes
par geste, sur un créneau qui a déjà eu lieu. Quand on y place quelqu'un, le
siège garde sa ligne et son début passe à « maintenant »
(`heure_debut_effective`, `SeatSplit.narrow`) : le remplaçant le tient à partir
de 9 h 20, jamais sur les minutes écoulées — lui attribuer le siège entier
prétendrait qu'il a tenu 9 h – 9 h 20, ce qui est faux, et c'est ce que le
passé figé de 0044 interdit. Les minutes écoulées ne sont plus couvertes par
aucune ligne : c'est ainsi que le plan dit que personne ne les a tenues, et
l'historique des actions garde le geste et sa minute. Le rognage s'écrit contre ce que le geste a lu, comme la scission : le
début n'avance que si le siège commence et est vide comme à la lecture. Un
geste qui ne place personne sur un siège vide (le vider encore) l'écrit tel
quel.

Une reconstruction régénère ce siège entier depuis le référentiel ; il doit
donc se reconnaître au plan enregistré. Il prend l'identifiant dérivé d'une
suite, `<siège>~HHmm`, sans nommer d'origine : un identifiant portant une
minute et sans `suite_de` est un siège rogné, que `SeatSplit.restore` rejoue
comme une scission — sa fenêtre, son titulaire, et la minute reportée dans
son nouvel identifiant. Sans cela, le gel positionnel rendrait au remplaçant
le créneau entier. Aucune colonne n'est ajoutée : la convention d'identifiant
existe déjà pour les suites, et un siège rogné se comporte partout ailleurs
comme un siège d'origine — il compte une place, pour l'effectif comme pour
l'équipage d'un stand premium, et il s'exporte dans un scénario.

La scission s'écrit **contre ce que le geste a lu** : l'origine n'est écourtée
que si elle finit et est tenue comme à la lecture, la suite n'est insérée que si
aucun siège ne porte déjà son identifiant, et un siège écrit sous condition
(« Libérer », « Remplacer », « Placer ») ne change de mains que s'il est encore
tenu par la personne affichée. Deux gestes décidés sur un plan périmé ne posent
donc jamais deux suites sur les mêmes minutes : le second est refusé en 409, et
rien n'est écrit. Les heures se lisent sur le calendrier : le reste d'un créneau
de nuit 22 h – 2 h scindé à 1 h tombe le lendemain, à 1 h.

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
  `ConstraintVerifier` le tient. À l'inverse, qui est présent sur le reste du
  créneau se lit sur la suite et non sur l'origine, partie à la minute de la
  scission : l'encadrement d'un mineur ne compte que les majeurs dont la
  fenêtre chevauche la sienne, le référent d'un stand complexe et la
  répartition mineurs / majeurs ne comptent pas une origine scindée. L'effectif min/max, lui, reste porté par le
  nombre de sièges générés : une suite n'en ajoute pas au référentiel.
- **Les rapports comptent une place, et deux fenêtres.** Tout ce qui compte
  des sièges — pourvus, à pourvoir, couverture, indicateurs, grille « Par
  stand », synthèse MCP, planning global — compte la place sur sa **partie
  courante**, celle qu'aucun siège ne continue (`SeatPlaces`, côté serveur
  comme côté écran) ; tout ce qui somme des heures somme chaque partie sur sa
  fenêtre. Une suite reprise par le titulaire de l'origine est, pour lui, le
  même siège : ni un poste de plus à l'équité, ni un changement à la
  publication.
- **L'export de scénario omet les suites.** Un scénario décrit les sièges à
  pourvoir, pas qui a tenu quelle part : écrit, le reste reviendrait comme un
  second siège entier et doublerait l'équipage du créneau.
- **« Nouveau depuis ce matin »** se lit cellule par cellule contre le plan
  publié (clé naturelle stand, jour, heures — 0025) : la suite est une cellule
  que le plan publié n'avait pas, sa place vide est nouvelle. Un siège rogné
  vide, lui, est le trou publié avec moins de minutes : le trou publié du
  même stand et du même jour dont la fenêtre le couvre en rend compte.

## Options écartées

- **Libérer le siège entier du créneau commencé** : l'historique perdrait qui a
  tenu 9 h – 9 h 20, et les heures travaillées de la personne absente
  mentiraient dans l'autre sens.
- **Figer au créneau et ouvrir un siège supplémentaire** : un siège de plus que
  le référentiel n'en prévoit, que l'effectif maximal du stand refuserait.
- **Scinder à l'heure pleine ou au quart d'heure** : une règle d'arrondi que
  personne ne devine, pour une minute gagnée sur l'affichage.
- **Scinder aussi le siège vide** : une origine vide ne garde rien que
  l'historique n'ait déjà, et chaque réparation laisserait un fragment de
  quelques minutes compté « non pourvu » sur un créneau déjà passé.
- **Placer le remplaçant sur tout le siège vide** : il serait réputé avoir
  tenu les minutes écoulées — ses heures travaillées et l'historique
  mentiraient.
- **Marquer le siège rogné par une colonne** plutôt que par son identifiant :
  une migration, un champ de plus au modèle et aux instantanés pour une
  information que la convention `~HHmm` porte déjà.
