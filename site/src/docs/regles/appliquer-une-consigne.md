# Appliquer une consigne de l'autorité

Un arrêté ferme des stands à certaines heures sur quelques jours : vous le posez en une fois sur tout l'événement, sans rien détruire dans la grille, et vous le levez de la même façon.

Une **consigne** est une fermeture imposée de l'extérieur (arrêté préfectoral, canicule) appliquée à tous les stands pour quelques dates. Elle se gère dans *Règles du planning*, onglet *Consignes*.

Les épreuves en plein air sont les premières concernées : voir [quand l'autorité ou la météo s'en mêlent](/planning-evenement-sportif/) pour un événement sportif.

## Poser une consigne

1. Ouvrez *Règles du planning*, onglet *Consignes*, et choisissez la bande horaire et les dates à venir.
2. Relisez la proposition : l'application coche les stands qui perdent des heures, et vous pouvez les rouvrir sur des fenêtres de **compensation** (le soir, le matin).
3. Lisez l'**aperçu chiffré** qui précède chaque pose, prolongation ou levée.
4. Appliquez.
5. Relancez le calcul : l'application propose une correction partielle sur place.

![Les consignes d'une édition](/images/fr/docs/consignes.webp "Les consignes posées sur une édition")

La consigne ne détruit rien de la grille. Les vacations fermées perdent leurs sièges, et les seuls créneaux ajoutés sont ceux que les réouvertures exigent.

Il existe aussi des raccourcis :

- « fermer des stands demain » est proposé sur le Solveur et sur l'écran Aujourd'hui ;
- un **préréglage nommé** (« Plan canicule ») rend chaque vague identique au geste près.

## Ce que voient les animateurs

Le courriel, l'espace et le PDF disent « horaires modifiés » avec le motif. Une publication fait le reste ; voir [Publier le planning](publier-planning.md).

## Lever une consigne

La levée rend leurs heures aux titulaires des sièges fermés. Elle se prévisualise comme une pose. Une journée passée garde pour toujours la consigne qui l'a gouvernée. La consigne s'inscrit dans les horaires de chaque stand, sur sa date, marquée « Consigne d'édition » ; la lever retire exactement ces horaires-là. Si vous en avez modifié un à la main entre-temps, il devient un horaire du stand et reste après la levée.

## Partir d'une alerte météo

Chaque matin, l'application lit la prévision d'Open-Meteo pour les lieux de l'événement. Quand la chaleur, les rafales ou l'orage franchissent les seuils de l'édition, elle prévient l'organisation (sur l'accueil, par e-mail et par webhook) en suggérant le préréglage de consigne prévu pour ce phénomène.

- « Préparer la consigne » ouvre le formulaire déjà rempli sur la bonne date. Rien ne s'applique sans votre relecture.
- Une même prévision ne sonne qu'une fois, et de nouveau seulement si elle s'aggrave.
- L'alerte s'allume et se règle par édition, dans Paramètres, onglet *Édition* (seuils par phénomène, préréglage suggéré). Elle suppose que l'hébergeur ne l'a pas coupée pour l'instance (voir [Autoriser les webhooks et l'alerte météo](../installer/appels-sortants.md)).

## Un plan de repli du jour sans consigne

Pour essayer une vraie variante (autre grille, autre équipe), dupliquez l'édition plutôt que de poser une consigne : voir [Enchaîner les éditions](enchainer-les-editions.md#essayer-une-variante).
