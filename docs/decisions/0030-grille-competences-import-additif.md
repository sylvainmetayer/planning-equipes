# 0030 — La grille des compétences transpose 0021 : identité par identifiant, case vide inchangée, jamais de suppression

- **Statut** : abandonné — l'échange de la grille par fichier est retiré, la saisie se fait à l'écran ; voir [0050](0050-identifiants-generes-par-edition.md) (D6)
- **Date** : septembre 2026
- **Portée** : référentiel, import de données, API, écran d'administration

## Contexte

Les appréciations d'un animateur — son niveau sur chaque typologie de jeu — se
saisissaient une fiche à la fois, ou en appliquant une même valeur à une
sélection. Pour cent cinquante animateurs et une dizaine de typologies, c'est
cent cinquante formulaires pour une saisie différenciée, alors que
l'organisateur tient cette information dans une matrice. La grille des
ouvertures de stands et l'import de la matrice des stands
([décision 0022](0022-import-de-la-grille-des-stands.md)) avaient déjà tracé le
chemin : une matrice éditable à l'écran, importable et exportable au format
tabulaire, sous le contrat de la
[décision 0021](0021-import-tabulaire-partiel-et-previsualise.md).

Trois questions restaient ouvertes en transposant : ce qu'une case vide du
fichier signifie, comment nommer une ligne, et comment le garde de
modification concurrente ([décision 0023](0023-modification-concurrente-par-horodatage.md))
s'applique à un enregistrement qui écrit plusieurs fiches.

## Décision

1. **Les cinq règles de 0021 s'appliquent** : import partiel qui ne supprime
   rien, analyse et écriture en deux appels dont le second reçoit le fichier,
   écriture atomique, fichier jamais écrit sur disque, aperçu ligne par ligne.

2. **L'identité d'une ligne est l'identifiant de l'animateur, et seulement
   lui.** Pas de résolution par le nom, contrairement à la grille des stands :
   deux bénévoles peuvent porter le même nom, et l'export ne porte ni nom ni
   prénom — l'identifiant suffit à retrouver la fiche, et un fichier qui ne
   nomme personne circule sans la précaution qu'un trombinoscope demande.
   Une ligne inconnue est rejetée ; l'import ne crée pas d'animateur, une
   fiche née d'une ligne de compétences n'aurait ni date de naissance ni
   identité.

3. **Une case vide du fichier laisse l'appréciation existante inchangée.**
   L'import ajoute et met à jour, il ne retire jamais — c'est la lecture de
   « partiel » de 0021, et ce que l'import CSV des animateurs fait déjà de sa
   colonne de compétences. L'alternative — une case vide retire
   l'appréciation — a été écartée avec le demandeur : un fichier partiel, une
   colonne oubliée ou un tableur qui a perdu une colonne effaceraient des
   appréciations en silence, et rien ne distinguerait « je ne sais pas » de
   « je retire ». Retirer reste un geste de la grille à l'écran, où la case
   vidée est visible avant d'être enregistrée. Conséquence assumée : un
   export réimporté sans changement n'écrit rien, et le rapport le dit ligne
   par ligne (« inchangée »).

4. **Une colonne sans typologie correspondante est ignorée et listée**, comme
   pour la grille des stands ; un niveau illisible rejette sa ligne en le
   nommant, plutôt que d'être lu comme vide.

5. **L'enregistrement depuis l'écran écrit une fiche par ligne modifiée,
   chacune avec sa propre précondition**, par le même chemin que le formulaire
   de la fiche — typologies validées, refus pendant une résolution, suivi de
   modification. La réponse est un compte rendu par ligne — écrite, périmée,
   refusée — et non un statut global : une fiche modifiée entre-temps par une
   autre session est refusée seule, les autres sont écrites, et l'écran offre
   pour elle le même choix que pour une fiche, recharger ou écraser. Un
   `409` global aurait annulé cent lignes correctes pour une seule périmée,
   ou obligé à réessayer sans savoir laquelle. Une ligne de l'écran remplace
   la carte complète des appréciations de la fiche, comme le formulaire :
   à l'écran, vider une case retire bien l'appréciation.

## Conséquences

- Deux sémantiques différentes pour une case vide, et c'est voulu : à l'écran
  elle retire, dans le fichier elle laisse. L'aide et le rapport d'import le
  disent ; la grille marque les cases modifiées avant l'enregistrement.
- L'export ne porte aucune donnée personnelle : il peut être envoyé, ouvert et
  retouché dans un tableur par quelqu'un qui n'a pas à connaître les noms.
- Les plafonds sont ceux de la grille des stands (1 000 000 de caractères,
  2 000 lignes) : une matrice porte une ligne par animateur.
- Le journal des actions distingue l'enregistrement de la grille de l'import
  du fichier : deux gestes, deux lignes d'historique.
