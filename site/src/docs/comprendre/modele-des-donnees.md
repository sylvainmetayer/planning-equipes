# Le modèle des données et ses invariants

Vous savez quels objets portent le problème de planning, ce que l'application dérive au lieu de le stocker, comment une place à pourvoir est représentée, et quelles règles du modèle ne se contournent pas quand vous ajoutez une fonction.

Le planning est un problème de *shift rostering* au sens de Timefold : des animateurs à placer sur des postes, sous contraintes, avec un score à trois niveaux (`HardMediumSoftScore`). Les classes et leurs champs se lisent dans [`domain/`](repo:src/main/java/dev/sylvain/planning/domain). Cette page porte ce que le code ne dit pas. Les noms de classes et de champs restent en vocabulaire métier français (`Animateur`, `Creneau`, `PosteAffectation`), les commentaires et les identifiants non métier en anglais ; le glossaire est dans [`AGENTS.md`](repo:AGENTS.md). Côté organisateur, [le fil d'une édition](../regles/fil-d-une-edition.md) présente les mêmes objets.

![Le modèle Timefold : solution, entité, variable](repo:docs/diagrammes/domaine.svg)

<sub>Source : [`diagrammes/domaine.puml`](repo:docs/diagrammes/domaine.puml).</sub>

## Ce que l'application ne stocke jamais

Le statut mineur, majeur ou de moins de 16 ans se dérive de `dateNaissance` **à la date du créneau** (`isMineurOn`, `isMajeurOn`). Trois régimes légaux en dépendent et un booléen se désynchroniserait.

Le drapeau « polyvalent » se dérive au chargement du problème : un animateur est polyvalent parce que ses compétences contiennent la typologie marquée `ninja`.

Les typologies de jeu sont des lignes d'un référentiel CRUD (table `typologie`), pas un enum Java. `Stand.typologiesProposees` et `Animateur.competences` y renvoient par clé étrangère, donc on en crée ou renomme sans toucher au code. Une typologie porte deux champs de plus :

- un **quota** facultatif, `maxCreneauxParAnimateur`, le nombre maximal de créneaux qu'un animateur y tient sur l'édition entière ; vide, il n'y a pas de plafond. La contrainte dure `plafondCreneauxParTypologie` le lit. Un poste compte pour chaque typologie que **son stand propose**, pas pour celles que son animateur maîtrise. Le quota est porté par la typologie plutôt que par un cinquième type d'exception ad hoc ([décision 0042](repo:docs/decisions/0042-quota-par-typologie-sur-la-typologie.md)) ;
- une **description** libre, la note de l'organisateur pour lui-même. Elle apparaît dans le formulaire des typologies, dans la fiche et dans la vue « Planning, par typologie », et nulle part ailleurs : ni sur un PDF, ni dans l'espace animateur.

## Un poste est une place

Un `PosteAffectation` est créé **par place à pourvoir**, jamais un par couple stand × créneau. Un poste non pourvu garde `animateur = null`.

La variable de planification déclare `allowsUnassigned = true` : une place peut rester vide pendant la recherche, et c'est `posteDoitEtrePourvu` qui en fait une exigence dure. Dans les contraintes, `forEach(...)` **exclut** les postes non pourvus et seul `forEachIncludingUnassigned(...)` les voit. `posteDoitEtrePourvu` utilise le second, et un test `animateur != null` après un `forEach` est inutile.

Le jour J, une place d'un créneau en cours peut être scindée à « maintenant » en deux sièges (`suiteDe`) qui restent une seule place : voir [le passé figé](horaires-et-fenetres-des-stands.md#le-passé-est-figé).

## Les bornes de l'édition se dérivent

Une `Edition` ne porte ni dates ni drapeau « en cours » : c'est un cloisonnement, pas un calendrier. L'événement court du **premier au dernier créneau** de l'édition, bornes comprises, et rien d'autre ne fait autorité. La dérivation est écrite une fois (`JoursEvenement`, dans `service/referentiel/`) et lue par la collecte des disponibilités comme par les avertissements de saisie. Trois conséquences, toutes voulues :

- un jour creux entre deux dates de créneaux (un lundi de relâche entre deux week-ends) est **dans** l'événement. L'ensemble des dates qui portent un créneau, lui, sert à la collecte des disponibilités, qui ne pose pas la même question ;
- une édition sans créneau n'a **pas** de bornes. Rien n'y est « hors événement » et les avertissements se taisent : l'alternative serait un faux positif sur l'écran par lequel une édition commence ;
- ajouter ou supprimer un créneau déplace les bornes. Un avertissement déjà affiché ne se réévalue pas : il décrit l'instant de l'écriture.

Être dans l'événement ne suffit pas pour être utilisable, et l'écart se paie sur les indisponibilités. Le formulaire de déclaration ne propose que les dates de créneaux, et appliquer une déclaration remplace `joursIndisponibles` en bloc. Une indisponibilité posée sur un jour creux est donc effacée à la première déclaration acceptée, sans que l'animateur l'ait jamais vue. L'import CSV **rejette** cette ligne ; la saisie manuelle **avertit** sans bloquer (`INDISPONIBILITE_JOUR_SANS_CRENEAU`). Deux doctrines assumées, jamais un silence.

## Les journées types génèrent les créneaux

Une édition décrit chaque sorte de journée une fois (« Jour normal », « Nocturne », « Montage ») par son nom et ses vacations, puis pose ces journées types sur un calendrier de dates ([décision 0032](repo:docs/decisions/0032-journees-types-nommees-vacations-fixes.md)). C'est le premier objet qui porte des dates indépendamment des créneaux : les dates d'une édition se saisissent là, avant le premier créneau.

Le calendrier est un **générateur**, jamais la vérité. Appliquer le calendrier matérialise les créneaux par différence sur la clé `(date, début, fin)`, celle que le contrôle de grille appelle un doublon :

| Cas | Effet |
| --- | --- |
| Créneau identique | Il garde son id, ses sièges et ses verrous. Seul son drapeau de relais repas peut changer en place |
| Créneau manquant | Il est créé |
| Créneau que la journée type ne nomme pas, sur une date gouvernée | Il est supprimé avec ses sièges, après un aperçu chiffré |
| Date que le calendrier n'affecte pas | Elle n'est jamais touchée |

Tout l'aval (postes, verrous, `JoursEvenement`) continue de lire les créneaux. La logique est pure (`JourneesTypesMaterialisation`) et le service ne fait que la persister.

Modifier une journée type après coup ne propage rien : ses dates passent « en écart » et l'organisateur réapplique. La **reconnaissance** fait le chemin inverse : elle lit les journées types qu'une grille implique (chaque date aux mêmes vacations et aux mêmes drapeaux est la même journée) et remplace journées types et calendrier. Elle suit tout remplacement de la grille (import de scénario, dérivation depuis les horaires des stands), si bien qu'une édition importée et une édition tapée se lisent pareil sur l'écran Créneaux.

Les créneaux qu'une [consigne](horaires-et-fenetres-des-stands.md#la-consigne-quatrième-couche) a ajoutés à la grille ne sont ni comptés en écart ni supprimés à l'application : une journée type ne les nomme jamais, ils appartiennent à la consigne, qui les retire à sa levée. La carte affiche ces dates « sous consigne » (`datesSousConsigne`).

## Invariants à ne pas casser

- Un poste est une place, jamais un couple stand × créneau. L'effectif minimal et maximal d'un stand est tenu en comptant les postes pourvus par stand et créneau, pas par une classe de contrainte dédiée.
- Un stand a au moins une typologie. Sans elle seuls les polyvalents pourraient le tenir, le goulot le plus serré du modèle, né d'une saisie que rien ne signalait. L'écriture le refuse par tous les chemins (CRUD, masse, MCP, import). Une ligne ancienne sans typologie reste lisible et solvable, et sera refusée à sa prochaine sauvegarde.
- Le statut mineur est calculé, jamais stocké.
- `souhaits` est un `Set`, sans ordre ni priorité : ne le transformez pas en liste ordonnée.
- Un créneau est une vacation, une tranche de travail réellement assignable, jamais une amplitude d'ouverture brute ([paramètres légaux et grille](parametres-legaux-et-qualite.md#une-grille-est-faite-de-vacations)).
- Les exceptions ad hoc prescriptives restent dures. Seules l'affinité et l'arrivée groupée sont souples, par conception ([exceptions ad hoc](exceptions-ad-hoc.md)).
- Les bornes d'une édition se dérivent de ses créneaux.
- Une place d'un créneau commencé est reprise du plan enregistré et épinglée par toute résolution ; elle compte dans les règles et aucune ne la reproche ([décision 0044](repo:docs/decisions/0044-le-passe-est-fige.md)).
- L'espace animateur ne montre jamais le planning de travail : ce qu'une personne voit est ce qu'on lui a envoyé ([plan publié](plan-verrous-et-publication.md#le-plan-publié)).
- Les horodatages sont tous en `timestamptz`.
- Les noms de domaine restent en français métier.

### Ce que la base garantit

`creneau` garde sa clé primaire `(id)`, pour que les identifiants publiés ne bougent pas, et porte une clé unique `(edition_id, id)`. Les clés qui le visent sont composites : un siège ne peut pas viser le créneau d'une autre édition. Celles qui peuvent survivre au créneau (un verrou, une exception ad hoc) font `ON DELETE SET NULL (creneau_id)` : sans liste de colonnes, le `SET NULL` viderait aussi `edition_id`. L'atterrissage d'une résolution n'écrase jamais le créneau d'une autre édition, un conflit d'identifiant l'arrête.

Toute référence à un autre objet porte sa clé. La cible d'un échange dirigé (créneau, stand) disparaît avec ce qu'elle vise, comme le créneau et le stand de la demande : une demande dont la cible n'existe plus n'a plus de sens. La continuation d'un siège coupé, la contrainte qu'une déclaration de coéquipier a créée, le plan notifié d'un animateur et l'instantané d'avant résolution se vident (`SET NULL`) quand leur cible disparaît. Les trois dernières clés sont différées, parce qu'un plan ou un dump s'écrit d'un bloc.

Le partitionnement par édition et ses garde-fous sont dans [cloisonnement-par-edition](cloisonnement-par-edition.md) ; les comptes et leurs droits dans [les comptes et rôles](acces-et-comptes.md).
