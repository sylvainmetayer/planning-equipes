# 0037 — Une grille de créneaux est toujours faite de vacations

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : créneaux, paramètres, génération des postes, contrôle de grille, import/export de scénario, API, MCP, écrans Créneaux et Paramètres, base
- **Complète** : [0032](0032-journees-types-nommees-vacations-fixes.md)

## Contexte

Une édition détenait une grille qui se lisait de **deux** façons
incompatibles. Soit des **amplitudes** — la journée d'ouverture de bout en bout
— qu'un *découpage* tranchait en vacations plus courtes avant de résoudre. Soit
des **vacations** finales, résolues telles quelles.

Rien dans les données ne tranche entre les deux : une journée de 10 h est une
amplitude plausible et une vacation illégale, et deux créneaux qui se
chevauchent sont une saisie en double entre amplitudes et la forme normale de
deux relèves décalées. Il a donc fallu **déclarer** le mode (V66), le demander
sur chaque appel qui juge la grille, et vivre avec un `diagnostiquer_grille_creneaux`
qui *suggérait* une réponse sans pouvoir la prouver.

Le découpage lui-même portait six réglages (durée cible, minimale, maximale,
chevauchement, stratégie de couverture pendant la pause, mode), une table, deux
endpoints, deux outils MCP, une carte d'écran et un générateur de 300 lignes.

En pratique, personne ne s'en sert. Un évènement **connaît ses horaires
d'ouverture** : l'organisateur sait qu'on ouvre à 10 h, qu'on relaye à midi et
qu'on ferme à 19 h, et il l'écrit. Depuis l'ADR 0032, il l'écrit même une seule
fois par forme de journée — la **journée type** — et l'affecte à des dates.
Les scénarios versionnés qui s'appuyaient encore sur le découpage le faisaient
pour produire une grille qu'ils auraient pu énoncer directement : rejouer le
découpage une dernière fois et écrire sa sortie donne le même problème, au
créneau près.

Deux lectures dont une seule sert, ce n'est pas une option offerte à
l'organisateur : c'est une question posée à chaque écran, à chaque outil et à
chaque fichier, dont la mauvaise réponse fait lire des relèves comme des
doublons.

## Décision

Le découpage disparaît. **Un créneau est une vacation**, et rien d'autre.

- Plus de `ParametresDecoupage`, de `PauseCoverageStrategy`, de
  `VacationGeneratorService`, ni des endpoints `/api/decoupage/*` et
  `/api/parametres-decoupage*` ; plus d'outils `previsualiser_decoupage`,
  `generer_decoupage`, `consulter_parametres_decoupage` ni
  `modifier_parametres_decoupage`.
- Plus de `ModeGrilleCreneaux` : le paramètre `mode` disparaît de
  `valider_creneaux`, `creer_creneaux_recurrents`,
  `previsualiser_derivation_creneaux`, `generer_creneaux_depuis_stands` et de
  leurs endpoints. Le contrôle de grille juge toujours des vacations : un
  chevauchement le même jour n'est plus une anomalie, une durée au-delà du
  plafond l'est toujours.
- `diagnostiquer_grille_creneaux` **reste**, amputé de ce qu'il devinait : il
  décrit la grille en place (combien de vacations, sur quelles dates, avec
  combien de relais repas) au lieu de proposer un mode assorti d'un indice de
  confiance.
- **Un réglage survit**, parce que la règle qu'il portait survit :
  `dureeVacationMaxMinutes` (6 h, seuil de l'art. L3121-16) rejoint
  `ParametresLegaux`. Il bornait ce que le découpage produisait ; il dit
  maintenant à partir de quand le contrôle avertit qu'une coupure interne
  devient obligatoire. C'est le même chemin que la coupure repas a fait en V72,
  pour la même raison : c'est une règle de l'évènement, jugée sur la grille que
  l'organisateur a.
- La couverture de pause à effectif réduit **n'était pas un réglage de
  découpage** mais un attribut du créneau (`couverturePause`, moitié de
  l'effectif arrondie au supérieur) : elle ne bouge pas. Les trois stratégies
  s'écrivent désormais à la main — pas de vacation sur la fenêtre (fermeture),
  une vacation ordinaire (relève à effectif plein), une vacation marquée
  (effectif réduit).
- La migration `V76` déplace le plafond vers `parametres_legaux` et supprime la
  table `parametres_decoupage`.
- Un scénario qui porte encore `parametresDecoupage:` ou `decoupageAuto:` est
  **refusé par son nom**, avec un message qui dit quoi écrire à la place. Les
  sept scénarios versionnés qui s'en servaient sont réécrits avec les vacations
  que le découpage produisait, à l'identique.

## Conséquences

- **C'est une rupture de contrat** : endpoints, outils MCP, schéma de scénario,
  clés JSON et colonnes disparaissent. Un fichier ou un client qui les utilise
  échoue — bruyamment, jamais en silence.
- Une édition qui tenait ses amplitudes en base les garde telles quelles, et
  elles se lisent désormais comme des vacations : ce sont des journées trop
  longues, que le contrôle signale (`VACATION_TROP_LONGUE`, et
  `REPOS_QUOTIDIEN_IMPOSSIBLE` au-delà du repos quotidien). La réponse est de
  décrire les vraies vacations, à la main ou par journées types, pas de
  relancer un découpage.
- Le refus à l'import est le choix délibéré contre l'acceptation silencieuse :
  ignorer `decoupageAuto:` importerait une grille d'amplitudes comme des
  vacations de 14 h, et son auteur l'apprendrait du solveur.
- Ce qui rendait le découpage utile — une journée continue couverte par des
  relèves qui se chevauchent — reste parfaitement exprimable : les scénarios
  `scenario-continu` et `scenario-continu-avec-coupure` énoncent les mêmes
  relèves qu'avant, sans le mécanisme.
- Si le besoin de générer revient, il se repensera comme un outil qui **écrit
  des vacations** dans la grille — au même titre que la dérivation depuis les
  horaires des stands ou l'application d'un calendrier de journées types — et
  non comme une seconde nature de créneau.
