# Horaires et ouvertures des stands par l'API

Les horaires d'un stand se lisent et s'écrivent à trois niveaux : la fiche (ses plages), le rapport des ouvertures (ce que le solveur recevrait) et la grille de saisie. Vous saurez ce que renvoie chacun, quelles anomalies le rapport détecte et ce que l'enregistrement d'une grille conserve ou remplace. Le calcul de la fenêtre effective (règles, exceptions, consignes) est décrit dans [horaires et fenêtres des stands](horaires-et-fenetres-des-stands.md) ; la grille de créneaux et les écritures concurrentes, dans [écrire les référentiels](api-referentiels.md).

## Les plages

La fiche du stand rend ses plages datées sous `plages`, sans expansion : chacune porte son `id`, son `mode` (`OUVERTURE` ou `FERMETURE`), son jour, ses heures, un `motif`, un `effectif` (pour une ouverture) et `depliee`, qui dit d'où elle vient (dépliée du modèle, ou saisie à la main ; l'origine est dite par le serveur). Un `heureFin` nul vaut « jusqu'à la fermeture ». Une écriture répond `400` si une plage est incohérente ou si un même jour porte à la fois une ouverture et une fermeture.

`ouvertures` et `indisponibilites` (les mêmes plages, séparées par mode) sont **dépréciées** : la fiche les rend encore et accepte de les recevoir, pour les clients de la 3.x, jusqu'en 5.0. Un corps qui porte `plages` **et** l'une de ces listes est départagé par la fiche enregistrée : la forme qui en diffère est celle que le client a changée, l'autre est la copie périmée de ce qu'il avait lu (un client de la 3.x qui modifie `ouvertures` en renvoyant les `plages` qu'il ne connaît pas, un client nouveau qui modifie `plages` en renvoyant les listes lues). Si les deux ont changé et ne disent pas la même chose, l'écriture répond `400`. Un client nouveau n'envoie que `plages`.

Trois routes écrivent une plage sans renvoyer toute la fiche, et répondent la fiche et ses avertissements comme `PUT /api/stands/{id}` :

| Route | Effet |
| --- | --- |
| `POST /api/stands/{id}/plages` | pose une plage (corps : `mode`, `date`, `heureDebut`, `heureFin`, `motif`, `effectif`), saisie à la main |
| `DELETE /api/stands/{id}/plages/{plageId}` | retire la plage de cet identifiant (`404` s'il n'existe pas sur ce stand) |
| `DELETE /api/stands/{id}/plages?date=AAAA-MM-JJ` | retire toutes les plages d'un jour : le stand y redevient ouvert toute la journée (`400` sans date) |

Ces écritures, comme celles de la fiche, répondent `409` pendant un calcul et quand la phase de l'édition fige les stands (`REFERENTIEL_FIGE`) : une plage est le planning que le calcul reçoit, aucun champ n'est laissé ouvert. Une plage retirée à la main ne revient pas : le modèle ne déplie que les jours nouveaux.

Les règles récurrentes de la 3.x n'existent plus ([décision 0104](repo:docs/decisions/0104-les-horaires-d-un-stand-sont-une-liste-de-plages-datees.md)) : la clé `horaires` de la fiche porte le **modèle** du stand : les règles qu'il garde tant que l'édition n'a aucun créneau, et que la résolution ne lit jamais. Une écriture qui y envoie des règles (l'éditeur d'horaires actuel le fait) les voit **gardées comme modèle**, puis dépliées en plages sur chaque jour que l'édition gagne ; quand elle a déjà des créneaux, elles sont **aussi converties aussitôt en plages** sur ses jours, avec la lecture de la 3.x (un jour qui porte déjà une plage la garde, les plages que l'ancien modèle avait dépliées sont retirées d'abord). Une écriture sans `horaires` laisse le modèle tel quel ; `horaires: []` l'efface ; renvoyer le modèle stocké sans le changer ne déplie rien. Jamais de refus ni de règle perdue en silence. Ce pont disparaît avec l'éditeur de plages. Le compactage des horaires est retiré (sa route n'existe plus) : une plage datée n'a plus de règle à rejoindre.

## Visualisation des ouvertures

`GET /api/ouvertures-stands` rend une grille stand par jour construite à partir des postes que le solveur recevrait : le rapport valide la donnée réelle, sans seconde interprétation de la saisie. `source` dit ce qui a décidé : `DEFAUT` (aucune plage, ouvert toute la journée), `REGLE` (seules des plages **dépliées du modèle** parlent de ce jour : le régime normal du stand) ou `EXCEPTION` (une plage saisie à la main). Chaque plage porte `depliee` ; l'origine est dite par le serveur, jamais par le client : une plage renvoyée telle qu'elle était stockée garde sa marque, une plage modifiée ou écrite par un client est une saisie à la main. Aucune anomalie ne bloque un calcul. Trois sont des défauts : `STAND_JAMAIS_OUVERT`, `FENETRE_SANS_EFFET` (aucun créneau du jour recoupé) et `SEGMENT_TROP_COURT`, signature du contournement `23:59`.

Un jour porte son libellé férié (`jours[].ferie`) et ses colonnes (`jours[].creneaux`) : un créneau entier, ou ses tranches quand une fenêtre commence ou finit à l'intérieur (4 personnes de 14 h à 19 h puis 2 jusqu'à 20 h coupent le créneau 14-20 en 14-19 et 19-20, pour tous les stands). Rien n'est stocké, les bornes se relisent sur les fenêtres. Chaque cellule porte une entrée par colonne (`creneaux[]` : `creneauId`, `tranche`, `effectif`, `partiel`, `segments`), `effectif` valant `null` si le stand y est fermé. `partiel` ne subsiste que pour une borne qu'aucune colonne ne suit ; `effectif` est alors le plus haut et `segments` dit ce que la case porte.

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
