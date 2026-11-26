---
ordre: 200
---

### Le gel du référentiel devient la phase de l'édition

- **Avant (3.x)** : le référentiel se figeait famille par famille (stands, créneaux, typologies et emplacements, compétences), chacune posée et levée à part dans Paramètres (table `gel_referentiel`). Rien ne se figeait de lui-même, pas même après une publication.
- **Après (4.0)** : une édition a une **phase**, Préparation, Calcul, Publiée ou Terminée ([ADR 0115](repo:docs/decisions/0115-la-phase-de-l-edition-remplace-le-gel.md)), et c'est elle qui fige. En Calcul et en Terminée, les quatre familles sont figées. En Publiée, la structure (stands, créneaux, typologies et emplacements) est figée mais les fiches des animateurs, compétences comprises, restent modifiables ; la structure peut être **débloquée** pour un changement de dernière minute, sans quitter la phase, et la publication suivante la refige. **La première publication fait passer l'édition en Publiée** ; les autres changements de phase sont des gestes explicites (`PUT /api/editions/courant/phase`). Les consignes, les disponibilités, les ajustements et les verrous restent libres dans toutes les phases, et le passé figé (`PASSE_FIGE`) reste un réglage d'instance, sans lien avec la phase.
- **La migration `V155` déduit la phase de l'état de chaque édition, et cette correspondance perd de l'information** :

| État 3.x | Phase 4.0 |
| --- | --- |
| Au moins une publication, aucune famille de structure figée | Publiée, structure débloquée : rien ne se ferme à la mise à jour, la prochaine publication la refige |
| Au moins une publication, une famille de structure figée | Publiée, structure figée **en entier** |
| Aucune publication, au moins une famille figée | Calcul : les **quatre** familles figées |
| Aucune publication, aucune famille figée | Préparation |

  Ce qui se perd : le détail par famille. Une édition qui n'avait figé que ses stands voit aussi ses créneaux, ses typologies et emplacements et ses compétences figés ; une édition publiée qui avait figé ses compétences les retrouve modifiables ; la date de gel de chaque famille devient une seule date de phase. La table `gel_referentiel` reste en place sans être lue, hors de l'export SQL, pour un retour à la 3.x, et la 5.0 la supprime.
- **Ce que vous faites** : après la mise à jour, ouvrez chaque édition en cours et vérifiez sa phase sur l'accueil (ligne « Phase de l'édition ») ou dans Administrer › Éditions. Si une famille est figée alors que vous la vouliez ouverte, changez la phase à cet endroit : repassez en Préparation pour rouvrir les quatre familles, ou, en Publiée, cliquez sur « Débloquer la structure » (un bandeau le rappelle tant qu'elle est débloquée). La carte « Gel du référentiel » de Paramètres, onglet *Édition*, n'existe plus. Les outils MCP `figer_referentiel` et `lever_gel` sont remplacés par `modifier_phase_edition` (argument `phase`, ou `structure` pour débloquer et refiger) : un assistant ou une invite qui les nomme doit changer de nom, sans alias. Jusqu'en 5.0, les routes `PUT` et `DELETE /api/editions/courant/gel/{famille}` restent des alias de la phase (figer une famille en Préparation les fige toutes) ; un script qui les appelle doit passer à `PUT /api/editions/courant/phase` et `/api/editions/courant/deblocage-structure` avant la 5.0. Après une publication, un script ou un import qui modifiait des stands ou des créneaux est refusé en `409 REFERENTIEL_FIGE` tant que la structure n'est pas débloquée.
