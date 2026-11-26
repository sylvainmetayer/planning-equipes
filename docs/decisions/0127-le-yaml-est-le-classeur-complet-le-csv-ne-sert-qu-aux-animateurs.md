# 0127 — Le YAML est le classeur complet, le CSV ne sert qu'aux animateurs

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : imports de fichiers (API, écran Fichiers, aide), routes de la 3.x, journal des actions
- **Remplace** : [0022](0022-import-de-la-grille-des-stands.md), l'import de la grille des stands, et l'import CSV des cinq référentiels courts ; [0021](0021-import-tabulaire-partiel-et-previsualise.md) est gardé

## Contexte

La 3.x offrait sept façons de remplir une édition depuis un fichier : un CSV
par référentiel court (typologies, emplacements, stands, créneaux, journées
types), la matrice des stands, le CSV des animateurs et le scénario YAML.
Les six premiers avaient chacun leur format, leur aperçu, leurs règles de
reconnaissance d'une ligne et leur écran ; le YAML, lui, décrit déjà l'édition
entière, dans l'ordre où l'application l'applique, et se contrôle avant d'écrire.
Six formats pour ce que le YAML dit déjà, c'était six formats à garder
d'accord avec le modèle à chaque changement (les plages datées, les gabarits),
et un organisateur qui ne savait pas lequel prendre.

## Décision

1. **Le YAML est le classeur complet d'une édition** : on l'exporte, on le
   corrige, on le réimporte. Il remplace l'import de chaque référentiel court
   et celui de la grille des stands (ses horaires s'écrivent en `plages`).
2. **Le CSV des animateurs reste**, et lui seul : c'est le tableur que les
   organisateurs tiennent déjà, il porte des personnes, et son format
   (correspondance de colonnes, doublons probables, mineurs) n'a pas
   d'équivalent à écrire à la main.
3. **Les exports CSV restent**, tous : ils servent à un tableur ; seul celui des
   animateurs se réimporte.
4. **Les dix-huit routes retirées répondent `410 Gone`** (`BusinessError.Gone`),
   quel que soit le corps, avec un message qui renvoie vers l'import YAML,
   plutôt que `404` : un script de la 3.x apprend où aller. Elles ne lisent ni
   n'écrivent rien, et n'ont donc plus d'entrée au catalogue des actions
   journalisées (ni code d'action : les lignes déjà écrites au journal sous
   les anciens codes s'affichent par leur code). Elles disparaissent en 5.0.
5. **Sur l'écran**, les cartes retirées de l'onglet Importer disparaissent ; un
   signet qui les nomme (`?cible=stands`) tombe sur la carte Scénario. Aucun
   outil MCP ne les portait : la table des renommages n'a pas de ligne à ajouter.
6. **La grille des compétences est gardée** : ce n'est pas un import de fichier
   (elle se saisit à l'écran, [0030](0030-grille-competences-import-additif.md)).

## Conséquences

- Moins de code à tenir d'accord avec le modèle : `StandGrilleImportService`,
  `ReferentielCsvImportService` et `GrilleCsv` s'en vont, avec leurs tests.
- Un opérateur qui préparait sa grille de stands dans un classeur la refait en
  YAML : le guide « Passer de la 3.x à la 4.0 » dit comment.
- Un organisateur qui ne veut pas écrire de YAML saisit à l'écran (les listes,
  la grille de saisie des ouvertures) ou part d'un export.

## Alternatives écartées

- **Garder les CSV et retirer la seule grille** : six formats à maintenir pour
  un gain que le YAML donne déjà.
- **Retirer sans réponse** (`404`) : un script de la 3.x échouerait sans dire
  pourquoi.
