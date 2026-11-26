# 0108 — Un seul type « Version », à trois origines

- **Statut** : accepté
- **Date** : novembre 2026
- **Portée** : plans gardés (table, API, outils MCP), publication, comparaison
- **Prolonge** : [0007](0007-instantanes-contenu-denormalise.md), [0092](0092-le-realise-se-calcule-a-la-demande.md), [0100](0100-convention-de-nommage-des-outils-mcp.md)

## Contexte

L'application gardait des plans sous trois noms : la copie qu'une personne
demande, la copie prise d'office avant chaque calcul, et la publication, le
plan que les animateurs ont reçu. Les trois vivaient déjà dans une même table
(`plan_snapshot`, contenu dénormalisé, décision 0007), mais l'origine ne se
lisait que de deux drapeaux, `automatique` et `publie_le`, et chaque écran, API
et outil MCP parlait d'« instantané » en laissant entendre que c'étaient tous
la même chose, ou trois choses. L'utilisateur voyait « instantané »,
« version » et « publication » pour un seul objet : un plan, à un moment, avec
l'origine de sa prise.

## Décision

1. **Un type, la version, avec une colonne `origine`** : `MANUELLE`,
   `AVANT_CALCUL`, `PUBLIEE`. La table devient `version_plan`. `publie_le` reste,
   comme date de publication, et une contrainte tient les deux d'accord :
   `origine = 'PUBLIEE'` si et seulement si `publie_le` est renseignée. `automatique`
   disparaît du schéma.
2. **La migration renomme, elle ne copie pas.** Les trois sources étant déjà
   une seule table, `V143` renomme la table, ses index et sa séquence, et
   calcule `origine` (la publication l'emporte sur l'automatique). Les clés
   étrangères qui visent la table (traces de publication, envois, marqueur du
   dernier plan notifié, plan remplacé par un calcul) la suivent sans qu'on
   les touche, ce qui est aussi ce qui rend la perte impossible : une copie
   puis une suppression aurait demandé de les redéclarer une à une.
   `VersionPlanMigrationTest` joue le fichier sur une base remplie à la façon
   de la 3.x et compare, ligne à ligne, le nombre et le contenu avant et après.
3. **L'API garde l'ancienne racine en alias.** `/api/planning/versions` est la
   route ; `/api/planning/snapshots` répond comme avant, `@Deprecated`, jusqu'en
   5.0. La réponse porte `origine` et garde `automatique` (vrai pour
   `AVANT_CALCUL`), de sorte que l'écran 3.x continue de fonctionner tant que
   l'écran Versions n'est pas refait.
4. **Les outils MCP suivent 0100**, sans alias : `creer_version`,
   `lister_versions`, `consulter_version`, `comparer_versions`,
   `restaurer_version`, `supprimer_version`. `restaurer` entre dans l'ensemble
   fermé des verbes de 0100 : restaurer remplace le plan courant, c'est donc un
   verbe de la classe destructive, que `appliquer` (qui n'écrase pas) ne dit pas.
   Les vues disent `origine` à la place de `automatique`.
5. **Les règles de conservation ne bougent pas.** La dernière publication ne se
   supprime pas ; une publication qui est la référence d'une journée écoulée
   non plus (0092) ; les copies automatiques sont purgées au-delà des N dernières,
   sauf celle que le dernier calcul effectif a remplacée.

## Options écartées

- **Créer `version_plan` à côté et y verser les lignes.** Même résultat qu'un
  renommage, avec un risque de perte et des clés étrangères à redéclarer.
- **Garder `automatique` et `publie_le` comme seule vérité.** Trois sens pour
  deux booléens dont un seul croisement (automatique et publié) est interdit
  par le code et non par le schéma ; une colonne et une contrainte le disent.
- **Renommer le webhook `planning.publie` en même temps.** Son champ
  `instantaneId` est lu par des intégrations extérieures : il reste, et son
  changement demandera sa propre décision.

## Conséquences

- L'écran « Versions » et « Comparer deux versions » s'appuient sur `origine` ;
  les mesures d'édition et la copie avant vidage suivent dans leurs propres
  décisions et ne sont pas touchées ici.
- Les classes Java gardent leur nom (`PlanSnapshotService`, `SnapshotMeta`) :
  elles ne sont ni sur le fil ni dans le schéma, et un renommage de plus de
  trente fichiers n'apporterait rien que l'API n'ait déjà.
- Un retour vers la 3.x passe par la restauration de la sauvegarde d'avant la
  montée de version.
