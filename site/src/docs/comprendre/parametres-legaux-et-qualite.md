# Vacations, paramètres légaux, repas et qualité

Vous savez ce qu'est une vacation et ce que la grille contrôle à son sujet, quels paramètres règlent la loi et lesquels règlent le confort, comment une pause due est calculée et déduite du travail effectif, et comment un fichier de scénario épingle ses propres valeurs.

## Une grille est faite de vacations

Un créneau est une **vacation** : une tranche de travail réelle, sur laquelle on affecte quelqu'un. Il n'y a pas d'autre sorte de créneau. L'**amplitude** (la journée d'ouverture de bout en bout) n'existe plus comme créneau : un événement connaît ses horaires d'ouverture et les projette en vacations par ses [journées types](modele-des-donnees.md#les-journées-types-génèrent-les-créneaux), et le découpage automatique qui tranchait une amplitude n'existe plus ([décision 0037](repo:docs/decisions/0037-une-grille-est-toujours-des-vacations.md)). De même, les « familles de relais » (plusieurs variantes décalées de chaque vacation) ont été retirées ([décision 0029](repo:docs/decisions/0029-retrait-des-familles-de-relais.md)) : une grille porte une seule variante de chaque vacation et tout stand se pose sur tout créneau ouvert.

### Ce que le contrôle de la grille signale

`CreneauGridService` (`GET /api/creneaux/controle`) classe ses anomalies en erreurs (la grille est mal formée ou garantit une violation d'une règle dure) et avertissements (suspect, peut-être voulu, jamais un refus). Trois concernent la durée et les repas :

| Anomalie | Sévérité | Déclenchée quand |
| --- | --- | --- |
| `VACATION_TROP_LONGUE` | Avertissement | La vacation dépasse `dureeVacationMaxMinutes` (6 h par défaut, le seuil de l'art. [L3121-16]). Elle contiendra une pause à relayer : vérifiez qu'un collègue du stand tient une place à ce moment, ou coupez la journée en deux |
| `REPOS_QUOTIDIEN_IMPOSSIBLE` | Erreur | La vacation dépasse `1440 − reposQuotidienMinimalMinutes` : toute affectation produirait un écart à une règle dure |
| `RELAIS_REPAS_HORS_FENETRE` | Avertissement | Une vacation `couverturePause` hors de toute fenêtre repas : l'effectif y est divisé par deux sans qu'aucune coupure puisse s'y prendre |

Une vacation de plus de 6 h n'est donc pas refusée : elle est légale si un collègue la relaie pendant la pause. Le seuil vit dans `ParametresLegaux`, parce que c'est une règle de l'événement jugée sur la grille que l'organisateur a, quelle que soit la façon dont elle a été écrite.

### Le chevauchement est un pic de demande

Deux vacations consécutives peuvent se recouvrir le temps d'une relève : deux postes existent alors sur le même stand, et l'effectif est garanti en excédent temporaire, jamais en déficit. Deux vacations du même jour qui se chevauchent ne sont donc jamais une anomalie. C'est aussi la principale source de pic : **si tous les stands relèvent au même instant, le nombre de sièges à pourvoir double à cet instant précis.** Ce doublement peut dépasser l'effectif disponible à cet instant : le problème est alors infaisable par construction, sans la moindre pénurie d'animateurs. Le levier est d'étaler les relèves pour qu'elles ne tombent pas toutes sur la même minute.

L'analyse de faisabilité (`GET /api/feasibility`) **ne voit pas ce pic** : c'est une estimation optimiste, agrégée par jour. Elle ne lit pas les compétences, et ne compte un mineur que là où il peut siéger (à côté d'un majeur, hors de sa nuit, d'un jour férié et d'un stand réservé aux majeurs). Elle peut répondre « réalisable » alors que le solveur ne ramène pas le score dur à zéro. Après résolution, le signal fiable est le **score dur réel**, pas `feasible` seul.

### Couvrir un stand pendant le repas

Trois façons de traiter le temps du midi, chacune écrite à la main sur la grille (formulaire du créneau ou journée type) :

| Ce qu'on veut | Comment on l'écrit |
| --- | --- |
| Le stand ferme | Aucune vacation sur la fenêtre |
| Effectif plein pendant la pause | Une vacation ordinaire sur la fenêtre |
| Effectif réduit | Une vacation marquée `couverturePause` |

`couverturePause` ne génère que la **moitié de l'effectif du segment ouvert, arrondie au supérieur** (`Creneau.siegesSegment`). L'arrondi supérieur fait qu'un stand tenu par une seule personne la garde au lieu de fermer : fermer reste une décision explicite, pas l'effet de bord d'une division entière. Une vacation pleine sur la fenêtre repas est à manier avec prudence sur un scénario déjà tendu : une équipe de plus par pause sur chaque stand concerné, concentrée sur la même fenêtre, est exactement le pic décrit plus haut.

### Un seul compte de sièges

Le nombre de sièges d'un segment ouvert (au moins un, la moitié arrondie au supérieur sur une vacation de couverture de pause) est `Creneau.siegesSegment`, et le segment le plus chargé d'un stand sur un créneau est `Creneau.siegesSimultanes`. La génération des postes, l'analyse de faisabilité, l'analyse de fragilité et la règle `eviterRoulementStandsPremium` lisent tous ces deux méthodes : ce que le solveur doit remplir et ce que les écrans annoncent ne peuvent pas être deux nombres différents.

## Les paramètres légaux

`ParametresLegaux` est un fait de problème (`@ProblemFactCollectionProperty` sur `PlanningEvenement`, comme `ContrainteAdHoc`), stocké **par édition**, réglable depuis `GET` et `PUT /api/parametres/legal` (section `legal` des paramètres, avec `organisation`, `notifications` et `instance` ; `GET /api/parametres` rend les quatre). Il porte à la fois les plafonds de la loi et les règles que l'organisation se donne :

| Champ | Défaut | Origine |
| --- | --- | --- |
| `dureeHebdomadaireMaxMinutes` | 48 h | Code du travail art. [L3121-20], ordre public. Contrainte `dureeHebdomadaireMax`, majeurs uniquement |
| `dureeHebdomadaireMaxMineurMinutes` | 35 h | Art. [L3162-1] ; art. [D4153-3] pour les 14 à moins de 16 ans. Contrainte `dureeHebdomadaireMaxMineur` |
| `reposQuotidienMinimalMinutes` | 11 h | Art. L3131-1 |
| `dureeVacationMaxMinutes` | 6 h | Seuil du contrôle de grille (art. L3121-16) |
| `dureePauseMinutes` | 30 min | Plancher légal de 20 min, voir [la pause légale](#la-pause-légale) |
| `coupureRepasMinutes`, fenêtres de midi et du soir | 60 min ; 12 h-14 h ; 19 h-21 h | Règle de l'organisation, voir [la coupure repas](#la-coupure-repas) |
| `heureDebutSoiree` | 20 h | Début de la soirée pour la lecture d'équité, une règle d'organisation. La nuit légale d'un mineur reste portée par `Creneau` |

`ParametresValidator` refuse tout ce qui franchit un plafond d'ordre public, avec l'article qui le fonde : au-delà de 48 h ou 35 h, une pause sous 20 minutes. Une valeur plus protectrice que la loi reste libre. Le repos quotidien négatif, une durée de vacation nulle, une fenêtre repas sans ses deux bornes et une `heureDebutSoiree` absente sont refusés aussi, parce que « pas de soirée » viderait une colonne de l'écran d'équité sans un mot.

Les autres seuils légaux sont des constantes du code, déclarées avec leur article dans `LegalConstraints` et `PlafondsLegauxMajeurs` : durée quotidienne (10 h), travail continu (6 h), repos hebdomadaire (35 h), jours travaillés par semaine (6). Ce sont des minima et maxima d'ordre public qu'un administrateur n'a aucune raison légitime d'assouplir.

La convention collective de l'Animation (ÉCLAT, [IDCC 1518]) n'est pas citée comme source : l'organisation a confirmé qu'elle ne relève pas de cette convention, et l'ancienne citation de l'art. 5.2 était erronée (il traite des jours de repos). Le Code seul fonde les valeurs. Le commentaire de la migration `V7__parametres_legaux.sql` porte encore cette citation : une migration appliquée ne se modifie pas, son empreinte est gelée (`FlywayMigrationsFrozenTest`).

Deux conventions de décompte :

- **Rattachement à la semaine.** Un créneau est rattaché entièrement à la semaine ISO de sa date de début (`Creneau.semaineIso`). Un créneau du dimanche 20 h à minuit compte dans la semaine qui s'achève. La convention simplifie le décompte et reste conservatrice tant que les créneaux de nuit sont courts.
- **Amplitude et travail effectif.** La durée d'un créneau mesure une amplitude, alors que les articles cités portent sur le travail effectif. Le modèle ne représente aucune pause à l'intérieur d'un créneau, donc les deux coïncident, ce qui revient à supposer qu'aucune pause n'y est prise. C'est pourquoi les pauses sont des **trous entre deux créneaux** ou des relais.

Les deux contraintes hebdomadaires regroupent les `PosteAffectation` par animateur et par **jour**, puis les jours par semaine ISO, et pénalisent le dépassement au prorata des minutes en trop. Le niveau intermédiaire par jour sert à déduire les pauses dues, dont le calcul est journalier.

### La pause légale

Le Code exige que la pause soit **réelle**, pas qu'elle soit planifiée : un organisateur qui relève chaque animateur trente minutes à l'intérieur d'une vacation 13 h-20 h est en règle avec l'art. [L3121-16]. L'application connaît donc deux manières de prendre une pause, et pas de troisième ([décision 0048](repo:docs/decisions/0048-une-seule-regle-de-pause.md)) :

- **un trou** d'au moins `dureePauseMinutes` entre deux vacations. Il rompt la séquence (`PauseSurPoste.sequences`), donc plus rien n'est dû de part et d'autre ;
- **un relais** : un autre animateur du **même stand** tenant une place pendant toute la pause (`PauseSurPoste.relayableBy`).

Une pause due qui n'a ni l'un ni l'autre est un **écart dur**, un point par pause, porté par `travailContinuMaxMajeur` et `travailContinuMaxMineur`, plus le forfait d'exclusion d'éligibilité pour un mineur. Il n'y a rien à déclarer : un paramètre `pauseSurPoste` a existé et éteignait ces deux règles, il est retiré.

La durée est **un seul paramètre**, `dureePauseMinutes` (section `parametres.legal` d'un scénario) : 30 minutes par défaut, plancher de 20 (art. L3121-16), relevée à 30 au minimum pour un mineur (art. L3162-3) par `ParametresLegaux.dureePauseMinutes(mineur)`.

**Le calcul est écrit une fois.** `PauseSurPoste.dues(postes, parametres)` rend les pauses d'un couple (animateur, jour) : une séquence de `L` minutes au-delà du plafond `cap` en doit `ceil((L − cap) / (cap + pause))`, la k-ième au plus tard quand la séquence atteint son k-ième plafond. Le solveur, l'écran Pauses, le PDF animateur et le flux ICS lisent tous cette méthode, donc aucun ne peut décrire une pause qu'un autre ne doit pas.

**La déduction est inconditionnelle.** Une pause due est du repos, pas du travail effectif (art. L3121-1), donc elle est retranchée de l'amplitude : plafonds quotidien et hebdomadaires, rapport des heures, équité, Besoin, KPI. Rien n'est caché par là : dans un plan sans écart dur, toute pause déduite a bien été prise, et c'est ce que les deux règles dures garantissent. Une journée 14 h-minuit vaut ainsi 9 h 30 de travail effectif ; 13 h-minuit en vaut 10 h 30 et reste refusée. Le calcul vit dans `EffectiveWork`. Les endroits qui comptent encore l'amplitude, et pourquoi, sont dans [les règles légales et les pauses](regles-legales-et-pauses.md).

## La coupure repas

`FenetreRepas` est un fait de problème, **un par fenêtre déclarée** : le début, la fin et la durée de la coupure exigée. Il est lu par `coupureRepasObligatoire` et `coupureRepasPlacementPrefere`, dont la règle est dans [les règles légales et les pauses](regles-legales-et-pauses.md). Un fait par fenêtre plutôt qu'un objet qui porte les deux : une journée à cheval sur midi *et* sur le soir produit **un écart par fenêtre**, chacun nommant ses horaires, plutôt qu'un agrégat sur lequel personne ne peut agir.

Les valeurs vivent dans `ParametresLegaux` (colonnes `coupure_repas_*`), et `FenetreRepas.from(...)` les projette. La coupure repas n'est pas une obligation légale mais la règle que l'organisation se donne, jugée sur toute grille : elle se règle donc avec les autres règles. Une [consigne](horaires-et-fenetres-des-stands.md#restater-la-coupure-repas-pour-une-date) peut les restater pour sa date.

**Un scénario est jugé sur les fenêtres qu'il déclare**, pas sur celles de la base : `ScenarioYamlReader` les pose sur le planning qu'il construit, et les harnais Java purs en font autant. Sans cela, une grille serait taillée avec un jeu de valeurs et notée avec un autre. En production, `ReferenceDataService` lit la ligne de l'édition.

Une fenêtre est écartée si ses bornes manquent, si elle est vide ou inversée, si la durée est nulle, ou si elle est **plus courte que la coupure qu'elle exige** : personne ne pourrait la satisfaire, et une règle qu'aucune affectation n'atteint sanctionnerait une saisie plutôt qu'un planning.

## Les paramètres de qualité

`ParametresQualite` est un fait de problème du même type, pour les seuils qui règlent le **confort** d'un planning et non la loi. C'est un `record` : un fait de problème est immuable par nature, le solveur le lit des milliers de fois par seconde et ne l'écrit jamais. Les seuils sont **stockés par édition** (`GET` et `PUT /api/parametres-qualite`) ; le bloc `planning.contraintes.*` de la configuration n'est que la valeur par défaut d'une édition qui n'a jamais ouvert l'écran. Rien n'impose de plancher ici : un réglage qui rend une règle inerte est une façon légitime de dire « pas ici ».

| Champ | Défaut | Propriété par défaut | Contrainte qui le lit |
| --- | --- | --- | --- |
| `maxEmplacementsDistinctsParJour` | 3 | `planning.contraintes.max-emplacements-par-jour` | `limiterEmplacementsParJour` |
| `heureServiceTardif` | 22 h | `planning.contraintes.heure-service-tardif` | `eviterFermeturePuisOuverture` |
| `heureServiceMatinal` | 10 h | `planning.contraintes.heure-service-matinal` | `eviterFermeturePuisOuverture` |
| `reposSouhaiteApresServiceTardifMinutes` | 720 | `planning.contraintes.repos-souhaite-apres-service-tardif-minutes` | `eviterFermeturePuisOuverture` |
| `typologiesDistinctesMax` | 2 | `planning.contraintes.typologies-distinctes-max` | `limiterTypologiesDistinctesParAnimateur` |
| `joursConsecutifsMax` | 8 | `planning.contraintes.jours-consecutifs-max` | `maxJoursConsecutifsTravailles` et sa forme dure |
| `vitesseMarcheKmH` | 4 | `planning.contraintes.vitesse-marche-km-h` | `trajetInsuffisantEntrePostes` |
| `facteurDetour` | 1,3 | `planning.contraintes.facteur-detour` | `trajetInsuffisantEntrePostes` |
| `toleranceTrajetMinutes` | 5 | `planning.contraintes.tolerance-trajet-minutes` | `trajetInsuffisantEntrePostes` |
| `toleranceArriveeGroupeeMinutes` | 30 | `planning.contraintes.tolerance-arrivee-groupee-minutes` | `arriveeGroupee` |

Quelques précisions de lecture :

- une heure laissée **vide** se lit comme absente, pas comme minuit : c'est ainsi qu'un déploiement neutralise `eviterFermeturePuisOuverture`. Un repos souhaité nul, ou au plus égal au plancher légal, rend la règle inerte : seul ce qu'elle demande au-delà du repos légal est pénalisé ;
- `typologiesDistinctesMax` compte sur l'édition entière, pas sur le jour, et les polyvalents en sont exempts ;
- `joursConsecutifsMax` vaut 8 parce que c'est une condition de faisabilité plus qu'un confort : la règle exige un jour de repos dans chaque fenêtre de `max + 1` jours. Un plafond trop serré avec la forme dure activée laisse des places vides, et aucun poids n'achète ce que l'arithmétique interdit. Le repos hebdomadaire reste tenu en dur par `maxJoursTravaillesParSemaine` et `reposHebdomadaireMinimal` ([décision 0045](repo:docs/decisions/0045-le-niveau-de-la-regle-des-jours-d-affilee.md)) ;
- `ParametresValidator` refuse seulement ce qui ne voudrait rien dire : un plafond inférieur à 1, un repos souhaité négatif, une vitesse de marche non positive, une tolérance d'arrivée groupée hors de 0 à 240 minutes.

`SolveRunner.prepareProblem` écrase systématiquement ce fait avec la valeur de l'édition : un appelant ne peut pas desserrer un seuil de qualité en l'envoyant dans son payload.

## Les sections de scénario

Un fichier de scénario peut fixer ses propres paramètres, sous `parametres:` : `legal`, `organisation` (les seuils de qualité) et `instance` (le budget de calcul). Pour `legal`, seuls les champs à surcharger sont donnés et les autres gardent le défaut **de la classe Java**, jamais la valeur en base : un gros scénario peut ainsi configurer lui-même sa durée de résolution sans dépendre de ce qu'un scénario précédent a laissé. Un fichier qui ne porte pas la section prend les paramètres de l'édition qui l'importe. Dans `organisation`, les deux heures sont lues telles quelles (une section qui n'en nomme aucune décrit une édition où `eviterFermeturePuisOuverture` ne dit rien, un réglage légitime) et les nombres omis prennent leur défaut.

Les sections et clés retirées sont **refusées par leur nom** plutôt qu'ignorées (`ScenarioBinder`) : `parametresDecoupage` et `decoupageAuto` (le découpage a disparu : une grille d'amplitudes lue comme des vacations donnerait des journées de 14 h sans un mot), et les clés `pauseSurPoste`, `pauseMinimaleEntreVacationsMinutes`, `dureePauseMajeurMinutes`, `dureePauseMineurMinutes` de `parametres.legal`, chacune avec sa remplaçante. Le même mécanisme refuse toute clé inconnue et un `schemaVersion` plus récent que celui que la version lit. Les formats complets sont dans [scenario-yaml](scenario-yaml.md).

<!-- Liens vers Légifrance. Chaque référence pointe vers la recherche par
     numéro d'article, qui résout toujours la version en vigueur : un
     identifiant LEGIARTI désigne une version datée, et vieillit en silence. -->
[D4153-3]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=D4153-3
[IDCC 1518]: https://www.legifrance.gouv.fr/conv_coll/id/KALICONT000005635177
[L3121-16]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3121-16
[L3121-20]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3121-20
[L3162-1]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3162-1
