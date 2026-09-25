# 0050 — Les identifiants sont générés, numérotés par édition

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : schéma (migration `V100`), référentiels, import de scénario et imports CSV, MCP, API
- **Prolonge** : [0001](0001-cloisonnement-par-edition.md) (les clés `(edition_id, id)`)

## Contexte

L'identifiant d'une édition, d'un animateur, d'un stand, d'une typologie,
d'un emplacement ou d'une contrainte ad hoc était **choisi par l'utilisateur**.
Seuls les créneaux, les journées types et les horaires avaient des numéros
générés ; les verrouillages, les demandes d'échange, les déclarations et les
calculs, des UUID tirés par le serveur. Cela faisait quatre régimes, et trois
défauts :

- **Confidentialité.** Le serveur MCP ne rend jamais un nom, mais il rend
  l'identifiant dans chaque réponse. Un identifiant tapé à la main
  (`marie.dupont`), ou dérivé du nom comme le faisait l'import CSV
  (`marie-dupont`), fait réapparaître la personne partout.
- **Cohérence.** Des identifiants tapés, des *slugs* (le nom d'une édition,
  celui d'un animateur importé), des numéros et des UUID cohabitaient.
- **Concurrence.** Un identifiant tapé qui entre en collision renvoie un 409,
  que l'utilisateur doit résoudre à la main.

## Décisions

### D1 — Un compteur par édition, pas une séquence globale

Chaque référentiel numérote ses lignes **dans son édition** : `A1` est le
premier animateur de chaque édition, `S1` le premier stand. Les préfixes sont
`A` (animateur), `S` (stand), `T` (typologie), `L` (emplacement, un *lieu*)
et `C` (contrainte ad hoc).

La duplication d'une édition reste ainsi un `INSERT … SELECT` qui garde les
identifiants : les clés composites `(edition_id, id)` de [0001](0001-cloisonnement-par-edition.md)
existent pour cela. Une séquence globale aurait obligé la duplication à tout
renuméroter, sur le modèle de `creneau_remap` étendu à chaque table fille.

PostgreSQL n'a pas de séquence par clé partielle. Le compteur est donc une
ligne de `compteur_identifiant (edition_id, entite, dernier)`, incrémentée par
un `INSERT … ON CONFLICT DO UPDATE … RETURNING`. Le verrou de ligne que prend
cette instruction sérialise deux créations concurrentes sans boucle de
réessai, ce qu'un `MAX + 1` aurait exigé. Et comme le compteur ne fait que
croître, **un numéro libéré par une suppression ne resert jamais** : une ligne
du journal ou un instantané qui le cite continue de désigner la ligne qu'il
désignait. La duplication copie les compteurs avec les lignes qu'ils ont
numérotées ; la restauration d'une sauvegarde les recale sur le plus grand
numéro rejoué.

Les **éditions** elles-mêmes (`E1`, `E2`…) sont tirées d'une séquence
globale : c'est par elles que les autres compteurs sont partitionnés.

Une édition garde un lien avec la précédente seulement si elle en est une
copie : `A12` de 2026 est la même personne que `A12` de 2025 lorsque 2026 a
été dupliquée de 2025. Rien ne s'appuie sur cette coïncidence. Si
l'historique d'une personne d'une année sur l'autre prend un jour de la
valeur, il méritera une notion explicite plutôt qu'une égalité d'identifiants.

### D2 — Un code lisible pour les stands, les typologies et les emplacements

Plusieurs fichiers **retrouvent une ligne par une clé lisible** : l'import des
référentiels, la colonne `typologies` des stands, la grille des ouvertures
et le fichier scénario. Un numéro rend ces fichiers
illisibles.

Les stands, les typologies et les emplacements portent donc un **code**
facultatif (`STRATEGIE`, `JEU-LIBRE`, `PAVILLON`), unique dans l'édition.
C'est lui que les fichiers citent, et partout où l'on attend une typologie,
un stand ou un emplacement, l'id et le code sont acceptés. Un code ne peut
pas avoir la forme d'un identifiant de son référentiel (`S4` pour un stand),
ce qui rend « un id ou un code » toujours univoque. Il ne peut pas non plus
contenir les séparateurs des fichiers (`,`, `;`, `|`, un saut de ligne).

Les **animateurs** n'ont pas de code : un code choisi à la main
réintroduirait l'identifiant nominatif que cette décision retire. Un fichier
les rapproche par leur identifiant, puis par leur adresse e-mail, puis par
leurs nom et prénom, et refuse une ligne ambiguë en nommant les candidats par
leur identifiant. Les contraintes ad hoc n'ont pas de code non plus : aucun
fichier ne les cite par une clé.

### D3 — Dans un fichier scénario, un identifiant est une référence locale

Le précédent des créneaux s'étend à tous les référentiels : l'identifiant
d'une ligne d'un fichier ne sert qu'à ce que le fichier la cite ailleurs (un
siège, une contrainte, une consigne). Ce qu'il désigne dans l'édition cible
se décide à l'import, ligne par ligne :

1. la ligne de même identifiant, **si elle est vraisemblablement la même** :
   même e-mail, ou mêmes nom et prénom, pour un animateur ; aucun code
   contradictoire pour un stand, une typologie ou un emplacement ;
2. sinon, la ligne du code (ou de l'identifiant lu comme un code, pour un
   fichier écrit à la main ou exporté avant cette décision) ; pour un
   animateur, la seule fiche de cet e-mail, puis la seule de ce nom ;
3. sinon, une ligne nouvelle, sous un identifiant tiré du compteur.

La première règle est ce qui fait qu'un scénario **exporté puis réimporté
dans la même édition** retrouve ses lignes : l'import met à jour au lieu de
supprimer puis recréer, et les jetons d'espace, les sessions, les demandes
d'échange et les confirmations survivent. Sans elle, chaque réimport
invaliderait les liens déjà imprimés sur les plannings.

La vérification d'identité de cette première règle n'est pas une précaution
de style. Chaque édition numérote à partir de 1 : `A3` d'une autre édition est
quelqu'un d'autre. Se fier au seul identifiant aurait écrit la fiche d'une
personne sur celle d'une autre, en lui laissant son jeton, donc l'accès à
l'espace de la seconde.

La section `edition` d'un fichier désigne l'édition cible par son
identifiant, sinon par son nom. Elle ne choisit jamais l'identifiant d'une
édition qu'elle crée.

### D4 — Les UUID restent des UUID

Les verrouillages, les demandes d'échange, les déclarations de disponibilité
et les calculs ne sont pas choisis par l'utilisateur. Des numéros
séquentiels n'y apporteraient rien en confidentialité, et y ajouteraient deux
fuites : l'espace animateur porte l'identifiant d'une demande dans ses
routes, où un numéro révélerait le volume et faciliterait le sondage ; et
`verrouillage_planning` a une clé globale. Seuls les identifiants métier que
l'utilisateur saisissait passent au compteur.

### D5 — Un nom d'édition n'a pas la forme d'un identifiant

L'argument MCP `edition` accepte un identifiant ou un nom, et essaie
l'identifiant d'abord. Une édition **nommée** « E2 » serait donc
inatteignable par son nom, ou pire, désignerait une autre édition. Un nom de
la forme `E` suivi d'un nombre est refusé à la création et au renommage. Un
nom purement numérique (« 2027 ») reste permis : il ne peut plus entrer en
collision avec un identifiant.

### D6 — La grille des compétences ne s'échange plus par fichier

Son export ne portait, par construction, que des identifiants d'animateurs et
des niveaux, sans aucun nom. Maintenant que chaque édition numérote à partir de
1, un tel fichier réimporté dans une autre édition que la sienne aurait posé
les niveaux sur d'autres personnes, et rien dans la ligne ne permettait de s'en
apercevoir. Plutôt que de marquer le fichier de son édition, l'échange est
retiré : la grille se saisit exclusivement à l'écran, où chaque ligne porte le
nom de la personne. La décision [0030](0030-grille-competences-import-additif.md),
qui fixait la sémantique de cet import, est abandonnée.

## La migration

`V100` renumérote l'existant. Un identifiant qui a déjà la forme de son
référentiel (`A12`, `S3`) **garde sa valeur** : il ne nomme personne, et tous
les fichiers qui le citent restent justes. Les autres (`marie.dupont`,
`HOMME-JEU`) reçoivent les numéros suivants, dans l'ordre naturel des
anciens identifiants, qui est celui dans lequel le solveur les rangeait. Un
stand, une typologie ou un emplacement renuméroté garde son ancien
identifiant comme code.

La réécriture est explicite plutôt que confiée à un `ON UPDATE CASCADE`.
Toutes les clés étrangères du schéma sont retirées, chaque colonne qui porte
un identifiant est réécrite à travers une table de correspondance, puis les
clés sont recréées à partir de leur définition. Une cascade n'aurait couvert
que les clés déclarées, alors que les colonnes dangereuses sont justement
celles qui n'en ont pas :

| Colonne | Oubliée, elle aurait… |
| --- | --- |
| `plan_snapshot.contenu` | fait voir tout le plan publié comme changé à la règle de stabilité, et rendu les instantanés irrestaurables |
| `notification_planifiee.cle` | renvoyé les rappels et relances déjà envoyés |
| `publication_destinataire.animateur_id` | fait annoncer à chacun que son planning a changé |
| `solver_job.perimetre` | donné un périmètre vide aux calculs rejoués au redémarrage |
| `journal_action.entite_id`, `acteur_id` | empêché l'historique de retrouver les noms |
| `declaration_disponibilite.souhaits` | fait citer des typologies inconnues à une déclaration en attente |
| `demande_echange.stand_cible_id` | fait viser un stand disparu à un échange dirigé |
| `kpi_historique.edition_id` | fait perdre leur édition aux courbes |

Une valeur qui désigne une ligne disparue (un animateur supprimé cité par un
ancien instantané ou une ligne du journal) devient un marqueur orphelin, `~`
suivi d'une empreinte. Conservée telle quelle, elle aurait pu désigner le
nouveau venu qui tire le même numéro, et elle était peut-être le nom même que
cette migration retire.

Les colonnes `edition_id` perdent leur valeur par défaut `'DEFAUT'`, qui
désignait une édition renumérotée : une ligne écrite sans édition échoue au
lieu de se ranger dans une édition qui n'existe plus.

## Conséquences

- **Rupture MAJEURE.** Le fichier scénario (section `edition`, identifiants
  devenus locaux), les outils MCP de création (ils ne prennent plus
  d'identifiant) et l'API REST (un identifiant envoyé à la création est
  ignoré) changent de contrat.
- **Une sauvegarde prise avant la migration ne se restaure plus après**,
  comme après `V52`. Il faut sauvegarder juste avant la mise à jour.
- Un fichier CSV conservé par un organisateur reste lisible. Son ancienne
  colonne `id` est lue comme un code quand l'édition n'a pas cet
  identifiant.
- Le navigateur garde l'identifiant de l'édition courante en stockage local.
  Après la migration, il désigne une édition qui n'existe plus : l'application
  retombe sur l'édition par défaut, et le journal local des notifications de
  l'ancienne clé devient orphelin.
- Un assistant MCP qui a retenu des identifiants au cours d'une conversation
  doit les relire.
- L'UID des repos dans les abonnements ICS embarque l'identifiant de
  l'animateur. Un agenda abonné voit les repos disparaître puis réapparaître
  une fois, sous leur nouvel UID.
- `POST /api/reference-data/import`, qui écrivait un planning JSON avec ses
  identifiants tels quels, disparaît : aucun écran ne l'appelait, et il était
  la seule écriture capable d'imposer un identifiant. L'import de scénario le
  remplace.
