# 0052 — Le gel du référentiel, distinct du verrou de planning

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : schéma (migration `V106`), services du référentiel, API, MCP, écrans de saisie
- **Voisines** : [0003](0003-verrouillage-par-pin-natif.md) (le verrou de planning), [0039](0039-validation-de-relecture-distincte-du-verrou.md) (« relu et accepté »), [0043](0043-consigne-d-edition-fermer-une-bande-sans-rien-detruire.md) (la consigne)

## Contexte

Entre deux calculs, et plus encore après une publication, rien n'empêchait de
changer par mégarde l'effectif d'un stand ou de supprimer un créneau. Le
recalcul suivant fait alors bouger un planning déjà envoyé. Ce qui existait ne
répondait pas :

- le **verrou de planning** (0003) fige des *sièges* d'un plan calculé ; il ne
  dit rien des fiches dont ce plan est calculé ;
- le **refus pendant une résolution** ne tient que le temps d'un calcul ;
- l'**avertissement « données modifiées depuis la résolution »** le dit après
  coup ;
- le **passé figé** protège des sièges, pas des fiches.

## Options envisagées

1. **Étendre le verrou de planning aux fiches.** Rejeté : autre objet, autre
   moment. Le verrou est lu par le solveur et porte sur un plan ; le gel est lu
   par les écritures et porte sur une préparation. Les réunir sous un même mot
   referait la confusion que 0039 a défaite entre verrou et relecture.
2. **Un gel par champ ou par fiche.** Rejeté : c'est un réglage fin, et une
   granularité par fiche reproduirait le verrou de planning. Une préparation se
   termine par familles (« les stands sont prêts »).
3. **Griser les formulaires seulement.** Rejeté : l'écran n'est pas le seul
   chemin. L'import CSV, l'import de grille, l'édition en lot et l'assistant
   MCP écrivent aussi ; c'est la leçon du refus pendant un calcul.
4. **Un gel automatique à une date, ou au premier calcul.** Rejeté : le gel est
   proposé aux jalons (premier calcul, première publication), jamais imposé.

## Décision

**D1 — Quatre familles, posées et levées par l'organisateur.** Stands
(création, suppression, effectifs, réserve majeurs, typologies proposées,
horaires), Créneaux (création, suppression, date, heures, couverture de pause,
et leurs trois générateurs : journées types appliquées, dérivation, séries),
Typologies & emplacements (création, suppression, plafond par typologie,
typologie polyvalente), Compétences (niveaux des animateurs déjà inscrits).
Une table `gel_referentiel`, une ligne par famille figée, cloisonnée par
édition ; une duplication ne la recopie pas, la nouvelle édition repart en
préparation.

**D2 — Le refus est au service, par annotation.** Une méthode de service qui
écrit une famille porte `@RefusedWhileFrozen(famille)` ; un intercepteur CDI
consulte le gel et refuse avant le premier effet, en `409` avec le code
`REFERENTIEL_FIGE` et un message qui nomme les familles à lever. Le service
étant la porte commune de l'écran, de l'import et de l'outil MCP, le refus
tient sur tous les chemins à la fois. Là où une écriture ne touche qu'une
partie de la famille — renommer un stand, corriger l'e-mail d'un animateur —
la méthode compare la fiche à son image stockée et appelle le refus
elle-même : l'annotation ne sait refuser que l'appel entier.

**D3 — Un test structurel tient la liste.** Chaque méthode des services
concernés est annotée, vérifiée dans son corps, déléguée, ouverte avec sa
raison, ou une lecture ; une méthode nouvelle dans aucune de ces cases fait
échouer le build. Un intercepteur ne voit pas un appel sur `this` : le même
test refuse qu'une méthode appelle une méthode annotée de sa propre classe
sans que sa propre garde couvre déjà les familles de celle-ci — une méthode non
gardée ne les couvre jamais.

**D4 — Ce qui reste ouvert l'est délibérément.** Disponibilités, souhaits,
déclarations, e-mail et création d'un animateur (une fiche neuve n'est pas une
retouche), ajustements, verrous de planning — et surtout les consignes (0043),
l'outil prévu pour fermer une bande tard sans rien détruire. L'écriture de
grille d'une consigne passe par ses propres méthodes transactionnelles, que
seule la consigne appelle. L'import de scénario et la remise à zéro, qui
remplacent tout, sont refusés tant qu'une famille est figée — comme un calcul
dont le client fournit le problème, dont l'atterrissage réécrit les fiches que
ce problème porte. Un calcul construit par le serveur à partir de l'édition lit
ce que le gel protège et n'est pas refusé.

## Conséquences

- Poser un gel pendant un calcul est accepté : il ne change aucune donnée lue.
- La levée demande confirmation et rappelle la phase (« le planning est
  publié »). Pose et levée sont journalisées dans l'Historique des actions.
- Le mécanisme d'annotation est celui que le refus pendant un calcul pourrait
  adopter à son tour ; ce refus-là reste un appel explicite pour l'instant.
- Une famille de plus s'ajoute à l'énumération, et le test structurel dit où
  la garder.
- **Limite connue : le gel est un garde-fou, pas une barrière de
  concurrence.** La lecture du gel et l'écriture qu'il garde sont deux
  transactions : une écriture partie juste avant qu'un gel soit posé peut
  encore aboutir juste après. Le gel protège d'une modification faite par
  mégarde, pas de deux gestes simultanés ; les rendre atomiques demanderait de
  verrouiller la ligne du gel dans chaque écriture du référentiel, pour un cas
  qu'un organisateur ne rencontre pas.
- **L'import d'un dump SQL n'est pas refusé** sous un gel : il remplace toute
  la base, gel compris, et restaure l'état de gel que le dump contenait. C'est
  une restauration, pas une écriture du référentiel.
