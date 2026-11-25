# Horaires et ouvertures des stands par l'API

Les horaires d'un stand se lisent et s'écrivent à trois niveaux : la fiche (règles et exceptions), le rapport des ouvertures (ce que le solveur recevrait) et la grille de saisie. Vous saurez ce que renvoie chacun, quelles anomalies le rapport détecte et ce que l'enregistrement d'une grille conserve ou remplace. Le calcul de la fenêtre effective (règles, exceptions, consignes) est décrit dans [horaires et fenêtres des stands](horaires-et-fenetres-des-stands.md) ; la grille de créneaux et les écritures concurrentes, dans [écrire les référentiels](api-referentiels.md).

## Les règles et les exceptions

La fiche du stand (`horaires`) rend deux niveaux sans expansion, la vue que l'IHM édite : les règles récurrentes, et les exceptions datées qui priment le jour qu'elles nomment. Un `heureFin` nul vaut « jusqu'à la fermeture ». Une écriture répond `400` si une règle est incohérente ou si deux règles de même portée sur des jours qui se croisent se contredisent.

`POST /api/stands/compactage-horaires` réécrit les fenêtres datées répétées en règles équivalentes ; `appliquer=false` (défaut) est un essai à blanc. Un stand n'est réécrit que si les règles proposées reproduisent exactement ses segments ouverts. `gapMinutes` compte les minutes d'ouverture en désaccord : `1` quand un ancien `23:59` était devenu la fermeture réelle du jour.

## Visualisation des ouvertures

`GET /api/ouvertures-stands` rend une grille stand par jour construite à partir des postes que le solveur recevrait : l'écran valide la donnée réelle, sans seconde interprétation de la saisie. `source` (`DEFAUT`, `REGLE`, `EXCEPTION`) dit quelle couche a décidé. Aucune des six anomalies ne bloque un calcul. Trois sont des défauts : `STAND_JAMAIS_OUVERT`, `FENETRE_SANS_EFFET` (aucun créneau du jour recoupé) et `SEGMENT_TROP_COURT`, signature du contournement `23:59`. Trois sont données pour information :

| Code | Déclencheur |
| --- | --- |
| `REGLES_CHEVAUCHANTES` | deux règles de même portée et de même mode décident un même jour avec des fenêtres qui se recouvrent : union des fenêtres, effectif le plus haut, que le message nomme |
| `REGLE_MASQUEE` | une règle couvre des jours de l'édition et n'en décide aucun : une règle plus spécifique ou une exception datée l'emporte partout |
| `FENETRES_CHEVAUCHANTES` | deux fenêtres d'une même règle (ou deux ouvertures datées d'un même jour) se recouvrent avec des effectifs différents |

Elles se jugent sur les jours réels de l'édition (les dates qui portent un créneau et le lendemain d'un créneau passant minuit). Une règle `PLAGE` hors de l'édition n'est pas masquée, une consigne ne masque rien, et des portées différentes en modes opposés (fermé le week-end sur une ouverture quotidienne) sont le mécanisme, non une anomalie. `horaireId` désigne la règle concernée. L'état de l'édition les compte (`ouvertures.informations`) sans qu'elles suffisent à passer la ligne « à vérifier ». L'éditeur de règles rejoue la détection en direct (`core/horaire-stand.ts`), tenu d'accord avec le serveur par un jeu de cas partagé, `horaire-stand-anomalies.cas.json`.

Un jour porte son libellé férié (`jours[].ferie`) et ses colonnes (`jours[].creneaux`) : un créneau entier, ou ses tranches quand une fenêtre commence ou finit à l'intérieur (4 personnes de 14 h à 19 h puis 2 jusqu'à 20 h coupent le créneau 14-20 en 14-19 et 19-20, pour tous les stands). Rien n'est stocké, les bornes se relisent sur les fenêtres. Chaque cellule porte une entrée par colonne (`creneaux[]` : `creneauId`, `tranche`, `effectif`, `partiel`, `segments`), `effectif` valant `null` si le stand y est fermé. `partiel` ne subsiste que pour une borne qu'aucune colonne ne suit ; `effectif` est alors le plus haut et `segments` dit ce que la case porte.

## Couches : d'où vient une ouverture

`GET /api/ouvertures-stands/couches?du=AAAA-MM-JJ&au=AAAA-MM-JJ` dit d'où vient chaque ouverture, là où le rapport dit ce qu'elle devient. Les deux bornes sont facultatives ; `400` sur une date illisible ou une plage inversée. Pour chaque stand et chaque jour de la plage portant un créneau :

| Champ | Couche | Lu sur |
| --- | --- | --- |
| `source`, `horaireIds`, `motif`, `nominal` | règle récurrente, exception datée ou ouvert par défaut | `HoraireStandResolver` seul |
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

Un stand envoyé voit ses règles et exceptions remplacées en totalité par ses cases : les créneaux consécutifs à même effectif deviennent une fenêtre datée, celle qui atteint la fin du jour reste ouverte, et un jour sans case est une fermeture explicite, car ne rien dire voudrait dire ouvert. `effectifMin` est la plus petite valeur saisie, `effectifMax` la plus grande. Les minutes d'un créneau qu'aucune case ne couvre gardent ce que le stand y avait ; deux cases qui se recouvrent, ou hors de leur créneau, font `400`, comme un créneau ou un stand inconnu ou un effectif nul (laissez la case vide pour fermer).

La grille ne détruit jamais ce qu'elle ne sait pas afficher. Une case partielle (le rapport la marque `partiel`) renvoyée avec la même valeur garde ses segments ; retapée autrement, la valeur couvre tout le créneau. Aplatir les cases partielles d'un stand est une demande explicite, `stands[].aplatir: true`, jamais l'effet de bord de l'enregistrement d'un voisin. Le compactage ramène ensuite les jours répétés en règles quand il prouve l'équivalence ; la réponse le dit stand par stand (`regles`, `exceptions`, `compacte`, `raison`). Les stands sont tous validés d'abord puis écrits en une transaction, `409` pendant une résolution. Une grille porte une seule variante de chaque vacation ([ADR 0029](repo:docs/decisions/0029-retrait-des-familles-de-relais.md)).
