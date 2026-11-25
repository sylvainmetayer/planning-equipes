# Horaires et ouvertures des stands par l'API

Les horaires d'un stand se lisent et s'écrivent à trois niveaux : la fiche (ses plages), le rapport des ouvertures (ce que le solveur recevrait) et la grille de saisie. Vous saurez ce que renvoie chacun, quelles anomalies le rapport détecte et ce que l'enregistrement d'une grille conserve ou remplace. Le calcul de la fenêtre effective (règles, exceptions, consignes) est décrit dans [horaires et fenêtres des stands](horaires-et-fenetres-des-stands.md) ; la grille de créneaux et les écritures concurrentes, dans [écrire les référentiels](api-referentiels.md).

## Les plages

La fiche du stand rend ses plages datées, sans expansion, la vue que l'IHM édite : les ouvertures sous `ouvertures` et les fermetures sous `indisponibilites` (chaque plage porte aussi son `mode`). Un `heureFin` nul vaut « jusqu'à la fermeture ». Une écriture répond `400` si une plage est incohérente ou si un même jour porte à la fois une ouverture et une fermeture.

Les règles récurrentes de la 3.x n'existent plus ([décision 0104](repo:docs/decisions/0104-les-horaires-d-un-stand-sont-une-liste-de-plages-datees.md)) : la clé `horaires` de la fiche reste dans la réponse, toujours vide, le temps que l'API parle de plages. Une écriture qui y envoie encore une règle (l'éditeur d'horaires actuel le fait) la voit **convertie aussitôt en plages** sur les jours de l'événement, avec la lecture de la 3.x : un jour qui porte déjà une plage la garde. Sans aucun créneau, il n'y a pas de jour où la poser et l'écriture répond `400` plutôt que de la perdre en silence. Ce pont disparaît avec l'éditeur de plages. `POST /api/stands/compactage-horaires` n'a plus rien à compacter et répond toujours un rapport vide.

## Visualisation des ouvertures

`GET /api/ouvertures-stands` rend une grille stand par jour construite à partir des postes que le solveur recevrait : l'écran valide la donnée réelle, sans seconde interprétation de la saisie. `source` (`DEFAUT` ou `EXCEPTION`, c'est-à-dire : le stand porte une plage ce jour-là) dit ce qui a décidé. Aucune anomalie ne bloque un calcul. Trois sont des défauts : `STAND_JAMAIS_OUVERT`, `FENETRE_SANS_EFFET` (aucun créneau du jour recoupé) et `SEGMENT_TROP_COURT`, signature du contournement `23:59`. Trois codes de la 3.x, nés des règles récurrentes, ne sont plus jamais levés :

| Code | Ce qu'il disait |
| --- | --- |
| `REGLES_CHEVAUCHANTES` | deux règles de même portée et de même mode décidaient un même jour avec des fenêtres qui se recouvraient |
| `REGLE_MASQUEE` | une règle couvrait des jours de l'édition et n'en décidait aucun |
| `FENETRES_CHEVAUCHANTES` | deux fenêtres d'une même règle se recouvraient avec des effectifs différents |

`horaireId` désignait la règle concernée ; il reste sur la réponse, toujours vide.

Un jour porte son libellé férié (`jours[].ferie`) et ses colonnes (`jours[].creneaux`) : un créneau entier, ou ses tranches quand une fenêtre commence ou finit à l'intérieur (4 personnes de 14 h à 19 h puis 2 jusqu'à 20 h coupent le créneau 14-20 en 14-19 et 19-20, pour tous les stands). Rien n'est stocké, les bornes se relisent sur les fenêtres. Chaque cellule porte une entrée par colonne (`creneaux[]` : `creneauId`, `tranche`, `effectif`, `partiel`, `segments`), `effectif` valant `null` si le stand y est fermé. `partiel` ne subsiste que pour une borne qu'aucune colonne ne suit ; `effectif` est alors le plus haut et `segments` dit ce que la case porte.

## Couches : d'où vient une ouverture

`GET /api/ouvertures-stands/couches?du=AAAA-MM-JJ&au=AAAA-MM-JJ` dit d'où vient chaque ouverture, là où le rapport dit ce qu'elle devient. Les deux bornes sont facultatives ; `400` sur une date illisible ou une plage inversée. Pour chaque stand et chaque jour de la plage portant un créneau :

| Champ | Couche | Lu sur |
| --- | --- | --- |
| `source`, `horaireIds`, `motif`, `nominal` | plage datée ou ouvert par défaut (`horaireIds` est toujours vide) | `HoraireStandResolver` seul |
| `jours[].consigne`, `reopenings` | la bande fermée par la consigne et les fenêtres où elle rouvre ce stand | la consigne posée |
| `effective` | ce qui reste une fois la consigne passée, dont les sièges sont découpés | `StandService.resolve`, la même entrée que le solveur |
| `jours[].vacations` | les créneaux, `addedByConsigne` pour ceux qu'une consigne a créés | la grille |

Les fenêtres sont en minutes depuis minuit du jour : un créneau qui passe minuit finit au-delà de 1440 (convention de `journee-stands.ts`). Un `effectif` absent vaut le minimum du stand ; sur une réouverture, `null` dit que la consigne hérite l'effectif de ce que la bande a retiré. L'endpoint est à part parce que le rapport est lu à chaque visite, alors que les couches ne servent qu'aux barres des cases et à la phrase qui explique une case. Il ne lit ni les sièges ni le plan : l'écran répond sur une édition jamais résolue.

## Saisie en grille

`PUT /api/ouvertures-stands/grille` écrit l'horaire des stands sous la forme du classeur de l'organisateur : un entier par colonne, vide pour fermé. Le corps ne porte que les stands modifiés, chacun avec toutes ses cases ; une case nomme son créneau et, pour une tranche, ses bornes dans le créneau :

```json
{ "stands": [ { "standId": "BOURSE",
                "cellules": [ { "creneauId": 1, "effectif": 2 },
                              { "creneauId": 2, "heureDebut": "14:00", "heureFin": "19:00", "effectif": 4 },
                              { "creneauId": 2, "heureDebut": "19:00", "heureFin": "20:00", "effectif": 2 } ] } ] }
```

Un stand envoyé voit ses plages remplacées en totalité par ses cases : les créneaux consécutifs à même effectif deviennent une fenêtre datée, celle qui atteint la fin du jour reste ouverte, et un jour sans case est une fermeture explicite, car ne rien dire voudrait dire ouvert. `effectifMin` est la plus petite valeur saisie, `effectifMax` la plus grande. Les minutes d'un créneau qu'aucune case ne couvre gardent ce que le stand y avait ; deux cases qui se recouvrent, ou hors de leur créneau, font `400`, comme un créneau ou un stand inconnu ou un effectif nul (laissez la case vide pour fermer).

La grille ne détruit jamais ce qu'elle ne sait pas afficher. Une case partielle (le rapport la marque `partiel`) renvoyée avec la même valeur garde ses segments ; retapée autrement, la valeur couvre tout le créneau. Aplatir les cases partielles d'un stand est une demande explicite, `stands[].aplatir: true`, jamais l'effet de bord de l'enregistrement d'un voisin. La réponse le dit stand par stand (`exceptions` compte les plages écrites ; `regles` vaut toujours 0 et `compacte` toujours faux, restes de la 3.x). Les stands sont tous validés d'abord puis écrits en une transaction, `409` pendant une résolution. Une grille porte une seule variante de chaque vacation ([ADR 0029](repo:docs/decisions/0029-retrait-des-familles-de-relais.md)).
