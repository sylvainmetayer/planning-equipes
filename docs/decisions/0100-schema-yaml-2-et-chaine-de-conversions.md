# 0100 — Le YAML passe en version 2 : le lecteur garde la v1 par une chaîne de conversions

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : format du scénario YAML, import, sauvegardes avant vidage, archives de fin d'événement

## Contexte

La 4.0 change le modèle (rôles, plages d'horaires, noms de règles). Le fichier
de scénario est aussi ce qui sert de sauvegarde (ADR 0084), d'archive de fin
d'événement et de fixture : des fichiers écrits en 3.x existent chez les
opérateurs et dans le dépôt, et restaurer une ancienne sauvegarde doit rester
un geste ordinaire.

## Décision

1. **L'écriture ne produit que `schemaVersion: 2`.**
2. **Le lecteur garde la v1** (clé absente ou égale à 1) et applique, avant de
   lier le document, une chaîne ordonnée de maillons `n → n + 1`
   (`ScenarioMigrations`). Chaque maillon est une fonction pure avec son test ;
   chaque PR de modèle de la 4.x ajoute le sien.
3. **Ce qu'un maillon ne peut pas convertir est ignoré avec un avertissement**
   renvoyé dans le rapport d'import (`avertissements`), jamais en silence.
4. Un numéro supérieur à la version courante reste refusé (« format plus
   récent »). Le lecteur v1 est retiré en 5.0.

Le premier maillon (1 → 2) est vide : il pose la mécanique et le numéro.

## Alternatives écartées

- **Refuser la v1** : casserait l'import des archives, des copies avant vidage
  et des scénarios des opérateurs, et la restauration d'une ancienne
  sauvegarde cesserait d'être normale.
- **Convertir les fichiers sur disque une fois pour toutes** : ne couvre pas
  les fichiers qui sont chez les opérateurs.

## Conséquences

- Un scénario v2 ne se relit pas en 3.x : le guide de passage demande de garder
  un export v1 avant de monter.
- Le rapport d'import gagne la liste `avertissements` (REST ; l'outil MCP
  d'import ne la reprend pas encore).
