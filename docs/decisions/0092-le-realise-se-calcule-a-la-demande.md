# 0092 — Le réalisé se calcule à la demande, sans job de nuit

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : analyse (Réalisé vs planifié), planification (`@Scheduled`)
- **Remplace** : [0070](0070-un-troisieme-job-planifie-fige-le-realise.md)
- **Prolonge** : [0074](0074-deux-jobs-planifies-de-plus-webhooks-et-meteo.md)

## Contexte

0070 avait ajouté un `@Scheduled` qui, chaque nuit, écrivait la mesure
« Réalisé vs planifié » de toute édition terminée dans `kpi_realise`. Le
besoin n'a pas changé : l'édition suivante lit cette mesure, qui doit survivre
à l'édition qu'elle décrit. Ce qui coûtait : une tâche de plus à régler
(`REALISE_CRON`, `REALISE_TIMEZONE`), à surveiller et à tester, pour une
écriture qui n'a lieu qu'une fois par édition, et dont le calcul existe déjà
à la demande derrière l'écran.

## Décision

Plus de job. `RealisedVsPlannedService` calcule à la demande, et la première
lecture faite une fois le dernier créneau de l'édition terminé, quand quelque
chose a été publié et que le passé figé est actif, écrit `kpi_realise` comme
un **cache** : même règle « écrit une fois » (`ON CONFLICT DO NOTHING`), même
table, mêmes lignes — celles déjà figées par l'ancien job sont lues telles
quelles, aucune migration. La comparaison reste celle de
`ChangementsJourneeService.compareSeats`. Un échec d'écriture est journalisé et
n'empêche pas la réponse.

## Conséquences

- L'application compte quatre `@Scheduled` : sauvegarde, envois de nuit,
  reprise des webhooks, alerte météo.
- La mesure n'existe qu'après une première lecture une fois l'événement fini :
  une édition que personne n'ouvre ensuite n'a pas de mesure à offrir à la
  suivante. Cette latence est celle d'un calcul, déjà payé à chaque ouverture
  de l'écran.
- Les variables `REALISE_CRON` et `REALISE_TIMEZONE` disparaissent ; les
  laisser dans un environnement est sans effet.
- Refiger reste une suppression SQL des lignes de l'édition, jamais une route.

## Alternatives écartées

- **Garder le job et l'écran** : deux chemins pour la même écriture.
- **Écrire à chaque lecture** : la mesure bougerait avec chaque correction
  tardive, ce que « écrit une fois » interdit.
