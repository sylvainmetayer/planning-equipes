# Règles légales, pauses et repas

Vous savez pourquoi la pause, la coupure repas et leurs déductions sont écrites comme elles le sont, ce que chacune coûte, ce que le solveur ne fait volontairement pas, et quel cadre juridique s'applique. Les repos hebdomadaires, les jours d'affilée et le repos après une fermeture tardive sont traités dans [Repos et jours d'affilée](repos-et-jours-d-affilee.md).

Le cadre expliqué à un organisateur est dans [le Code du travail et les plannings](/code-du-travail/), et le cas des moins de 18 ans dans [les animateurs mineurs](/animateurs-mineurs/).

## La durée des pauses

Une seule durée de pause vaut pour l'édition : `dureePauseMinutes`, réglée sur la page Règles du planning, onglet Légal, sur la ligne de chaque règle qui la lit (ou dans Paramètres, onglet Légal) ([ADR 0048](repo:docs/decisions/0048-une-seule-regle-de-pause.md)). Elle est lue par `travailContinuMax`, `PauseSurPoste`, le filtre d'éligibilité, tous les compteurs d'heures et l'analyse des pauses, donc par l'écran Pauses et par la carte « Pause de 18:20 à 18:40 » du PDF animateur. Tous passent par `ParametresLegaux.dureePauseMinutes(mineur)`, seule façon de garantir qu'aucun ne soit plus strict qu'un autre.

C'est un **plancher**, à l'inverse des deux durées hebdomadaires qui sont des plafonds : 20 minutes (art. [L3121-16]), refusées en dessous par le service, libres au-dessus. Le défaut est de 30 minutes. Donner plus que le Code est un choix d'organisation (une relève de 30 minutes s'organise plus simplement qu'une de 20) ; la seule direction qu'une application ne doit pas laisser prendre est l'autre. Un assistant la règle aussi, avec le même plancher, par l'outil MCP `modifier_parametres_legaux`.

**Le plancher du mineur ne se stocke pas, il s'applique à la lecture.** Les 30 minutes de l'art. [L3162-3] sont d'ordre public, mais une édition qui accorde 25 minutes n'est pas illégale : elle donne 25 à ses majeurs et 30 à ses mineurs. `dureePauseMinutes(true)` rend `max(valeur, 30)` au lieu de refuser le réglage, ce qui aurait forcé l'édition à donner 30 à tout le monde. Les constantes `PlafondsLegauxMajeurs.PAUSE_MINIMALE_MINUTES` et `PlafondsLegauxMineurs.PAUSE_MINIMALE_MINUTES` sont les deux planchers.

### Ce que la pause déduit, ce que l'amplitude compte

La pause est du repos, pas du travail effectif (art. L3121-1) : elle est **déduite**, payée ou non, sans rien à déclarer. Dans un plan sans écart dur, toute pause déduite a bien été prise, c'est ce que `travailContinuMax` garantit. La déduction vaut partout :

| Où | Ce qui est mesuré |
|---|---|
| `dureeQuotidienneMax` | travail effectif : amplitude moins les pauses dues |
| `dureeHebdomadaireMax`, `dureeHebdomadaireMaxMineur`, `dureeHebdomadaireMaxDeuxSemaines` | idem, sommé sur la semaine ISO |
| page Planning par personne, CSV « Heures pour la paie » (`PlanningHoursService`), outil `heures_travaillees` | idem : total et colonnes hebdomadaires |
| tableau d'équité (`EquiteService`) | idem : c'est cette grandeur qu'on répartit |
| KPI (`PlanningKpiService`) | idem, sauf les instantanés anciens |
| volumétrie (`ProblemScaleService`) | idem pour les sièges tenus ; un siège vide compte son amplitude |

`EffectiveWork` est l'unique endroit où ce calcul vit. Une pause est due **par journée et par animateur** (une séquence ne traverse pas minuit) : tout lecteur hebdomadaire groupe par jour avant de sommer, jamais l'inverse (`LegalConstraints.joursAvecParametres`). Un vrai trou entre deux vacations ne fait rien déduire : aucune séquence n'y dépasse six heures, donc aucune pause n'y est due, le trou est la pause. `HeuresCoherentesTest` verrouille l'égalité des lectures : sommer l'amplitude dans un écran et le travail effectif dans un autre donnait deux versions du même planning, et une demi-heure par jour d'écart, assez petit pour passer pour un arrondi.

**L'amplitude journalière se voit, elle ne se borne pas.** Du premier début à la dernière fin d'une journée, trous et pauses compris, elle s'affiche (maximum, nombre de journées au-delà de 12 h) dans la synthèse par personne, le CSV des heures, `heures_travaillees` et la timeline de la fiche. Le seuil est une constante (`PlanningHoursService.SEUIL_AMPLITUDE_MINUTES`), aucune règle ne le lit : borner l'amplitude attend l'avis d'un juriste.

L'amplitude reste la grandeur de trois cas, nommés :

- les trois compteurs de prime de la paie (dimanche, jour férié, « Nuit (paie) » après 22 h) et leurs équivalents du tableau d'équité. Une prime se paie sur la présence, et une pause ne se range pas d'un côté de minuit sans inventer quand elle a été prise. Les trois colonnes ne s'additionnent donc pas au total. La soirée n'est pas une prime : il n'y en a qu'une, l'heure réglable des paramètres légaux ([ADR 0055](repo:docs/decisions/0055-une-seule-soiree-la-nuit-de-la-paie-a-part.md)) ;
- un siège vide dans la volumétrie (`hoursToFill`) : ce qu'il coûtera en pauses à son titulaire est inconnu tant que personne ne le tient ;
- les KPI d'un instantané ancien, recalculés en mode dégradé : leurs sièges ne portent pas d'horaire, aucune séquence ne s'en déduit.

La borne « charge horaire » de l'écran Besoin relève de même les deux plafonds de la pause qu'on prend dessous (`maxDailyAmplitude`) : une journée de 10 h 20 d'amplitude est 10 h de travail, donc une personne peut la couvrir. Diviser une amplitude par un plafond de travail effectif réclamerait des gens dont le plan n'a pas besoin, ce qui est la seule erreur qui compte pour une borne annoncée comme une preuve.

## La pause légale : un trou ou un relais

Une pause due se prend de deux manières, et l'outil n'en connaît pas de troisième :

- **un trou** d'au moins `dureePauseMinutes` dans la grille. Il rompt la séquence, plus rien n'est dû de part et d'autre ;
- **un relais** : un collègue du **même stand** tenant une place pendant toute la pause.

Sans l'un ni l'autre, c'est un **écart dur**, un point par pause non relayable, porté par `travailContinuMax`, plus le forfait d'exclusion d'éligibilité pour un mineur ([les exclusions d'éligibilité](pourquoi-ces-regles.md)). Les pauses dues sortent de `PauseSurPoste`, que l'écran Pauses, le PDF animateur et le flux ICS lisent aussi : aucun des quatre ne diverge sur ce qui est dû ni sur quand.

**Pourquoi dur et non dosé.** Un relais qui n'existe pas n'est pas un confort perdu. Sans personne pour tenir le stand, la personne ne peut pas le quitter, sa « pause » reste du travail effectif (art. L3121-1 et L3121-2 ; rémunérer la pause n'en change pas la qualification, Cass. soc. 22 mai 2019, n° 17-26.914), et les vingt minutes de l'art. L3121-16 ne sont pas données. C'est aussi ce qui autorise la déduction ci-dessus : les deux décisions ne tiennent qu'ensemble. Une case « pause prise sur le poste » qui éteignait `travailContinuMax*` a existé ; elle laissait deux lectures du même planning et éteignait la règle des mineurs alors que les 4 h 30 de l'art. L3162-3 sont d'ordre public.

Là où la règle mord : un **stand à une seule place tenu plus de six heures d'affilée**. Personne ne peut relayer, aucune affectation n'atteint zéro dur. La réponse est de retailler la grille ou d'ouvrir une place de plus ; il n'y a pas de poids à baisser, la règle n'en admet pas.

**Une pause due dans une séquence déjà écoulée n'est reprochée à personne** ([ADR 0044](repo:docs/decisions/0044-le-passe-est-fige.md)). Ses sièges sont épinglés : un écart dur posé là ne serait réparable par aucun mouvement, et un calcul relancé en cours d'événement n'atteindrait plus jamais zéro. La règle regroupe par animateur et par jour, mais le garde-fou de journée ne suffit pas, car une journée dont la matinée est passée garde un siège le soir. C'est le siège que le relais aurait dû couvrir qui décide.

## La coupure repas

`coupureRepasObligatoire` (dure) et `coupureRepasPlacementPrefere` (soft) portent la coupure repas. Sans elles, une journée de dix heures d'affilée sortait à zéro dur sans que rien le signale.

**Ce n'est pas le Code du travail.** La seule pause qu'il impose est celle de l'art. L3121-16, que `travailContinuMax` porte déjà. La coupure repas est la règle d'organisation de l'événement, celle pour laquelle la grille du classeur source taille ses vacations de midi. Elle est tenue en dur par choix, sous la catégorie protégée « Organisation (repas) ».

### La règle, exactement

Pour un couple (animateur, date) et chaque fenêtre repas déclarée :

- elle est **due** si la personne travaille de part et d'autre : un poste commence avant l'ouverture de la fenêtre et un poste finit après sa fermeture. Qui commence à 19 h a mangé avant, qui termine à 14 h mangera après ;
- elle est **satisfaite** si un trou libre d'au moins la durée paramétrée tient entièrement dans la fenêtre. Sur une fenêtre 12 h-14 h à 60 minutes, c'est 12-13, 13-14 ou tout ce qui se place entre. Une coupure 13 h 45-14 h 45 ne compte que pour ses quinze premières minutes : la fenêtre est le service de restauration ;
- la **pénalité** est le nombre de minutes manquantes, jamais un tout-ou-rien : une journée à un quart d'heure près et une journée qui ne s'arrête jamais ne sont pas le même problème, et un palier ne laisserait aucune pente à descendre ;
- il y a **une coupure par fenêtre traversée** : une journée 10 h-23 h en doit deux.

La coupure vaut **une heure** par défaut (`coupureRepasMinutes`). Une fenêtre de deux heures se divise alors en deux créneaux entiers (12-13 ou 13-14 le midi, 19-20 ou 20-21 le soir), qui sont l'organisation réelle de l'événement ; à 45 minutes, la coupure flottait dans la fenêtre sans tomber sur un créneau. Une fenêtre plus courte que la coupure qu'elle exige est écartée d'office (`FenetreRepas.depuis`) : personne ne pourrait la satisfaire, et sanctionner une saisie n'est pas le rôle du score.

**Une coupure repas est une pause au sens de l'art. L3121-16** : elle rompt la séquence continue, le compteur des six heures repart de zéro. C'est une conséquence du modèle, `PauseSurPoste.sequences` rompant une séquence sur tout trou d'au moins la pause légale, et `RepasConstraintsTest` la verrouille parce que rien dans le code ne l'écrit.

### La rotation et ce qu'elle coûte

Offrir le choix entre 12-13 et 13-14 suppose deux créneaux distincts à midi, et que quelqu'un tienne le stand pendant que les autres mangent. `couverturePause` sur un créneau divise par deux, arrondi au supérieur, les sièges qu'un stand y ouvre ; le drapeau se pose sur le créneau ou sur la vacation d'une journée type. Sans lui, une rotation réclame deux équipages complets. Une grille en blocs jointifs (11 h-12 h puis 12 h-13 h) ne demande aucun réglage d'écart entre vacations.

Le **repos quotidien** de 11 h reste dû entre la fin de soirée et le matin suivant. Le plancher de l'écran Besoin ne le voit pas (ses bornes l'ignorent) et peut annoncer une marge confortable pendant qu'une partie de l'effectif est inutilisable.

`coupureRepasPlacementPrefere` départage ensuite 12-13 de 13-14 en pénalisant la distance au bout de la fenêtre vers lequel elle penche. **Le midi, c'est le plus tard** : les stands viennent d'ouvrir, on ne mange pas à midi pile. **Le soir, c'est le plus tôt** : on mange tôt pour rouvrir ensuite. La règle lit ce que la personne *pourrait* choisir : une journée qui laisse tout le midi libre n'est pas « mange à midi pile », elle ne coûte rien. La couverture des stands étant dure, c'est son arbitrage avec cette préférence qui répartit la rotation.

### Deux indépendances

**Indépendante de la pause légale.** Un relais dit que les vingt minutes de l'art. L3121-16 ont lieu, il ne dit rien du déjeuner : satisfaire l'une ne dispense jamais de l'autre. Un trou d'une heure coupe la séquence de six heures au passage, c'est la seule chose que les deux règles ont en commun.

**Indépendante du `modeGrille`.** Les fenêtres repas voyagent en faits de problème (`FenetreRepas`, projeté depuis `ParametresLegaux`) sur toute grille, qu'elle soit saisie en amplitudes ou en vacations. La coupure se règle avec les paramètres légaux.

### Ce qu'elle peut rendre infaisable

Sur une grille dont **un seul créneau couvre toute la fenêtre** (une vacation 10 h-20 h d'un bloc), son titulaire ne peut pas s'absenter et le siège doit être pourvu : aucune affectation n'atteint zéro dur. La réponse est de retailler la grille, ou d'éteindre la règle depuis Règles du planning.

Un poids fort sur une règle medium se paie sur la phase de faisabilité, qui accepte ses mouvements sur le score entier. La règle d'usage vaut pour toutes les règles medium : atteindre zéro dur au dosage par défaut, puis doser et relancer à chaud, jamais un poids fort dans un départ à froid.

### Où les autres écrans la lisent

L'écran Pauses liste la coupure due, le plus grand trou libre et ce qui manque, par le **même `CoupureRepas`** que la règle : il n'y en a qu'un, ils ne peuvent pas diverger. L'écran Besoin compte la coupure dans son plancher (cinquième borne de `StaffingAnalyzer`), mais `StaffingResource` ne lui transmet les fenêtres que si la règle est active : une règle que le solveur n'a pas à honorer ne doit pas relever le nombre d'animateurs à recruter. Cette borne compte un **siège qui couvre toute la fenêtre** comme ne laissant aucun trou pour manger à son titulaire : un siège du soir 20 h-minuit contre une fenêtre 20 h-21 h lui interdit l'après-midi. Un siège qui déborde des deux côtés, lui, ne relève rien : c'est la grille infaisable ci-dessus, qu'aucun effectif ne sauve. L'écran Problèmes n'a rien de spécifique : la règle étant dure, ses écarts remontent par `ConstraintCatalog.NOMS_DURS`.

## La convention collective de l'Animation (ÉCLAT, IDCC 1518)

Elle **ne s'applique pas** : l'organisation a confirmé qu'elle n'en relève pas. Tout ce que le code cite est le Code du travail seul. La mention « CCN Animation ÉCLAT art. 5.2 » qui accompagnait le plafond de 48 h a été retirée, car elle était fausse deux fois : l'art. 5.2 traite des jours de repos et la semaine haute figure à l'art. 5.7.2.3. Elle subsiste dans le commentaire de `V7__parametres_legaux.sql` : une migration appliquée ne se modifie pas, son empreinte est gelée (`FlywayMigrationsFrozenTest`).

C'est un **fait déclaré, pas une vérification juridique**. Si la convention s'appliquait, l'art. 5.3 imposerait 45 minutes de coupure à toute journée (les 30 minutes retenues sont sous ce plancher) et plafonnerait l'amplitude à 12 h, et l'art. 5.2 donnerait deux jours de repos consécutifs à tout salarié, ce qui interdirait mécaniquement les douze jours d'affilée. Une réserve reste à lever : une page Légifrance rendait ces articles « non en vigueur » alors que les textes consolidés de 2024 les portent « en vigueur, étendu ».

## Hors périmètre assumé

Ces obligations sont réelles et volontairement non implémentées, pour que leur absence soit un choix écrit.

**Durée hebdomadaire moyenne de 44 h sur 12 semaines ([L3121-22]).** Un événement couvre quelques semaines ISO au plus : le solveur ne connaît ni les semaines précédentes ni les suivantes. C'est une limite structurelle. Le contrôle relève du service RH, à partir du cumul hebdomadaire que le Planning par personne expose.

**Travail de nuit des majeurs ([L3122-1] et suivants).** `chevaucheNuit()` n'est consulté que par les règles des mineurs, alors que les scénarios livrés comportent un créneau nocturne quotidien. La qualification de « travailleur de nuit » dépend d'un seuil et d'une régularité, mais aussi du contrat, d'un accord collectif ou d'une autorisation de l'inspection, des faits que l'application ne détient pas. La numérotation exacte des articles reste non vérifiée : contrôle manuel d'ici là, avec un juriste.

| Autre sujet | Pourquoi hors périmètre |
|---|---|
| Heures supplémentaires, contingent annuel ([L3121-30]) | Le planning produit est l'assiette du décompte, pas le décompte |
| Nature des indisponibilités | `joursIndisponibles` est un `Set<LocalDate>` non typé : impossible de distinguer un repos légal, un congé payé et une convenance |
| Autorisation d'inspection pour les moins de 16 ans ([L4153-3], [D4153-2]) | Donnée administrative absente du modèle, à vérifier manuellement |
| Dérogation sectorielle aux jours fériés ([R3164-2]) | Non instruite ; le défaut le plus protecteur s'applique |
| Repos dominical des mineurs ([L3132-3], [L3164-3] à [L3164-5], [R3164-1]) | Les dérogations de L3132-4 et L3132-8 ne s'appliquent pas aux moins de 18 ans, et seuls des apprentis de douze secteurs énumérés peuvent travailler le dimanche. Le secteur de l'organisateur et le statut de chaque animateur sont des faits que le dépôt ne contient pas : un mineur placé un dimanche est accepté, à contrôler manuellement |

[D4153-2]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=D4153-2
[L3121-16]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3121-16
[L3121-22]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3121-22
[L3121-30]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3121-30
[L3121-35]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3121-35
[L3122-1]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3122-1
[L3131-1]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3131-1
[L3132-2]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3132-2
[L3132-3]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3132-3
[L3162-3]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3162-3
[L3164-1]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3164-1
[L3164-2]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3164-2
[L3164-3]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3164-3
[L3164-5]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L3164-5
[L4153-3]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=L4153-3
[R3164-1]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=R3164-1
[R3164-2]: https://www.legifrance.gouv.fr/search/code?tab_selection=code&searchField=NUM_ARTICLE&query=R3164-2
