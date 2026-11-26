# 0115 — La phase de l'édition remplace le gel du référentiel par famille

- **Statut** : accepté, implémenté (côté serveur ; l'écran et l'outil MCP de la phase suivent)
- **Date** : novembre 2026
- **Portée** : schéma (migration `V155`), `domain/Edition`, `service/edition/EditionPhaseService`, `service/referentiel/GelReferentielService`, publication, API (`/api/editions/courant/phase`, `/api/editions/courant/deblocage-structure`), routes et outils du gel gardés en alias
- **Remplace** : [0052](0052-gel-du-referentiel-distinct-du-verrou.md) (le gel posé famille par famille) ; ses familles, ses champs couverts et son contrat d'annotation sont gardés
- **Applique** : [0091](0091-refonte-fonctionnelle-4-0.md), point 3 (trois mécanismes de gel, dont la phase de l'édition)
- **Voisines** : [0044](0044-le-passe-est-fige.md) (le passé figé, inchangé), [0072](0072-une-seule-edition-active.md) (l'édition active, indépendante de la phase), [0089](0089-la-levee-avertit-quand-les-journees-figees-vident-la-semaine.md) (les journées figées par verrou, que la phase ne touche pas)

## Contexte

La 3.x figeait le référentiel famille par famille (0052) : stands, créneaux,
typologies et emplacements, compétences, chacune posée et levée à part. Une
préparation se pense pourtant en étapes de l'édition, pas en familles :
« on prépare », « on calcule », « c'est publié », « c'est fini ». Quatre
interrupteurs disaient la même chose moins bien, et rien ne figeait le
référentiel au moment où le risque apparaît — la première publication —
puisque le gel n'était jamais imposé.

## Décision

**D1 — Une phase par édition, quatre valeurs.** `edition.phase` vaut
`PREPARATION`, `CALCUL`, `PUBLIEE` ou `TERMINEE`, avec `phase_depuis`. Ce que
chaque phase fige :

| Phase | Stands, créneaux, typologies et emplacements | Compétences des animateurs |
| --- | --- | --- |
| Préparation | modifiables | modifiables |
| Calcul | figés | figées |
| Publiée | figés, sauf structure débloquée | modifiables |
| Terminée | figés | figées |

Les familles de 0052 et la liste des champs qu'elles couvrent restent la
mesure de ce qui est figé : un stand renommé reste un stand modifiable, une
fiche d'animateur neuve aussi. Ce qui restait ouvert sous tout gel le reste
dans toutes les phases : disponibilités, souhaits, déclarations, ajustements,
verrous et consignes.

**D2 — Publier fait passer en Publiée.** La première publication fait passer
l'édition en `PUBLIEE` (de Préparation comme de Calcul) ; une publication
suivante l'y laisse. Une édition terminée reste terminée : publier ne rouvre
jamais rien. Revenir en Calcul ou en Préparation est un geste explicite, de
même que passer en Terminée. Toutes les transitions sont permises, retour
compris : la phase est une déclaration, et revenir en Préparation est la façon
de rouvrir le référentiel.

**D3 — En Publiée, la structure se débloque pour un changement de dernière
minute.** Ajouter un stand la veille ne doit pas obliger à quitter la phase
Publiée. `PUT /api/editions/courant/deblocage-structure` pose
`structure_debloquee` sans changer de phase ; `DELETE` sur le même chemin la
refige. Le geste est journalisé (`STRUCTURE_DEBLOQUEE`, `STRUCTURE_REFIGEE`),
réservé à qui écrit dans l'édition (administrateur, organisateur), refusé au
lecteur comme toute écriture. Il est refusé en `409` hors de la phase Publiée,
où il ne voudrait rien dire. **La publication suivante refige la structure**,
et entrer dans une phase aussi : on n'oublie pas un déblocage ouvert, il ne
survit pas au geste qui l'a rendu nécessaire. C'est le plus simple qui reste
sûr ; un refigement à heure fixe ou à la première résolution a été écarté
(une règle de plus à expliquer pour un cas que la publication couvre déjà).

**D4 — L'annotation garde son nom.** `@RefusedWhileFrozen(famille)` dit
d'une méthode qu'elle écrit ces familles ; seul change ce qui décide qu'elles
sont figées. La renommer `@RefusedInPhase` aurait touché une quinzaine de
services et le test structurel sans rien changer au contrat, et le code du
refus sur le fil (`409 REFERENTIEL_FIGE`, champ `familles`) reste celui que
l'écran sait lire. `GelReferentielStructuralTest` garde le même contrat : une
méthode d'écriture des services gardés sans annotation, sans appel à
`gel.refuseIfFrozen` dans son corps et sans argumentaire d'ouverture fait
échouer le build, et un appel d'une méthode annotée sur `this` est refusé. Le
message du refus dit comment rouvrir : repasser en Préparation, débloquer la
structure, ou changer la phase d'une édition terminée.

**D5 — La migration déduit la phase, et perd la finesse par famille.**
`V155` :

- une édition qui a déjà publié passe en `PUBLIEE` ; sa structure reste
  **débloquée** si aucune famille de structure n'était figée, pour qu'aucune
  écriture permise la veille ne soit refusée le lendemain de la mise à jour ;
- une édition sans publication dont une famille au moins était figée passe
  en `CALCUL`, qui fige les quatre familles ;
- sinon, `PREPARATION`.

Un référentiel figé « à la main » sur une seule famille se retrouve donc figé
en entier (décision de l'organisation), et une édition publiée qui n'avait
figé que ses compétences les voit rouvertes. Le guide « Passer à la 4.0 » le
dit. `gel_referentiel` n'est plus lue ni écrite : elle reste en place sous son
nom, hors de l'export SQL, pour un retour à la 3.x, et la 5.0 la supprimera.

**D6 — Les routes et outils du gel restent, en alias, jusqu'en 5.0.**
`PUT`/`DELETE /api/editions/courant/gel/{famille}` (dépréciées) et les outils
`figer_referentiel`, `lever_gel` traduisent le geste en phase : figer une
famille en Préparation fait passer en Calcul, lever en Calcul ramène en
Préparation ; dans une édition publiée, lever débloque la structure et figer
la refige ; lever dans une édition terminée est refusé. La lecture
`GET /api/editions/courant/gel` dit l'état de chaque famille d'après la phase.

**D7 — La duplication repart en Préparation**, comme le gel de 0052 ne se
recopiait pas. L'import d'un scénario, la restauration d'une copie avant
vidage et la remise à zéro restent refusés dès qu'une famille est figée : ils
remplacent toute l'édition. Ils ne changent pas la phase.

## Conséquences

- Le passé figé (`PASSE_FIGE`) reste un drapeau d'instance, indépendant de la
  phase.
- La phase n'a rien à voir avec l'édition active (0072) : une édition inactive
  a sa phase, et seule l'active publie, donc seule l'active passe en Publiée
  par publication.
- Changer de phase ou débloquer la structure est accepté pendant une
  résolution : comme le gel, la phase refuse les écritures à venir et ne
  change aucune donnée lue. Les écritures refusées pendant une résolution
  (`@RefusedWhileSolving`) le restent, indépendamment.
- Une limite de 0052 demeure : la lecture de la phase et l'écriture qu'elle
  garde sont deux transactions ; la phase protège d'une modification par
  mégarde, pas de deux gestes simultanés.
- L'import d'un dump SQL restaure la phase avec la ligne de l'édition.
- La date « figé depuis » que lisent les cadenas est celle de l'entrée dans la
  phase : un déblocage suivi d'un refigement ne la déplace pas.
- Les fixtures de bout en bout qui publient pour remplir l'espace animateur
  remettent ensuite l'édition en Préparation : elles figurent une édition en
  cours de préparation, que les specs modifient.
