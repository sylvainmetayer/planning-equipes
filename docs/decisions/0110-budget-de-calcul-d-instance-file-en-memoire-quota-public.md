# 0110 — Le budget de calcul est un réglage d'instance, la file est en mémoire, le quota ne vaut que sur une instance publique

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : solveur (budget, file des jobs, quota), API et outils MCP des paramètres, scénario YAML, configuration, mentions légales
- **Remplace en partie** : [0051](0051-budget-de-calcul-par-edition-sous-plafond-d-exploitant.md) (le budget ne se règle plus par édition) et, dans [0075](0075-quota-de-calcul-de-l-instance-dans-l-application.md), la file rejouée au démarrage qui n'était pas recomptée
- **Limite** : [0075](0075-quota-de-calcul-de-l-instance-dans-l-application.md) (le quota ne vaut que sur une instance publique) et [0040](0040-mentions-legales-exigees-au-demarrage.md), [0105](0105-variables-de-marque-et-de-mentions-groupees.md) (le refus de démarrer sans mentions ne vaut que sur une instance publique)
- **Précise** : [0107](0107-parametres-en-quatre-sections.md) (la section `instance` devient `calcul`)

## Contexte

Le solveur est **un seul pour toute l'instance** : un calcul à la fois, toutes
éditions confondues. Pourtant la durée maximale et l'arrêt sur plateau se
réglaient par édition, sous deux plafonds d'exploitant (0051), et chaque
édition devait dire comment elle comptait occuper une machine qu'elle partage.
Trois autres choses allaient avec :

- la file des calculs « planifiés » était écrite en base et **rejouée au
  démarrage**, ce qui coûtait un champ `rejouable`, un rejeu qui contourne le
  quota, et une table dont les lignes en attente pouvaient lancer un vrai
  calcul sans que personne l'ait demandé après une panne ;
- le quota de calcul (0075) et la file plafonnée n'étaient pensés que pour une
  instance qui partage sa machine avec une production, une démonstration
  publique : ailleurs, ils refuseraient l'organisateur chez lui ;
- le refus de démarrer sans mentions légales (0040) valait pour toute
  instance en production, y compris celle que seule une équipe atteint, qui
  n'a personne à qui publier une page.

## Décision

1. **Le budget est un réglage d'instance.** `planning.solver.seconds-limit`
   (`SOLVER_SECONDS_LIMIT`, 900 s) et `planning.solver.unimproved-seconds-limit`
   (`SOLVER_UNIMPROVED_SECONDS_LIMIT`, 180 s) valent pour toutes les éditions.
   `SOLVER_SECONDS_LIMIT_MAX` ne borne plus que ce qu'un lancement demande
   (`?seconds=`, MCP) et la vérification du besoin ; le plafond du plateau est
   retiré. Plus aucune valeur « enregistrée puis rognée » : le `CappedFrom` du
   budget, ses deux colonnes de l'historique des calculs et l'avertissement
   qui l'accompagnait disparaissent. L'écran *Règles du planning › Calcul*
   affiche le budget sans le modifier.
2. **La migration archive avant de retirer.** `V144` copie les durées et
   plateaux qu'une édition avait réglés dans `solve_budget_avant_4_0`, puis
   retire les deux colonnes de `parametres_solveur`. L'archive n'est ni lue,
   ni exportée, ni sauvegardée par l'export SQL ; la 5.0 la supprime.
3. **La section `instance` de 0107 devient `calcul`.** Elle ne porte plus que
   le mail de fin de résolution, qui est bien un réglage d'édition, et expose le
   budget de l'instance en lecture seule. `instance` nommait ce qu'elle ne
   contient plus. L'API sert `/api/parametres/calcul` ; l'ancienne racine
   `/api/parametres-solveur` reste un alias jusqu'en 5.0, qui ignore les champs
   de budget qu'un client 3.x enverrait encore.
4. **Un scénario YAML ne fixe plus le budget.** La clé `parametresSolveur` d'un
   ancien fichier est lue, ignorée, et l'import le dit dans ses avertissements ;
   l'export ne l'écrit plus. Les scénarios livrés ne le portent plus : leur durée
   est celle de l'instance (ou de la variable que pose leur test).
5. **La file est en mémoire.** Un job en file n'est jamais écrit dans
   `solver_job` ; au démarrage, rien n'est rejoué. Une ligne laissée en file
   (une 3.x) ou qui tenait le solveur devient `INTERROMPU` avec la raison écrite
   sur la tâche, le journal en donne le nombre, et l'arrêt du serveur écrit le
   nombre de tâches planifiées qu'il perd. Seul un calcul complet resté sur un
   worker (0086) est suivi de nouveau : ce n'est pas la file mais un calcul qui
   tourne.
6. **Le quota ne vaut que sur une instance publique**, déclarée par
   `PUBLIC_INSTANCE=true`. Ailleurs, `SolverQuota.consume()` et `checkQueue()` ne
   font rien, et le démarrage avertit si les deux variables du quota sont posées.
   « Un nouveau chemin de calcul appelle `consume()` » reste vrai : le garde est
   dans `SolverQuota`, pas chez ses appelants.
7. **Le refus de démarrer sans mentions légales ne vaut que sur une instance
   publique.** Sur une autre, la même phrase est un avertissement au démarrage.
   `LEGAL_DEMO_INSTANCE=true` dispense toujours des deux.

## Alternatives écartées

- **Garder un budget par édition, sous le plafond.** C'est l'état que cette
  décision quitte : le solveur est partagé, et un réglage par édition ne dit rien
  de ce que la machine peut donner. Il avait aussi fait naître un état
  intermédiaire (valeur enregistrée au-dessus d'un plafond abaissé depuis) qu'il
  fallait expliquer sur chaque job.
- **Régler le budget depuis l'écran, dans une table d'instance.** Cela aurait
  demandé une table, un dépôt, une précondition d'écriture et sa place dans
  chaque export, pour un réglage qu'un exploitant change une fois par an ; la
  variable d'environnement est déjà le geste d'exploitation des autres plafonds.
  Rien ne l'interdit plus tard : l'API de lecture est déjà celle de l'instance.
- **Supprimer `solve_budget` sans l'archiver.** Plus simple, mais irréversible :
  une 3.x ne retrouve pas les durées que ses éditions avaient choisies. L'archive
  coûte une table que la 5.0 supprime.
- **Écrire la file sans la rejouer.** Cela garde une trace de ce qui est perdu,
  mais écrit à chaque mise en file une ligne que rien ne lit ; l'historique des
  calculs qui ont tenu le solveur dit déjà ce qui est arrivé, et l'arrêt écrit
  ce qu'il perd.
- **Déduire « instance publique » de `PUBLIC_URL`, du mode de lancement ou de
  `LEGAL_DEMO_INSTANCE`.** Comme pour 0040, ce qu'une instance est *faite pour*
  ne se lit nulle part dans sa configuration : le drapeau est explicite.

## Conséquences

- Une instance qui était en production et ne déclare pas `PUBLIC_INSTANCE`
  cesse de refuser de démarrer sans mentions et d'appliquer son quota : le guide
  de passage à la 4.0 le dit en toutes lettres, avec l'action à faire.
- Un redémarrage perd les calculs planifiés ; le guide le dit aussi.
- `SOLVER_UNIMPROVED_SECONDS_LIMIT_MAX` est lue pour avertir (`RetiredVariables`)
  puis retirée en 5.0.
- Les clients 3.x qui écrivaient `dureeResolutionSecondes` ou `plateauSecondes`
  par `/api/parametres-solveur` voient ces champs ignorés ; ceux qui lisaient
  `cappedFromSecondsLimit` sur un job n'en trouvent plus.
- Le tournage de la vitrine fixe sa durée par `SOLVER_SECONDS_LIMIT`, plus par
  le scénario.
