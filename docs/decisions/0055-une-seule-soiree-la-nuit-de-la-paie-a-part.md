# 0055 — Une seule soirée dans l'application, la nuit de la paie à part

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : rapports des heures et d'équité, page Planning (par personne), export CSV de la paie
- **Voisine de** : [0048](0048-une-seule-regle-de-pause.md) (une seule grandeur d'heures)

## Contexte

Deux écrans mettaient une soirée à côté du nom d'un animateur, chacun la sienne.
L'écran Équité comptait les heures après le début de soirée réglable des
paramètres légaux (20 h par défaut) ; l'écran Heures comptait les heures après
22 h, une borne fixe, pour la paie. Sur une même personne, l'un disait « 18 h de
soirée », l'autre « 0 h après 22 h » : deux chiffres vrais, que rien à l'écran
ne distinguait, et qu'un organisateur lisait comme une contradiction.

La page Planning réunit ces deux écrans sur l'axe « Par personne » : une ligne,
une colonne par grandeur. Il fallait décider laquelle des deux bornes s'appelle
« soirée ».

## Options envisagées

| Option | Ce qu'elle coûte |
| --- | --- |
| **A.** Garder les deux, chacune sur son écran | Les deux écrans disparaissent dans la même grille : deux colonnes « soirée » côte à côte, ou une seule qui change de sens selon d'où l'on vient |
| **B.** Une seule borne, 22 h, pour tout | La soirée cesse d'être réglable : une édition qui ferme à 21 h n'a plus aucune soirée à répartir équitablement |
| **C.** Une seule borne, la réglable, pour tout | La paie perd sa colonne de nuit, qu'elle compte sur une borne fixe convenue avec l'organisation, et un réglage de confort déplacerait un montant versé |
| **D.** Une seule soirée, la réglable ; la borne de 22 h ne survit que comme la nuit de la paie, nommée comme telle | Une colonne de plus, « Nuit (paie) », et un en-tête de CSV renommé |

## Décision

**Option D.**

- **La soirée est l'heure réglable des paramètres légaux, partout.** Le rapport
  des heures (`PlanningHoursService`) la lit comme le rapport d'équité
  (`EquiteService`), avec le même calcul (`EquiteService.eveningMinutes`) et
  les paramètres de l'édition tels qu'ils sont — non ceux du plan que le
  navigateur envoie, qui sont ceux de sa dernière résolution. Il rend
  `heuresSoiree` et l'heure sous laquelle il l'a lue, `heureDebutSoiree`.
- **22 h n'est plus « le soir ».** La borne fixe reste celle de la paie :
  la colonne « Nuit (paie) » de la page Planning, et la colonne
  `nuit paie (apres 22h)` du CSV « Heures pour la paie ». Ni l'une ni l'autre
  ne se présente comme une soirée.
- `HeuresCoherentesTest` donne à une même personne, sous une soirée réglée à
  18 h, la même soirée dans les deux rapports, et une nuit de paie comptée
  depuis 22 h.

## Conséquences

- Une seule colonne « Soirée » sur la page Planning, par personne ; sa légende
  rappelle l'heure réglée et mène aux paramètres.
- Le CSV de la paie change d'en-tête pour sa dernière colonne : un classeur qui
  la lisait par son nom doit suivre ; un classeur qui la lisait par sa position
  n'y voit rien.
- Le total d'heures du rapport des heures se calcule désormais sous les
  paramètres légaux courants, comme celui de l'équité : deux écrans ne peuvent
  plus donner deux totaux pour la même personne parce que l'un lit les
  paramètres du dernier calcul.
