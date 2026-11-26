# Faisabilité, fragilité et pauses côté API

Vous saurez ce que disent, sans lancer le solveur, les lectures de capacité du plan : la faisabilité et le besoin minimum en animateurs, la fragilité (qui est irremplaçable), les pauses légales et l'intendance des repas. Elles servent aussi au jour même, décrit dans [jour J côté API](api-jour-j-et-animateurs.md).

## Faisabilité et besoin en animateurs

Deux calculs de capacité sans lancer le solveur, affichables avant une résolution de plusieurs minutes. Ils sont délibérément optimistes et se lisent comme un plancher.

- `GET /api/feasibility` ignore quel stand précis chaque animateur pourrait tenir : `feasible: true` ne garantit pas un score dur nul après résolution. La compétence n'y entre pas : depuis qu'elle est une contrainte medium, n'importe quel animateur disponible peut tenir n'importe quel stand, et un écart d'appréciation se signale après résolution.
- `GET /api/staffing` ne regarde les compétences que dans son volet par typologie, et ignore le repos quotidien. Les indisponibilités déclarées sont reportées à part.

### Les causes de la faisabilité

Une cause n'est pas toujours un manque d'animateurs. Les causes suivantes sont `CRITIQUE` et nomment les exceptions concernées dans `contrainteIds` :

| Cause | Sens |
| --- | --- |
| `CONTRAINTES_AD_HOC_CONTRADICTOIRES` | Des exceptions qui ne peuvent pas toutes tenir. Elles sont refusées à la saisie : celles-ci ont été enregistrées avant ce contrôle ou importées en bloc. Classée avant les sous-effectifs, car elle se corrige en supprimant une ligne saisie |

La contradiction déclenche une confirmation sur les trois boutons de l'écran Solveur, car elles garantissent un score dur négatif quel que soit le budget. Le sous-effectif, même `CRITIQUE`, n'en déclenche aucune : le solveur l'atténue encore, et une confirmation qui se déclenche à chaque soirée tendue n'est plus lue.

`PLAFOND_JOURS_CONSECUTIFS` lit le plafond de jours travaillés d'affilée contre toute la grille, dès que la règle est active. C'est une seule cause, sur la fenêtre la plus tendue, dont `date` est le premier jour. `demande` et `capacite` comptent des jours-personnes, `manque` le déficit (0 sur une simple marge mince). Elle est `CRITIQUE` seulement comme preuve sous la règle obligatoire, `ELEVE` sinon, et une cause `ELEVE` ne rend pas le rapport non réalisable. Une `CRITIQUE` se range avec les contradictions pour ne jamais tomber hors des dix causes renvoyées. Le plan enregistré n'est relu que dans deux cas : pendant l'événement, un jour passé compte les personnes qui l'ont réellement travaillé ; sous la règle obligatoire, quand la grille passe, la même cause peut venir du plan en place.

### Les bornes du besoin minimum

Quatre bornes, la plus grande étant retenue. Chacune découle d'une règle que `LegalConstraints` applique vraiment, si bien que plancher et solveur ne peuvent pas se contredire.

| Borne | Calcul |
| --- | --- |
| Pic simultané | Sièges ouverts au même instant : personne n'en tient deux |
| Pic avec pause | Le même pic sur des intervalles prolongés de la pause légale entre deux vacations : le nombre exact d'animateurs distincts qu'exige la journée la plus chargée |
| Charge horaire | Les heures de la semaine ISO la plus chargée, rapportées à ce qu'un animateur peut y travailler |
| Rotation sur les jours | Les jours-personnes de cette même semaine, rapportés aux six jours qu'un animateur peut y travailler (art. L3132-1) |

Les deux dernières se prouvent semaine par semaine, jamais sur le total : la capacité inemployée d'une semaine que l'événement effleure n'en dote aucune autre. La capacité d'une semaine est plafonnée deux fois, par le plafond hebdomadaire et par les jours que l'événement y occupe (six au plus, de dix heures au plus). La rotation rattrape le cas d'une journée de cent personnes répétée sept jours d'affilée, qui ne se tient pas à cent puisque personne ne travaille sept jours. Le besoin d'une journée est le plus grand du pic avec pause et de ses heures divisées par le plafond quotidien.

`minimumAvecIndisponibilites` corrige le plancher par les indisponibilités déclarées : une journée dont 70 % du vivier connu sont libres exige un vivier de `besoin / 0,7`. C'est une projection (elle suppose les recrues à venir aussi indisponibles), d'où son champ distinct ; `indisponibilitesDeclarees` distingue « tout le monde est libre » de « personne n'a répondu ».

`parCompetence` décline les quatre bornes par typologie, le goulot par compétence (« il manque 4 personnes compétentes en escape game »), avec deux règles d'attribution :

- un siège compte pour une typologie seulement si son stand ne propose qu'elle. Les sièges d'un stand multi-typologies ne sont revendiqués par aucune et vont dans `siegesNonAttribues`. Un stand sans aucune typologie est le cas inverse : `hasCompetenceFor` ne répond oui qu'aux polyvalents, ses sièges sont la demande la plus serrée et rejoignent la ligne polyvalente (`siegesReservesAuxPolyvalents`). Sans typologie polyvalente au référentiel, personne ne peut les tenir ;
- un polyvalent (typologie marquée `polyvalent`) compte comme spécialiste dans les seules compétences qu'il déclare, jamais dans toutes : le compter partout ajouterait la même personne à chaque vivier et masquerait le goulot. Il est reporté une fois de plus comme renfort partagé (`polyvalents`). `typologiePolyvalenteDefinie` à faux se lit « pas de renfort ici », pas « pénurie ». Le manque de la ligne polyvalente est isolé dans `manquePolyvalents`, car ce vivier étant celui des renforts, il ne peut pas être proposé contre son propre manque.

Les deux approximations restantes vont dans le même sens (demande sous-estimée pour les stands multi-typologies, vivier surestimé pour un animateur compétent sur plusieurs) : un goulot signalé en est un, l'absence de goulot ne prouve rien.

Sans animateur au référentiel, il n'y a pas de bornes : la réponse est celle d'une édition vide, toutes les bornes à 0, et `animateursTotal` à 0 est ce qui distingue « liste pas encore remplie » de « aucun stand à typologie unique ». Une simulation est construite côté serveur sur des copies en mémoire, rien n'est écrit : le JSON d'un planning réel dépasse la taille de corps admise.

## Fragilité du planning

`GET /api/fragilite` répond à une question distincte : qui est irremplaçable. `/api/feasibility` dit pourquoi un planning ne tient pas, `/api/staffing` combien recruter, celui-ci ce qu'un désistement emporterait. Il ne lance aucune résolution : il lit le plan persisté et les compétences. Deux indicateurs :

- par animateur, les couples stand x créneau qui passeraient sous l'effectif minimum s'il se désiste. Les sièges sont générés à `max(1, effectif du segment ouvert)`, moitié sur une vacation de couverture de pause : le nombre de sièges d'un groupe est son plancher. Le chiffre qui classe est `postesIrremplacables`, les groupes où personne d'autre ne pourrait reprendre le siège. Un groupe déjà en sous-effectif n'est imputé à personne (`groupesDejaSousEffectif`) ;
- les couples stand x créneau tenus par au plus une personne compétente pour les typologies du stand, avec la sévérité correspondante.

Un remplaçant possible est compétent, non déclaré indisponible ce jour-là, majeur si le stand est réservé aux majeurs, et libre de tout siège chevauchant. Repos quotidien, plafond hebdomadaire, ad hoc et préférences ne sont pas vérifiés : un « irremplaçable » l'est certainement, un « remplaçable » peut ne pas l'être après résolution. Un seul désistement est simulé à la fois.

Les polyvalents sont comptés différemment selon l'indicateur, à dessein (`Animateur.hasCompetenceFor` répond oui pour eux sur tous les stands) :

- la rareté de compétence ne compte que les spécialistes et affiche les polyvalents disponibles en `renforts`. Les inclure ferait disparaître presque toutes les lignes, un vide qui serait un artefact de mesure ;
- la remplaçabilité d'un siège les compte pleinement : la question est de savoir si le siège peut être tenu demain, et le solveur y enverrait un polyvalent.

Le raisonnement et les deux options écartées sont dans [la décision 0017](repo:docs/decisions/0017-fragilite-le-ninja-est-un-renfort-pas-un-specialiste.md). `polyvalentConfigure: false` signale qu'aucune typologie n'est marquée polyvalente : les deux comptages coïncident alors et un `renforts` à zéro ne dit rien.

Trois précisions de contrat. Un couple est en réalité un triplet stand x créneau x fenêtre : un stand fermé en milieu de créneau produit plusieurs lignes qui ne diffèrent que par `heureDebut`, et `groupesSansSpecialiste` comme `groupesAnalyses` se comptent dans cette unité. `effectifMin` est l'effectif configuré pour la fenêtre, pas le plancher : sur une vacation de couverture de pause (`couverturePause: true`) les sièges sont générés à la moitié arrondie au supérieur, et `siegesRequis` porte le plancher applicable. Les listes sont bornées (20 postes détaillés par animateur, 100 couples) ; `postesNonDetailles` et `totalCompetencesRares` disent ce qui n'est pas montré, et les animateurs sans affectation ne sont pas listés.

## Pauses légales

`GET /api/pauses` dit où tombent les pauses, pour qui, de quelle heure à quelle heure, sur quel stand et qui relaie. Le solveur exige qu'une pause due soit prenable (un trou dans la grille ou un collègue du même stand : art. L3121-16, six heures ; art. L3162-3, 4h30 et trente minutes au minimum pour un mineur) sans dire à quelle minute elle se prend. Poser la rotation est ce que fait ce rapport. Une pause que personne ne peut relayer est un écart dur ; un plan persisté peut en porter une, rapportée avec `relaisDisponible` à faux.

C'est une lecture du plan persisté, sous les paramètres légaux courants (« avec ce que je déclare aujourd'hui, que reste-t-il à organiser »), jamais une résolution. Sans plan : rapport vide, `journeesAnalysees` à zéro, pas d'erreur.

- `journees[]` : une entrée par animateur et par jour qui doit ou liste une pause, triée par date puis nom, avec `mineur` (le barème), `sequences[]` et `pausesPlanifiees[]`.
- `sequences[]` : les séquences de travail ininterrompu sur les fenêtres effectives des sièges, fusionnées quand le trou entre deux est plus court que la pause légale. Chaque `pausesDues[]` porte `debut` et `fin`, `heureLimite` (au plus tard ; `debut` ne la dépasse jamais), `dureeMinutes` (celle de l'édition, au moins 30 pour un mineur), le stand tenu pendant la pause, `relais[]`, `relaisDisponible` et `simultanee`. Une séquence de douze heures et demie en doit deux, la seconde six heures vingt après la première.
- `pausesPlanifiees[]` : les trous d'au moins la pause légale entre deux séquences, typiquement la relève de midi.
- `journeesAnalysees`, `pausesDues`, `relaisManquants`, `message` : les compteurs de l'écran et sa phrase.

La rotation pose, sur chaque stand et chaque jour, les pauses l'une après l'autre au plus tard possible : la première traitée est celle dont l'heure limite est la plus tardive, la suivante se termine où la précédente commence, si bien qu'une seule personne du stand est sortie à la fois. Une pause ne commence pas trop tôt non plus : ce qui reste de la séquence doit tenir sous le seuil. Quand les fenêtres ne laissent pas la place (trois personnes sur douze heures d'affilée), la pause est posée au début de sa fenêtre et `simultanee` le dit ; le plan n'est pas modifié. Une date de naissance inconnue est lue au barème adulte, la pause adulte étant toujours due. Une seule règle de pause existe ([décision 0048](repo:docs/decisions/0048-une-seule-regle-de-pause.md)) ; les règles elles-mêmes sont dans [les règles légales et les pauses](regles-legales-et-pauses.md).

La même lecture sert le planning individuel : `pauses[]` de `GET /api/espace-animateur/{jeton}` (sur le plan publié), son PDF (« Pause de 18:20 à 18:50 (30 min) » sous la vacation qui la doit) et son flux de calendrier. Le PDF porte aussi la coupure repas, qui n'est pas la pause légale : c'est la règle que l'organisation se donne, une heure par défaut, dans les fenêtres midi et soir des paramètres légaux. Quand la pause légale tombe dans la coupure, une seule carte le dit (« Repas de 13:00 à 14:00 (60 min), pause légale comprise ») : deux cartes feraient lire deux obligations pour un seul moment.

## Intendance des repas

`GET /api/pauses/intendance` est la même lecture, comptée au lieu d'être nommée : par jour et par fenêtre, combien de personnes sont en coupure, heure pleine par heure pleine et par emplacement. Le rendu « Pauses et repas » de la page Planning dit qui sort et quand ; celui-ci dit combien de repas préparer et où les porter.

- `journees[].fenetres[]` : une table par fenêtre déclarée, avec `tranches[]` (début de chaque bande, calé sur l'heure et non sur le début de la fenêtre : 12:15-13:45 se lit 12-13 puis 13-14, la largeur étant `pasMinutes`), `emplacements[]` et les totaux.
- `emplacements[].personnes[]` et `mineurs[]` : un compte par tranche. `total` compte les personnes distinctes de la fenêtre, jamais la somme de `personnes[]`, qui compte quelqu'un une fois par heure que sa coupure traverse.
- `message` dit pourquoi le rapport est vide quand il l'est (rien de résolu, aucune fenêtre, aucune journée ne traversant une fenêtre) : « 0 » et « rien n'est résolu » ne sont pas le même écran.

Le lieu est l'emplacement du stand que la personne quitte quand la coupure commence, là où il faut porter à manger. L'heure est celle où `PauseAnalyzer` place la coupure, au plus tôt dans le trou libre, et non celle que `FenetreRepas.auPlusTard` préférerait, pour que les deux écrans lisent la même journée de la même façon. Personne n'est nommé et les mineurs sont comptés : ni nom ni date de naissance ne sortent. `GET /api/pauses/intendance/export` est la même liste à plat (`jour;fenetre;emplacement;tranche;personnes;dont mineurs`), parce qu'elle sort de l'outil pour aller à l'intendance. L'organisation des repas elle-même reste hors de l'outil : ce point n'expose que le chiffre que seul le planning connaît.
