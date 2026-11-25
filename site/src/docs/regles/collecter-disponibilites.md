# Collecter les disponibilités des animateurs

Vous ouvrez une période pendant laquelle chaque animateur déclare lui-même ses jours d'indisponibilité et ses souhaits, puis vous appliquez les déclarations en bloc avant le calcul.

Prérequis : les animateurs ont une adresse e-mail et l'édition est **l'édition active**. Chaque animateur déclare depuis son espace (voir [L'espace des animateurs](espace-animateur.md)), au doigt sur un téléphone, en cochant des jours.

## Ouvrir la collecte

1. Ouvrez Paramètres, onglet *Édition*, et réglez la période de collecte des disponibilités (elle s'ouvre et se ferme depuis cet onglet ; la page Disponibilités en garde une ligne d'état).
2. Cochez, si vous le voulez, la case d'**invitation** : elle envoie à chacun le lien de son espace au moment d'ouvrir la collecte. Cochez-la au premier tour, laissez-la de côté quand vous rouvrez la période après une correction.
3. Si vous voulez qu'un rappel parte automatiquement, allumez la **relance** : elle est éteinte par défaut.

![Les déclarations de disponibilités reçues](/images/fr/docs/disponibilites.webp "La collecte des disponibilités et les déclarations reçues")

### Renvoyer les liens après l'ouverture

Collecte ouverte, le bouton « Envoyer les liens » de la page *Disponibilités* renvoie les liens sans toucher à la période. Il écrit :

- aux animateurs pas encore invités (par défaut : fiches ajoutées ou adresses complétées après l'ouverture) ;
- ou à tous ceux qui n'ont rien déclaré.

Le compte rendu dit qui a été joint, qui n'a pas d'adresse et quels envois ont échoué.

### La relance automatique de fin de période

Si vous l'avez allumée avec la collecte, chaque animateur qui a reçu l'invitation et n'a encore rien déclaré reçoit, trois jours avant la fin de la période, un e-mail qui rappelle l'échéance avec le lien de son espace.

- Il n'y a qu'une relance par personne et par date de fin.
- Une nuit manquée est rattrapée le lendemain.
- Rien ne part d'une collecte fermée ou sans date de fin, ni d'une édition qui n'est pas celle en cours.

## Appliquer les déclarations

Une déclaration reste une **proposition** : elle n'est prise en compte qu'une fois appliquée par vous, en bloc, depuis la page *Disponibilités* (onglet de la page *Animateurs*). Tant qu'elle est en attente, le calcul ne la voit pas, et le Solveur vous le dit dans « ce que le calcul recevra ».

## Traiter les demandes de covoiturage

Pendant la collecte, dans l'onglet *Covoiturage* de son espace, un animateur nomme jusqu'à trois coéquipiers avec qui il vient (« Je viens avec… »). Cet onglet est à part de sa déclaration. Hors collecte, il montre où en est sa demande.

Côté organisation, l'onglet *Covoiturage* de la page *Disponibilités* retrouve ces demandes. Si chacun a nommé les autres, il dit combien de jours leurs indisponibilités divergent. Vous décidez chaque demande à part :

- **valider** l'arrivée groupée ;
- ou l'**écarter**, avec un motif facultatif que l'animateur lit.

Chacun est prévenu par e-mail.

Une demande validée devient un ajustement manuel que seule l'organisation peut annuler, depuis ce même onglet, en prévenant chaque membre. Le calcul cherche à leur donner les mêmes jours et des arrivées et départs proches (tolérance réglable, 30 minutes par défaut), sans imposer le même stand. Le Rail marque les membres, la page Problèmes liste les jours désalignés, et l'espace dit à chacun, jour par jour, si son covoiturage tient.

## Ensuite

Lancez le calcul : [Calculer le planning](calculer-planning.md).
