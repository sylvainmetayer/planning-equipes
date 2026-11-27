# Ce que font les outils du serveur MCP

Vous savez quelles familles d'outils le serveur MCP expose, ce que chacune lit ou écrit, quels prompts et quelles ressources il sert, et ce qui reste volontairement hors de portée de l'assistant. Le serveur annonce lui-même la liste exacte des outils ; le paquet [`mcp`](repo:src/main/java/dev/sylvain/planning/mcp/) en est la source, une classe par famille. Les règles que tous suivent (édition en argument, refus métier, hints, écriture pendant un calcul) sont dans [les règles des outils](mcp-conventions-des-outils.md), et la connexion dans [le branchement d'un assistant](mcp-connexion.md).

Partout, un outil qui lit un plan lit le **plan enregistré**, et une personne sort par **id**, jamais par nom, dans une liste triée par id (un ordre alphabétique des noms en dirait encore quelque chose).

## Savoir où en est l'édition

`consulter_edition` est le premier appel utile. Il rend la checklist du cycle que la page d'accueil affiche (référentiels, collecte, ouvertures, besoin, dernière résolution, publication, accusés de réception, foire), chaque ligne avec son statut (`A_FAIRE`, `ATTENTION`, `INFO`, `FAIT`) et les chiffres qui le décident, calculés côté serveur donc identiques pour l'écran et l'assistant. Une résolution en cours s'y lit comme un statut, sans faire échouer l'appel. Il ne contient que des comptes et des dates.

- Le bloc `coherence` compte les anomalies de saisie ; `lister_anomalies_referentiel` en donne le détail (famille, gravité, code, fiche concernée par `objet` et `objetId`) sans la phrase ni la date que l'écran affiche. C'est la règle de `WarningCodes` appliquée à la liste entière : l'avertissement sur un mineur date sa majorité.
- Le bloc `aTraiter` répond à « que dois-je faire ce matin ? » : déclarations et échanges en attente, journées non relues des sept jours à venir, silencieux à relancer. « Aujourd'hui » est celui de `JourJClock`, la date simulée comprise.
- `consulter_planning` dit quand le solveur a tourné pour la dernière fois, `consulter_publication` quand les animateurs ont été prévenus. Seule la seconde décrit ce que les gens ont sous les yeux : un plan calculé ce matin et publié la semaine dernière est, pour eux, celui de la semaine dernière. `consulter_publication` dit aussi qui serait concerné par la prochaine publication, sans rien envoyer.
- `changements_journee` dit ce qui a bougé sur une date, depuis la dernière publication (`reference=publication`) ou depuis le plan d'avant la dernière résolution (`reference=resolution`), par siège et par animateur. Un siège gardé par la même personne à d'autres heures est une ligne `HORAIRES`. `referenceDisponible` faux veut dire « rien à comparer », jamais « aucun changement ».
- `comparer_realise_planifie` rend l'écran du même nom pour chaque stand et chaque journée **écoulée** : sièges publiés, tenus, absences, remplacements, vides, retirés (consigne, créneau supprimé : jamais une absence) ou ajoutés, minutes publiées, réalisées et perdues. Chaque journée est comparée à la publication en vigueur à son premier créneau ; une journée commencée avant toute publication (`lateReference`) n'entre dans aucun total. `nature` vaut `DECLARED` : ce qui a été enregistré, pas un pointage.
- `consulter_planning`, vue `synthese`, répond en quelques dizaines de lignes (postes pourvus au total, par stand et par jour), là où la vue `affectations` dépenserait le contexte de l'assistant avant qu'il voie qu'un stand est en sous-effectif le samedi.

## Calculer et corriger le plan

`lancer_calcul` (« Calculer ») lance le calcul complet depuis le plan en place : les verrous et le passé figé sont respectés, tout le reste peut bouger ; `deZero` recommence de zéro et perd la qualité déjà atteinte. `lancer_correction` (« Corriger ») ne rouvre que les sièges en écart, ceux qu'un changement a invalidés, et fige le reste pour ce calcul ; ses `animateurIds`, `jours` et `standIds` rouvrent en plus tout ce qu'ils désignent, ne valent que pour ce calcul et **ne touchent pas aux verrous** : ce qui est figé durablement se pose avec `verrouiller`. `consulter_calcul` suit un calcul (ou le calcul actif, `IDLE` quand rien ne tourne) et rend l'historique avec `historique`, `arreter_calcul` et `supprimer_calcul` l'arrêtent et l'oublient.

`diagnostiquer_plan` ne résout rien : il recalcule le score du plan enregistré, celui dont parle `consulter_planning`. Il rend la **lecture du score** (`lecture`), les mêmes phrases que la page Solveur, écrites par gabarits : elles ne nomment que des règles, des jours et des nombres, jamais une personne. Chaque règle en défaut porte ses `actions` types (code, geste, explication) ; avec `ecartsDurs`, il rend aussi le message anonymisé de chaque écart dur.

`consulter_planning` lit le plan enregistré sous une `vue` : `etat` (quand il a été calculé, et s'il est périmé), `synthese` (quelques dizaines de lignes, par stand et par jour) ou `affectations` (les sièges, filtrés et plafonnés). `analyser_affectation` explique le score d'un siège. Pour retoucher un siège sans relancer le calcul, `simuler_affectation` chiffre un changement sans rien écrire (`mode` `affecter` : un autre titulaire ; `deplacer` : vers un autre siège ou une autre personne ; `candidats` : qui pourrait le tenir), et `modifier_affectation` l'applique (`affecter`, ou `deplacer`, refusé s'il casse une règle dure). Deux outils et non un seul : un assistant qui évalue et applique dans le même appel ne laisse aucune étape où un humain puisse dire non. `modifier_affectation` refuse un siège **verrouillé**, le verrou étant ce qu'on lui demande de respecter, et un siège dont le créneau est commencé. `modifier_demande_echange` (`decision` `accepter`) est, avec lui, l'un des deux seuls outils qui écrivent dans le plan sans solveur : il applique l'échange puis le fige par des verrouillages `ANIMATEUR_CRENEAU`. D'où `analyser_impact_echange` à côté, car la prévalidation stockée avec la demande décrit le plan du jour où elle a été envoyée.

`modifier_contrainte` règle une règle pour l'édition, un réglage par appel : `actif`, `poids` (ou `poidsParDefaut`), ou `niveau` d'une règle d'organisation : `OBLIGATOIRE`, `PREFERENCE_FORTE`, `PREFERENCE_NORMALE` ou `PREFERENCE_LEGERE` (une préférence écrit le poids de sa position). `lister_contraintes` donne `niveauReglable` et `niveauChoisi` ; une règle légale, d'affectation, ad hoc, de verrou ou une préférence souple refuse le niveau. L'outil est accepté pendant un calcul et le dit (`RESOLUTION_EN_COURS`), et rend la ligne de la règle telle que `lister_contraintes` la lit après le changement.

Un réglage fait par `modifier_contrainte` se lit dans le journal des actions de l'application (filtre « Règles »), sous l'acteur `ASSISTANT`, avec le nom de la règle et ce qui a changé (activation, poids ou niveau), sans la valeur. L'ancien outil de lecture de l'historique des pondérations est retiré (voir le tableau des anciens noms) : le journal ne garde pas de valeur, et aucun outil ne le lit.

## Lire le plan sous différents angles

| Outil | Ce qu'il rend |
| --- | --- |
| `heures_travaillees` | par animateur désigné par id, heures par semaine ISO et total, amplitude journalière maximale, journées au-delà de 12 h (un affichage, aucune règle). `source=persiste` par défaut, `source=publie` pour la publication en vigueur, refusé tant que rien n'est publié ; la réponse dit quel plan elle a lu (`source`, `planDate`) |
| `equite_planning` | une ligne par animateur affecté (heures totales, par semaine, de soirée, de week-end, de jour férié, postes pénibles, séries) et, par colonne, médiane, minimum, maximum, écart-type. `colonnesSolveur` dit quelles colonnes une règle mesure et si elle est active : une colonne que le solveur ne pèse pas est une information, pas un défaut |
| `analyser_typologies` | la lecture par typologie de l'onglet Typologies de la page Stands : heures et postes tenus, `heuresParJour`, et quatre listes de personnes réduites à `nombre` et `animateurIds` (`affectes`, `competents`, `competentsJamaisAffectes`, `affectesSansCompetence`) |
| `lister_typologies` | chaque typologie avec ses compteurs (`competents`, `polyvalents`, `souhaits`, `stands`) et son `etat` : `ORPHELINE`, `FRAGILE`, `INUTILISEE`, `SANS_COMPETENT_INUTILISEE`, `NORMALE` |
| `analyser_marge` | la marge par jour et tranche, en mode `avant` (défaut) ou `apres` ; le mode `tension` la croise avec la fragilité du plan, chaque case portant une `gravite` (`CRITIQUE`, `ELEVEE`, `SURVEILLEE`, `CALME`) et ses `motifs`, les sièges irremplaçables y étant tous comptés |
| `plan_formation` | par typologie en manque ou tenue par un spécialiste unique, les chiffres des analyses et les candidats classés (jours de tension disponibles, puis souhait, puis niveau) ; un candidat est `DEBUTANT` ou `AUTONOME`, jamais `REFERENT`. Sans plan enregistré, `planPersiste` vaut `false` et seule la partie besoin parle |
| `analyser_pauses` | par animateur et par jour, où tombe la pause due, sur quel stand, qui peut relayer (`relaisAnimateurIds`) ; une pause que personne ne peut relayer est un écart dur, le filtre `sansRelais` ne rend que celles-là |
| `analyser_enchainements` | deux postes consécutifs d'un animateur sur deux emplacements géolocalisés dont le battement ne laisse pas le temps de marcher, ou seulement en prenant sur la pause (`walkOnBreak`) ; `geolocated=false` dit qu'aucun trajet n'était calculable, plutôt qu'une liste vide qui se lirait « tout va bien ». Vitesse, détour et tolérance se règlent par `modifier_parametres` (section `organisation`) |
| `analyser_arrivees_groupees` | pour chaque groupe, jour par jour, qui travaille, qui manque, l'écart des arrivées et des départs |
| `comparer_editions` | le delta de la vue Éditions de l'écran Comparer deux versions, sans aucun nom : une ligne d'animateur porte ses ids dans chaque édition et le nom des champs qui diffèrent, jamais leurs valeurs. Rien n'est rapproché par id ; `matching` dit sur quoi deux lignes l'ont été (`CODE`, `NOM`, `EMAIL`, `IDENTITE`, `POSITION`) |

La pause se règle par `consulter_parametres` et `modifier_parametres` (section `legal`) : une seule durée, `dureePauseMinutes` (30 par défaut), refusée sous son plancher d'ordre public de 20 minutes et que rien ne plafonne. Les 30 minutes dues à un mineur s'appliquent à la lecture, pas au réglage : une édition à 25 donne 25 aux majeurs et 30 aux mineurs. Les plafonds, le rapport des heures, l'équité et les indicateurs déduisent cette même durée ([ADR 0048](repo:docs/decisions/0048-une-seule-regle-de-pause.md)). La même section porte `heureDebutSoiree` et `dureeVacationMaxMinutes`.

## Construire la grille et les horaires

Une grille est faite de vacations et ne se demande plus en mode : un créneau est une vacation, et deux créneaux qui se chevauchent le même jour sont deux relèves décalées, jamais une anomalie ([ADR 0037](repo:docs/decisions/0037-une-grille-est-toujours-des-vacations.md)). Le contrôle (`valider_creneaux`, `previsualiser_generateur_creneaux`, `diagnostiquer_grille_creneaux`) signale toujours une vacation au-delà de `dureeVacationMaxMinutes` (6 h par défaut), une vacation que le repos quotidien rend intenable, un relais repas hors fenêtre, un doublon, un trou, une date isolée. `diagnostiquer_grille_creneaux` est le premier appel pour découvrir une édition.

Un seul générateur produit la grille en lot ([ADR 0126](repo:docs/decisions/0126-un-generateur-de-creneaux-unique.md)) : `previsualiser_generateur_creneaux` montre ce qu'il produirait et contrôle la grille obtenue, `appliquer_generateur_creneaux` l'écrit, avec les mêmes arguments et une `source`. `stands` découpe la grille que les horaires des stands impliquent (une coupure à chaque heure où un stand ouvre ou ferme), en ajout ou, avec `remplacer=true`, à la place de la grille et du plan avec elle ; `recurrence` ajoute des fenêtres répétées sur des dates ; `gabarits` applique le calendrier des gabarits ; `grille` lit les gabarits que la grille implique. `creer_creneau` et `modifier_creneau` prennent un `couverturePause` facultatif : le relais repas garde le stand ouvert à la moitié de son effectif, arrondie au supérieur.

Le modèle d'horaires d'un stand (ses règles récurrentes, ADR 0104) figure dans `consulter_stand` et `lister_stands` sous `horaires`. `creer_horaire_stand` d'une règle identique à une règle existante ne change rien ; `supprimer_horaires_stand` efface le modèle **et** les plages qu'il a dépliées, et garde les plages saisies à la main (`creer_plage_stand`).

Les plages datées d'un stand figurent dans `consulter_stand` et `lister_stands` sous `plages` (identifiant, mode, jour, heures, effectif, et `depliee` : dépliée du modèle ou saisie à la main). `creer_plage_stand` en pose une, ouverture ou fermeture (`mode`) ; `supprimer_plage_stand` en retire une (`plageId`) ou toutes celles d'un jour (`date`). Les trois écritures de plages sont refusées pendant un calcul et quand la phase de l'édition fige les stands.

Une fenêtre d'ouverture (`creer_horaire_stand`, `creer_stand_complet`, `creer_plage_stand`) peut nommer son effectif par le suffixe `@N` (`10:00-12:00@2,14:00-@4`) ou l'argument `effectif` d'une ouverture datée ; sans lui elle reprend le minimum du stand. Zéro est refusé (un stand sans personne est une fermeture) et le suffixe est rejeté sur la récurrence d'un créneau.

Les gabarits sont les journées types de la 3.x. `modifier_gabarit` crée ou remplace un gabarit par son nom et ses vacations sur une ligne (« 09:00-12:00, 12:00-13:00 R, 13:00-14:00 R, 14:00-20:00 », `R` marquant un relais repas aux sièges divisés par deux), et le pose sur une plage ou des dates ; `supprimer_gabarit` retire un gabarit, ou seulement des dates du calendrier. Rien de cela n'écrit un créneau : `appliquer_generateur_creneaux` avec la source `gabarits` le fait, après l'aperçu. `lister_gabarits` rend les dates en écart ou sous consigne, que l'application ne touche pas ([ADR 0032](repo:docs/decisions/0032-journees-types-nommees-vacations-fixes.md)).

## Poser une consigne

Un arrêté ferme une bande horaire pour tous les stands, et la question est de savoir si le plan peut être retourné avant la nuit ([ADR 0043](repo:docs/decisions/0043-consigne-d-edition-fermer-une-bande-sans-rien-detruire.md)). Huit outils miroitent l'écran Consignes : `lister_consignes`, `consulter_preselection_consigne`, `simuler_consigne`, `appliquer_consigne`, `simuler_levee_consigne`, `lever_consigne`, `definir_prereglage_consigne`, `supprimer_prereglage_consigne`. Aucun ne nomme une personne.

Les **fenêtres** s'écrivent `HH:MM-HH:MM` séparées par des virgules, la fin omise ou à `00:00` valant « jusqu'à minuit » (`08:00-10:00,18:00-`). Les **ouvertures** nomment le stand, sa fenêtre et, après `*`, l'effectif de cette fenêtre : `BOURSE=08:00-10:00*2,BOURSE=18:00-22:00*7`. Sans `*N`, la fenêtre hérite du plus fort effectif que le stand perd dans la bande, sinon de son minimum. Le motif est obligatoire : il est imprimé partout où la journée est dite modifiée. Les fenêtres repas (`repasMidi`, `repasSoir`, `coupureRepasMinutes`, `justificationRepas`) ne valent que sur les dates de la consigne.

1. `consulter_preselection_consigne` sur chaque date : ce que la bande prend à chaque stand, les stands proposés, ceux écartés pour horaires posés à la main. N'écrit rien.
2. `simuler_consigne` : par jour, sièges et minutes avant et après, créneaux à ajouter, verrous et règles ad hoc touchés. C'est la validation complète sans écriture : une requête que la simulation accepte sera acceptée telle quelle.
3. `appliquer_consigne`, mêmes arguments : pose ou remplace la consigne d'une date et n'ajoute à la grille que les créneaux que les ouvertures exigent. Les dates doivent être à venir et s'écrivent toutes ensemble ou pas du tout.
4. `lancer_correction`, puis `consulter_publication` et `publier_planning`.

Lever suit le même pli : `simuler_levee_consigne` nomme les créneaux ajoutés qui partiraient avec leurs sièges et avertit quand les journées figées épuisent les gens disponibles, `lever_consigne` les retire et refuse une date passée ou en cours, un jour travaillé gardant la consigne qui l'a gouverné. Un préréglage (« Plan canicule ») mémorise bande, motif et fenêtres et suit la duplication de l'édition. `consulter_alertes_meteo` rend les alertes levées le matin avec le préréglage suggéré pour le phénomène ; il ne pose rien, appliquer la suggestion reste un `appliquer_consigne` explicite.

## Décider ce que les animateurs ont demandé

Les déclarations de disponibilité et les demandes d'échange arrivent de l'espace animateur. `appliquer_declaration_disponibilite` écrit la déclaration sur la fiche par le même `write*` que l'écran de saisie : les données sont marquées modifiées, `consulter_planning` signale le plan périmé, le journal porte les champs remplacés (`joursIndisponibles`, `souhaits`) et l'outil rend les avertissements en codes.

`consulter_foire_echanges` rend la fenêtre de la foire et ses statistiques (volumes, taux, délais, répartitions par jour et par stand) sur une période de création (`du`, `au`, ou `touteEdition`, refusé avec les deux autres). Aucun animateur n'y figure, pas même par id.

`consulter_publication`, vue `confirmations`, compte, parmi les animateurs qui ont un poste sur le plan publié, les confirmés, relancés, silencieux et les **échecs d'envoi** (comptés à part, sans adresse) et date la publication à laquelle ils répondent ; `jamaisPublie` vrai veut dire que la question n'a encore été posée à personne.

`lister_demandes_covoiturage` rend les « Je viens avec… » de la collecte, à part de la déclaration : membres, statut, `confirmedByAll`, jours où les indisponibilités divergent, ajustement créé, le tout par id et avec le statut `EN_ATTENTE` (non validée) ou `VALIDEE`. La validation, l'écart et l'annulation restent des gestes d'écran, parce que la validation prévient les animateurs par courriel ; un assistant crée directement un `ARRIVEE_GROUPEE` par `creer_contrainte_ad_hoc`. En revanche, `supprimer_contrainte_ad_hoc` sur une arrivée groupée qu'une demande validée soutient rend un résultat en erreur portant la phrase de l'écran Ajustements manuels (annulez-la depuis Disponibilités > Covoiturage).

## Éditions, scénarios et publication

Un outil par objet et par verbe, le geste passé en argument ([ADR 0127](repo:docs/decisions/0127-un-outil-mcp-par-objet-et-par-verbe.md)). `creer_edition` crée une édition vide, ou la copie d'une autre avec `source`, avec ou sans ses animateurs. `modifier_edition` change un réglage par appel : `nom`, ou `active` (vrai : elle devient l'édition active et l'ancienne cesse de l'être ; faux : plus rien ne part). La phase reste `modifier_phase_edition`, ouvert à l'organisateur connecté par OAuth, là où `creer_edition` et `modifier_edition` sont réservés à l'administrateur de l'instance.

`importer_scenario` prend un scénario livré (`nom`) ou un contenu YAML (`yaml`), l'un des deux ; `diagnostiquer_scenario` vérifie un YAML sans rien importer, et `supprimer_donnees_edition` vide l'édition. `consulter_publication` lit la publication sous une `vue` : `etat` (défaut), `destinataires` (la trace d'une publication) ou `confirmations`. Trois outils écrivent aux gens, chacun avec son verbe : `publier_planning` (tout le monde, mêmes clés qu'en 3.x : `snapshotId`, `envoyes`…), `envoyer_planning_animateur` (le planning publié d'une seule personne) et `envoyer_relance_animateurs` (les silencieux désignés). Un envoi n'est jamais un mode d'un autre outil : un argument oublié ne doit pas écrire à toute l'équipe. `consulter_sauvegardes` dit l'état de la sauvegarde nocturne, `modifier_sauvegardes` la suspend.

## Les prompts et les ressources

Le serveur sert aussi **vingt-deux prompts**, un par moment du cycle d'un événement (de `demarrer_une_nouvelle_edition` à `tirer_le_bilan_de_l_evenement`, en passant par `construire_la_grille_de_creneaux`, `figer_le_referentiel`, `verifier_avant_resolution`, `publier_le_planning` ou `tenir_le_jour_j`), chacun enchaînant les outils dans le bon ordre. Ils disent aussi ce que les outils ne savent pas faire (l'import de la matrice des stands, la saisie en grille, marquer une absence le jour J et valider un covoiturage restent à l'écran), sans quoi l'assistant chercherait un outil qui n'existe pas.

Chaque prompt prend un argument `edition` **facultatif**. Fourni, le texte le nomme ; omis, il demande à l'assistant d'appeler `lister_editions` et de retenir l'édition active, ou de poser la question s'il n'y en a aucune. Dans les deux cas il lui demande de passer l'édition à chaque appel d'outil. Pour `demarrer_une_nouvelle_edition`, c'est l'édition **source** de la duplication ; pour `tirer_le_bilan_de_l_evenement`, omis, il fait proposer l'édition dont l'événement vient de finir, rarement encore l'active. `preparer_un_plan_de_repli` prépare le repli comme une consigne datée dans l'édition vivante, sans la dupliquer.

Un prompt qui nomme un outil sortant exige un accord explicite et fait d'abord vérifier que l'édition est l'active ; l'assistant s'arrête plutôt que d'en activer une de son propre chef. `tenir_le_jour_j` prévient par `publier_planning` : `envoyer_planning_animateur` renverrait le plan déjà publié, donc l'ancien. La page MCP de l'interface les lit sur `GET /api/mcp/prompts` au lieu de les recopier : un texte, une source. Ils ne sont **pas traduits**. `McpPromptsWordingTest` relit leur texte rendu, avec et sans édition : tout mot en snake_case doit nommer un outil ou un prompt annoncé (sinon il figure, argumenté, dans les exceptions du test), et tout outil dont `openWorldHint` est vrai cité par un prompt muet sur l'accord fait échouer le build. Un outil renommé ne peut donc pas laisser un prompt derrière lui.

Deux **ressources**, à attacher une fois pour informer tous les appels suivants :

| URI | Contenu |
| --- | --- |
| `planning://contraintes` | le catalogue des contraintes, généré depuis `ConstraintCatalog` : il ne peut ni décrire une règle absente ni en oublier une ajoutée la veille |
| `planning://vocabulaire` | stand, créneau, poste, vacation, journée type, typologie, édition, et l'ordre dans lequel on prépare un événement ; le seul texte écrit à la main |

## Anciens noms d'outils

La 4.0 renomme, regroupe ou retire des outils de la 3.x ([ADR 0100](repo:docs/decisions/0100-convention-de-nommage-des-outils-mcp.md)). Un outil regroupé fait sous un seul nom, avec un argument (`vue`, `mode`, `decision`), ce que faisaient plusieurs ([ADR 0127](repo:docs/decisions/0127-un-outil-mcp-par-objet-et-par-verbe.md)) ; ses anciens noms restent publiés jusqu'en 5.0 à côté du nouveau, leur description commençant par « Ancien nom » : ceux de la relecture des journées, ceux des journées types, des créneaux récurrents et de la dérivation, que le générateur de créneaux a regroupés ([ADR 0126](repo:docs/decisions/0126-un-generateur-de-creneaux-unique.md)), ceux du plan, du calcul, des règles et des échanges, et ceux des paramètres, des éditions, des scénarios, de la publication et des sauvegardes. L'ancien outil de réglage du solveur n'est plus publié : le budget de calcul qu'il réglait est passé à l'instance, et seul le mail de fin de résolution reste dans `modifier_parametres` (section `calcul`). Ce tableau est le rendu des lignes changées de [`mcp-renommages.tsv`](repo:src/main/resources/mcp-renommages.tsv) ; un nom absent n'a pas changé. Quand le fichier change, `McpRenommageTest` échoue et imprime le tableau à coller entre les deux repères.

<!-- mcp-renommages:debut -->
| Nom en 3.x | Nom en 4.0 |
| --- | --- |
| `accepter_demande_echange` | `modifier_demande_echange` |
| `activer_contrainte` | `modifier_contrainte` |
| `activer_edition` | `modifier_edition` |
| `affecter_journee_type` | `modifier_gabarit` |
| `affecter_poste` | `modifier_affectation` |
| `ajouter_fermeture_stand` | `creer_plage_stand` |
| `ajouter_horaire_stand` | `creer_horaire_stand` |
| `ajouter_ouverture_stand` | `creer_plage_stand` |
| `ajouter_validation_journee` | `modifier_etat_journee` |
| `arreter_solveur` | `arreter_calcul` |
| `capturer_instantane` | `creer_version` |
| `compacter_horaires_stands` | retiré |
| `comparer_instantanes` | `comparer_versions` |
| `configurer_foire_echanges` | `modifier_foire_echanges` |
| `consulter_historique_ponderation` | retiré |
| `consulter_instantane` | `consulter_version` |
| `consulter_parametres_legaux` | `consulter_parametres` |
| `consulter_parametres_notifications` | `consulter_parametres` |
| `consulter_parametres_qualite` | `consulter_parametres` |
| `consulter_parametres_solveur` | `consulter_parametres` |
| `creer_creneaux_recurrents` | `appliquer_generateur_creneaux` |
| `definir_journee_type` | `modifier_gabarit` |
| `deplacer_affectation` | `modifier_affectation` |
| `desactiver_contrainte` | `modifier_contrainte` |
| `desactiver_edition` | `modifier_edition` |
| `dupliquer_edition` | `creer_edition` |
| `edition_courante` | `lister_editions` |
| `effacer_horaires_stand` | `supprimer_horaires_stand` |
| `effacer_plages_stand` | `supprimer_plage_stand` |
| `etat_edition` | `consulter_edition` |
| `etat_planning` | `consulter_planning` |
| `etat_publication` | `consulter_publication` |
| `etat_sauvegardes` | `consulter_sauvegardes` |
| `expliquer_affectation` | `analyser_affectation` |
| `expliquer_echec_contraintes_dures` | `diagnostiquer_plan` |
| `figer_referentiel` | `modifier_phase_edition` |
| `generer_creneaux_depuis_stands` | `appliquer_generateur_creneaux` |
| `importer_scenario_yaml` | `importer_scenario` |
| `lancer_solveur` | `lancer_calcul` |
| `lever_gel` | `modifier_phase_edition` |
| `lister_affectations` | `consulter_planning` |
| `lister_destinataires_publication` | `consulter_publication` |
| `lister_instantanes` | `lister_versions` |
| `lister_jobs` | `consulter_calcul` |
| `lister_journees_types` | `lister_gabarits` |
| `lister_kpi_historique` | `lister_mesures_edition` |
| `lister_validations_journee` | `consulter_etats_journee` |
| `materialiser_journees_types` | `appliquer_generateur_creneaux` |
| `modifier_parametres_legaux` | `modifier_parametres` |
| `modifier_parametres_notifications` | `modifier_parametres` |
| `modifier_parametres_qualite` | `modifier_parametres` |
| `modifier_parametres_solveur` | `modifier_parametres` |
| `modifier_poids_contrainte` | `modifier_contrainte` |
| `previsualiser_application_journees_types` | `previsualiser_generateur_creneaux` |
| `previsualiser_creneaux_recurrents` | `previsualiser_generateur_creneaux` |
| `previsualiser_derivation_creneaux` | `previsualiser_generateur_creneaux` |
| `previsualiser_reconnaissance_journees_types` | `previsualiser_generateur_creneaux` |
| `realise_vs_planifie` | `comparer_realise_planifie` |
| `reconnaitre_journees_types` | `appliquer_generateur_creneaux` |
| `refuser_demande_echange` | `modifier_demande_echange` |
| `reinitialiser_donnees` | `supprimer_donnees_edition` |
| `relancer_animateurs` | `envoyer_relance_animateurs` |
| `renommer_edition` | `modifier_edition` |
| `resoudre_incremental` | `lancer_correction` |
| `restaurer_instantane` | `restaurer_version` |
| `resultats_animateur` | `consulter_planning` |
| `retirer_dates_journee_type` | `supprimer_gabarit` |
| `retirer_validation_journee` | `modifier_etat_journee` |
| `simuler_deplacement` | `simuler_affectation` |
| `simuler_swap` | `simuler_affectation` |
| `statut_solveur` | `consulter_calcul` |
| `suggerer_reparations` | `simuler_affectation` |
| `supprimer_instantane` | `supprimer_version` |
| `supprimer_job` | `supprimer_calcul` |
| `supprimer_journee_type` | `supprimer_gabarit` |
| `supprimer_kpi_historique` | `supprimer_mesure_edition` |
| `synthese_affectations` | `consulter_planning` |
| `synthese_confirmations` | `consulter_publication` |
| `valider_scenario_yaml` | `diagnostiquer_scenario` |
<!-- mcp-renommages:fin -->

## Ce qui n'a pas d'outil

| Ce qui reste dehors | Pourquoi |
| --- | --- |
| Export de scénario YAML | il contient prénom, nom et date de naissance, puisqu'il doit être réimportable |
| Sauvegardes avant vidage | chacune est un export de scénario. `supprimer_donnees_edition` et les imports en écrivent une, mais seul un administrateur la lit, la restaure ou la supprime, par l'API ([ADR 0084](repo:docs/decisions/0084-sauvegarder-l-edition-avant-de-la-vider.md)) |
| Dump SQL, exports PDF, ICS et CSV | toutes les données personnelles, ou des binaires destinés au téléchargement depuis l'interface |
| Import CSV des bénévoles | le tableur porte nom, prénom, date de naissance et adresse : un outil qui l'accepterait ferait entrer par MCP ce qui n'en sort pas |
| `/api/planning/sample`, `/persisted` | un `PlanningEvenement` entier, plusieurs dizaines de Mo avec les données personnelles ; `consulter_planning` (vue `affectations`) couvre le besoin en restant filtré |
| Espace animateur et abonnement ICS | le côté animateur, derrière un jeton personnel : MCP voit les demandes et déclarations côté admin, jamais l'espace de quelqu'un |
| Rotation du jeton d'espace d'un animateur | elle invalide le lien déjà imprimé sur un PDF distribué, conséquence qu'on ne peut pas annuler depuis une conversation |
| Webhooks sortants | une adresse Slack, Discord ou Matrix est un secret, et un webhook ouvre une sortie réseau depuis le serveur : le configurer depuis une conversation serait une voie d'exfiltration toute tracée. Ils se règlent dans Paramètres › Instance |
| `/api/horloge` | l'horloge simulée déplace pour tout le monde le mode jour J, le passé figé et les espaces animateurs : un réglage d'instance, pas une question d'organisation |
| `/api/config`, `/api/auth`, `/api/branding`, `/api/mentions-legales`, `/api/debug` | clés publiques du navigateur, session admin, habillage et bacs à sable de développement : rien qu'un assistant puisse en faire |
