# 0126 — Un générateur de créneaux unique, dont les journées types deviennent les gabarits

- **Statut** : accepté, implémenté (serveur, API, outils MCP ; l'écran suit)
- **Date** : novembre 2026
- **Portée** : schéma (migration `V169`), `domain/Gabarit`, `service/referentiel/GenerateurCreneaux` et `GabaritRepository`, API (`/api/generateur-creneaux`), outils MCP, scénario YAML (version 6) ; routes et outils des journées types, de la série récurrente et de la dérivation gardés en alias jusqu'en 5.0
- **Remplace** : [0032](0032-journees-types-nommees-vacations-fixes.md) pour le nom et la place des journées types (devenues les gabarits du générateur) ; leur sémantique est gardée telle quelle
- **Applique** : [0091](0091-refonte-fonctionnelle-4-0.md) (un générateur unique, les journées types fondues dedans)
- **Prolonge** : [0104](0104-les-horaires-d-un-stand-sont-une-liste-de-plages-datees.md) (le modèle d'un stand, déplié par la porte unique des créneaux), [0100](0100-convention-de-nommage-des-outils-mcp.md) (les noms d'outils)
- **Voisines** : [0037](0037-une-grille-est-toujours-des-vacations.md) (une grille est faite de vacations, inchangé), [0121](0121-l-etat-de-la-journee-se-lit-sur-la-relecture-et-le-verrou.md) (les anciens noms d'outils gardés en alias)

## Contexte

En 3.x, quatre chemins produisaient la grille en lot, chacun avec ses routes,
ses outils et sa façon de l'écrire : les journées types et leur calendrier
(0032), la série récurrente des créneaux, la dérivation depuis les horaires des
stands, et la grille de saisie des ouvertures. Un organisateur devait savoir
lequel prendre, un assistant MCP en voyait douze outils, et chaque chemin avait
son aperçu à lui. La 4.0 fait du modèle d'un stand (0104) le point de départ
naturel de la grille ; l'audit fonctionnel a décidé d'un générateur unique où
les journées types sont fondues.

## Options envisagées

**(A) Un générateur à sources, les journées types renommées en gabarits.**
Retenu. Une source dit d'où viennent les créneaux : les horaires des stands
(leur modèle et leurs plages), les gabarits appliqués à leurs dates, ou un
motif récurrent. Trois gestes valent pour toutes : l'aperçu, l'application, le
retrait des dates du calendrier.

**(B) Les journées types deviennent des règles du modèle des stands.** Écarté :
une journée type dit les vacations d'un jour pour tout l'événement, un modèle
de stand dit quand un stand est ouvert ; les fondre aurait obligé à répéter
une journée type sur chaque stand, et perdu le calendrier.

**(C) Ne fusionner que les routes, garder les quatre services.** Écarté :
l'écran à venir aurait dû reconstruire lui-même l'aperçu de chaque chemin, et
le contrôle de grille serait resté appelé à quatre endroits.

## Décision

1. **Un service, `GenerateurCreneaux`**, est la seule porte de la grille en
   lot : `preview` et `apply` prennent une demande à source (`GABARITS`,
   `STANDS`, `RECURRENCE`) et rendent les créneaux produits et le contrôle de
   la grille obtenue ; `withdraw` retire des dates du calendrier ; les gabarits
   et leur calendrier s'écrivent par lui. Le créneau saisi à la main reste
   `CreneauService`, et le contrôle de grille reste `CreneauGridService`, que
   le générateur lit à chaque aperçu.
2. **Chaque source garde sa sémantique de la 3.x.** Les gabarits s'appliquent
   par différence sur `(date, début, fin)`, une date sans gabarit n'est jamais
   touchée (0032) ; la dérivation des stands ajoute ou remplace la grille ; la
   récurrence ajoute. Un test fige, sur tous les scénarios livrés, les
   créneaux que chaque source produit, relevés sur la 3.x avant la fusion : la
   grille est l'entrée du solveur, elle ne bouge pas d'un créneau.
3. **Tout créneau créé passe par `CreneauRepository`**, la porte qui déplie le
   modèle d'un stand sur un jour que l'édition ne comptait pas (0104).
4. **Une journée type devient un gabarit**, entrée pour entrée : même nom,
   mêmes vacations, mêmes dates. `V169` renomme les tables (`gabarit`,
   `gabarit_vacation`, `gabarit_date`) sans toucher une ligne. Ce que le
   générateur refuserait d'appliquer (aucune vacation, une vacation de durée
   nulle, deux fois la même) ne se convertit pas : la ligne part, avec ses
   dates, dans `journee_type_avant_4_0`, jusqu'en 5.0. Le YAML passe en
   version 6, la section `journeesTypes` devenant `gabarits` ; un fichier qui
   porte les deux garde `gabarits` et le dit.
5. **Les anciens noms restent des alias jusqu'en 5.0.** Les routes
   `/api/journees-types`, `/api/creneaux/recurrence` et `/api/creneaux/derivation`
   délèguent au générateur et gardent leurs anciennes clés (`journeesTypes`,
   `journeeTypeId`) : l'écran actuel continue de s'en servir jusqu'au sien. Les
   douze outils MCP restent publiés, leur description ouvrant sur « Ancien
   nom », à côté des cinq nouveaux (`lister_gabarits`, `modifier_gabarit`,
   `supprimer_gabarit`, `previsualiser_generateur_creneaux`,
   `appliquer_generateur_creneaux`). C'est l'exception à 0100 § 5 que la
   relecture des journées a ouverte (0121) : un ancien nom est lu pendant toute
   la 4.x.
6. **La grille de saisie des ouvertures reste à part.** Elle écrit les plages
   d'un stand depuis une case par créneau ; elle ne produit aucun créneau, et
   son import retiré par ailleurs, elle n'a rien à faire dans le générateur.

## Conséquences

- L'écran Calendrier peut s'écrire sur une seule API : un aperçu, une
  application, un retrait, quelle que soit la source.
- Un assistant MCP fait avec cinq outils ce que faisaient douze ; les alias
  partent en 5.0, avec `journee_type_avant_4_0`.
- Les messages d'erreur disent encore « journée type » tant que l'écran le dit.
