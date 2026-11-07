# 0083 — Toute clé étrangère porte un index côté référençant, et un test le tient sans exception

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : schéma PostgreSQL (migrations), tests structurels
- **Complète** : [0001](0001-cloisonnement-par-edition.md) (le cloisonnement par édition, dont les cascades portent la suppression d'une édition)

## Contexte

PostgreSQL indexe d'office le côté *référencé* d'une clé étrangère : la cible
est une clé primaire ou unique, il n'a pas le choix. Il n'indexe **jamais** le
côté *référençant*. Or le contrôle d'intégrité, à chaque suppression d'une
ligne parent, cherche les lignes enfants par `colonne = $1`. Sans index, il
parcourt la table enfant entière, **une fois par ligne parent supprimée** : la
suppression de *n* parents est quadratique en la taille de l'enfant.

Cela ne se voit pas en développement. Il tombe sur les opérations qui
supprimaient beaucoup d'un coup : une édition entière (toutes les cascades), le
remplacement de la grille de créneaux (un contrôle sur les sièges par créneau),
un stand ou un animateur et leurs sièges. La suppression d'une édition réelle
tient alors un verrou pendant des secondes, pour un contrôle qui, indexé, se
compte en millisecondes.

Le défaut est invisible à la relecture : une migration qui ajoute une table
avec sa clé étrangère a l'air complète. Les tables ajoutées après la première
passe d'indexation ont toutes recommencé l'oubli.

## Décision

1. **Toute clé étrangère est servie, côté référençant, par un index dont les
   colonnes de tête sont exactement celles de la clé, dans le même ordre.**
   Une clé primaire composite, une clé unique ou un index plus large dont la
   clé est un préfixe comptent. Les mêmes colonnes dans un autre ordre ne
   comptent pas.
2. **Un index partiel ne compte que si son prédicat est le seul `colonne IS
   NOT NULL`** sur les colonnes de la clé : c'est tout ce qu'implique la
   recherche du contrôle d'intégrité. Les colonnes presque toujours vides
   (continuation d'un siège, cible d'un échange dirigé, marqueur de plan
   notifié) reçoivent un tel index, qui ne coûte presque rien en écriture.
3. **Un index unique partiel posé pour une règle d'unicité ne sert pas la
   clé.** Une déclaration, une demande ou un signalement a un unique sous
   `WHERE statut = 'EN_ATTENTE'` : il ne sert ni le contrôle (dès qu'une ligne
   est tranchée) ni la lecture « mes déclarations » de l'espace. Un index
   plein `(edition_id, animateur_id)` est posé à côté.
4. **Les index vers `creneau` suivent la clé** : composites
   `(edition_id, creneau_id)`, comme toutes les clés vers un créneau depuis
   que celui-ci porte l'édition dans sa clé unique. Un index `(creneau_id)`
   seul ne sert plus rien et est remplacé.
5. **Un test lit le schéma réellement migré** (`pg_constraint`, `pg_index`) et
   refuse toute clé étrangère sans index, **sans liste d'exceptions**. C'est le
   seul test structurel du dépôt qui n'en a pas : une clé non indexée n'est
   jamais un choix, seulement un oubli, et une liste d'exceptions serait la
   porte par où il revient.

La même migration reprend deux index qui ne servaient pas les lectures qu'on
leur demandait : le journal, trié par `(survenu_le DESC, id DESC)` alors que
l'index s'arrêtait à `survenu_le` (PostgreSQL lisait toute l'édition pour
n'en rendre que cinquante), et les index `(stand_id)` des ouvertures et des
indisponibilités de stand, refaits sur `(edition_id, stand_id)` pour servir
aussi la clé vers l'édition.

## Alternatives écartées

- **Indexer au cas par cas, quand une lenteur se manifeste.** Elle ne se
  manifeste pas là où on regarde : le coût tombe dans un contrôle de clé que
  aucun plan de requête applicative ne montre.
- **Retirer les clés étrangères qui coûtent cher.** Elles garantissent qu'un
  siège ne survit pas à son créneau, et le cloisonnement par édition (0001)
  repose sur leurs cascades. Le coût vient de l'index absent, pas de la clé.
- **Une liste d'exceptions dans le test**, pour les petites tables. Une table
  petite aujourd'hui ne l'est pas toujours, et la liste devient l'endroit où
  l'on range ce qu'on n'a pas voulu regarder. Un index sur une table de
  quelques lignes ne coûte rien.
- **Accepter tout index partiel dont la colonne de tête est la bonne.** Un
  prédicat quelconque ne garantit pas que la ligne cherchée est dans l'index :
  le contrôle d'intégrité ne pourrait pas l'utiliser, et le test dirait vert.

## Conséquences

- **Écritures** : chaque index est une écriture de plus par ligne insérée,
  modifiée sur ses colonnes ou supprimée. Le coût se paie surtout là où le
  plan est réécrit en bloc : l'atterrissage d'une résolution supprime et
  réinsère tous les sièges, et `poste_affectation` porte quatre index de plus.
  Il est payé une fois par résolution, dans la transaction même qui économise
  la suppression quadratique ailleurs ; écrire seulement la différence des
  sièges, plutôt que tout le plan, l'effacerait en grande partie.
- **Place** : quelques octets par ligne et par index, dominés par
  `poste_affectation`, la table qui grossit en sièges × éditions.
- **Une migration qui ajoute une clé étrangère ajoute son index**, ou le test
  échoue sur la base migrée. Le message nomme la table, la clé et ses colonnes.
- **Une clé vers `creneau` est composite** (`(edition_id, creneau_id)`) ; un
  index sur `creneau_id` seul n'est plus le bon.
