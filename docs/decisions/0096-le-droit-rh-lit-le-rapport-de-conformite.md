# 0096 — Le droit RH lit le rapport de conformité : un droit par édition, des règles relues telles qu'écrites

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : droits des comptes, rapport de conformité, journal des actions
- **Prolonge** : [0093](0093-connexion-par-un-fournisseur-oidc-et-secours-ferme.md) (les comptes et leurs droits), [0095](0095-le-responsable-de-stand-lit-son-stand.md) (un rôle qui n'ouvre qu'un préfixe)

## Contexte

Face à une inspection du travail, les chiffres existent — heures par semaine,
dimanche, jours fériés, nuit, plafonds des paramètres légaux, règles légales du
planning — mais aucun document ne les met en regard les uns des autres. Il faut
aujourd'hui croiser à la main plusieurs écrans. Le document nomme des personnes,
des mineurs compris : qui le lit, et ce que l'application garde de cette lecture,
sont des décisions, pas des détails.

## Décision

1. **Un droit `RH` sur un compte, toujours porté par une édition.** Comme le
   responsable de stand, c'est un droit de plus, pas une porte de plus : le
   rôle `rh` n'est que dans `role-rh` (`/api/rh/*`, aussi ouvert à `admin`).
   Il s'ajoute aux autres droits ; un organisateur sans ce droit ne lit rien du
   rapport.
2. **L'édition est dans le chemin, pas dans l'en-tête.** Un compte qui n'est
   que RH n'est organisateur d'aucune édition : l'en-tête d'édition lui serait
   refusé. `RhService` décide du droit édition par édition, et répond le même
   `404` pour une édition sans droit et une édition inconnue.
3. **Le rapport lit le plan publié**, celui que les personnes ont reçu, jamais
   un brouillon. Sans publication, `409`.
4. **Le rapport ne calcule aucune règle.** Les heures sont celles de
   `PlanningHoursService`, les plafonds ceux des paramètres légaux que les règles
   lisent, les écarts sont les correspondances des règles légales du catalogue,
   relues telles qu'écrites sur le plan publié. Les bascules d'édition (règle
   éteinte pour le solveur) **ne s'appliquent pas** : un rapport pour
   l'inspection compte ce que la loi interdit, que l'organisateur ait éteint la
   règle ou non. Aucune règle du solveur ne change.
5. **Consultations et exports sont journalisés par le service**, dans l'édition
   du rapport, et non par le filtre de réponse, qui classe sous l'édition de
   l'en-tête que cette route n'envoie pas.
6. **Jamais par MCP**, et ni date de naissance, ni adresse, ni coordonnées dans
   le PDF ou le CSV : un nom et le statut de mineur.

## Les contacts d'urgence et représentants légaux

Le même droit lit les contacts d'urgence et les représentants légaux (nom et
téléphone, facultatifs). Quatre choix en découlent :

1. **Une table à part, `animateur_contact`**, et non des colonnes de la fiche.
   `Animateur` est un fait du problème du solveur, sérialisé par le scénario, les
   exports, le MCP et l'espace des collègues : une donnée d'un tiers qui ne doit
   sortir par aucun d'eux ne se range pas dans le type qu'ils lisent tous.
2. **L'animateur voit et modifie les siens** dans son espace, jamais ceux d'un autre.
3. **Le représentant légal d'un mineur reste facultatif**, avec un avertissement à la
   saisie (par identifiant) et une ligne dans le rapport de conformité : refuser
   l'enregistrement bloquerait la saisie d'une personne à joindre pour un défaut
   d'information qu'on ne maîtrise pas.
4. **Une duplication d'édition ne les recopie pas**, et la fiche supprimée les
   emporte : la conservation d'un numéro de tiers ne dépasse pas l'édition.

## Alternatives écartées

- **Un onglet réservé aux administrateurs** : l'organisation confie ce rapport à
  une personne RH qui ne doit pas administrer l'instance.
- **Le droit d'organisateur élargi** : il ouvre toute l'administration de
  l'édition ; le rapport nomme des mineurs, il se donne séparément.
- **Réécrire les plafonds dans le rapport** : un second endroit qui dérive le
  jour où un seuil bouge. Les plafonds sont lus, jamais recopiés.
