# 0127 — Un outil MCP par objet et par verbe, les anciens noms en alias jusqu'en 5.0

- **Statut** : accepté, implémenté (plan, calcul, règles, échanges ; les autres familles suivent)
- **Date** : novembre 2026
- **Portée** : outils MCP, table `mcp-renommages.tsv`, invites et ressources MCP, guide de migration 4.0
- **Révise** : [0100](0100-convention-de-nommage-des-outils-mcp.md) § 5 (« pas d'alias »)
- **Prolonge** : [0100](0100-convention-de-nommage-des-outils-mcp.md) (la convention `<verbe>_<objet>`), [0121](0121-l-etat-de-la-journee-se-lit-sur-la-relecture-et-le-verrou.md) et [0126](0126-un-generateur-de-creneaux-unique.md) (les premiers alias)

## Contexte

La 3.1 publie 157 outils. Un assistant les relit tous à chaque connexion, et beaucoup disent la même chose sur des objets voisins : trois lectures du plan (`etat_planning`, `lister_affectations`, `synthese_affectations`), trois simulations d'un siège, quatre réglages d'une règle. 0100 a fixé la forme des noms et refusé les alias ; 0121 et 0126 ont gardé leurs anciens noms quand même, parce qu'une invite ou un script écrit pour la 3.x doit continuer de marcher pendant toute la 4.x.

## Décision

1. **Un outil par objet et par verbe.** Ce qui distinguait des outils voisins devient un argument : la vue lue (`consulter_planning`, `vue` = `etat`, `synthese` ou `affectations`), le geste (`simuler_affectation` et `modifier_affectation`, `mode` = `affecter`, `deplacer` ou `candidats`), la décision (`modifier_demande_echange`, `decision` = `accepter` ou `refuser`), le réglage (`modifier_contrainte`, un seul de `actif`, `poids`, `poidsParDefaut`, `niveau` par appel). La cible est d'environ 60 à 80 outils pour toute l'application.
2. **Le verbe garde ses hints** (0100 § 2) : lire et écrire restent deux outils, `simuler_affectation` à côté de `modifier_affectation`, pour qu'un humain puisse dire non entre les deux. Un argument ne change jamais la classe de hints d'un outil.
3. **Le calcul a deux outils**, comme l'écran a deux boutons : `lancer_calcul` (« Calculer », complet depuis le plan en place, `deZero` pour recommencer) et `lancer_correction` (« Corriger », les seuls sièges en écart). `lancer` est un verbe destructif de 0100 : un calcul remplace le plan enregistré. `PLAN_COURANT` (échouer faute de plan à reprendre) n'a pas d'équivalent : sans plan, « depuis le plan en place » part de zéro, et l'ancien nom garde `reamorcage` jusqu'en 5.0.
4. **Les anciens noms restent publiés jusqu'en 5.0**, avec leurs arguments et leurs réponses, la description commençant par « Ancien nom, gardé jusqu'en 5.0 : utiliser … ». La table `mcp-renommages.tsv` donne pour chacun le nouvel outil ; un outil ajouté en 4.0 et regroupé avant toute version publiée (`modifier_niveau_contrainte`) disparaît sans alias.
5. **Un outil regroupé journalise le geste fait** : le catalogue lui donne une action par défaut, et l'outil déclare l'autre (`CurrentAction`) quand l'argument en choisit une autre — un déplacement, un refus d'échange, une activation de règle.

## Options écartées

- **Pas d'alias** (0100 § 5) : une invite écrite pour la 3.x casserait au premier appel, sans que l'opérateur sache quoi changer ; le coût des doublons dans les tests structurels est une ligne par ancien nom, retirée en 5.0.
- **Un outil qui simule et applique selon un drapeau** : il suffirait d'un argument oublié pour écrire sans étape de relecture.

## Conséquences

- Plan, calcul, règles et échanges passent de 33 outils à 21, plus 23 anciens noms publiés jusqu'en 5.0.
- En 5.0, les méthodes `@Deprecated` des classes `mcp` partent avec leurs lignes des listes structurelles.
