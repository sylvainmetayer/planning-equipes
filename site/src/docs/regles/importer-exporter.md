# Importer et exporter des fichiers

Vous remplissez une édition depuis vos tableurs, vous exportez ce que vous voulez corriger ou partager, et vous archivez l'édition à la fin de l'événement.

Tout se passe dans *Fichiers* (groupe *Préparer*), un écran à trois onglets : *Importer*, *Exporter*, *Archive*. Chaque écran de référentiel a en plus son bouton « Importer », qui ouvre la même carte d'import sans quitter la liste. Le cycle complet tient en trois gestes : exporter, corriger dans le tableur, réimporter.

Votre point de départ est un tableur ? [Passer d'un tableur à un logiciel de planning](/tableur-ou-logiciel-de-planning/) dit ce que ce changement apporte, et quand il ne s'impose pas.

## Importer

L'onglet *Importer* propose une carte par référentiel, dans l'ordre où les données se tiennent :

1. typologies ;
2. emplacements ;
3. stands ;
4. créneaux ;
5. gabarits ;
6. animateurs ;
7. la grille des stands ;
8. enfin le fichier scénario, **le seul qui remplace l'édition** au lieu de la compléter.

![L'écran d'import de fichiers](/images/fr/docs/fichiers.webp "L'écran Fichiers, onglet Importer")

Les règles communes :

- Les trois premiers fichiers demandent le minimum : un code et un libellé (typologies), un code et un nom (emplacements), un code, un nom et des typologies (stands). L'effectif d'un stand et les coordonnées d'un emplacement restent facultatifs.
- Les créneaux se reconnaissent à leur date et à leurs deux heures plutôt qu'à un identifiant.
- Les gabarits se reconnaissent à leur nom. Leurs vacations tiennent sur une ligne (« 09:00-12:00, 12:00-13:00 R, 14:00-20:00 »). Leurs dates complètent le calendrier sans le remplacer : la grille ne bouge qu'à l'application du calendrier.
- Une colonne absente n'efface rien, et une ligne dont le code est connu est mise à jour.
- Une typologie citée sans exister est créée, après l'avoir annoncée.
- Aucun identifiant n'est jamais saisi ni lu dans un fichier : l'application attribue les siens (A1, A2… pour les animateurs, S1… pour les stands), numérotés dans chaque édition. Une ligne se désigne par son code, sinon par son nom ; un animateur par son adresse e-mail, sinon par ses nom et prénom.
- Chaque carte accepte aussi les cellules copiées depuis un tableur, lues comme un fichier. Une fois l'import écrit, un lien ouvre l'écran des lignes importées.

### Importer la grille des stands

La matrice de votre classeur arrive telle quelle : stands en lignes, jours et créneaux en colonnes, un effectif par case.

1. Chaque colonne se pose sur son créneau ; une colonne inconnue est ignorée et listée.
2. Relisez l'aperçu ligne par ligne : chaque stand voit son horaire réécrit depuis ses cases.
3. Validez.

La grille actuelle se télécharge comme modèle.

### Importer vos animateurs depuis un tableur

Le tableur de bénévoles arrive tel quel.

1. Désignez quelle colonne est quel champ.
2. Lisez ligne par ligne ce que l'import ferait : acceptée, ou rejetée avec la raison et le numéro de ligne du fichier. Rien n'est écrit tant que vous n'avez pas validé.
3. Validez.

Par défaut l'import ajoute et met à jour sans supprimer personne, et complète les jours d'indisponibilité déjà déclarés au lieu de les effacer. Un CSV d'exemple se télécharge depuis l'écran.

### Charger un exemple ou un scénario

Un **fichier scénario** décrit une configuration complète d'événement ; l'import valide le fichier et explique ce qui cloche. Un fichier se vérifie aussi sans rien importer.

Les exemples livrés avec l'application se chargent depuis la carte **Exemples** de l'onglet Importer. Chacun a un nom lisible, sa taille et ce qu'il montre ; ils sont rangés « pour découvrir » et « pour tester un cas ». La gamme d'essais du calcul et les cas extrêmes, livrés mais non listés, se chargent par leur nom.

Avant de remplacer une édition par un scénario, l'application en garde une copie : voir [Enchaîner les éditions](enchainer-les-editions.md#repartir-de-zéro).

## Exporter

L'onglet *Exporter* offre deux formes.

- L'archive ZIP : un CSV par référentiel (typologies, emplacements, stands, créneaux, gabarits, animateurs), chacun à cocher, et chacun dans la forme exacte que l'onglet d'import correspondant relit. De quoi recopier une édition sur l'autre, rejouer le calendrier d'une année sur l'autre, ou corriger en masse dans un tableur puis réimporter.
- Le fichier scénario : l'édition entière dans un seul fichier, celui que la carte Scénario des imports relit.

Chaque liste de référentiel offre aussi « Exporter cette liste », qui télécharge en CSV ce que le tableau montre.

## Archiver une édition terminée

L'onglet *Archive* garde la trace d'une édition terminée en un clic. L'état de l'édition le propose une fois le dernier jour de l'événement passé.

Un seul ZIP réunit, au choix : le planning global, l'équité, les heures travaillées, les référentiels, le scénario qui se réimporte, la relecture de publication et les plannings individuels. Un manifeste, lisible sans l'application, dit de quel plan viennent ces fichiers, quand il a été calculé et publié, et combien de journées ont été relues.

Les fichiers sont ceux que chaque écran aurait téléchargés. Aucun jeton d'accès n'y figure, et le téléchargement est inscrit à l'historique.

## Les formats en détail

Les colonnes exactes des fichiers CSV sont décrites dans [les imports CSV](../comprendre/imports-csv.md), le schéma du scénario dans [le fichier de scénario](../comprendre/scenario-yaml.md) et la façon de revenir sur un import dans [restaurer une édition](../comprendre/restaurer-une-edition.md).
