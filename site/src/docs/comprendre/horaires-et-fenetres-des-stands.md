# Horaires, consignes et fenêtres effectives

Vous savez comment les horaires d'un stand deviennent des fenêtres d'ouverture, comment une consigne d'édition les referme sans rien détruire, pourquoi le passé ne se réécrit plus, et ce que porte une place quand son créneau n'est ouvert que par morceaux.

## Trois états par jour pour un stand

Chaque jour est décidé indépendamment. Un jour ne peut pas porter à la fois des fermetures et des ouvertures : l'écriture le refuse, ce qui évite d'arbitrer un conflit au calcul.

| Ce que le stand déclare ce jour-là | Lecture |
| --- | --- |
| Ni fermeture ni ouverture | Ouvert sans restriction, le cas largement majoritaire |
| Au moins une fermeture | Ouvert par défaut, fermé sur les seules fenêtres listées. Le calcul soustrait l'union des fermetures qui chevauchent le créneau : une fermeture en plein milieu laisse deux segments ouverts |
| Au moins une ouverture | **Fermé par défaut**, ouvert sur les seules fenêtres listées. C'est le cas d'un stand qui n'ouvre que sur quelques créneaux : une ouverture au lieu d'une fermeture par créneau |

`heureFin` vide veut dire « jusqu'à la fermeture » : la fenêtre court jusqu'à la fin du créneau évalué. Une fenêtre ne peut pas chevaucher minuit, contrairement à un créneau. Une fenêtre datée du jour J+1 n'est lue que par un créneau qui traverse réellement minuit.

## L'effectif se porte sur la fenêtre

Un stand dont la charge varie dans la journée (4 personnes le matin, 5 le soir) reste **un seul stand** : chaque fenêtre d'ouverture porte son `effectif`. Vide, il hérite de `effectifMin`, si bien qu'une fenêtre qui n'en nomme aucun génère ce qu'elle générait avant que le champ existe. Le minimum est ce qu'un stand exige à son heure la plus creuse : l'appliquer au pic le sous-doterait.

Enregistrer un stand depuis la grille de saisie **dérive ses deux bornes de ses cases** : `effectifMin` est la plus petite valeur saisie, `effectifMax` la plus grande, dans les deux sens (un stand déclaré à 5 dont aucune case ne dépasse 2 ressort à 2). La fiche du stand reste l'endroit où fixer une capacité que la grille ne montre pas. Un effectif de fenêtre ne peut pas dépasser `effectifMax`, que le validateur et le formulaire refusent tous les deux : sinon la génération rendrait obligatoires plus de sièges que le stand n'est déclaré capable d'en tenir.

Un créneau à cheval sur deux fenêtres d'effectifs différents produit **deux groupes de sièges**, chacun portant la [fenêtre effective](#fenêtre-effective) de son segment. Là où deux fenêtres se recouvrent, le **plus haut** effectif s'applique : satisfaire le plus grand satisfait les deux. Les tranches contiguës de même effectif sont refusionnées. `segmentsOuverts` est le calcul primaire ; `segmentsOuvertsMinutes` en est la projection et reste la géométrie d'ouverture pure, où deux segments que seul l'effectif sépare redeviennent une ouverture continue.

### Deux champs que le solveur ne lit pas

`effectifMax` borne la saisie (validateur du stand, ouverture posée par une consigne, dérivation depuis la grille) et rien d'autre. Le nombre de sièges d'un créneau est l'effectif de sa fenêtre, à défaut `effectifMin`, et aucune règle ne plafonne le nombre d'animateurs sur un stand. Relever `effectifMax` n'ajoute aucun poste : c'est l'effectif de fenêtre qu'il faut changer.

`manager` est une étiquette de fiche (filtre de liste, colonne des imports CSV, comparaison entre éditions). Aucune règle ne le lit : un animateur marqué manager est planifié comme les autres et compte dans les plafonds de durée, légaux compris. L'encadrement par des managers hors planning est un fait d'organisation, c'est pourquoi `mineurNecessiteEncadrementMajeur` est éteinte par défaut ; allumée, elle exige un majeur planifié sur le même stand et le même créneau, manager ou non.

## Horaires récurrents : trois couches, un seul mode par jour

Les fenêtres datées sont des **exceptions**. Le motif qui se répète se saisit au-dessus, en règles (`HoraireStand`) portant un mode (`OUVERTURE` ou `FERMETURE`) et un sélecteur de jours (`TypeJoursHoraire`) de spécificité croissante : `TOUS` (0), `JOURS_SEMAINE` (1), `PLAGE` (2), `DATES` (3).

La résolution se fait jour calendaire par jour calendaire (`HoraireStandResolver`) :

1. **une fenêtre datée ce jour-là** gagne seule et remplace entièrement ce que les règles disaient du jour ;
2. **sinon les règles qui couvrent le jour**, à la spécificité maximale, en union de leurs fenêtres. Deux règles de même spécificité et de modes opposés sur des jours qui se croisent sont refusées à l'écriture. Pour une donnée arrivée autrement (un scénario écrit à la main), le résolveur garde un arbitrage déterministe : `OUVERTURE` l'emporte, la lecture la plus restrictive ;
3. **sinon**, cela dépend de ce que le stand déclare. S'il déclare des ouvertures quelque part dans ses règles, le jour est **fermé** : « ouvert ces douze jours-là » est un horaire, et un jour absent est un jour où le stand n'ouvre pas. Si ses règles ne font que fermer, il décrit des exceptions à un stand ouvert et le jour est **ouvert toute la journée**.

Le résultat est toujours **un seul mode par jour**. C'est ce qui préserve l'invariant des trois états, et ce qui évite que le calcul de segments, les contraintes ou les exports aient à connaître les règles. Si un seul jour de l'événement reste non énoncé, aucune règle ne peut prendre `TOUS` : elle gouvernerait un jour laissé volontairement ouvert par défaut.

La troisième couche évite un piège : « ouvert ces douze dates et rien d'autre » demandait autrefois une seconde règle de remplissage, une fermeture partant d'une heure assez tôt pour couvrir tous les créneaux, que le moindre créneau ajouté plus tôt rouvrait en silence. `HoraireElagage` retire ces fermetures devenues muettes, après avoir vérifié segment par segment qu'aucune ouverture ne bouge. Il ne retire **jamais** une ouverture, même sans effet sur la grille du jour : c'est un énoncé qui attend un créneau.

**Limite assumée** : une exception *remplace* la journée au lieu de se soustraire aux règles. « Ouvert 10 h-12 h et 14 h-fermeture tous les jours, sauf le 14 juillet après-midi » demande de ressaisir la journée entière. C'est le prix de l'invariant à un mode par jour ; l'alternative mélangerait deux modes sur une journée, l'ambiguïté que cet invariant écarte.

### Ce que la résolution tranche en silence

L'analyse des ouvertures le signale **sans le refuser** : deux règles de même portée et de même mode qui se recouvrent un même jour (union des fenêtres, effectif le plus haut : « @4 » puis « @2 » sur les mêmes heures, c'est 4), une règle qu'aucun jour n'applique parce qu'une règle plus spécifique ou une exception datée l'emporte partout, deux fenêtres d'une même règle qui se recouvrent à des effectifs différents. On avertit parce que le résultat est déterministe, que certains recouvrements sont voulus (une règle de fond plus une règle de pic) et qu'un refus casserait des scénarios existants. Seul le cas réellement ambigu, même portée et modes opposés, reste refusé. `HoraireStandResolver.resolveDayWithRules` rend les règles qui ont décidé chaque jour, sans changer le verdict.

### Vue brute, vue effective, compactage

L'expansion des règles n'est **jamais écrite** sur les listes datées : un stand résolu peut repasser par une sauvegarde sans figer son expansion en centaines de lignes. Le service expose deux vues, une vue CRUD brute et une vue effective résolue sur les jours de l'édition, que prennent le solveur, la génération de postes et l'analyse de faisabilité.

Le **compactage** dérive les règles à la demande et ne réécrit un stand que si elles reproduisent ses propres segments ouverts. L'écart se mesure en **minutes d'ouverture** en désaccord, jamais en appariant des segments : un « fermé 10 h-23 h 59 » réécrit en « fermé jusqu'à la fermeture » fait passer le nombre de segments de 1 à 0, alors que le désaccord réel est une seule minute. Compter les segments ferait refuser les stands que la réécriture aide le plus.

## La consigne, quatrième couche

Un arrêté préfectoral (canicule, orage) ferme une bande horaire pour **tous** les stands sur quelques dates, avec prolongations et levées. Ce n'est ni une règle de stand ni une exception datée : la décision vient de l'extérieur, s'applique à tous sans que personne l'ait déclarée, et doit se lever sans rien réécrire. C'est une **consigne d'édition** ([décision 0043](repo:docs/decisions/0043-consigne-d-edition-fermer-une-bande-sans-rien-detruire.md)), lue par `ConsigneResolver` au-dessus des trois couches et par un seul point d'entrée, `StandService.resolve`. Un appelant qui déroulerait les règles lui-même doterait en silence une bande fermée, et `ConsigneCoucheStructurelleTest` le refuse.

**Le modèle** : une ligne par date, qui porte :

- une **bande interdite**, avec un début obligatoire et une fin facultative valant « jusqu'à minuit » (`00:00` → vide pour la journée entière) ;
- un **motif** obligatoire ;
- le nom du **préréglage** d'origine s'il y en a un (un « Plan canicule » : bande, motif et fenêtres par défaut, mémorisé sur l'édition et copié à la duplication, contrairement aux consignes datées) ;
- des **fenêtres de compensation par défaut**, avant ou après la bande ;
- des **ouvertures** (un stand, une fenêtre, un effectif facultatif, plusieurs fenêtres par stand) ;
- les **créneaux ajoutés** à la grille, marqués comme tels.

Trois règles, dans cet ordre, pour chaque stand d'une date sous consigne :

1. **La bande ferme tous les stands**, au-dessus de leurs règles et de leurs exceptions, et ne ferme que cela : un stand garde ses horaires hors de la bande. Un 14 h-20 h devient un 18 h-20 h.
2. **Les créneaux que la consigne a ajoutés n'appartiennent aux horaires de personne** : un stand y est fermé, sauf si l'une de ses propres fenêtres de consigne les couvre. Sans cela, un stand ouvert par défaut serait doté sur une soirée que personne n'a choisie pour lui.
3. **Une ouverture est une extension choisie stand par stand** : le stand ouvre sur ses fenêtres en plus de ses horaires, jamais dans la bande, à l'effectif saisi sur cette fenêtre, sinon au plus fort effectif qu'il perd dans la bande, sinon à son minimum. Là où une fenêtre recouvre des heures qu'il avait déjà, le plus haut des deux effectifs s'applique.

La journée ressort **réénoncée entière** : en ouvertures explicites (fermé par défaut) quand il reste quelque chose, en fermeture de journée entière sinon. Un seul mode par jour, comme après les trois couches, et rien en aval n'a à connaître la consigne.

**Ce qui est ajouté à la grille, et rien d'autre.** Une fenêtre choisie qu'aucun créneau ne couvre (20 h-22 h après une journée qui finit à 20 h) fait ajouter le créneau manquant, marqué comme ajouté par la consigne. C'est la seule écriture sur la grille, elle est additive, et une ouverture doit l'exiger. Modifier une consigne conserve un créneau ajouté, avec ses sièges, tant qu'une fenêtre l'exige exactement, et retire les autres.

**Ce qui n'est jamais détruit.** Les créneaux nominaux gardent id, sièges et verrous. Les vacations de la bande deviennent des vacations sans siège : un stand fermé sur tout un créneau n'y engendre aucun poste, fermé sur une partie il engendre les postes du reste, à leur fenêtre effective. Lever la consigne, c'est ne plus l'appliquer : les stands retrouvent leurs horaires, et seuls les créneaux ajoutés partent, avec leurs sièges. La publication suivante annonce leur retrait. La [stabilité du plan publié](plan-verrous-et-publication.md#le-plan-publié) fait le reste : le titulaire d'un 14 h-20 h reste sur le 18 h-20 h pendant l'alerte et retrouve son après-midi à la levée.

**Le passé reste intact.** Poser, modifier et lever refusent une date passée ou en cours : une date déjà travaillée garde pour toujours la consigne qui l'a gouvernée. Poser ou lever retire la validation de relecture des journées touchées, verrou ou non, parce que les sièges de la bande n'existent plus.

**Les heures effectives, partout.** Cumuls, repos, heures, équité, indicateurs : tout lit déjà la fenêtre effective, donc une journée sous consigne compte ce qui est réellement travaillé sans qu'aucun calcul change. Un instantané porte les consignes en vigueur à sa capture (vide sur un instantané antérieur : « inconnu », jamais « aucune »).

**Limites assumées** : la bande est obligatoire (une consigne sans bande est une journée type) ; il y a une bande par jour ; les disponibilités restent à la journée ; les règles de nuit et de repos lisent le créneau entier, pas la fenêtre effective, ce qui ferme les fenêtres tardives aux mineurs ; une fenêtre du soir qui prolonge l'après-midi demande deux personnes par siège tant que la coupure repas du soir interdit d'enchaîner 18 h-20 h et 20 h-22 h.

### Restater la coupure repas pour une date

La coupure repas est la règle de l'organisateur, pas celle du Code du travail ([repas et pauses](parametres-legaux-et-qualite.md#la-coupure-repas)). Une consigne peut donc fixer, pour sa seule date, d'autres fenêtres de midi et du soir et une autre durée de coupure, avec une justification obligatoire en termes métier. Le solveur reçoit alors deux sortes de `FenetreRepas` : celles de l'édition, qui portent les dates où elles s'effacent, et celles de la consigne, datées. `FenetreRepas.appliesTo(date)` est ce que la contrainte et chaque lecteur demandent. Lever la consigne retire ses fenêtres avec elle ; les paramètres légaux de l'édition n'ont jamais été écrits. Les plafonds légaux ne sont pas surchargeables.

## Le passé est figé

Toute résolution, complète ou incrémentale, depuis l'écran ou par MCP, reprend du plan enregistré les places des créneaux déjà commencés et les épingle, quel que soit leur état ([décision 0044](repo:docs/decisions/0044-le-passe-est-fige.md)). Le titulaire est gardé même s'il a depuis déclaré la journée indisponible ou qu'une indisponibilité forcée le couvre maintenant (il était là) ; un titulaire supprimé laisse la place vide ; une place passée vide est épinglée quand même, puisque personne ne peut tenir hier.

Un créneau est *commencé* si sa date est passée, ou si c'est aujourd'hui et que le début effectif de la place est atteint. Un créneau à cheval sur minuit appartient à sa date de début. « Aujourd'hui » est celui de l'horloge du jour J : la machine en production, ou la date figée depuis Paramètres › Instance sous `quarkus:dev` ou avec `HORLOGE_SIMULEE_AUTORISEE=true`.

Ces places **comptent** dans les règles qui lient les jours (repos quotidien entre hier et aujourd'hui, durées hebdomadaires, jours consécutifs, pauses, « fermer tard puis ouvrir tôt ») mais ne sont **jamais reprochées** : un trou d'hier ou un mineur placé la nuit hier ne bloquent pas le zéro dur d'aujourd'hui. Le périmètre d'une replanification ne rouvre jamais une place passée.

**Le passé ne se modifie plus à la main non plus.** Les gestes qui réécrivent une place sans solveur (« Déplacer vers… » du panneau du siège, `deplacer_affectation`, l'acceptation d'un échange, une réparation appliquée ou suggérée) refusent une place dont le créneau est commencé, avec la même phrase : « Ce créneau est déjà commencé : le passé ne se modifie plus. » Elle sort en `400` côté REST et en refus métier côté MCP (`FrozenPast.PAST_SEAT_REFUSAL`).

### Le passé est figé à la minute

Une réparation, un « Placer » ou un « Marquer absent » sur le siège d'un créneau **en cours** ne sont pas refusés ([décision 0066](repo:docs/decisions/0066-le-passe-est-fige-a-la-minute.md)). L'écriture **scinde le siège à « maintenant »**, la minute courante de l'horloge du jour J, en deux sièges réels :

- l'origine est écourtée à cette minute (`heureFinEffective`) et garde son titulaire, si bien que l'historique dit qui a tenu 9 h 00 – 9 h 20 ;
- la suite couvre le reste du créneau (`heureDebutEffective`), porte `suiteDe` (l'identifiant de l'origine, persisté dans `poste_affectation.suite_de`) et reçoit l'écriture. Son identifiant est dérivé : `<origine>~HHmm`.

Un siège **vide** n'est pas scindé : y placer quelqu'un le **rogne** (même ligne, début avancé à « maintenant », identifiant `<siège>~HHmm` sans `suiteDe`), sans laisser de fragment vide compté « non pourvu ». Seul un siège déjà terminé reste refusé. Une place scindée reste **une** place :

| Où | Ce que la scission change |
| --- | --- |
| Génération par place et équipage d'un stand premium (`crewByStand`) | Rien : la suite n'est pas une place de plus |
| Reconstruction (réamorçage, incrémental) | `SeatSplit.restore` rejoue les cellules scindées ou rognées du plan persisté sur les sièges générés ; le solveur ne rouvre que la suite |
| Instantanés | La suite et son `suiteDe` sont capturés et restaurés avec le plan |
| Export de scénario | Les suites sont omises : un scénario décrit les places à pourvoir |
| « Nouveau depuis ce matin » (Aujourd'hui, affichage mural) | La suite est une cellule que le plan publié n'avait pas : sa place vide est nouvelle |

**Rien à planifier.** Une résolution dont toutes les places sont passées (édition terminée, date simulée après l'événement) est refusée plutôt que de produire un plan vide à zéro dur. Si des places passées sont restées vides (premier calcul lancé pendant l'événement, titulaire supprimé), la résolution a lieu et son résultat porte `postesPassesVides`, annoncé comme un avertissement.

`PASSE_FIGE=false` coupe toute la règle (épinglage, refus des gestes, refus d'une résolution sans avenir), pour une recette qui rejoue une édition ancienne. Elle est coupée par défaut sous le profil de test.

## Fenêtre effective

Un poste issu d'un segment partiel porte une fenêtre plus étroite que son créneau. Ce n'est **pas un sous-créneau** : la clé étrangère vise un créneau réel et persisté, la fenêtre vit sur le poste, et les accesseurs retombent sur le créneau quand la surcharge est absente. Les règles qui lisent la fenêtre et celles qui lisent le créneau entier sont distinguées dans [les règles du solveur](pourquoi-ces-regles.md).
