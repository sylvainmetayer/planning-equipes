---
ordre: 235
---

### La consigne s'écrit en plages datées

- **Avant (3.x)** : une consigne d'édition (une bande fermée pour tous les stands, les ouvertures de compensation choisies stand par stand) était posée à chaque lecture au-dessus des horaires des stands, sans rien écrire sur eux.
- **Après (4.0)** : poser une consigne **écrit des plages datées** sur chaque stand de sa date, marquées comme les siennes : une fermeture sur la bande, une sur chaque créneau qu'elle a ajouté, une ouverture par fenêtre de compensation ([décision 0125](repo:docs/decisions/0125-la-consigne-s-ecrit-en-plages-datees.md)). Ces plages sont lues au-dessus de celles du stand, et la journée obtenue est la même. Lever la consigne retire exactement ses plages ; une plage de consigne modifiée à la main entre-temps perd sa marque et reste. Les gestes (proposer, aperçu, poser, changer, lever), l'écran Consignes et les outils MCP `appliquer_consigne`, `lever_consigne`, `simuler_consigne` et `simuler_levee_consigne` ne changent pas. La migration `V168` écrit les plages des consignes déjà posées, passées comprises ; un test compare, sur chacun des scénarios livrés placé sous deux consignes, les fenêtres et les sièges obtenus à ceux de la 3.x, et un autre rejoue la migration contre l'écriture de l'application.
- **Ce que vous faites** : rien pour vos données. Une fiche de stand ouverte avant la pose ou la levée d'une consigne est refusée à l'enregistrement (rechargez-la). Les plages d'une consigne apparaissent désormais dans les plages du stand (`consigne: true` dans l'API) ; un script qui lit les horaires d'un stand doit les lire avec elles. Un export YAML ne les porte pas : il porte la consigne, et l'import les réécrit.
