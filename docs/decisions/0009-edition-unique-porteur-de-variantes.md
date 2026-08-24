# 0009 — L'édition est l'unique porteur de variantes

- **Statut** : accepté, implémenté — révise [0001](0001-cloisonnement-par-edition.md), remplace [0008](0008-file-sequentielle-avant-parallelisme.md)
- **Date** : août 2026
- **Portée** : persistance, domaine, service, frontend

## Contexte

Le modèle comptait deux niveaux de cloisonnement : l'**édition**, qui portait le
référentiel complet, et le **groupe de créneaux**, censé porter les variantes de
planning à l'intérieur d'une édition.

Le besoin réel qui a servi de révélateur : basculer, au sein d'une même édition,
d'un plan nominal à un plan dégradé — horaires différents par stand, effectifs
modifiés, et potentiellement d'autres modifications imposées par une autorité
selon les informations reçues. Autrement dit **un delta arbitraire sur le
référentiel**, pas un ensemble d'attributs connu d'avance.

Or le groupe de créneaux ne cloisonnait **que les créneaux**. Stands, horaires
récurrents, animateurs et paramètres restaient partagés par l'édition. Importer
un second scénario dans la même édition écrasait donc silencieusement le
référentiel du premier : les deux groupes se retrouvaient à mélanger des données
qui n'allaient pas ensemble, sans qu'aucun écran ne soit en cause — le modèle
lui-même le permettait.

## Options envisagées

| Option | Verdict |
| --- | --- |
| Cloisonner davantage d'attributs par groupe | Course perdue : les horaires aujourd'hui, les effectifs demain, les contraintes ad hoc après. Un delta arbitraire ne se rattrape pas attribut par attribut. |
| Un seul scénario vivant par édition, réimporté à chaque bascule | Tenable, mais laisse le groupe de créneaux exister sans qu'il porte plus rien |
| **L'édition devient l'unique porteur de variantes** | Retenu |

## Décision

**Supprimer la notion de groupe de créneaux.** Une variante est une édition ; le
découpage remplace les créneaux en place, dans l'édition courante.

Ce qui a emporté la décision n'est pas l'élégance mais un constat de
duplication : affectations, résolution mémorisée, verrouillages, paramètres —
**tout était déjà cloisonné par édition**. L'édition-variante donnait donc
nativement « chaque variante garde son planning résolu », là où le groupe de
créneaux ne l'obtenait qu'au prix d'une machinerie de compensation : instantanés
par groupe, file de résolution, bandeau de décalage. Le groupe était une
demi-mesure, et on supprime la demi-mesure plutôt que de l'étayer.

Une objection technique avait d'abord été opposée à ce modèle — la duplication
d'édition dupliquerait les jetons d'accès des animateurs, invalidant les liens
déjà diffusés. **Elle était fausse** : chaque copie reçoit un jeton neuf, et les
indisponibilités suivent. C'est la vérification de cette objection, et non un
argument de principe, qui a fait basculer la décision.

## Conséquences

- Une bascule de variante se prépare la veille : dupliquer l'édition, appliquer
  le delta, résoudre de nuit, basculer le matin. Elle se conclut nécessairement
  par une rediffusion des plannings — sans quoi les animateurs consultent un
  plan périmé.
- Toute la machinerie par groupe disparaît : schéma, CRUD, écrans, file de
  résolution, bandeau de décalage. [0008](0008-file-sequentielle-avant-parallelisme.md)
  devient sans objet.
- Un audit de la duplication d'édition s'imposait, et a révélé plus grave que ce
  qui était cherché : les règles d'horaires récurrentes n'étaient pas copiées du
  tout. Une édition dupliquée retombait silencieusement sur « ouvert sur chaque
  créneau », ce qui aurait faussé la bascule dès sa deuxième étape. **Corrigé
  avant le reste** — une fonctionnalité bâtie sur une duplication incomplète
  aurait produit des plannings faux sans jamais lever d'erreur.
- Coût accepté : plus de variantes de découpage à l'intérieur d'une édition sans
  dupliquer le référentiel. C'est le prix d'un modèle à une seule notion.
