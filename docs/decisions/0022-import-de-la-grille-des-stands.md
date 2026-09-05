# 0022 — L'import de la grille des stands transpose la décision 0021

- **Statut** : accepté
- **Date** : septembre 2026
- **Portée** : import de données, API, écran d'administration

## Contexte

L'audit de la saisie des horaires de stands (septembre 2026) a établi que la
source de l'organisateur est une matrice stands × (jour × bande horaire) avec un
entier par case, et que sur l'événement de référence 697 stand-jours sur 697 ont
déjà leurs fenêtres coupées aux changements d'effectif. La grille de saisie à
l'écran en est la copie ; le collage d'un bloc y verse déjà des cases. Il
manquait l'import d'un fichier entier, avec un rapport.

La [décision 0021](0021-import-tabulaire-partiel-et-previsualise.md) laissait
ouverte l'extension aux stands : « les cinq règles se transposent ; seule
l'identité changerait ». C'est ce qui est fait ici, avec trois écarts qu'il
faut écrire.

## Décision

1. **Les cinq règles de 0021 s'appliquent** : import partiel qui ne supprime
   rien, analyse et écriture en deux appels dont le second reçoit le fichier,
   écriture atomique, fichier jamais écrit sur disque. La conversion d'un stand
   est celle de la grille de saisie (`GrilleHorairesStands`) : tout l'horaire
   réécrit depuis les cases, compacté en règles, bornes dérivées.

2. **L'identité d'une ligne est l'identifiant du stand, sinon son nom exact**
   (casse et accents indifférents). Un nom porté par deux stands rejette la
   ligne en nommant les candidats ; un stand inconnu rejette la ligne. L'import
   ne crée pas de stand : typologies et emplacement lui manqueraient, et une
   fiche créée « à compléter » serait oubliée telle quelle.

3. **Une colonne sans créneau correspondant est ignorée et listée**, pas un
   motif de refus du fichier. Un classeur porte souvent une bande de montage ou
   une colonne de commentaire que l'édition n'a pas ; refuser le fichier pour
   elle obligerait à le retoucher avant chaque import.

4. **Un créneau sans colonne garde la case actuelle du stand.** La grille de
   saisie réécrit tout l'horaire d'un stand ; l'import, lui, ne réécrit que ce
   que le fichier dit et complète le reste avec l'état en place, dans l'esprit
   du « partiel » de 0021. Le rapport le signale, avec la liste des créneaux
   concernés.

5. **Pas de mode « remplacement complet »** : il n'y a rien à supprimer, un
   stand absent du fichier est simplement laissé tel quel.

6. **Une édition sans créneau refuse l'import en bloc** : les colonnes
   n'auraient rien sur quoi se poser. Le message renvoie vers la série ou la
   dérivation des créneaux depuis les stands.

## Conséquences

- Le fichier d'exemple n'est pas une ressource versionnée mais **la grille
  actuelle de l'édition**, rendue par l'API : elle est juste par construction
  et se réimporte telle quelle, ce qu'un test vérifie.
- Le rapport porte les colonnes autant que les lignes : c'est là que se lit une
  bande mal écrite ou un jour de trop.
- Le fichier voyage deux fois sur le réseau, comme pour les animateurs, et
  sous les mêmes plafonds.
