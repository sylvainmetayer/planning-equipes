# Repos et jours d'affilée

Vous savez comment le solveur lit un repos à cheval sur deux semaines, pourquoi deux semaines pleines d'affilée sont interdites, pourquoi le plafond de jours consécutifs existe sous deux formes et se règle à huit jours, et ce que coûte une fermeture tardive suivie d'une ouverture matinale. Les articles cités sont ceux du Code du travail, seul cadre appliqué ; les pauses et la coupure repas sont dans [Règles légales, pauses et repas](regles-legales-et-pauses.md).

## La frontière de semaine

Les deux repos hebdomadaires se lisent sur la **semaine civile**, du lundi 0 h au dimanche 24 h ([L3121-35], définition supplétive qu'un accord d'entreprise peut déplacer, donnée que l'application ne détient pas), la même fenêtre que `Creneau.semaineIso()` et les plafonds hebdomadaires. Reste la question d'un repos à cheval sur le lundi.

**Majeurs, `reposHebdomadaireMinimal` ([L3132-2]).** Le texte parle de « vingt-quatre heures consécutives auxquelles s'ajoutent les heures consécutives de repos quotidien » : les 24 h sont dans la semaine, seul le repos quotidien qui s'y ajoute peut déborder. `creditReposMinutes` calcule `min(durée réelle, part dans la semaine + 11 h)`. Un repos à cheval est crédité aux deux semaines mais n'atteint le plancher de 35 h que dans celle où il passe au moins 24 h. Un repos du samedi 20 h au lundi 10 h (38 h) satisfait la semaine du samedi et laisse la suivante à 21 h, comme la Cour de cassation le lit (Cass. soc. 13 nov. 2025, n° 24-10.733, aux visas des articles L3132-1 et L3132-2).

**Mineurs, `reposHebdomadaireMineur` ([L3164-2]).** « Deux jours de repos consécutifs par semaine » se compte en jours civils à l'intérieur de la semaine. Un dimanche et le lundi suivant sont un jour libre dans chacune de deux semaines, pas deux jours consécutifs de l'une d'elles : aucune des deux n'est satisfaite. Créditer la paire aux deux semaines a été écarté, car cela ramenait un mineur à un seul jour libre par semaine, soit la dérogation à 36 h de l'alinéa 2 sans l'accord qu'elle exige. `mineurAvecDimancheEtLundiLibresAChevalSurDeuxSemainesEstPenaliseSurChacune` (`LegalConstraintsTest`) verrouille ce choix. Les jours que l'événement ne couvre pas restent des jours libres, mais les deux jours doivent se suivre : une semaine ouverte en pointillé (lundi, mercredi, vendredi, dimanche) reste pénalisée si le mineur les tient tous.

## Deux semaines pleines d'affilée

`dureeHebdomadaireMax` plafonne chaque semaine ISO indépendamment : rien n'empêchait deux semaines pleines dos à dos, forme vers laquelle un solveur converge sur un événement à cheval sur deux semaines avec un besoin tendu. `dureeHebdomadaireMaxDeuxSemaines` (dure) l'interdit pour les majeurs.

Son seuil est celui de `ParametresLegaux.dureeHebdomadaireMaxMinutes`, jamais une seconde constante à 48 h qui pourrait diverger. La pénalité compte les **paires** de semaines pleines consécutives : trois semaines de suite coûtent deux fois plus, pour que le solveur ait une pente à descendre plutôt qu'un mur. Une semaine est pleine en **travail effectif**, pas en amplitude, et les créneaux étant en heures entières, « strictement sous 48 h » vaut « 47 h au plus ».

C'est la forme courte de l'art. [L3121-22] (44 h en moyenne sur douze semaines consécutives), la seule qui ait un sens sur quinze jours : le solveur ne connaît ni les semaines d'avant ni celles d'après. La moyenne glissante est [hors périmètre](regles-legales-et-pauses.md). La règle ne rattrape pas 47 h 59 puis 48 h, permis par construction : c'est la formulation demandée par l'organisateur.

## Les jours d'affilée

`maxJoursTravaillesParSemaine` (L3132-1) et `reposHebdomadaireMinimal` (L3132-2, 35 h) garantissent un jour libre par semaine civile. Elles laissent pourtant passer mardi-dimanche puis lundi-samedi : douze jours d'affilée à zéro écart dur. Un plafond de jours **glissants** est donc une règle à part, et une **politique de l'organisateur**, pas une obligation. L3132-1 se lit sur la semaine civile (L3121-35), et la Cour de cassation juge que le repos hebdomadaire n'a pas à tomber au plus tard après six jours consécutifs (Cass. soc. 13 nov. 2025, n° 24-10.733 ; même lecture CJUE C-306/16).

Elle existe sous deux formes, parce qu'un poids ne change jamais le niveau d'une règle (`SolverConfiguration.constraintWeightOverrides` mappe sur le niveau du catalogue) :

| Règle | Niveau | Par défaut |
|---|---|---|
| `maxJoursConsecutifsTravailles` | medium, dosable | active, importance normale (poids 5) |
| `maxJoursConsecutifsTravaillesDur` | dur | éteinte |

Les deux partagent leur corps et leur seuil, donc ne divergent ni sur ce qu'elles comptent ni sur le nombre de jours qu'elles laissent passer. Elles peuvent être actives ensemble, ce qui dit « jamais plus de N, et de préférence moins ». La forme dure s'allume par l'écran Règles du planning, par `activer_contrainte` ou par `contraintes.activees` d'un scénario, sans migration, comme l'[ADR 0041](repo:docs/decisions/0041-encadrement-des-mineurs-eteint-par-defaut.md).

### Pourquoi le seuil est de huit jours

Le seuil (`joursConsecutifsMax`, **huit par défaut**) se règle par édition, sur la ligne de la règle. Il quitte la constante du code parce qu'il décide de la **faisabilité**, pas du confort : la règle exige un jour de repos dans chaque fenêtre de (seuil + 1) jours, donc un jour de plus ou de moins change le nombre de jours-repos à trouver. Sur la grille hivernale versionnée (`festival-hivernal`), six jours est un plafond que rien ne satisfait avec la forme dure allumée, alors que huit atteint zéro écart dur. Un plafond inatteignable vaut moins qu'un plafond plus large qui tient, et le poids n'y peut rien : il ne rend pas atteignable ce que l'arithmétique interdit. Resserrer vers six se comble par la grille ou par l'effectif, pas par le solveur. Le protocole de comparaison est dans l'[ADR 0045](repo:docs/decisions/0045-le-niveau-de-la-regle-des-jours-d-affilee.md).

Au dosage livré, la forme moyenne laisse des personnes au-delà du seuil : c'est ce que l'événement demande à effectif constant. L'écran Équité le montre, colonne « plus longue série », avant la publication.

### Vérifier qu'un seuil tient, puis le faire trouver

Ce sont deux questions. La première est arithmétique, et l'analyse de faisabilité la pose avant tout calcul dès que l'une des deux formes est allumée. Chaque journée demande au moins son **plancher** (son pic de sièges simultanés, lu dans les mêmes segments que ceux d'où naissent les sièges). Chaque animateur n'offre, sur une fenêtre de (seuil + 1) jours, que le plus petit de deux nombres : le seuil, ou ses jours disponibles dans la fenêtre. Sur la fenêtre la plus tendue, l'écran Problèmes dit ce qu'il en est, les dates nommées et jamais une personne :

| Marge (offre moins demande) | Ce que l'analyse en dit |
|---|---|
| négative, forme dure allumée | **bloquant** : aucun plan ne tient la règle, il manque N jours-personnes, soit au moins ⌈N / seuil⌉ animateurs |
| négative, forme moyenne seule | avertissement : le plan dépassera forcément le plafond pour certains |
| positive mais sous 10 % de la demande | avertissement : la règle ne tiendra que si chaque journée emploie presque le minimum de personnes |
| plus large | rien |

La condition est *nécessaire*, pas suffisante : les compétences, les heures, les règles légales et une journée tenue par plus que son plancher consomment la marge. Gonfler le plancher pour attraper ces cas ferait crier à l'impossible sur des grilles que le solveur remplit sans peine. Elle ne couple pas non plus par compétence. Sous la forme dure, quand la grille laisse de la place, l'analyse relit le plan en place avec la même arithmétique et avertit si même là le compte ne passe pas. Une fenêtre entièrement passée n'est jamais jugée ; dans une fenêtre à cheval sur le passé, un jour déjà travaillé compte qui l'a réellement tenu, pas son plancher.

L'écran Besoin compte la forme dure exactement : il cherche le plus petit effectif dont les calendriers de repos tiennent ensemble les six jours par semaine et le seuil d'affilée, par un flot maximal sur les jours de repos ([ADR 0071](repo:docs/decisions/0071-le-besoin-en-animateurs-un-minimum-exact-verifie-par-une-equipe-fictive.md)). La forme moyenne seule ne relève jamais ce chiffre : une règle que le solveur peut enfreindre ne doit pas faire recruter.

Ce qui tranche reste un **plan** : construit à part et importé, l'écran Problèmes le juge, et zéro écart dur prouve que le besoin est tenable. La seconde question est celle du solveur. Sous la forme dure, sa phase de faisabilité cherche par **jours entiers** : la chaîne qui remplit un trou libère un jour de la *série* et non seulement de la semaine ISO, un jour rendu ne va qu'à des collègues déjà présents ce jour-là, et une journée tenue en morceaux se regroupe sur moins de personnes ([ADR 0049](repo:docs/decisions/0049-la-regle-dure-des-jours-d-affilee-se-cherche-par-jours-entiers.md)). La construction place aussi les sièges par date plutôt que par rareté, car dans l'ordre par rareté le montage que tout le monde sait tenir passait en dernier, quand les séries qui le suivent étaient déjà longues ([ADR 0068](repo:docs/decisions/0068-sous-la-regle-dure-la-construction-suit-le-calendrier.md)).

## Fermer tard puis ouvrir tôt

`reposQuotidienMinimal` tient le repos quotidien en dur : 11 h pour un majeur ([L3131-1]), 12 h pour un mineur et 14 h avant 16 ans ([L3164-1]). Quelqu'un qui ferme à 23 h et reprend à 10 h respecte les 11 h à la minute près, ce qui est légal mais discutable sur quinze jours. `eviterFermeturePuisOuverture` (medium, « Qualité d'organisation ») exprime cette préférence et rien d'autre : ce n'est pas une obligation du Code, d'où l'absence de catégorie « Légal ».

### Ce qu'elle compte

Trois seuils de `ParametresQualite` : service tardif (la journée **finit** à 22 h ou après), service matinal (la journée du lendemain **commence** à 10 h ou avant) et repos souhaité (12 h). Les réglages sont `planning.contraintes.heure-service-tardif`, `planning.contraintes.heure-service-matinal` et `planning.contraintes.repos-souhaite-apres-service-tardif-minutes`.

La pénalité est le nombre de minutes manquant au repos souhaité, **comptées à partir du plancher légal** : `souhaité − max(repos réel, plancher légal)`. Cette formule empêche la règle de devenir un second plancher déguisé :

- un planning déjà illégal n'est pas facturé deux fois pour les mêmes minutes : celles sous le plancher restent l'affaire de `reposQuotidienMinimal` ;
- là où la loi exige déjà autant que le souhait (un mineur, 12 h ; avant 16 ans, 14 h), la règle est entièrement muette ;
- un repos souhaité réglé au niveau du plancher, ou en dessous, la rend inerte : une préférence qui ne dépasse pas la loi ne dit rien. Laisser les deux heures de service vides est une réponse légitime ([ADR 0031](repo:docs/decisions/0031-signaler-le-plancher-sans-le-decider.md)).

Les bornes se comparent **en instant**, pas en heure d'horloge : une vacation 20 h-0 h finit le lendemain à 0 h et passerait pour matinale lue comme une heure, alors que c'est la fermeture que la règle cherche.

### Une nuit, une pénalité

La règle raisonne sur des **journées**, pas sur des paires de vacations. Le repos d'une nuit est une quantité unique (dernière fin de J, premier début de J+1), alors qu'une jointure par paires la facture une fois par couple : quelqu'un qui reprend à 8 h puis de nouveau à 10 h payait deux fois la même nuit, et supprimer le siège de 10 h divisait la pénalité par deux sans rendre une minute de sommeil. Le gradient que descend le solveur doit être le déficit lui-même : la journée est agrégée d'abord (`max(fin)`, `min(début)`), puis les deux journées sont jointes sur la clé d'adjacence. Un soir coupé en deux est couvert par le même geste. `reposQuotidienMinimal` peut se permettre la forme par paires : c'est un plancher binaire, pas une magnitude.

### Pourquoi 22 h et 10 h

10 h et non 9 h : avec un seuil matinal à 9 h, une vacation finissant à 22 h ou après et une reprise à 9 h ou avant sont au plus 11 h l'une de l'autre, jamais au-delà du plancher légal d'un majeur que la règle refuse de facturer. Le seul couple attrapé serait le point exact 22 h → 9 h : une règle inerte. À 10 h la bande utile est `[11 h, 12 h[`, exactement le motif visé.

### La tension avec la continuité sur stand premium

`eviterRoulementStandsPremium` pousse à garder les mêmes têtes sur un stand premium ; sur un stand qui ouvre et ferme chaque jour, éviter fermeture puis ouverture revient à les faire tourner. Les deux règles sont medium et dosables : l'arbitrage se règle **par édition**, pas dans le code. Sur la grille réelle anonymisée `festival-realiste-canicule`, la règle supprime l'essentiel du clopening pour un prix de quelques dizaines de points chez les autres règles. `scenario-complet` ne peut pas répondre à la question : aucun créneau n'y commence avant 11 h, la règle ne peut pas se déclencher, et un chiffre nul y est un artefact de grille.


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
