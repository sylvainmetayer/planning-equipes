# Importer et exporter des fichiers

Vous remplissez une édition depuis vos tableurs, vous exportez ce que vous voulez corriger ou partager, et vous archivez l'édition à la fin de l'événement.

Tout se passe dans *Fichiers* (entrée du menu), un écran à trois onglets : *Importer*, *Exporter*, *Archive*. L'écran des animateurs a en plus son bouton « Importer », qui ouvre le même CSV sans quitter la liste. Le cycle complet tient en trois gestes : exporter, corriger, réimporter.

Votre point de départ est un tableur ? [Passer d'un tableur à un logiciel de planning](/tableur-ou-logiciel-de-planning/) dit ce que ce changement apporte, et quand il ne s'impose pas.

## Importer

L'onglet *Importer* propose quatre cartes :

1. le **scénario YAML**, le classeur complet d'une édition : typologies, emplacements, stands et leurs horaires, créneaux, gabarits, animateurs, ajustements, paramètres. C'est **le seul qui remplace l'édition** au lieu de la compléter ;
2. les **animateurs**, depuis un tableur CSV ;
3. les **exemples** livrés avec l'application ;
4. la **vérification d'un fichier** scénario, qui n'écrit rien.

![L'écran d'import de fichiers](/images/fr/docs/fichiers.webp "L'écran Fichiers, onglet Importer")

Les imports CSV des typologies, des emplacements, des stands, des créneaux et des journées types, et celui de la grille des stands, n'existent plus depuis la 4.0 : le fichier YAML les remplace. Pour reprendre votre grille de stands, exportez l'édition en YAML (onglet *Exporter*), réécrivez les horaires des stands dans ce fichier, puis réimportez-le : [Passer de la 3.x à la 4.0](../installer/passer-a-la-4-0.md#les-imports-csv-des-référentiels-et-de-la-grille-des-stands-sont-retirés) donne l'écriture exacte.

Les règles communes :

- Aucun identifiant n'est jamais saisi ni lu dans un fichier CSV : l'application attribue les siens (A1, A2… pour les animateurs, S1… pour les stands), numérotés dans chaque édition. Un animateur se désigne par son adresse e-mail, sinon par ses nom et prénom.
- Une colonne absente n'efface rien, et une ligne déjà connue est mise à jour.
- Le CSV des animateurs accepte aussi les cellules copiées depuis un tableur, lues comme un fichier. Une fois l'import écrit, un lien ouvre la liste des animateurs sur les lignes importées.

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

- L'archive ZIP : un CSV par référentiel (typologies, emplacements, stands, créneaux, gabarits, animateurs), chacun à cocher, à ouvrir dans un tableur. Seul le fichier des animateurs se réimporte.
- Le fichier scénario : l'édition entière dans un seul fichier, celui que la carte Scénario des imports relit. C'est lui qui permet de recopier une édition sur l'autre, de rejouer le calendrier d'une année sur l'autre, ou de corriger en masse puis de réimporter.

Chaque liste de référentiel offre aussi « Exporter cette liste », qui télécharge en CSV ce que le tableau montre.

## Archiver une édition terminée

L'onglet *Archive* garde la trace d'une édition terminée en un clic. L'état de l'édition le propose une fois le dernier jour de l'événement passé.

Un seul ZIP réunit, au choix : le planning global, l'équité, les heures travaillées, les référentiels, le scénario qui se réimporte, la relecture de publication et les plannings individuels. Un manifeste, lisible sans l'application, dit de quel plan viennent ces fichiers, quand il a été calculé et publié, et combien de journées ont été relues.

Les fichiers sont ceux que chaque écran aurait téléchargés. Aucun jeton d'accès n'y figure, et le téléchargement est inscrit à l'historique.

## Les formats en détail

Les colonnes exactes du CSV des animateurs sont décrites dans [les imports CSV](../comprendre/imports-csv.md), le schéma du scénario dans [le fichier de scénario](../comprendre/scenario-yaml.md) et la façon de revenir sur un import dans [restaurer une édition](../comprendre/restaurer-une-edition.md).
