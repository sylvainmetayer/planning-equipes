# 0038 — La date de dernière mutation du référentiel descend en base

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : instantanés de plan, référentiel, IHM, MCP

Révise, sur ce seul point, l'inventaire de
[0001](0001-cloisonnement-par-edition.md), qui rangeait
`ReferenceDataChangeTracker` avec `ConstraintAnalysisStore` du côté de la
mémoire vive.

## Contexte

`PlanSnapshotResource.restaurer` remettait un instantané en plan courant sans
rien dire de son âge, et la page Instantanés n'affichait que `cree_le`. Un
administrateur restaurait donc en confiance un plan calculé avant l'ajout d'un
stand ou la suppression d'un animateur : le planning remis en place annulait en
silence la prise en compte de tout ce qui avait changé depuis (#170).

L'indice existait déjà, mais il ne pouvait pas servir à ça.
`ReferenceDataChangeTracker` gardait la date de dernière mutation du
référentiel dans une `ConcurrentHashMap` indexée par édition, et n'alimentait
que le bandeau `dataStale` du shell. Le choix de ne pas la persister était
délibéré et écrit : indice best-effort, muet après redémarrage, rien n'en
dépendait.

C'est cette dernière proposition qui a cessé d'être vraie. Sur un bandeau, un
indice muet ne coûte rien — l'utilisateur n'apprend simplement rien. Devant un
bouton *Restaurer*, une carte vide se lit « jamais modifié », donc « à jour » :
après chaque redémarrage, **tout** instantané passerait pour frais, et le badge
affirmerait précisément l'inverse de ce que l'issue veut éviter. Un « à jour »
faux y est pire que pas de badge du tout.

## Options envisagées

**(A) Rester en mémoire, badge assumé muet après redémarrage.** C'est ce que
proposait le corps de l'issue (« commencer best-effort »). Écarté : le badge ne
serait pas *muet* après un redémarrage, il serait *faux*. Une absence
d'information et une affirmation erronée ne se distinguent pas dans une carte
vide — il aurait fallu un troisième état « fraîcheur inconnue », qui ramène
l'utilisateur exactement là où il était et ajoute un cas à chaque lecteur.

**(B) Recalculer la fraîcheur à la lecture**, en prenant le `max(modifie_le)`
des six référentiels à chaque affichage. Écarté : six agrégats par liste
d'instantanés, et surtout une suppression de fiche ne laisse aucune ligne — la
mutation la plus dangereuse pour une restauration serait la seule invisible.

**(C) Une colonne `reference_modifie_le` sur `edition`**, écrite par le seul
`markModified()` que tous les services de référentiel appellent déjà.

## Décision

(C), avec quatre règles.

1. **Un seul point d'écriture, aucun appelant à toucher.** Les trente-sept
   `changeTracker.markModified()` du référentiel sont tous *après* le commit
   de leur écriture : une écriture refusée ne marque rien, et une transaction
   annulée après coup ne peut que faire passer un instantané pour périmé à
   tort — le sens prudent.
2. **Pas de cache mémoire devant.** La base devient la seule source, y compris
   pour les autres éditions : le comparateur A/B liste les instantanés de
   toutes les éditions (#70), ce qu'une carte indexée sur l'édition *courante*
   ne sait pas répondre. La fraîcheur se lit donc par instantané, dans la même
   jointure `edition` que le nom de l'édition.
3. **L'existant est repris à la migration**, depuis le `max(modifie_le)` des
   six référentiels ([0023](0023-modification-concurrente-par-horodatage.md)) :
   sans quoi toute édition antérieure au déploiement afficherait « à jour »
   jusqu'à sa prochaine écriture, c'est-à-dire le faux positif qu'on vient
   d'écarter. Limite assumée, celle de (B) : une fiche supprimée n'a laissé
   aucune ligne, donc la date reprise est celle de la dernière écriture
   *survivante*. Elle ne vaut que pour les éditions existant au déploiement.
4. **Le refus est une question, pas un mur.** Restaurer un instantané périmé
   répond `409` sans rien écrire, et se rejoue avec `forcer` : le plan est
   restaurable, il ne décrit simplement plus le référentiel d'aujourd'hui.
   `forcer` ne lève que ce refus-là — une référence disparue reste refusée,
   et elle est vérifiée en premier, un instantané étant le plus souvent périmé
   *parce que* des ids ont disparu : nommer lesquels est la seule information
   actionnable. La garde vit dans le service, comme celle de la résolution en
   cours : l'API et l'outil MCP la partagent plutôt que d'en tenir chacun une.

## Conséquences

- Le badge « à jour / périmé » de la page Instantanés, l'avertissement du
  comparateur et les vues MCP lisent tous le même booléen, calculé une fois
  dans `SnapshotMeta`.
- `markModified()` écrit désormais en base, dans sa propre transaction. Un
  échec n'est pas avalé : la marque est ce sur quoi une restauration est jugée.
- Le bandeau `dataStale` y gagne accessoirement de survivre à un redémarrage.
  Ce n'était pas le but ; c'est la raison pour laquelle rien d'autre n'a été
  ajouté pour lui.
- `ConstraintAnalysisStore` reste en mémoire vive : rien ne dépend de sa
  justesse au point de transformer une absence en affirmation — la première
  lecture après un redémarrage la redérive du plan persisté
  ([0014](0014-analyser-le-plan-persiste.md)).
