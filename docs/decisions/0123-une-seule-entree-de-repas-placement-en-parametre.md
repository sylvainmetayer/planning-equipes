# 0123 — Une seule entrée « coupure repas », son placement préféré en paramètre de l'édition

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : catalogue des règles, écran des règles, paramètres de l'édition (migration `V165`), scénario YAML, API et outil MCP `modifier_parametres`, moteur direct
- **Applique** : [0111](0111-le-niveau-d-une-regle-est-le-niveau-de-son-poids.md) (le niveau est celui du poids), mesure du banc des règles de repas (`MealRulesBenchTest`)

## Contexte

Deux règles portaient la coupure repas : `coupureRepasObligatoire` (la coupure due, dure
par défaut, de niveau réglable) et `coupureRepasPlacementPrefere` (l'endroit de la fenêtre
où elle est prise, souple). L'organisation attendait une seule règle, le placement devenant
un paramètre. Le banc mesure trois façons de la faire, sur les six scénarios livrés, aux
deux moteurs :

- **Placement au niveau dur** : aucun plan faisable sur quatre scénarios sur six, aux deux
  moteurs. Une préférence d'agrément n'est pas une obligation.
- **Coupure et placement ensemble au niveau medium** : la coupure n'est plus tenue sur
  plusieurs scénarios, parce que la préférence se paie au même prix qu'elle.
- **Placement supprimé** : la faisabilité reste entière, le medium bouge de moins de 3 %
  au moteur direct, mais l'organisation perd un réglage qu'elle utilise.

## Décision

1. **Une seule entrée à l'écran et dans le catalogue servi, pas une seule règle de score.**
   « Coupure repas » (`coupureRepasObligatoire`) est la ligne de l'écran des règles : son
   niveau reste réglable, dur par défaut, et elle porte le lien vers le paramètre de
   placement. `coupureRepasPlacementPrefere` reste une contrainte, avec son poids et son
   interrupteur, mais devient un **terme** de la première (`ConstraintCatalog.TERMES`,
   `termeDe` dans la vue de l'API) : l'écran ne lui donne plus de ligne.
2. **Le terme reste souple, de poids 1, sans niveau** (0111, point 3) : un niveau n'a pas
   de sens sur une préférence, et c'est ce qui rendait les deux fusions de niveau
   impraticables.
3. **Le placement est un paramètre de l'édition**, `placementRepas`, dans la section
   « Organisation » des paramètres (table `parametres_qualite`, colonne `placement_repas`,
   précondition `modifie_le` comme le reste de la section). Quatre valeurs :
   `FIN_MIDI_DEBUT_SOIR` (défaut : le placement actuel de chaque fenêtre, tard le midi, tôt
   le soir), `DEBUT`, `FIN`, `AUCUN`. Il est appliqué aux faits `FenetreRepas` quand le
   problème est construit, une seule fois et au même endroit pour les deux moteurs.
4. **Aucun score ne bouge avec le défaut** : `FIN_MIDI_DEBUT_SOIR` laisse chaque fenêtre
   telle qu'elle était. La migration donne cette valeur à toute édition existante.
5. **Le moteur direct écrit le même terme** : une fenêtre « sans placement » (`AUCUN`)
   coûte zéro dans les deux écritures ; `HandSearchDifferentialTest` joue chaque valeur et
   exige que le terme ait mordu pour toutes sauf `AUCUN`.

## Options écartées

- **Une règle unique à un seul niveau.** Mesurée : infaisable, ou une coupure non tenue.
- **Un second niveau réglable sur le placement.** Le même défaut : « obligatoire » ne veut
  rien dire sur un agrément (0111).
- **Retirer `coupureRepasPlacementPrefere` du catalogue.** Ses poids, interrupteurs et
  planchers sont tenus par nom, dans les éditions et les scénarios déjà écrits : le nom
  reste, on ne le montre plus comme une règle.

## Conséquences

- Rien ne disparaît côté stockage ni côté scénario : un fichier qui nomme
  `coupureRepasPlacementPrefere` dans `contraintes:` est lu comme avant. Un fichier sans
  `placementRepas` garde le défaut.
- Une édition qui veut « pas de préférence » le dit par `AUCUN` au lieu de désactiver la
  règle ; désactiver le terme reste possible et équivaut.
- La migration reprend en `AUCUN` le placement d'une édition qui avait désactivé le terme,
  et retire l'interrupteur devenu muet, pour que l'écran dise ce que fait le plan. Une
  édition qui n'a jamais enregistré ses paramètres d'organisation garde son interrupteur :
  ses valeurs viennent de la configuration de l'instance, qu'une migration ne lit pas.
- Un poids propre à l'édition sur le terme continue de s'appliquer, mais l'écran ne le
  montre plus : le terme est de poids 1 par décision, et le poids ne se règle que par l'API
  ou un fichier.
