# 0007 — Instantanés de plan : un contenu dénormalisé, pas une copie de lignes

- **Statut** : accepté, implémenté
- **Date** : août 2026
- **Portée** : persistance, service, API, frontend

## Contexte

Il n'existait qu'**un seul plan persisté**, écrasé à chaque résolution. La table
qui mémorisait la dernière résolution était contrainte à une ligne unique
(`id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1)`), et rien ne permettait
d'indexer plusieurs plans.

Conséquence concrète : on résolvait sur une variante, on basculait sur une
autre, on relançait — et le premier plan était définitivement perdu. Un bandeau
signalait l'incohérence sans jamais l'éviter. Le seul contournement était
d'exporter avant chaque bascule.

## Options envisagées

| Option | Ce qui se passe quand la variante d'origine disparaît |
| --- | --- |
| Copier les lignes d'affectation dans une table d'archive | L'archive référence les créneaux par clé étrangère : **elle meurt avec eux** |
| Sérialiser le plan complet, dénormalisé, dans une colonne JSONB | L'instantané survit, il ne dépend plus de rien |

## Décision

**Le contenu dénormalisé.** C'est le point de conception qui décide de tout le
reste : une copie de lignes serait morte exactement dans le cas d'usage visé —
conserver le plan d'une variante qu'on abandonne.

L'instantané porte donc son plan sérialisé, plus les libellés dont il a besoin
pour rester lisible après la disparition de ce qu'il désigne. Il est capturé
automatiquement avant chaque résolution, ce qui protège aussi l'utilisateur qui
n'a pas pensé à enregistrer.

La restauration échoue proprement, en listant ce qui manque, plutôt que de
restaurer un plan à moitié valide.

## Conséquences

- La dénormalisation duplique de l'information. C'est le prix de la survie de
  l'instantané, et il est assumé : un instantané est une photographie, pas une
  vue.
- Une politique de purge devient nécessaire — sans elle, les captures
  automatiques s'accumulent indéfiniment.
- L'existence de plusieurs plans comparables est le prérequis naturel d'un
  comparateur A/B, qui se réduit dès lors à un écran de différences.

## Annexe — ce que la contrainte de ligne unique interdisait

| Avant | Après |
| --- | --- |
| Un plan persisté, écrasé à chaque résolution | Autant d'instantanés que nécessaire, plus le plan courant |
| Résolution mémorisée dans une table à ligne unique | Une entrée par capture, horodatée et libellée |
| Bascule de variante = perte du plan précédent | Bascule = restauration d'un instantané |
| Comparer deux résolutions : impossible par construction | Comparaison = différence entre deux instantanés |
