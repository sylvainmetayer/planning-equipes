# Comprendre ce que promet un numéro de version

Un numéro de version sert à répondre à deux questions : « je suis sur quelle version ? » et « le correctif y est-il ? ». Vous apprenez ici ce que chaque numéro vous promet avant de monter, et ce qui, dans une montée de version, demande un geste de votre part. La procédure de montée est dans [Mettre à jour vers une nouvelle version](mettre-a-jour.md) ; la fabrication d'une release et le patch d'une ancienne ligne, pour qui publie, dans [Publier une version et patcher la précédente](publier-une-version.md).

On démarre à `1.0.0`, à l'ouverture publique. Il n'y a pas de `0.x` : un `0.x` dit « je ne m'engage sur rien », ce qui est faux, le produit tourne en production sur un vrai événement.

## Ce qui compte comme « l'API »

« Rupture d'API » ne veut rien dire tant qu'on n'a pas dit ce qui est l'API. Ici, la surface publique n'est pas le code Java. C'est ce que vous ou un client manipulez.

Font partie de la surface publique, et les changer incompatiblement est une montée MAJOR :

- le format du scénario YAML (clés `festival:` et `festival.dateDebut`, forme des stands et des créneaux) : c'est un fichier que le client écrit ;
- les variables d'environnement (`BRANDING_*`, `LEGAL_*`, `PLANNING_MCP_*`, `FLYWAY_REPAIR_AT_START`…) : en retirer une, ou changer un défaut d'une façon qui change le comportement ;
- les URL stables, au premier chef les liens d'espace animateur imprimés sur des PDF distribués à des humains : les casser, c'est invalider du papier déjà en circulation ;
- l'API REST consommée par autre chose que le frontend du produit, serveur MCP compris ;
- une migration Flyway irréversible au sens exploitation : suppression ou renommage de colonne, contrainte qui rejette des données existantes. Le schéma est *forward-only* : revenir à l'image précédente ne suffit pas à revenir en arrière, il faut restaurer un dump ([sauvegarder et restaurer](sauvegarde-et-restauration.md)). Une version qui rend le retour arrière impossible doit se voir de loin.

Une image plus ancienne **refuse de démarrer** sur une base plus récente, et vous avez deux issues, détaillées dans [Une base en avance sur l'image](mettre-a-jour.md#une-base-en-avance-sur-limage) : redéployer la version qui a migré la base, ou restaurer une sauvegarde antérieure à la montée.

## Ce que promettent les deux autres numéros

MINOR : fonctionnalité ajoutée, nouvelle variable d'environnement avec un défaut qui préserve le comportement, migration additive (nouvelle table, colonne nullable), nouvelle contrainte du solveur *désactivable*.

PATCH : correction de bug, sécurité, dépendances, documentation. Aucune migration destructive, aucune variable retirée.

## Les montées qui demandent un geste de votre part

Une contrainte légale nouvelle ou durcie peut rendre insatisfiable un planning qui passait avant. Techniquement additive, elle est pratiquement une rupture d'exploitation. Le verdict : MINOR, avec une entrée « ⚠️ Attention » obligatoire aux notes de release. MAJOR ferait changer de majeure chaque fois que le Code du travail bouge.

Le marqueur est le scope réservé `contraintes-legales` (`feat(contraintes-legales): …`), pas le `!`, qui reste ce que la convention en dit, le signe d'une rupture MAJOR et ce que `git cliff --bumped-version` lit pour calculer la montée. Les deux mènent à la même section « ⚠️ Attention » (`cliff.toml`), sans que le même caractère ait à signifier MAJOR ici et MINOR là.

Les autres ruptures d'exploitation non majeures (un réglage dont l'échelle change, une valeur à convertir à la main dans un fichier, une variable d'environnement ou un dump d'avant la mise à jour) suivent la même règle : MINOR, avec une entrée « ⚠️ Attention ». Leur marqueur est le scope réservé `attention` (`feat(attention): …`) ; le corps du commit dit ce que vous devez faire. `contraintes-legales` reste réservé aux contraintes légales, pour que la section se lise d'un coup d'œil.

Lisez donc l'entrée « ⚠️ Attention » des notes avant chaque montée : c'est elle qui vous dit si quelque chose vous attend. Des exemples réels de gestes à faire sont dans [les montées qui demandent un geste](mettre-a-jour.md#4-les-montées-qui-demandent-un-geste).

## Combien de temps une ligne est corrigée

Une seule ligne de maintenance existe à la fois : la dernière `MAJOR.MINOR` publiée avant la courante ([comment elle se tient](publier-une-version.md#3-patcher-une-version-antérieure)). Pour la 3.x, c'est la branche `release/3.5`, qui ne reçoit plus que des correctifs.

**Après la sortie de la 4.0.0, la 3.x reçoit des correctifs de sécurité pendant six mois**, puis plus rien : la branche est abandonnée et la réponse devient « montez de version ». Ces six mois courent à partir de la date de la release 4.0.0. Seules les failles de sécurité sont corrigées, pas les anomalies fonctionnelles ni les dépendances sans faille connue.

## Ce que la release attend pour une majeure

Une montée MAJOR se prépare dans les messages de commit. Entre deux tags, `release.yml` lit les sujets : un `!` après le type (`feat(scenario)!: …`) demande une montée MAJOR, un `feat` demande au moins une MINOR. Le contrôle échoue si le numéro posé est **inférieur** à ce que les sujets demandent, par exemple une `4.0.1` sur des commits à `!`, ou une `3.5.1` qui contiendrait un `feat`.

Il ne refuse pas l'inverse : une `4.0.0` posée sans aucun `!` entre les deux tags passerait. C'est pourtant ce `!` qui range le changement sous « ⚠️ Attention » dans les notes et dans l'écran *Nouveautés*, et qui permet à `git cliff --bumped-version` de proposer 4.0.0. Toute PR de rupture de la 4.0 porte donc un `!` dans son sujet, et son paragraphe dans [le guide de passage](passer-a-la-4-0.md).

## Lire la version d'une instance

La version affichée dans le pied de page, sur la page *Débogage*, dans `GET /api/config` (champ `version`) et dans la ligne de démarrage est celle du tag git `vX.Y.Z` : `X.Y.Z` sur une image de release, le SHA court sur une image de recette, `999-SNAPSHOT` en build local, valeur qui ne ressemble volontairement à aucune version publiée. D'où vient ce tag : [Publier une version](publier-une-version.md#1-la-source-de-vérité--un-tag-git).
