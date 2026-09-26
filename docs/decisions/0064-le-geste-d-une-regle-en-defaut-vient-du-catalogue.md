# 0064 — Le geste d'une règle en défaut vient du catalogue, le poids en dernier

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : `ConstraintCatalog` (leviers), `BlockerPlaybook`, `GET /api/constraints` (`actions`, `hotspots`), onglet Problèmes du Diagnostic
- **Voisine de** : [0054](0054-placer-sur-un-siege-vide-ecrit-le-siege-puis-le-verrou.md) (« Placer » sur un siège vide)

## Contexte

Sur un plan sans écart dur, l'onglet Problèmes du Diagnostic montrait douze
règles de qualité en défaut, et douze fois le même bouton : baisser le poids de
la règle. Les explications nommaient pourtant le bon geste — monter le niveau
d'un animateur, retirer le drapeau premium d'un stand, relever un plafond —
sans jamais y mener. Le playbook ne connaissait de levier propre qu'à six
règles, toutes dures ; les autres retombaient sur la ligne de leur catégorie,
qui pour la qualité était le poids. Et aucune carte ne disait où l'écart
mordait ni sur qui : « 554 correspondances », sans un stand ni un jour.

## Options envisagées

| Option | Ce qu'elle coûte |
| --- | --- |
| **A.** Garder le poids comme geste par catégorie, et enrichir les phrases | L'écran continue de proposer de s'en soucier moins ; les phrases nomment un geste sans lien |
| **B.** Écrire le geste de chaque règle dans le playbook (`BY_RULE`) | Le geste vit loin de la règle qu'il corrige ; une règle ajoutée au catalogue retombe sans bruit sur le poids |
| **C.** Des leviers typés portés par le catalogue, traduits en écrans par le playbook, le poids ajouté en dernier à toute règle pesée | Un type de plus dans le catalogue, et une liste à tenir à jour — tenue par test |
| **D.** Le geste choisi à l'écran à partir du texte de la remédiation | Un contrat implicite sur des phrases ; un client MCP ne le lit pas |

Pour le « où » : le pivot des écarts compte chaque axe à part — ce stand, ce
jour — et ne dit pas quel créneau de quel stand, la seule chose qu'on ouvre
pour corriger. Un quatrième axe « stand × créneau » dans le pivot aurait changé
un contrat que l'écran Règles du planning et les assistants lisent déjà.

## Décision

**Option C**, et un champ à part pour le « où ».

- `ConstraintCatalog` porte les **leviers** de chaque règle moyenne et souple,
  et des règles dures dont le levier est précis : une liste ordonnée de types
  (`SEAT`, `SKILL`, `STAND_PROFILE`, `CAP`, `THRESHOLD`, `REPAIR`…).
  `BlockerPlaybook` traduit chaque levier en une navigation positionnée sur le
  premier écart ; une règle dure sans levier garde la ligne de sa catégorie.
- La baisse du poids n'est **jamais** un levier : le playbook l'ajoute, en
  dernier, à toute règle pesée, et jamais à une règle dure. L'écran la masque
  quand le poids est déjà au plus bas.
- `BlockerPlaybookTest` échoue sur une règle moyenne ou souple sans levier, sur
  une règle dont le premier geste serait le poids, et sur un « Régler le
  plafond » posé sur une règle qui ne lit aucun réglage.
- `hotspots`, dans la vue des règles seulement (pas dans le diagnostic d'un
  job) : les trois couples stand × créneau qui rassemblent le plus d'écarts,
  en identifiants. Chaque couple ouvre la page Planning sur ce siège.

## Conséquences

- Les codes d'action changent pour un client MCP : `AJUSTER_POIDS` disparaît
  (toute règle pesée finit sur `BAISSER_POIDS`), et `OUVRIR_FICHE_STAND`,
  `REGLER_PLAFOND`, `REGLER_SEUILS`, `VOIR_CHARGE`, `VERROUILLER` apparaissent.
- Le libellé du banc devient « Qui peut tenir ce siège ? » partout ; le
  Diagnostic le pose sur place, sur un siège libre du créneau, et son « Placer »
  est celui de [0054](0054-placer-sur-un-siege-vide-ecrit-le-siege-puis-le-verrou.md).
- Ajouter une règle moyenne ou souple, c'est lui donner un levier : le build le
  rappelle.
