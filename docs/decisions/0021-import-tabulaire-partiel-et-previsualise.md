# 0021 — L'import tabulaire d'animateurs est partiel, prévisualisé et rejoué

- **Statut** : accepté
- **Date** : août 2026
- **Portée** : import de données, API, écran d'administration, RGPD

## Contexte

Les deux entrées de données existantes remplacent : le scénario YAML réécrit
tout le référentiel de l'édition, le dump SQL rejoue une base entière. Aucune
des deux ne répond à la situation la plus banale du démarrage d'une édition —
un organisateur arrive avec le tableur de ses bénévoles, et veut le verser dans
l'application sans écraser ce qu'elle contient déjà.

Trois contraintes se sont imposées avant même d'écrire une ligne.

**Les jours d'indisponibilité importés ne sont pas des données neutres.** Le
formulaire de l'espace animateur ne dessine que les jours de l'événement,
c'est-à-dire les dates distinctes des créneaux existants ; et l'acceptation
d'une déclaration remplace la totalité des jours de la fiche. Un jour importé
hors de ces dates est donc stocké, invisible pour l'animateur, puis effacé
sans un mot à la première déclaration acceptée. Le cas est loin d'être
théorique : l'ordre naturel est d'importer le tableur **avant** de saisir la
grille de créneaux, et l'événement n'a alors aucune date à opposer.

**Un fichier de 150 bénévoles porte des homonymes.** Reconnaître une ligne
« Jean Martin » comme la fiche « Jean Martin » déjà en base est juste dans 149
cas et faux dans le cent-cinquantième, sans que personne ne le voie.

**Le fichier porte des données personnelles**, dates de naissance et mineurs
compris, et l'application n'écrit sur disque qu'à un seul endroit — les
sauvegardes.

## Options envisagées

**Reprendre la sémantique du YAML : le fichier remplace tout.** Cohérent avec
l'existant, et une seule règle à expliquer. Mais l'usage n'est pas le même : le
YAML décrit un événement complet et versionné, le tableur est une liste
partielle, souvent à trois colonnes, souvent envoyée en rattrapage. Un
remplacement effacerait les compétences saisies à l'écran, les adresses, et les
jours que les animateurs ont déclarés eux-mêmes pendant la collecte.

**Prévisualiser et écrire dans le même appel**, en laissant le client
n'appliquer que ce qu'il a affiché. Une requête de moins, et un écran plus
simple. Mais il n'y a alors aucun instant où l'opérateur puisse dire non, et le
serveur écrirait ce que le navigateur lui renvoie : un rechargement de page ou
un corps fabriqué à la main suffirait à faire passer une ligne devant un
contrôle.

**Accepter les jours hors événement** en assumant qu'ils seront effacés plus
tard. Le rapport dirait « tout est passé » et l'organisateur découvrirait le
contraire des semaines après, sans rien pour relier les deux.

**Ajouter une bibliothèque de lecture de tableurs** pour accepter aussi le
`.xlsx`. Écarté : le dépôt n'ajoute pas de dépendance sans accord explicite, et
un lecteur CSV conforme à la RFC 4180 tient en cent lignes pures, testables
sans conteneur. Le classeur déposé par erreur est reconnu et renvoie la marche
à suivre.

## Décision

1. **L'import est partiel par défaut.** Il ajoute et met à jour, ne supprime
   personne, et une colonne non associée ne touche pas le champ correspondant
   sur une fiche existante. Le remplacement complet reste disponible, sur une
   case à cocher — et il est refusé tant qu'une ligne est rejetée, sinon il
   supprimerait justement les personnes que ces lignes n'ont pas su décrire.

2. **Les jours d'indisponibilité sont ajoutés, pas substitués**, sauf demande
   explicite. Un jour hors des dates de créneaux **rejette la ligne**, avec son
   motif au rapport — la même règle que la déclaration applique déjà. Et une
   édition sans aucun créneau **refuse l'import en bloc** : sans dates de
   référence, rien n'est ni vérifiable ni affichable.

3. **L'identité se lit dans cet ordre : identifiant, e-mail, prénom + nom.**
   Un nom qui désigne deux fiches ne se tranche pas, il **rejette la ligne** en
   nommant les identifiants candidats. Deviner serait juste presque toujours,
   et une erreur d'attribution de planning ne se voit qu'au dernier moment.

4. **L'analyse et l'écriture sont deux appels, et le second reçoit le
   fichier.** Il le relit et rejoue toutes les vérifications. Le rapport
   affiché n'a aucune autorité : il informe l'opérateur, il n'instruit pas le
   serveur.

5. **L'écriture est atomique.** Les lignes acceptées et les suppressions
   éventuelles partent dans une seule transaction, parce que le rapport est une
   promesse : un compte rendu annonçant 148 lignes après un retour arrière
   serait un mensonge que rien ne rattraperait.

6. **Le fichier ne touche jamais le disque.** Il est reçu dans le corps de la
   requête, lu en mémoire, et rendu avec elle.

## Conséquences

- Retirer une compétence ou un souhait reste un geste de l'écran de
  référentiel : l'import ne fait qu'ajouter. C'est assumé — un fichier partiel
  ne doit pas faire désapprendre.
- La date de naissance est obligatoire à la création. Elle l'était déjà en base,
  mais surtout tout le régime mineur / majeur s'en déduit : une fiche sans elle
  passerait sous le régime adulte sans un mot.
- Le fichier voyage deux fois sur le réseau, une fois par appel, et à chaque
  changement de correspondance de colonnes. C'est le prix de la règle 4, et il
  est borné par un plafond explicite de taille et de nombre de lignes.
- Le fichier source reste chez l'organisateur, et c'est la copie que le
  registre des traitements doit consigner : l'application ne peut rien garantir
  au-delà de sa propre mémoire.
- Étendre le mécanisme aux stands ou aux créneaux reste ouvert. Les cinq règles
  ci-dessus se transposent ; seule l'identité changerait.
