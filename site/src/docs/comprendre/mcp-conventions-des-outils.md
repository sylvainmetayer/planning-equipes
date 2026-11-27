# Les règles que suit tout outil MCP

Vous savez ce que doit respecter un outil MCP, existant ou à ajouter : désigner l'édition par argument, faire revenir un refus métier lisible, déclarer ses hints et son nom publié, dire ce qu'il advient d'une écriture pendant un calcul, respecter le gel du référentiel, et signaler qu'il envoie un courriel. Chaque règle est tenue par un test structurel qui fait échouer le build : un outil ajouté plus tard ne peut pas y échapper par omission. Les outils eux-mêmes, famille par famille, sont dans [les outils du serveur MCP](mcp-outils.md) ; l'authentification et la confidentialité sont dans [le branchement d'un assistant](mcp-connexion.md).

MCP et REST sont deux appelants des mêmes règles : un outil délègue aux services et jamais à une ressource JAX-RS (`LayeringStructuralTest`).

## Désigner l'édition par argument

Une requête MCP n'est pas une requête JAX-RS : `EditionHeaderFilter` ne la voit jamais, et **`X-Edition-Id` n'a aucun effet sur `/mcp`**. Chaque outil qui travaille dans une édition porte un argument `edition`, qui accepte l'id ou le nom. Il est **obligatoire** ([ADR 0072](repo:docs/decisions/0072-une-seule-edition-active.md)) : omis, l'appel est refusé (`EDITION_REQUISE`) plutôt que d'agir dans une édition que personne n'a nommée. Une édition inconnue échoue de la même façon (`EDITION_INCONNUE`, avec la liste des éditions existantes), comme l'en-tête HTTP qui en nomme une supprimée : un repli silencieux enverrait l'écriture dans la mauvaise. L'id est essayé avant le nom, c'est pourquoi un nom d'édition de la forme d'un id (`E` suivi d'un nombre) est refusé à la création et au renommage.

Trois pièces s'en chargent. `@EditionArg` marque l'argument porteur (l'annotation fait le lien, le nom de l'argument n'y est pour rien). `@EditionCiblee` se pose sur la **classe** d'outils, si bien qu'un outil ajouté plus tard en hérite. `EditionCibleeInterceptor` lie l'édition autour de l'appel.

> **Piège d'auto-invocation.** Un outil qui en appelle un autre sur `this` court-circuite l'intercepteur. Passez par un helper privé, jamais par l'outil voisin.

`McpEditionStructurelleTest` échoue sur tout `@Tool` qui travaille dans une édition sans permettre de la désigner. Quelques exemptions, qui ne lisent ni n'écrivent rien dans une édition : `lister_scenarios` et `valider_scenario_yaml` (fichiers livrés, validation pure), les outils de pilotage des calculs (`consulter_calcul`, `arreter_calcul`, `supprimer_calcul`, et leurs anciens noms : le registre est global, chaque job portant l'édition pour laquelle il a été lancé), `lister_mesures_edition` et `supprimer_mesure_edition` (non cloisonnés pour qu'une édition se compare à la précédente) et les deux outils de sauvegarde. Tout autre outil sans édition est `@InstanceOnlyTool` (réservé à l'administrateur) ou justifié dans le test.

Deux précisions. Les lancements de solveur capturent l'édition **à la soumission** : le job y reste attaché même si l'édition active change ensuite. Et une section `edition:` dans un YAML importé prime sur l'argument, puisque le fichier désigne explicitement sa cible. `comparer_editions` prend `reference` et `cible` comme deux arguments ordinaires.

Une seule édition est **active** ; elle seule publie, envoie des courriels et ouvre l'espace animateur, le flux ICS et l'affichage mural. `lister_editions` la signale, `edition_courante` la nomme (en erreur entre deux événements, quand aucune ne l'est), `activer_edition` et `desactiver_edition` la changent.

## Faire revenir un refus métier

Sans traitement, un `BusinessError` repart en erreur JSON-RPC générique (`-32603`, « Internal error ») : la phrase qui dit ce qui est refusé est perdue, et l'assistant réessaie à l'identique faute de distinguer une saisie fautive d'un serveur cassé. `RefusMetierInterceptor`, pendant MCP de `api/BusinessErrorMapper`, fait revenir un `BusinessError` comme résultat d'outil en erreur (`isError`) portant sa phrase. **Seul un refus délibéré passe par là** : un `IllegalArgumentException` involontaire ou une panne de base gardent leur « Internal error » et leur alerte, comme le REST garde son 500.

Il se lie aux classes d'outils par `@RefusMetier`, troisième annotation à côté de `@EditionCiblee` et `@Journalise` : ce sont trois questions différentes, et `SauvegardeMcpTools` ne porte pas `@EditionCiblee`. Il s'applique **en dehors** de `EditionCibleeInterceptor`, si bien que le refus d'une édition inconnue, levé avant le corps de l'outil, arrive lui aussi. `McpRefusMetierStructurelleTest` échoue sur toute classe d'outils qui oublie le binding.

**La phrase traverse telle quelle, ce qui n'est sûr que parce qu'aucun refus ne nomme quelqu'un.** La repasser par `AnonymisationViolations` déclencherait par accident sa reconnaissance de la forme « Prénom Nom (id) » sur des phrases comme « Deux horaires de même portée (MONDAY,TUESDAY)… ». La règle est donc tenue à la source : `McpRefusMetierStructurelleTest` échoue sur un `BusinessError` construit à partir du nom, du prénom, de la date de naissance ou de l'adresse d'un animateur. Désignez toujours un animateur par son id dans un message de refus.

## Déclarer les hints et le nom

Les quatre *hints* de la spécification MCP sont déclarés sur **tous** les outils. Leurs défauts sont `destructiveHint = true` et `openWorldHint = true` : un outil muet comme `lister_stands` s'annoncerait comme un appel destructif vers l'extérieur, et le signal ne voudrait plus rien dire.

| Hint | Valeur |
| --- | --- |
| `readOnlyHint` | vrai pour les outils qui ne font que lire |
| `destructiveHint` | vrai pour les suppressions, les imports, une restauration d'instantané et les résolutions, qui remplacent le plan enregistré |
| `idempotentHint` | vrai quand rappeler l'outil avec les mêmes arguments ne change plus rien |
| `openWorldHint` | vrai pour les seuls outils qui envoient du courriel, faux partout ailleurs |

`McpAnnotationsStructurelleTest` tient les deux bouts. Le `openWorldHint = false` sert de marqueur (un bloc oublié garde le défaut et échoue), et les hints attendus sont dérivés du **préfixe du nom** de l'outil (`lister_`, `consulter_`, `analyser_`… en lecture ; `supprimer_`, `importer_`, `lancer_calcul`… destructifs ; `creer_`, `modifier_`… non destructifs) : un `supprimer_stand` déclaré en lecture seule échoue, et un nom que le test ne sait pas classer échoue aussi. Les outils sortants sont **énumérés** dans le test, jamais déduits : un envoi est une décision relue, et l'inscrire dans la liste est cette décision.

Chaque outil et chaque prompt porte son nom publié en toutes lettres (`@Tool(name = "lister_stands", …)` sur la méthode Java `listStands`). La méthode suit la règle du reste du code (anglais, camelCase) ; le nom publié reste français, c'est celui que l'assistant choisit, que le journal enregistre (`CatalogueActions`) et que citent les prompts. Renommer la méthode ne doit jamais renommer l'outil pour les clients déjà branchés. `McpToolNamesTest` échoue sur un `@Tool` ou un `@Prompt` sans `name` explicite, et les autres tests structurels lisent ce nom publié, jamais le nom Java.

## Nommer un outil

Le nom d'un outil est `<verbe>_<objet>` : le verbe d'intention d'abord, pris dans un ensemble fermé (`lister`, `consulter`, `analyser`, `diagnostiquer`, `comparer`, `simuler`, `previsualiser` pour lire ; `creer`, `modifier`, `activer`, `desactiver`, `verrouiller`, `appliquer`, `arreter`, `publier`, `envoyer` pour écrire ; `supprimer`, `deverrouiller`, `lancer`, `importer`, `restaurer` pour écraser ou effacer ; `exporter`), puis l'objet. C'est le verbe dont `McpAnnotationsStructurelleTest` dérive les hints : un outil de réglages à sections se scinde donc en un outil de lecture et un outil d'écriture (verbes `consulter` et `modifier`, objet `parametres`), chacun avec un argument `section`. Les noms publiés en 3.1.0 gardent le leur tant que le modèle qu'ils servent n'est pas refondu en 4.0 : `src/main/resources/mcp-renommages.tsv` dit, pour chacun, son nom courant ou `retiré`, et `McpRenommageTest` refuse un nom sans ligne, un renommage hors convention et une [table des anciens noms](mcp-outils.md#anciens-noms-doutils) qui ne suit plus le fichier. Il n'y a pas d'alias ([ADR 0100](repo:docs/decisions/0100-convention-de-nommage-des-outils-mcp.md)).

## Écrire pendant qu'un calcul tient l'édition

L'atterrissage d'un calcul réécrit les places et reprend les stands, créneaux et animateurs de son problème : une écriture faite entre-temps serait défaite. Chaque outil d'écriture a donc une réponse à « et si un calcul tient l'édition ? », et `McpWarnsWhileSolvingStructuralTest` fait échouer le build sur un outil qui n'en a pas. Il relit aussi, pour chaque outil refusé, que la méthode de service nommée porte `@RefusedWhileSolving` (ou l'appel explicite d'un refus conditionnel que `RefusedWhileSolvingStructuralTest` argumente). Les trois listes sont disjointes et exhaustives.

| Pendant un calcul de l'édition | Outils | Ce que reçoit l'assistant |
| --- | --- | --- |
| **Refusé** | modification et suppression d'animateurs et de stands, horaires et plages d'un stand, modification et suppression de créneaux, créneaux récurrents et dérivés, matérialisation des gabarits, consignes, `modifier_affectation`, `modifier_demande_echange`, restauration d'instantané, imports, réinitialisation | le refus (`409` côté REST) avec le job en cause nommé |
| **Accepté, averti** (`@WarnsWhileSolving`) | typologies, emplacements, contraintes ad hoc, paramètres légaux et durée du solveur, verrouillages, activation et poids des contraintes, création d'un stand, d'un animateur ou d'un créneau, `modifier_etat_journee` | la réponse habituelle, avec **`RESOLUTION_EN_COURS`** dans `avertissements` |
| **Sans rapport** | le solveur lui-même, éditions, gel du référentiel, publications et envois, préréglages de consigne, gabarits non matérialisés, instantanés, paramètres de notification et de sauvegarde | la réponse habituelle ; chaque exclusion porte son motif dans le test |

`RESOLUTION_EN_COURS` veut dire : **enregistré**, mais le calcul en cours a lu le référentiel et le plan à son démarrage et ne verra pas cette écriture ; relancez un calcul une fois celui-ci terminé. `consulter_calcul` nomme le job et porte `feasibleAtSeconds` (les secondes écoulées quand son plan est devenu faisable, `null` tant qu'il ne l'est pas) : un assistant peut proposer d'arrêter là, `arreter_calcul` gardant le meilleur plan atteint. Sur `modifier_etat_journee`, il dit en plus qu'une relecture peut ne pas survivre : l'atterrissage retire la relecture des journées que le calcul a recalculées, sauf celles entièrement passées ou figées (verrou `JOUR`) ; une journée figée pendant le calcul n'est pas vue par lui. Il ne vient ni d'un calcul d'une autre édition (c'est l'argument `edition` qui compte) ni d'un calcul seulement en file, qui lira l'écriture à son tour.

Ne transformez jamais un refus en avertissement : il protège contre la résurrection de données par l'atterrissage. Les écrans verrouillent ces saisies le temps du calcul ; un assistant sans écran n'a que l'avertissement.

## Respecter la phase de l'édition

La phase de l'édition décide quelles familles du référentiel sont figées (`STANDS`, `CRENEAUX`, `TYPOLOGIES_EMPLACEMENTS`, `COMPETENCES`, [ADR 0115](repo:docs/decisions/0115-la-phase-de-l-edition-remplace-le-gel.md)), et `etat_edition` dit lesquelles le sont dans son bloc `gel`. `modifier_phase_edition` la change : son argument `phase` déclare `PREPARATION`, `CALCUL`, `PUBLIEE` ou `TERMINEE`, retour en arrière compris, et son argument `structure` (`DEBLOQUER` ou `REFIGER`, en `PUBLIEE` seulement) rouvre ou referme la structure sans quitter la phase ; l'un ou l'autre, jamais les deux. Il remplace les deux outils du gel de la 3.x, qui n'existent plus (le tableau des [anciens noms](mcp-outils.md) les renvoie vers lui) : les routes REST du gel de la 3.x, elles, restent des alias jusqu'en 5.0. Chaque geste est journalisé sous l'action de sa route (phase changée, structure débloquée, structure refigée) et l'outil est classé « sans rapport » avec un calcul : une phase refuse les écritures à venir, elle ne change aucune donnée qu'un calcul lit.

Le refus d'une écriture figée est posé sur les services, que les outils appellent comme les écrans : `modifier_stand` qui change un effectif, `supprimer_creneau`, `creer_typologie` ou `modifier_animateur` qui change des compétences reviennent en refus métier, avec la phrase qui nomme les familles figées et dit comment les rouvrir ; `modifier_stand` qui ne touche que le nom, ou `appliquer_consigne`, passent. Ne rouvrez le référentiel que sur demande explicite : après une publication, toute retouche fait bouger des plannings déjà envoyés.

## Signaler un courriel sortant

Quatre outils font sortir un courriel et sont les seuls à s'annoncer `openWorldHint = true` : `publier_planning`, `envoyer_planning_animateur`, `relancer_animateurs` et `configurer_collecte_disponibilites` lorsqu'il prévient les animateurs. Ils sont refusés sur une édition inactive (`409 EDITION_INACTIVE`), et `ActiveEditionEmissionStructuralTest` échoue sur un envoi ou un outil sortant ni gardé ni argumenté.

`publier_planning` capture d'abord l'instantané publié puis écrit aux gens : un envoi qui échoue à mi-chemin laisse un plan publié cohérent et une trace de qui a été manqué, que relit `lister_destinataires_publication`, par id. `relancer_animateurs` suit la règle de la relance de nuit : **une seule relance par personne et par publication**, quelle que soit la main. L'outil réserve la même clé que le job nocturne, donc quelqu'un relancé par l'assistant ne l'est pas une seconde fois la nuit suivante, sauf si l'envoi de nuit a échoué et rendu la clé. Le compte rendu ne porte que des ids : `envoyes`, `dejaConfirmes`, `sansEmail`, `dejaRelancesPourCettePublication`, `echecs`, `sansPoste` et `adresseRefusee`. Il est refusé si rien n'a jamais été publié ou si un id est inconnu, et rien ne part alors, pas même aux ids valides.

## Là où un outil diffère du REST

L'assistant nomme des ids qu'il a pu inventer, ne voit pas de bouton grisé et lit un résultat vide comme un succès. D'où des écarts volontaires.

| Outil | Écart avec le REST | Raison |
| --- | --- | --- |
| `modifier_animateur` | fusionne au lieu de remplacer | l'assistant ne lit pas l'identité qu'il faudrait renvoyer |
| `modifier_stand`, `modifier_animateur`, `modifier_creneau`, `modifier_emplacement`, `modifier_typologie` | `modifieLe` facultatif | la fusion relit la fiche juste avant d'écrire : sans l'argument, le contrôle de modification concurrente ne dit rien. Un assistant qui a lu la fiche plus tôt passe la valeur lue pour être refusé si elle a bougé |
| `modifier_affectation` | refuse un animateur que le siège ne peut pas accueillir : le geste est scoré sur le plan enregistré et rejeté s'il dégrade le score dur ou crée un écart dur sur le siège ([ADR 0046](repo:docs/decisions/0046-un-placement-intenable-est-dit-avant-le-calcul.md)) | l'écran ne propose qu'un candidat déjà scoré, un assistant nomme qui il veut |
| `verrouiller` | rend `{ verrouillage, avertissements }` ; le code `VERROUILLAGE_SUR_VIOLATION_DURE` dit que les places figées cassent déjà une règle dure | le verrou est écrit (il fige, il n'exempte pas), mais celui qui enchaîne sur `lancer_calcul` doit savoir d'où viendra le score dur négatif |
| `deverrouiller` | échoue sur un id inconnu, là où le `DELETE` répond `204` ou `404` | « retiré » sur une ligne qui n'existait pas ferait croire le planning libre de bouger |
| `creer_version`, `restaurer_version` | lèvent une erreur là où le REST renvoie un `409` avec un corps | un résultat que l'assistant lit comme un succès ne doit pas dire que rien n'a été écrit |
| `simuler_affectation`, `modifier_affectation` | un seul outil pour les trois gestes de « Simuler un changement » (déplacer, échanger, attribuer), la cible étant un poste ou une personne | le serveur choisit le geste puis refuse ce qui casserait une règle dure |
| `lancer_calcul` | repart du plan enregistré par défaut ; `deZero` demande le départ à froid (l'ancien nom garde `reamorcage` : `AUTO`, `PLAN_COURANT`, `AUCUN`) | relancer « pour voir » détruirait sinon en silence la qualité atteinte |
| `lancer_calcul` | argument `distant`, et `calculDistant` dans `consulter_calcul` | avec une machine de calcul ([ADR 0086](repo:docs/decisions/0086-le-calcul-deporte-un-worker-sans-conteneur-et-un-crochet.md)), `true` y envoie la recherche, `false` la garde sur place, absent suit le réglage de l'instance. `calculDistant` vaut `PREPARING` tant que le worker n'a pas reçu le problème, puis `SOLVING`. Un calcul distant qui échoue finit `FAILED` avec son motif : relancez avec `distant=false` |
| `lancer_calcul`, `lancer_correction` | pendant l'événement, les places des créneaux déjà commencés sont reprises du plan enregistré et épinglées ; un job dont toutes les places sont passées finit `FAILED` avec « Rien à planifier… », et `consulter_calcul` porte `avertissement` quand le passé tient des trous ([ADR 0044](repo:docs/decisions/0044-le-passe-est-fige.md)) | un assistant ne réécrit pas hier plus que l'écran, et un plan vide à zéro dur se lirait comme un succès |
| `simuler_affectation`, `modifier_affectation`, `modifier_demande_echange`, `analyser_impact_echange` | refusent un poste dont le créneau est commencé : « Ce créneau est déjà commencé : le passé ne se modifie plus » | l'outil de simulation refuse déjà, pour ne pas bâtir un plan sur un geste que l'écriture refusera |
| `consulter_calcul` | sans `jobId` ni job actif, répond `IDLE` avec « Aucun solveur ne tourne. » en `avertissement` ; un `jobId` inconnu est refusé | un outil qui ne renvoie rien ne s'encode pas, l'assistant recevait « Internal error » |
| `consulter_planning`, `consulter_version` | plafonnent la liste (200 par défaut) et annoncent le total | un plan réel porte plusieurs milliers de postes ; le total rend la troncature lisible |
| `consulter_charge` (vue `heures`) | lit le plan enregistré par défaut, là où l'écran ouvre sur la publication en vigueur | un assistant lit en général ce qu'il vient de retoucher |
| `diagnostiquer_plan` | erreur sans plan enregistré, là où `POST /api/constraints/diagnostic` rend la vue vide | une structure vide se lirait « aucune contrainte en défaut » |
| `publier_planning`, `configurer_collecte_disponibilites` | comptent les personnes sans adresse et les échecs d'envoi, le REST les nomme | `lister_destinataires_publication` redonne le détail par id |

Les calculs lancés par `lancer_calcul` et `lancer_correction` passent par les soumissions **rejouables** du `SolverJobService`, comme les écrans : le problème est construit au démarrage du job, non à l'appel. Cela donne la mise en file (`enFile`, sans quoi un solveur occupé refuse la demande), et la fraîcheur, puisqu'une écriture faite pendant l'attente est prise en compte ; la file elle-même est en mémoire, un redémarrage la perd. Le budget suit la règle de l'écran ([le calcul](api-calcul.md)) : sans `secondes`, le job tourne la durée et l'arrêt sur plateau de l'instance, que `consulter_parametres` (section `calcul`) montre sans qu'aucun outil le règle. Au-dessus du plafond de l'instance, un `secondes` est refusé par une phrase qui cite ce plafond.
