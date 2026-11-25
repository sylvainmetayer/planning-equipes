# 0103 — Retrait du lanceur de machine à la demande

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : résolution (`service/solve`), configuration, déploiement
- **Remplace en partie** : [0086](0086-le-calcul-deporte-un-worker-sans-conteneur-et-un-crochet.md) (la machine démarrée par un crochet HTTP ; le worker permanent et son contrat restent)

## Contexte

L'ADR 0086 a livré le calcul déporté sous deux formes : un worker permanent
connu par son adresse, ou une machine qu'un lanceur de l'exploitant crée à la
demande, derrière un crochet HTTP (démarrer, état, arrêter). La seconde forme
demandait à l'application de suivre le cycle de vie d'une machine louée : une
table, un minuteur d'inactivité, un décompte d'heures facturées, des arrêts
redemandés, un secret dérivé par machine, et un lanceur d'exemple à tenir à
jour contre un hébergeur réel. Aucune instance connue ne s'en sert.

## Décision

Le crochet et tout ce qui le servait sont retirés en 4.0 : `SOLVER_WORKER_HOOK_URL`,
`SOLVER_WORKER_HOOK_SECRET`, `SOLVER_WORKER_INSTANCE`, `SOLVER_WORKER_START_TIMEOUT`
et `SOLVER_WORKER_IDLE_TIMEOUT`, le lanceur d'exemple, les gestes « préparer » et
« arrêter » de l'écran Solveur. Le worker permanent (`SOLVER_WORKER_URL`), son
contrat, la reprise d'un calcul après un redémarrage et la règle qu'**un échec
distant fait échouer la tâche, sans repli sur place** sont conservés tels quels.

Les cinq variables sont lues au démarrage pour avertir qu'elles n'ont plus
d'effet, sans refuser de démarrer (comme pour [0102](0102-retrait-du-mode-en-tete-de-proxy.md)).
L'écran Solveur garde le choix « Sur place » ou « Sur la machine de calcul »,
puisqu'un worker permanent reste possible.

Qui loue une machine à l'heure l'allume et l'éteint avec les outils de son
hébergeur, et la déclare comme worker permanent.

## Alternatives écartées

- **Garder le crochet, sans le documenter** : du code que personne ne teste de
  bout en bout contre un hébergeur ne vaut pas son entretien.
- **Un lanceur intégré à l'application** : elle détiendrait un jeton capable de
  créer des machines, ce que 0086 refusait.

## Conséquences

- Une instance qui s'appuyait sur le lanceur doit déclarer son worker à la
  main (guide de passage à la 4.0). Sinon ses calculs tournent sur place.
- La table `machine_calcul` n'est plus ni lue ni écrite. Elle est laissée en
  place, hors de l'export SQL comme avant, et **une migration ultérieure la
  supprimera** : un schéma ne se défait pas dans la même version qu'un retrait
  de code, que l'on peut encore revenir.
- `GET /api/machine-calcul` reste, réduit à `configured` et `remoteByDefault` :
  l'écran Solveur en a besoin pour proposer « Sur place » ou « Sur la machine
  de calcul ». `POST /api/machine-calcul/preparer` et `/arreter` disparaissent.
