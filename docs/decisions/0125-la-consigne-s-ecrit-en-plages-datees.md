# 0125 — La consigne s'écrit en plages datées

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : consigne d'édition, résolution des horaires de stand, migration de la base, export et import YAML, copie d'édition
- **Remplace** : [0043](0043-consigne-d-edition-fermer-une-bande-sans-rien-detruire.md), pour la façon dont la consigne agit sur les stands (le modèle de la consigne, ses gestes et ses règles restent ceux de 0043)

## Contexte

[0043](0043-consigne-d-edition-fermer-une-bande-sans-rien-detruire.md)
posait la consigne en **quatrième couche** du résolveur : à chaque lecture,
`ConsigneResolver` relisait les consignes de l'édition et réénonçait la
journée de chaque stand au-dessus de ses règles et de ses exceptions. Depuis
[0104](0104-les-horaires-d-un-stand-sont-une-liste-de-plages-datees.md), un
stand n'a plus de règle à replier : ses jours sont exactement ses plages
datées. Il ne restait qu'une couche au-dessus des plages, que tout lecteur
devait connaître, et un test structurel pour vérifier que personne ne
l'oubliait. Une consigne est pourtant, pour l'organisateur, une décision
datée sur les stands : « le 8 juillet, fermé de 12 h à 18 h, ce stand rouvre
de 18 h à 22 h ». Ce sont des plages.

## Décision

1. **Poser une consigne écrit des plages datées** sur chaque stand de sa date
   (`ConsignePlages`), dans la même transaction que la consigne : une
   **fermeture sur la bande** ; une **fermeture sur chaque créneau que la
   consigne a ajouté** (ils n'appartiennent aux horaires de personne) ; une
   **ouverture par fenêtre de compensation** du stand, hors de la bande, à
   l'effectif saisi, sinon au plus fort effectif que la bande lui retire
   (écrit vide quand c'est son minimum, qui revient au même). Les trois règles
   de 0043 sont gardées, écrites au lieu d'être appliquées.

2. **La marque est un booléen `consigne` sur la plage**, dans les deux tables
   de plages, comme `depliee` (0104 § 9) — et non une référence à la consigne.
   Une consigne n'a pas d'autre identité que sa date, et une plage de consigne
   est toujours datée de ce jour : la marque et la date de la plage désignent
   la consigne sans ambiguïté. Une clé étrangère vers `consigne_edition` aurait
   ordonné la copie d'édition, le vidage et l'import de sauvegarde autour d'une
   table de plus, pour une intégrité que la seule écriture — la pose, la levée
   et la migration, toutes dans `ConsigneRepository` ou le SQL de `V168` —
   tient déjà. Une plage ne porte jamais les deux marques.

3. **Les plages de la consigne se lisent au-dessus de celles du stand.**
   `HoraireStandResolver` lit la journée du stand sur ses propres plages, un
   mode par jour comme avant ; les fermetures de la consigne en retirent leur
   étendue, et ses ouvertures s'y ajoutent, quoi que disent les plages du stand
   là. Un stand fermé chaque soir à partir de 17 h rouvre de 18 h à 22 h si la
   consigne le dit. La journée ressort réénoncée en ouvertures explicites, ou
   en fermeture de journée entière, exactement comme la couche la rendait : en
   aval, rien ne connaît la consigne. Ce n'est plus une couche : c'est une
   lecture des plages, qui ne consulte aucune table de consigne.

4. **Lever retire exactement les plages marquées.** Une plage de consigne
   modifiée à la main entre-temps **perd la marque** et reste : l'origine suit
   le contenu (`PlageStand.carryOrigins`, la règle de `depliee`), si bien
   qu'une plage renvoyée telle quelle par l'écran garde sa marque, et qu'une
   plage changée devient une plage du stand. Une plage ajoutée à la main ce
   jour-là reste aussi. La règle « un jour, un mode » de la saisie ne vise que
   les plages du stand : la bande d'une consigne sur un stand qui déclare ses
   ouvertures n'est pas une contradiction.

5. **Poser ou lever change l'estampille de chaque stand de l'édition.** Une
   fiche ouverte avant aurait réécrit la journée sans la bande, ou l'aurait
   remise après la levée, sans que personne ne soit prévenu ; elle est refusée
   en `409` comme toute écriture sur une lecture périmée.

6. **Toute lecture des plages passe par `StandService.resolve`.**
   `ConsigneCoucheStructurelleTest` change d'objet : il n'y a plus de couche à
   oublier, mais une lecture à ne pas refaire à côté. Un lecteur qui lirait les
   plages stockées lui-même, un mode par jour, honorerait les ouvertures d'un
   stand et doterait la bande. Le test refuse tout appelant du résolveur hors
   de `StandService` qui n'est pas argumenté, et tient à part les lecteurs des
   **heures propres** du stand (`resolveOwnHours`, les plages de consigne
   laissées de côté) : les avertissements de saisie, la journée nominale dont
   part la consigne elle-même, l'export de scénario.

7. **Un fichier YAML ne porte pas ces plages ; une copie d'édition non plus.**
   Le fichier porte la consigne (section `consignes`), et l'import réécrit ses
   plages sur les stands qu'il vient d'importer ; un fichier sans section
   `consignes` garde celles de l'édition, et leurs plages sont réécrites sur les
   stands importés. La copie d'édition ne reprend pas les consignes datées
   (0043) et ne reprend donc pas leurs plages. Pas de nouvelle version du
   format, donc pas de maillon.

8. **La migration `V168` écrit les plages des consignes déjà posées**, passées
   comprises, avec les mêmes règles, en SQL. Deux tests tiennent l'équivalence :
   `ConsigneAsPlagesEquivalenceTest` place chacun des scénarios livrés sous deux
   consignes (une soirée rouverte, une bande jusqu'à minuit avec une fenêtre qui
   la chevauche) et compare les segments ouverts et la liste des sièges à ceux
   que la couche de la 3.x donnait, empreinte relevée une fois pour toutes sur
   le dernier commit qui l'avait ; `ConsignePlagesMigrationTest` joue `V168`
   contre l'écriture de l'application. Le détail des scores par règle sur un
   plan figé est identique de part et d'autre.

## Ce que ça coûte

- **L'effectif hérité est figé à la pose.** La couche le recalculait à chaque
  lecture ; il est maintenant écrit sur la plage. Si le stand change ses
  horaires du jour pendant l'alerte, l'héritage ne suit pas : reposer la
  consigne (la modifier) le recalcule. Une fenêtre sans effectif hérité suit,
  elle, le minimum du stand.
- **Plus de lignes en base** : une fermeture par stand et par date sous
  consigne, plus une par créneau ajouté, plus les fenêtres. Quelques centaines
  pour une vague de cinq jours sur une centaine de stands.
- **Les plages de consigne apparaissent dans la fiche du stand** (`consigne`
  dans l'API). Un client qui lit les horaires d'un stand doit les lire avec
  elles, au-dessus des siennes.
- **Une plage de consigne modifiée à la main qui ferait porter deux modes aux
  plages propres du stand est refusée** par la règle de saisie : la retoucher
  passe alors par l'écran Consignes, qui la réécrit marquée.
- **La migration ne redescend pas.** Retour : l'export YAML d'avant la montée,
  ou la sauvegarde de la base.

## Alternatives écartées

- **Une origine `CONSIGNE` dans une colonne `origine` à trois valeurs**
  (saisie, dépliée, consigne) : plus propre sur le papier, mais elle remplaçait
  `depliee`, déjà sur le fil (API, YAML, copie, échange avec un worker), pour
  le même résultat.
- **Une lecture sans ordre : « les ouvertures, moins toutes les fermetures »**
  sur un jour qui porte les deux modes. Essayée d'abord : la bande ferme bien
  les ouvertures d'un stand, mais une fermeture propre du stand ferme alors
  aussi la fenêtre de la consigne — le stand fermé chaque soir à 17 h ne
  rouvrait plus le soir, le cas même d'une canicule. L'empreinte des scénarios
  livrés le montrait sur une quinzaine de fichiers. Les deux exigences (la
  bande l'emporte sur les ouvertures du stand, la fenêtre sur ses fermetures)
  n'ont pas d'arbitrage sans ordre entre les plages.
- **Réécrire la journée entière du stand en plages de consigne et mettre de
  côté les siennes** : la levée aurait dû les rendre, et une plage saisie
  pendant l'alerte n'aurait plus su où aller.
- **Recalculer les plages de la consigne à chaque écriture d'un stand** :
  l'héritage aurait suivi, mais une plage de consigne supprimée ou modifiée à
  la main serait revenue à l'enregistrement suivant.
- **Garder la quatrième couche** : c'est l'état d'avant, une lecture de plus à
  connaître pour qui lit des horaires, alors que la consigne est, pour
  l'organisateur, une décision datée sur ses stands.
