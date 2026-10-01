# 0070 — Un troisième job planifié fige le réalisé de fin d'événement

- **Statut** : accepté, implémenté — **révisé par [0074](0074-deux-jobs-planifies-de-plus-webhooks-et-meteo.md)** : ce job n'est plus le dernier, la reprise des webhooks et l'alerte météo en ajoutent deux
- **Date** : octobre 2026
- **Portée** : planification (`@Scheduled`), historique inter-éditions
- **Prolonge** : [0015](0015-sauvegarde-par-pg-dump-restauration-hors-application.md), [0044](0044-le-passe-est-fige.md)

## Contexte

L'écran « Réalisé vs planifié » mesure, par stand et par journée écoulée,
l'écart entre le plan publié et le plan tenu : absences, remplacements, sièges
restés vides, heures perdues. Cette mesure sert pendant l'événement, mais
surtout après : c'est l'édition **suivante** qui en a besoin, pour savoir
quels stands et quelles typologies perdent du monde au moment de dimensionner
son besoin.

Deux faits rendent la question non triviale :

- une édition ne stocke pas ses dates. L'événement court du premier au dernier
  créneau de l'édition ; « la fin de l'événement » n'est donc un instant que
  pour qui lit les créneaux, et personne ne clique sur un bouton « événement
  terminé » ;
- la mesure doit **survivre à l'édition qu'elle décrit**, comme l'historique
  des KPI : l'édition suivante est souvent une copie, et l'ancienne finit
  supprimée.

L'application avait jusqu'ici deux `@Scheduled` — la sauvegarde de nuit (0015)
et les envois de nuit — et la purge de l'historique des actions avait été
greffée sur le second plutôt que d'en créer un troisième.

## Décision

**Un troisième job planifié, `RealisedFreezeJob`, passe chaque nuit et fige,
une fois pour toutes, la mesure de chaque édition terminée** dans une table
dédiée, `kpi_realise` : une ligne par typologie et une pour l'événement
entier, des comptes et des minutes, aucune personne.

- **Quand une journée est « écoulée »** : quand son **dernier créneau est
  terminé**, à l'horloge du passé figé (0044, celle de la machine en
  production) — pas à minuit, qui compterait une journée dont la vacation de
  nuit est encore tenue. Elle **commence** à son premier créneau, comme le
  mode jour J la compte : la publication en vigueur à cet instant est sa
  référence, si bien qu'une republication à 7 h avant une ouverture à 9 h est
  ce que la journée s'est vu promettre.
- **Quand une édition est « terminée »** : son dernier créneau est terminé.
  Une édition jamais publiée n'a pas de référence : rien n'est écrit, plutôt
  qu'une ligne de zéros.
- **Une seule écriture** : une édition qui a déjà ses lignes est sautée. La
  mesure est celle de la première nuit qui suit l'événement, et `fige_le` dit
  quand ; une modification ultérieure de l'édition — une absence saisie le
  lendemain, un créneau supprimé — ne la réécrit pas. L'insertion porte
  `ON CONFLICT DO NOTHING` : deux passages concurrents n'écrivent qu'une fois,
  et seul celui qui a écrit trace `REALISE_FIGE` dans l'historique des
  actions. Pour refiger une édition, un exploitant supprime ses lignes en SQL
  (`docs/exploitation.md`) : la nuit suivante les réécrit. Il n'y a pas de
  route pour le faire — c'est un geste rare, qui efface une mesure dont
  l'édition suivante a besoin.
- **Pas sans passé figé** : une édition dont le rapport dit le passé figé
  désactivé (`PASSE_FIGE=false`) est sautée, et le job l'écrit au journal.
  Une résolution a pu réécrire ses journées écoulées ; une mesure écrite une
  seule fois ne peut pas reposer sur un réalisé que plus rien ne garantit.
- **Une journée à référence tardive ne compte pas.** Quand rien n'était
  publié au début d'une journée, la première publication faite après sert de
  référence à l'écran, signalée — mais elle contient déjà les changements de
  la journée faits avant elle, qui se liraient comme tenus : aucun écart, des
  taux dégonflés. Une telle journée est montrée, jamais comptée : ni dans les
  totaux de l'écran, ni dans la mesure figée, ni dans `jours_comptes`.
- **La référence d'une journée écoulée ne se supprime pas.** Supprimer une
  publication remplacée la ferait remplacer, pour les journées qu'elle
  mesurait, par une autre, et réécrirait leur écart après coup — ce qu'une
  republication n'a justement pas le droit de faire. La suppression d'une
  publication qui est la référence d'au moins une journée écoulée comptée est
  donc refusée (`409`, par l'API comme par MCP) ; les autres publications
  remplacées se suppriment comme avant (0011).
- **Survie à la suppression** : la table n'a aucune clé étrangère, recopie le
  nom de l'édition, celui de chaque typologie et les bornes de l'événement.
  Supprimer l'édition ne la touche pas ; le job, simplement, ne la voit plus.
- **« L'édition précédente »** est celle dont l'événement s'est terminé le plus
  tard **avant le premier jour** de l'édition qui lit — l'ordre des événements,
  seul ordre qui ait un sens entre éditions. Ni l'ordre de création (une
  variante dupliquée la semaine dernière passerait devant l'année dernière),
  ni le nom (texte libre). Une édition sans créneau n'a pas de premier jour,
  donc pas de précédente. Deux événements finis le même jour — une variante
  dupliquée de l'édition réelle — se départagent par la mesure qui compte le
  plus de journées (une variante publiée tard en compte moins), puis par la
  première figée, puis par l'identifiant : un ordre déterministe, quel que
  soit celui dans lequel le job les a rencontrées. L'édition suivante relit
  chaque typologie **par son nom** : un identifiant de typologie est un
  compteur propre à l'édition, et le même `T1` peut nommer une autre
  catégorie l'année suivante.
- **Il ne lit pas l'interrupteur des notifications.** Cet interrupteur est ce
  qui sépare les envois de nuit des bénévoles de l'an dernier : il protège des
  personnes contre un message. Le job n'envoie rien et n'écrit que des
  comptes ; le soumettre à cet interrupteur aurait privé de mesure toute
  édition qui n'a jamais voulu de rappels automatiques, sans rien protéger.

Il suit les conventions des deux autres : cron et fuseau configurables
(`REALISE_CRON`, `REALISE_TIMEZONE`, 3 h 30 par défaut, avant la sauvegarde
qui l'emporte donc), `SKIP` en cas de chevauchement, échec journalisé et non
propagé, chaque édition entrée par `EditionContext.executeIn` dans son propre
`try/catch`.

## Options écartées

- **Figer au fil de l'eau, à la lecture de l'écran** : la mesure ne serait
  écrite que si quelqu'un ouvre l'écran après l'événement, et une écriture
  cachée dans une lecture est ce qu'on ne sait plus expliquer ensuite.
- **Greffer l'écriture sur les envois de nuit**, comme la purge de
  l'historique : ce job ne parcourt que les éditions armées. Il aurait fallu
  soit contourner sa garde — la seule chose entre lui et l'an dernier —, soit
  n'écrire la mesure que pour les éditions qui envoient des rappels. Un
  troisième job à configurer coûte moins que l'une ou l'autre.
- **Un champ dans `kpi_historique`** : cette table a une ligne par résolution ;
  la mesure du réalisé n'en a qu'une par édition, et elle n'est connue
  qu'une fois l'événement passé, quand plus aucune résolution n'a lieu.
- **Un bouton « clôturer l'édition »** : un geste que personne n'est sûr de
  faire, pour une date que les créneaux connaissent déjà.

## Conséquences

- L'écran Versions de l'édition suivante et l'onglet Besoin du Diagnostic lisent
  la mesure de la précédente, pour information : aucun calcul ne la reprend.
- Une mesure orpheline (édition supprimée) ne se retire que par SQL : elle ne
  contient rien de nominatif, et la conserver est précisément son objet.
- Une édition modifiée après coup — un créneau passé supprimé, une absence
  saisie le lendemain — garde la mesure de la première nuit. La refiger est un
  `DELETE` des lignes de l'édition dans `kpi_realise`, puis une nuit.
- Une publication remplacée qui mesure une journée écoulée reste dans l'écran
  Versions tant que l'édition existe.
