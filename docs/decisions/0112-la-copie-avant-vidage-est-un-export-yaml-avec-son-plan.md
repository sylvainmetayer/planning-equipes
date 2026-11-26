# 0112 — La copie avant vidage est un seul export YAML, plan compris

- **Statut** : accepté
- **Date** : novembre 2026
- **Portée** : format du scénario YAML, copie avant vidage (table, service), restauration
- **Remplace en partie** : [0084](0084-sauvegarder-l-edition-avant-de-la-vider.md) (le plan en colonne à part, et la restauration en gestes ordonnés qui lui est propre)
- **Prolonge** : [0101](0101-schema-yaml-2-et-chaine-de-conversions.md)

## Contexte

La décision 0084 range avant chaque vidage deux choses : l'export de scénario
de l'édition (texte YAML) et son planning, sièges et verrous, dans une colonne
JSON séparée. Restaurer, c'était donc deux lectures, l'import du scénario, puis
un code à part qui remet le planning et les verrous : un « geste ordonné » dont
l'échec en cours de route demandait un message et des règles de reprise
propres. 0084 avait écarté de porter les affectations dans le scénario, au
motif que l'import changerait de sens pour tous les fichiers.

La décision de l'organisation (Q38) est qu'une copie avant vidage soit **un
export YAML, restauré par l'import normal**.

## Décision

1. **Le plan est la dernière section du fichier** : `plan:`, avec `affectations`
   (un siège : stand, jour, heures, animateur, heures effectives, suite) et
   `verrous`. Les identifiants sont ceux du fichier, comme dans toute autre
   section ; un créneau se nomme par sa date et ses heures, la clé que les
   verrous stockaient déjà. La section est **facultative** : un export
   ordinaire n'en porte pas, et le format reste en version 3, puisqu'un fichier
   sans cette section se lit comme avant. Seule la copie avant vidage l'écrit.
2. **L'import d'une restauration applique cette section** : après le
   référentiel, les paramètres, les contraintes et les consignes, il remet les
   sièges, retrouvés par les clés de 0084 (code puis nom pour un stand,
   adresse puis prénom et nom pour un animateur, jour et heures pour un
   créneau), puis les verrous par l'écriture habituelle. Ce qui n'est pas
   retrouvé est listé par identifiants dans les avertissements du rapport.
   **Un import ordinaire l'ignore**, avec un avertissement : Fichiers › Importer,
   `importer_scenario_yaml` ou un exemple livré ne posent jamais de planning.
   Un planning posé par un fichier quelconque serait une écriture de sièges et
   de verrous de plus, hors des contrôles des autres ; la restauration, elle,
   ne lit qu'une copie que l'application a rangée elle-même. Alternative
   laissée ouverte : appliquer aussi le plan d'un import manuel, à condition
   de le faire passer par les mêmes contrôles que les écritures de plan.
3. **Restaurer, c'est sauvegarder l'état actuel puis importer le fichier**.
   Plus de code de restauration à part : le message d'échec dit si le
   scénario était encore en cours d'import ou déjà importé (le planning alors
   pas remis, ou en partie), et nomme la sauvegarde de l'état juste avant la
   tentative (0084, inchangé sur ce point). La restauration reste **non atomique**, parce
   que l'import l'est ; c'est la même propriété, plus un cas particulier.
4. **Les copies déjà rangées restent restaurables, sans rien réécrire.** Une
   copie d'une version 3.x garde son plan dans la colonne `plan`, que la
   migration `V147` rend simplement facultative et que l'application n'écrit
   plus. À la lecture (restauration, téléchargement), la colonne est relue
   comme la section `plan:` du même fichier. Aucune ligne n'est ni copiée, ni
   déplacée, ni supprimée ; le chemin de lecture de la colonne est retiré en
   5.0, avec le lecteur v1 (à confirmer : une migration qui réécrirait ces
   copies en YAML est possible, mais elle ne gagne rien tant qu'on les lit).
5. **Droits et durée inchangés** : seul un administrateur liste, télécharge,
   restaure ou supprime une copie ; aucune purge automatique, une copie
   disparaît avec son édition ou par suppression manuelle. Le fichier
   contenant noms, dates de naissance, adresses et téléphones des animateurs,
   sa durée de conservation est celle de l'édition.

## Conséquences

- Le code qui retrouve les sièges et les verrous est appelé par l'import ; il
  reste dans le service de la copie, avec ses tests, et ne se duplique pas.
- Une copie se télécharge et s'ouvre comme n'importe quel scénario, son plan
  en dernière section ; réimportée à la main, elle ne remet que le scénario.
- Les types de verrou de la section sont ceux de l'énumération du moment : un
  changement de ces types convertit la section par un maillon de la chaîne
  de 0101 (elle fait partie du document) et, pour les copies 3.x, la colonne
  à sa lecture, qui ne traverse pas la chaîne.
- Le schéma publié du scénario gagne la section `plan` ; elle n'est destinée à
  personne d'autre que la copie, et n'apparaît dans aucun exemple livré.
- Alternative écartée : un second format de fichier (ZIP de deux documents)
  qu'un import dédié lirait, qui aurait conservé le geste ordonné à part.
