# Les règles du solveur et leur catalogue

Vous savez où lire la liste des 42 règles, comment elles se rangent par niveau, pourquoi l'écran et le code n'emploient pas le même mot pour un écart, et ce que deux champs de la fiche ne pilotent pas. Le tableau en fin de page est la liste complète, tenue à jour par un test.

Un planning est valide quand aucune règle dure n'est violée, et bon quand les règles de qualité sont peu pénalisées. Les règles se rangent sur les trois niveaux du `HardMediumSoftScore` de Timefold : **dur** (bloquant), **medium** (fortement pénalisé, signalé à l'organisateur) et **soft** (préférence qui départage deux plannings valides).

Les trois niveaux sont aussi racontés pour un organisateur dans [trois niveaux d'exigence](/comment-marche-le-calcul/), et le cadre juridique dans [le Code du travail](/code-du-travail/).

> **Ne reclassez jamais une règle dure en medium ou soft sans validation explicite**, en particulier tout ce qui touche au cadre légal des mineurs.

Chaque règle est écrite deux fois : dans les flux de contraintes de Timefold, qui la définissent, et dans le moteur direct (`solver/handsearch`), qui doit la noter à l'identique. [Comment le planning est calculé](comment-le-planning-est-calcule.md) décrit les deux moteurs et [Ajouter une règle](ajouter-une-regle.md) la marche à suivre.

## Où se trouve la liste

Le niveau, la catégorie, la description métier et l'article de loi de chaque règle vivent dans [`ConstraintCatalog`](repo:src/main/java/dev/sylvain/planning/solver/ConstraintCatalog.java). Le catalogue est servi par `GET /api/constraints` et affiché par l'écran Règles du planning (`/regles`). Cette partie de la documentation porte les mécanismes et les arbitrages que la description d'une ligne ne peut pas contenir :

| Question | Page |
|---|---|
| Pourquoi une règle légale, de pause, de repos ou de semaine est écrite ainsi | [Règles légales et pauses](regles-legales-et-pauses.md) |
| Typologie polyvalente, quotas, trajet, covoiturage, seuils de qualité, stabilité du plan publié | [Règles de couverture et de confort](regles-de-couverture-et-de-confort.md) |
| Éteindre, pondérer, lire un plancher, historique de dosage | [Doser et désactiver les règles](doser-et-desactiver-les-regles.md) |
| Exceptions posées à la main et leurs contradictions | [Les exceptions ad hoc](exceptions-ad-hoc.md) |

L'écran range les règles en tableau, une par ligne. L'onglet **Légal** porte les règles dures (la loi, la sécurité des mineurs, le repas, l'affectation), l'onglet **Qualité** les règles medium et soft. Une ligne nomme la règle par son **libellé court** (`libelleCourt`, par exemple « Places pourvues » ou « Coupure repas ») et jamais par son nom technique : `ConstraintCatalogShortLabelTest` tient ce dernier à l'écart de tout ce qu'un organisateur lit. Le détail (texte long, conseil en cas d'écart, poids exact, historique des réglages) s'ouvre dans un panneau.

**Le nom d'une règle est son adresse** : `/regles?regle=coupureRepasObligatoire` ouvre le panneau de cette règle, sur son onglet. Renommer une règle casse donc les liens partagés, comme n'importe quelle rupture de contrat d'API.

Les descriptions restent du texte brut côté serveur : la même chaîne part vers les outils MCP et se range dans une demande d'échange. Le frontend reconnaît à l'affichage les numéros d'article qu'elles citent et les lie vers Légifrance (`core/legifrance.ts`, rendu par `app-legal-text`) : écrire `art. L3162-1` dans une description suffit à obtenir le lien.

## « Écart » à l'écran, `violation` dans le code

Un cas où une règle n'est pas satisfaite se nomme un **écart** partout où l'organisateur le lit : libellés, aide en ligne, documentation. Le mot vaut pour les trois niveaux, ce que « infraction » ou « manquement » ne feraient pas sans accuser une préférence de confort d'un délit.

Le code garde `violation` : identifiants Java et TypeScript, clés JSON (`violations`, `violationsParContrainte`, `violationsIntroduites`), `ViolationFormatter`. Le renommer romprait le contrat JSON et donc tout client MCP, pour un gain de lecture seulement. Le décalage est délibéré : ne « corrigez » ni un libellé vers `violation` ni un identifiant vers `écart`.

## `effectifMax` et `manager` : deux champs que le solveur ne lit pas

Ces deux champs ressemblent à des leviers de règle et n'en sont pas.

- `effectifMax` d'un stand borne la saisie ([modèle des données](modele-des-donnees.md)). Il ne fixe ni le nombre de sièges, qui vient de l'effectif de la fenêtre ou à défaut de `effectifMin`, ni un plafond que le solveur vérifierait.
- `manager` d'un animateur est une étiquette. Aucune règle ne la lit : un manager planifié subit les mêmes plafonds, `dureeHebdomadaireMax` comprise, et `mineurNecessiteEncadrementMajeur` compte tout majeur présent, manager ou non.

Si une règle doit un jour en dépendre, elle s'ajoute au catalogue avec son test. Elle ne se déduit pas du champ.

## La fenêtre effective : qui la lit, qui l'ignore

Un stand fermé pour une partie d'un créneau, ou normalement fermé et ouvert sur des fenêtres précises, génère un poste par segment encore ouvert. Chacun porte une fenêtre horaire *effective* plus étroite que le créneau, et `posteDoitEtrePourvu` s'applique à ces postes réduits : personne n'est exigé sur la plage fermée. Le mécanisme ne crée jamais de créneau supplémentaire : la clé étrangère reste le créneau réel, la fenêtre vit sur le poste.

Une consigne d'édition (la bande qu'un arrêté ferme pour tous les stands, et les fenêtres rouvertes en compensation, voir [horaires et fenêtres des stands](horaires-et-fenetres-des-stands.md)) se lit par ces mêmes fenêtres. Aucune règle n'a à la connaître : ce qui suit vaut tel quel pour une journée sous consigne, la règle de nuit des mineurs comprise.

| Règle ou calcul qui lit la fenêtre effective | Pourquoi |
|---|---|
| `pasDeChevauchementHoraire` | deux postes dont les segments ne se recouvrent pas réellement ne sont plus signalés à tort |
| les cumuls d'heures et les repos | un poste réduit ne compte que le temps réellement couvert, et le repos qui suit démarre à la fin de ce temps |

`travailDeNuitInterditPourMineur` reste volontairement calée sur le créneau entier : une règle de sécurité des mineurs ne doit jamais devenir plus permissive par effet de bord d'une fonctionnalité de disponibilité de stand.

## Les exclusions d'éligibilité pèsent plus lourd que tout

Six règles disent qui ne peut **jamais** tenir un siège : `animateurDisponible`, `standReserveAuxMajeurs`, `travailDeNuitInterditPourMineur`, `travailInterditJourFerieMineur`, `dureeQuotidienneMax` et `travailContinuMax` (pour un mineur ; un majeur n'y est pas exclu mais seulement pénalisé à la minute). Ce sont les motifs de `EligibleAnimateurMoveFilter`. Une septième porte le même forfait sans être un motif du filtre, parce qu'elle dépend de qui d'autre tient le stand : `mineurNecessiteEncadrementMajeur`. Un mineur seul coûtait sinon exactement un siège vide, et sur une équipe de mineurs seul l'ordre de la recherche départageait ([ADR 0035](repo:docs/decisions/0035-mineur-seul-au-forfait.md)). Cette septième règle est éteinte par défaut ([ADR 0041](repo:docs/decisions/0041-encadrement-des-mineurs-eteint-par-defaut.md)) : le forfait n'est dû que si l'organisation l'a allumée.

Le filtre écarte ces animateurs de la recherche, mais la reconstruction du *ruin and recreate* ne le lit pas, et ces règles ne pesaient qu'un point dur : autant qu'un siège vide, moins que quelques minutes de repos. Un plan qui ne pouvait pas tout tenir plaçait alors un mineur de 15 ans la nuit plutôt que de laisser un siège vide, ou une animatrice le jour de son indisponibilité plutôt que de laisser une affectation forcée non tenue.

Chaque écart coûte donc un forfait de **10 000 points durs** (`ExclusionEligibilite.FORFAIT`), plus les minutes de dépassement pour les deux plafonds comptés à la minute. Un siège vide ou une exception non tenue se lit sur la page Problèmes et se traite ; un siège illégal ne doit pas sortir d'un solve quand une alternative existe. Le forfait est dans la pondération des correspondances : un poids d'édition le multiplie au lieu de le remplacer. Les options écartées sont dans l'[ADR 0034](repo:docs/decisions/0034-exclusions-eligibilite-plus-lourdes-que-tout.md).

## Une règle par plafond, le plafond lu selon l'âge

Le plafond de travail d'une journée et celui d'une période de travail ininterrompue existaient chacun en deux règles, une pour les majeurs, une pour les mineurs : deux lignes à l'écran, deux interrupteurs, deux poids, pour un seul sujet. Elles sont maintenant **une règle chacune**, `dureeQuotidienneMax` et `travailContinuMax`, et le plafond se lit sur l'âge de l'animateur **à la date du créneau** (jamais un drapeau stocké), dans `PlafondsLegauxMajeurs` et `PlafondsLegauxMineurs`, qui restent les seuls endroits où ces chiffres sont écrits :

| Règle | Majeur | Mineur de 16 à moins de 18 ans | Mineur de moins de 16 ans |
|---|---|---|---|
| `dureeQuotidienneMax` | 10 h (art. L3121-18) | 8 h (art. L3162-1) | 7 h (art. D4153-3) |
| `travailContinuMax` | 6 h, pause d'au moins 20 min (art. L3121-16) | 4 h 30, pause d'au moins 30 min (art. L3162-3) | 4 h 30, pause d'au moins 30 min (art. L3162-3) |

La fusion ne change **aucun score** : mêmes violations, mêmes minutes de dépassement, même forfait d'exclusion pour un mineur. Le test `AgeCapMergeEquivalenceTest` le tient, plan par plan, sur les scénarios livrés. Ce qui change, c'est qu'un réglage porte désormais sur les deux âges à la fois : on ne peut plus éteindre le plafond des majeurs en laissant celui des mineurs, ni inversement. La migration d'une édition qui avait réglé les deux différemment prend la lecture la plus protectrice ([ADR 0114](repo:docs/decisions/0114-une-regle-par-plafond-lue-selon-l-age.md)).

Le plafond hebdomadaire et le repos hebdomadaire ont suivi, sur le même procédé ([ADR 0116](repo:docs/decisions/0116-une-regle-par-plafond-hebdomadaire-et-par-age.md)) : `dureeHebdomadaireMax` (48 h pour un majeur, 35 h pour un mineur, les deux durées de Paramètres › Légal) et `reposHebdomadaireMinimal` (35 h consécutives pour tous, et pour un mineur, en plus, deux jours de repos consécutifs dans chaque semaine civile, art. L3164-2). Ces deux règles **gardent leur nom** ; les règles `dureeHebdomadaireMaxMineur` et `reposHebdomadaireMineur` sont retirées. Même équivalence au score près, tenue par le même test ; une semaine à cheval sur un dix-huitième anniversaire reste jugée âge par âge, sur ses propres jours.

## Les contraintes, telles que le catalogue les déclare

Ce tableau est le rendu de `ConstraintCatalog`. Quand le catalogue change, `DocumentationStructuralTest` échoue et imprime le tableau à coller entre les deux repères.

<!-- catalogue:debut -->
| Contrainte | Niveau | Catégorie | Ce qu'elle dit |
|---|---|---|---|
| `posteDoitEtrePourvu` | HARD | Affectation | Chaque place ouverte sur un stand doit être pourvue par un animateur. |
| `animateurDisponible` | HARD | Affectation | Un animateur ne peut pas être affecté un jour qu'il a déclaré indisponible. |
| `pasDeChevauchementHoraire` | HARD | Affectation | Un animateur ne peut pas tenir deux postes dont les créneaux se chevauchent dans le temps (y compris deux créneaux distincts qui se recouvrent, et pas seulement deux postes sur le même créneau). |
| `plafondCreneauxParTypologie` | HARD | Affectation | Sur une typologie qui porte un plafond, un animateur ne tient pas plus que ce nombre de créneaux sur l'ensemble de l'édition. Un poste compte pour chaque typologie que son stand propose. Une typologie sans plafond n'impose rien. |
| `standReserveAuxMajeurs` | HARD | Légal (mineurs) | Les stands réservés aux majeurs ne peuvent accueillir aucun mineur. |
| `mineurNecessiteEncadrementMajeur` | HARD | Sécurité (mineurs) | Éteinte par défaut. Un mineur doit toujours être accompagné d'au moins un majeur sur le même stand et le même créneau. Règle de sécurité posée par l'organisateur, pas une obligation du Code du travail : l'organisateur de l'évènement la remplit par ses managers, qui ne sont pas planifiés, et ne la demande donc pas au solveur. Une organisation sans encadrant hors planning l'allume depuis l'écran Règles du planning. |
| `travailDeNuitInterditPourMineur` | HARD | Légal (mineurs) | Un mineur ne peut pas être affecté sur un créneau qui empiète sur sa nuit légale : 20 h-6 h avant 16 ans, 22 h-6 h de 16 à 18 ans (Code du travail art. L3163-1). |
| `dureeQuotidienneMax` | HARD | Légal (temps de travail) | Un animateur ne peut pas dépasser le plafond de travail effectif d'une même journée, lu selon son âge à la date du créneau : 10 heures pour un majeur (Code du travail art. L3121-18), 8 heures pour un mineur (art. L3162-1), ramenées à 7 heures avant 16 ans (art. D4153-3). Les pauses dues sont déduites : une pause, relayée ou prise comme un trou, est du repos et non du travail effectif (art. L3121-1). |
| `travailInterditJourFerieMineur` | HARD | Légal (mineurs) | Un mineur ne peut pas travailler un jour férié légal (Code du travail art. L3164-6, liste de l'art. L3133-1). Aucune dérogation sectorielle n'est appliquée : celle de l'art. R3164-2 reste à instruire. |
| `dureeHebdomadaireMax` | HARD | Légal (temps de travail) | Un animateur ne peut pas dépasser la durée hebdomadaire de travail effectif, lue selon son âge à la date du créneau : 48 heures par défaut pour un majeur, tous payés, manager ou non (Code du travail art. L3121-20, d'ordre public), 35 heures par défaut pour un mineur (art. L3162-1 ; art. D4153-3 pour les 14 à moins de 16 ans employés pendant les vacances scolaires). Les deux plafonds sont les paramètres de durée hebdomadaire maximale, des majeurs et des mineurs. Les pauses dues sont déduites, comme au plafond quotidien. |
| `dureeHebdomadaireMaxDeuxSemaines` | HARD | Légal (temps de travail) | Un animateur majeur ne peut pas atteindre la durée hebdomadaire maximale sur deux semaines ISO consécutives : 48 h une semaine puis 48 h la suivante est refusé, 47 h puis 48 h reste permis. Forme courte et opérationnelle de la moyenne de 44 h sur douze semaines (Code du travail art. L3121-22) — la seule qui ait un sens sur un événement de quinze jours. Le seuil est celui du paramètre de durée hebdomadaire maximale, jamais une seconde constante. Une semaine se juge pleine en travail effectif : les pauses dues en sont déduites. |
| `reposQuotidienMinimal` | HARD | Légal (temps de travail) | Entre deux journées travaillées, tout animateur bénéficie d'un repos quotidien minimal : 11 h pour un majeur (art. L3131-1), 12 h pour un mineur et 14 h avant 16 ans (art. L3164-1). |
| `maxJoursTravaillesParSemaine` | HARD | Légal (temps de travail) | Aucun animateur ne peut travailler plus de six jours dans la même semaine (Code du travail art. L3132-1). |
| `reposHebdomadaireMinimal` | HARD | Légal (temps de travail) | Chaque animateur bénéficie, dans chaque semaine, d'un repos hebdomadaire de 35 heures consécutives : 24 heures (art. L3132-2) auxquelles s'ajoutent les 11 heures de repos quotidien (art. L3131-1). Un repos à cheval sur le lundi compte en entier pour la semaine où il tombe. Un mineur bénéficie en plus de deux jours de repos consécutifs à l'intérieur de chaque semaine civile, du lundi 0 h au dimanche 24 h (art. L3164-2 et L3121-35) : un dimanche et le lundi qui le suit sont chacun un jour de repos de leur semaine, mais ne forment la paire d'aucune des deux. Les dérogations conventionnelles supposent un accord étendu ou une autorisation de l'inspection du travail : elles ne sont pas présumées. |
| `travailContinuMax` | HARD | Légal (temps de travail) | Aucune période de travail ininterrompue au-delà du plafond de l'âge de l'animateur à la date du créneau : 6 heures pour un majeur (art. L3121-16), 4 h 30 pour un mineur (art. L3162-3). L'application ne réclame la pause d'un majeur qu'au-delà de 6 heures de travail continu : l'art. L3121-16 la rend due dès que le temps de travail quotidien atteint 6 heures, et c'est un écart assumé au texte, retenu pour qu'un créneau de 6 heures exactes (14 h-20 h par exemple) puisse être couvert, la pause tombant sinon à sa fin. Au-delà, chaque pause due doit être prise, soit comme un trou dans la grille d'au moins la durée paramétrée, soit relayée par un collègue du même stand tenant une place pendant toute la pause. Une pause que personne ne peut prendre est un écart dur. La durée est celle paramétrée, au minimum 20 minutes pour un majeur et portée à 30 minutes pour un mineur : ce plancher est d'ordre public. C'est aussi ce qui autorise la déduction de la pause des plafonds quotidien et hebdomadaire : dans un plan sans écart dur, toute pause déduite a bien été prise. |
| `coupureRepasObligatoire` | HARD | Organisation (repas) | Qui travaille de part et d'autre d'une fenêtre repas doit disposer, entièrement dans cette fenêtre, d'une coupure libre de la durée paramétrée (60 min par défaut, midi 12 h-14 h et soir 19 h-21 h). Commencer sa journée à l'ouverture de la fenêtre, ou la terminer à sa fermeture, ne doit rien : on a mangé avant, ou on mangera après. Une journée à cheval sur les deux fenêtres doit deux coupures. Ce n'est pas une obligation du Code du travail — la seule pause qu'il impose est celle de 20 minutes à la sixième heure (art. L3121-16), portée par travailContinuMax — mais la règle d'organisation de l'événement, tenue en dur par choix. La pause légale et la coupure repas restent deux choses distinctes : la coupure d'une heure ne tient pas lieu de relais, et un relais ne tient pas lieu de repas — même si un trou d'une heure coupe bien la séquence de six heures au passage. Entre deux coupures possibles, un terme souple (coupureRepasPlacementPrefere, poids 1, sans niveau) préfère celle que règle le paramètre « placement de la coupure repas » : fin du midi et début du soir par défaut, début ou fin de chaque fenêtre, ou aucune préférence. |
| `coupureRepasPlacementPrefere` | SOFT | Préférences | Terme de « Coupure repas » : entre deux coupures possibles dans la même fenêtre, préférer celle que règle le paramètre de placement. Par défaut, vers laquelle la fenêtre penche : le midi la plus tard — 13 h-14 h plutôt que 12 h-13 h, les stands viennent d'ouvrir — et le soir la plus tôt, pour rouvrir ensuite ; « début » ou « fin » imposent le même sens aux deux fenêtres, « aucun » éteint la préférence. La couverture des stands, elle, est dure : c'est son arbitrage avec cette préférence qui répartit la rotation. |
| `indisponibiliteForcee` | HARD | Contraintes ad hoc | Indisponibilité posée manuellement par l'administrateur : l'animateur ne doit jamais être affecté sur le périmètre visé. |
| `incompatibiliteAdHoc` | HARD | Contraintes ad hoc | Deux animateurs déclarés incompatibles ne doivent jamais travailler sur le même créneau. |
| `affectationForcee` | HARD | Contraintes ad hoc | Affectation imposée par l'administrateur : l'animateur doit être présent sur le créneau ou le stand visé. |
| `affiniteAdHoc` | SOFT | Contraintes ad hoc | Paire d'animateurs à privilégier : chaque créneau où les deux sont affectés au même stand est récompensé. Contrainte souple : elle favorise la co-affectation quand c'est possible, sans jamais la forcer. |
| `arriveeGroupee` | SOFT | Contraintes ad hoc | Groupe d'arrivée (covoiturage, 2 à 4 animateurs) : les membres travaillent les mêmes jours, arrivent et repartent ensemble, à la tolérance près (30 min par défaut, réglable dans Paramètres › Organisation). Chaque jour et chaque paire du groupe coûtent les minutes d'écart au-delà de la tolérance, à l'arrivée comme au départ, et un forfait de deux heures quand l'un travaille et l'autre non. Aucune contrainte de stand : les membres peuvent tenir des stands différents. Contrainte souple : elle cède devant les règles légales et l'équilibre de charge. |
| `animateurVerrouilleCreneauFige` | HARD | Verrouillage du planning | Un animateur verrouillé sur un créneau (par exemple après un échange validé) y garde ce qu'il tient : le solveur ne peut ni le lui retirer ni lui en attribuer un autre, sans geler le reste de son planning. |
| `standComplexeAvecReferent` | MEDIUM | Qualité d'organisation | Chaque stand devrait compter au moins un référent sur chaque créneau. |
| `equilibrerCharge` | MEDIUM | Qualité d'organisation | La charge de travail doit être répartie équitablement entre les animateurs. |
| `stabiliteDuPlanPublie` | MEDIUM | Qualité d'organisation | Une fois un planning publié, chaque personne déplacée d'un siège qu'elle tenait dans le plan publié coûte : le solveur ne bouscule les gens déjà prévenus que si le gain vaut le dérangement. Muette tant que rien n'a été publié ; une vacation que le plan publié n'avait pas — autre jour, autres heures ou autre stand — reste libre. |
| `repartitionMineursParCreneau` | MEDIUM | Qualité d'organisation | Sur un créneau, un stand ne devrait pas compter plus de mineurs que de majeurs. |
| `experienceRequisePourStandsPremium` | MEDIUM | Qualité d'organisation | Un stand premium ne devrait pas être tenu par un animateur débutant sur sa typologie. |
| `eviterRoulementStandsPremium` | MEDIUM | Qualité d'organisation | Sur un stand premium, limiter le nombre d'animateurs différents qui s'y relaient au-delà d'un équipage : on privilégie la continuité. |
| `eviterChangementEmplacementEloigne` | MEDIUM | Qualité d'organisation | Entre deux créneaux consécutifs, éviter de faire basculer un animateur vers un stand dont l'emplacement est éloigné (> 300 m à vol d'oiseau) de celui du créneau précédent. |
| `trajetInsuffisantEntrePostes` | MEDIUM | Qualité d'organisation | Entre deux postes d'un même animateur le même jour, séparés par un battement, sur deux emplacements différents : le battement doit laisser le temps d'aller de l'un à l'autre à pied (distance à vol d'oiseau × facteur de détour ÷ vitesse de marche). Chaque minute manquante au-delà de la tolérance est pénalisée. Deux postes qui se touchent restent l'affaire de eviterChangementEmplacementEloigne : une paire n'est jamais jugée par les deux règles. |
| `limiterEmplacementsParJour` | MEDIUM | Qualité d'organisation | Sur une même journée, limiter le nombre d'emplacements distincts visités par un animateur (plafond réglable, 3 par défaut) : au-delà, la journée est dispersée quelles que soient les distances. |
| `eviterEnchainementStandsEpuisants` | MEDIUM | Qualité d'organisation | Entre deux créneaux consécutifs, éviter d'enchaîner un animateur sur deux stands physiquement épuisants sans repos ni stand plus facile entre les deux. |
| `eviterFermeturePuisOuverture` | MEDIUM | Qualité d'organisation | Après une vacation qui finit tard (22 h par défaut), éviter une reprise matinale le lendemain (10 h par défaut) : on souhaite alors 12 h de repos plutôt que le minimum légal. Préférence d'organisation, pas une obligation du Code du travail : seules les minutes au-dessus du repos quotidien légal sont comptées ici, celles en dessous restent l'affaire de reposQuotidienMinimal, qui les tient en dur. La règle est donc muette quand la loi exige déjà autant (un mineur, 12 h ; avant 16 ans, 14 h). |
| `appreciationIncompatible` | MEDIUM | Qualité d'organisation | L'appréciation de l'administrateur ne couvre aucune typologie de jeu proposée par le stand. |
| `souhaitsIncompatibles` | MEDIUM | Qualité d'organisation | Aucune des typologies de jeu proposées par le stand ne figure dans les souhaits déclarés de l'animateur. |
| `limiterTypologiesDistinctesParAnimateur` | MEDIUM | Qualité d'organisation | Un animateur devrait intervenir sur un petit nombre de typologies de jeu (plafond réglable, 2 par défaut) sur l'ensemble de l'édition, et pas seulement sur une journée : deux typologies le même après-midi et deux à une semaine d'écart comptent pareil. |
| `maxJoursConsecutifsTravailles` | MEDIUM | Qualité d'organisation | Un animateur ne devrait pas travailler plus de jours consécutifs que le plafond réglé dans Paramètres › Organisation (huit par défaut) sans au moins un jour de repos : moins est possible, plus ne devrait pas l'être. Règle d'organisation, dosable : aucun article du Code du travail n'impose un décompte glissant (L3132-1 se lit sur la semaine civile, Cass. soc. 13 nov. 2025, n° 24-10.733). |
| `maxJoursConsecutifsTravaillesDur` | HARD | Qualité d'organisation | Éteinte par défaut. Le même plafond de jours consécutifs, tenu en dur : au-delà, le plan est refusé au lieu d'être pénalisé. Le seuil est celui de l'édition, réglable dans Paramètres › Organisation : les deux formes le lisent au même endroit. Un poids ne change jamais le niveau d'une règle, d'où une contrainte séparée, qu'une édition allume depuis l'écran Règles du planning, par activer_contrainte ou par contraintes.activees d'un scénario. Reste rangée en « Qualité d'organisation » et non en « Légal » : c'est une politique de l'organisateur, pas une obligation du Code du travail. |
| `favoriserMixiteDesNiveaux` | SOFT | Préférences | Quand un référent est présent sur un créneau, y associer un débutant pour favoriser la montée en compétence. |
| `equilibrerCreneauxPenibles` | SOFT | Préférences | Répartir équitablement entre animateurs les créneaux pénibles (stands épuisants ou premium). |
| `preserverBufferPolyvalents` | SOFT | Préférences | Garder au moins un animateur polyvalent (typologie polyvalente) libre sur chaque créneau, pour pouvoir réparer le planning en cas d'absence de dernière minute. |
<!-- catalogue:fin -->
