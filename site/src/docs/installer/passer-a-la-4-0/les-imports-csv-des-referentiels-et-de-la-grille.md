---
ordre: 246
---

### Les imports CSV des référentiels et de la grille des stands sont retirés

- **Avant (3.x)** : l'onglet Importer de *Fichiers* offrait sept imports : un CSV pour chacun des cinq référentiels courts (typologies, emplacements, stands, créneaux, journées types), la **grille des stands** (une matrice stands × créneaux, un effectif par case), le CSV des animateurs et le scénario YAML ; chaque écran de référentiel avait son bouton « Importer ».
- **Après (4.0)** : **le YAML est le classeur complet d'une édition, et le CSV ne sert plus qu'aux animateurs** ([décision 0127](repo:docs/decisions/0127-le-yaml-est-le-classeur-complet-le-csv-ne-sert-qu-aux-animateurs.md)). L'onglet Importer n'a plus que quatre cartes (Scénario, Animateurs, Exemples, Vérifier un fichier) ; un signet sur une carte retirée (`/fichiers?cible=stands`) tombe sur la carte Scénario. Les dix-huit routes retirées répondent **`410 Gone`**, quel que soit le corps, avec un message qui renvoie vers `POST /api/reference-data/import-scenario-fichier` : `…/typologies`, `…/emplacements`, `…/stands`, `…/creneaux` et `…/journees-types`, chacune en `/import-csv`, `/import-csv/analyse` et `/import-csv/exemple`, et `/api/stands/import-grille` avec ses sœurs `/analyse` et `/exemple`. Elles disparaissent en 5.0. **Les exports CSV ne changent pas** (l'archive de *Fichiers › Exporter* garde ses six fichiers) ; la grille de saisie des compétences, qui n'est pas un fichier, non plus. Aucun outil MCP ne portait ces imports.
- **Ce que vous faites** :
  - **Un script qui appelait l'une de ces routes** passe au YAML : il reçoit `410` et le message qui le dit. Pour les cinq référentiels, écrivez les sections `typologies`, `emplacements`, `stands`, `creneaux` et `gabarits` du fichier (le [format du scénario](../comprendre/scenario-yaml.md) les décrit, [le schéma](repo:docs/schema/scenario-schema.json) les valide, et la carte « Vérifier un fichier » les contrôle sans rien écrire). Un import YAML **remplace** l'édition au lieu de la compléter : partez d'un export de l'édition (*Fichiers › Exporter*, « Exporter les données actuelles en scénario »), modifiez-le, puis réimportez-le ; une copie de l'édition est gardée avant l'écriture.
  - **Refaire une grille de stands en YAML.** La matrice (stands en lignes, créneaux en colonnes, un effectif par case) s'écrit, pour chaque stand, une plage d'ouverture par case non vide, dans la liste `plages` du stand : la `date` et les heures de la colonne, et l'`effectif` de la case.

    ```yaml
    stands:
      - id: LUDOTHEQUE
        nom: Ludothèque
        typologiesProposees: [JEUX_DE_SOCIETE]
        effectifMin: 1
        effectifMax: 3
        plages:
          - { date: 2027-07-03, mode: OUVERTURE, heureDebut: '10:00', heureFin: '12:30', effectif: 2 }
          - { date: 2027-07-03, mode: OUVERTURE, heureDebut: '14:00', heureFin: '18:00', effectif: 3 }
    ```

    Une colonne du classeur qui s'étend sur plusieurs créneaux s'écrit une plage par créneau, ou une seule plage de la largeur de la colonne (une fenêtre à ses propres bornes, comme l'import de la grille la posait). Un tableur sait produire ces lignes : une colonne de formules qui assemble `{ date: …, mode: OUVERTURE, heureDebut: '…', heureFin: '…', effectif: … }` pour chaque case non vide, collée sous `plages:`. Pour un stand ouvert tous les jours aux mêmes heures, écrivez plutôt son **modèle** sous `horaires` (`mode: OUVERTURE`, `jours: TOUS`, `fenetres`) : l'application le déplie en plages sur les jours des créneaux.
  - **Sans écrire de YAML**, la grille se refait à l'écran : *Préparer › Calendrier*, onglet des horaires, vue « Saisie » (le même tableau stand × créneau, un effectif par case), ou la fiche de chaque stand.
  - **Les cinq CSV de référentiel** se refont de la même façon : créez les lignes à l'écran, ou écrivez la section du YAML. Les CSV exportés par la 3.x se relisent dans un tableur pour en copier les lignes dans le YAML, jamais dans l'application.
  - **L'historique** : les actions d'import retirées (typologies, emplacements, stands, grille, créneaux, journées types) n'ont plus de libellé ; les lignes déjà écrites au journal s'affichent sous leur code.
