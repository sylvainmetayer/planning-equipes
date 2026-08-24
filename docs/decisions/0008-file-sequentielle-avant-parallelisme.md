# 0008 — Résoudre les variantes en file séquentielle avant d'envisager le parallélisme

- **Statut** : accepté puis **retiré** — remplacé par [0009](0009-edition-unique-porteur-de-variantes.md)
- **Date** : août 2026
- **Portée** : service, solveur

## Contexte

Chaque variante de planning devait disposer en permanence de son propre plan
résolu, pour qu'en changer n'impose pas d'attendre une nouvelle résolution.

L'intuition de départ était de lancer plusieurs solveurs en parallèle, un par
variante. L'analyse a montré que l'obstacle n'était pas le solveur mais la
persistance — un seul plan pouvait être persisté à la fois — et que le
parallélisme se heurtait par ailleurs à des limites matérielles mesurables.

## Options envisagées

- **Parallélisme immédiat** — impose de lever trois verrous de persistance et
  une question de dimensionnement mémoire, avant tout bénéfice utilisateur.
- **File séquentielle** — enchaîner les résolutions et conserver le dernier
  instantané de chacune. Aucun changement de schéma, aucun risque mémoire, et
  la bascule devient une restauration d'instantané, chemin déjà existant.

## Décision

**La file séquentielle d'abord**, le parallélisme seulement si le temps total
devient limitant.

Le raisonnement tient dans les mesures ci-dessous : sur une machine à quatre
cœurs, **un seul** solveur consomme déjà près de quatre cœurs. Le parallélisme
n'aurait donc pas divisé le temps par le nombre de variantes, tout en
multipliant l'empreinte mémoire — pour un gain hypothétique.

## Pourquoi cette décision a été retirée

La file a été livrée puis **supprimée le jour même**. Non parce qu'elle
fonctionnait mal, mais parce que [0009](0009-edition-unique-porteur-de-variantes.md)
a supprimé la notion même qu'elle orchestrait : les variantes ne sont plus des
groupes de créneaux à l'intérieur d'une édition, ce sont des éditions. Le
problème que la file résolvait n'existe plus.

Le raisonnement reste consigné parce qu'il conserve sa valeur — les mesures
ci-dessous s'appliquent à toute réflexion future sur le parallélisme.

## Annexe — mesures

Relevées sur l'application packagée, avec le scénario `scenario-complet`
(versionné dans `src/main/resources/scenarios/`), sur une machine à 4 cœurs.

| Mesure | Valeur |
| --- | --- |
| Mémoire résidente de la JVM au repos | 277 Mo |
| Mémoire résidente pendant une résolution | ~717 Mo |
| Tas utilisé pendant une résolution | 50 à 230 Mo, en dents de scie |
| CPU du processus pendant **une seule** résolution | **390 %** |

Deux enseignements, tous deux contre-intuitifs :

1. **Environ 450 Mo par solveur.** Sur une cible modeste — un nano-ordinateur,
   par exemple — deux ou trois résolutions simultanées posent un problème de
   dimensionnement réel, pas théorique.
2. **Les cœurs ne sont pas disponibles.** L'évaluation des mouvements est
   pourtant mono-thread : c'est le ramasse-miettes qui sature la machine, vu le
   taux d'allocation. Lancer N solveurs ne diviserait pas le temps par N.
