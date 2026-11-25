# Enchaîner les éditions

Vous créez une édition, vous activez celle qui commence, vous repartez de l'année précédente et vous comparez deux éditions.

Tout le référentiel (stands, animateurs, créneaux…) et les résultats sont cloisonnés par édition (« Année 2025 », « Année 2026 »). Deux onglets du navigateur peuvent travailler sur deux éditions à la fois.

## Créer une édition

1. Cliquez sur « Gérer » dans la barre d'édition, au-dessus de chaque page : la page *Éditions* s'ouvre.
2. Dans « Nouvelle édition », donnez un nom. Évitez un nom qui a la forme d'un identifiant d'édition : l'application le refuse.
3. Choisissez « Copier depuis » une édition existante, ou « Édition vide ». Avec une copie, vous pouvez décider de reprendre ou non les animateurs.
4. Cliquez sur « Ajouter ».

![La liste des éditions](/images/fr/docs/editions.webp "La liste des éditions")

Chaque édition naît **inactive**.

## Activer une édition

Une seule édition est **active**. Elle seule publie, écrit aux animateurs, et ouvre leur espace, leurs calendriers et l'affichage mural. On prépare la suivante sans risque, puis on l'active d'un geste.

1. Dans la page *Éditions*, cliquez sur le bouton « Activer » (une icône de drapeau, infobulle « Activer : elle seule publiera et écrira aux animateurs ») sur la ligne de l'édition qui commence.
2. Lisez l'aperçu de ce que la bascule ferme : les liens d'espace et de calendrier et les affichages muraux de l'ancienne édition cessent de répondre, ses demandes d'échange restent sans réponse, et les résolutions en file sur ces éditions sont comptées.
3. Confirmez avec « Activer ».

L'activation est refusée tant qu'une résolution tourne sur l'une des deux éditions.

Un bandeau rappelle d'activer l'édition qui commence, ou de désactiver celle qui est finie. Désactiver une édition (même ligne, bouton « Désactiver ») ferme tout : plus aucune édition n'est active, rien n'est publié ni envoyé, et les liens d'espace, les calendriers et l'affichage mural cessent de répondre.

Pour travailler dans une autre édition sans l'activer, utilisez « Travailler dans cette édition » sur sa ligne : la barre d'édition indique celle que vous consultez.

## Essayer une variante

Dupliquez l'édition pour essayer une vraie variante (autre grille, autre équipe) et la résoudre à côté, sans rien envoyer : une copie naît inactive. Un plan de repli du jour (canicule, orage) se pose, lui, par une consigne dans l'édition active : voir [Appliquer une consigne de l'autorité](appliquer-une-consigne.md).

## Comparer deux éditions

La page *Comparer deux éditions* (bouton « Comparer deux éditions » de la page Éditions) montre ce qui a changé dans le référentiel d'une édition à l'autre, par exemple l'an dernier et cette année, ou une édition et sa variante.

- Un résumé chiffré en tête (« +3 stands, −12 animateurs, +410 heures à pourvoir »).
- Le détail : stands, animateurs arrivés et partis, typologies, emplacements, journées types, créneaux alignés sur le rang du jour d'ouverture, paramètres, ajustements comptés par type, et la volumétrie des deux côte à côte.
- Chaque ligne dit sur quoi elle a été rapprochée (code, nom à vérifier, e-mail, identité), jamais sur l'identifiant, et ouvre sa fiche dans l'édition qui la porte.
- Une duplication sans les personnes est dite en tête plutôt que de lister toute l'équipe comme partie.

La page est en lecture seule, et l'export CSV ne nomme aucun animateur.

## Repartir de zéro

« Vider cette édition » repart d'une édition vide sans toucher aux autres ni aux réglages de l'instance. Avant de la vider, comme avant d'y importer un scénario, l'application en garde une copie, planning et verrous compris. Aucun écran ne liste ces copies : elles se retrouvent et se remettent en place par l'API de l'application, une opération technique. La procédure est dans [Repartir d'une édition, revenir en arrière](../comprendre/restaurer-une-edition.md#les-sauvegardes-avant-vidage).

## Supprimer une édition

Sur la ligne de l'édition, « Supprimer » demande de saisir le nom de l'édition pour confirmer. Un nom incorrect n'efface rien.

## Après l'événement

- Comparez le prévu et le réalisé : [Suivre le jour J](suivre-le-jour-j.md#mesurer-le-réalisé-contre-le-prévu).
- Archivez l'édition : [Importer et exporter des fichiers](importer-exporter.md#archiver-une-édition-terminée).
- Repartez de cette édition pour la suivante, avec « Copier depuis ».
