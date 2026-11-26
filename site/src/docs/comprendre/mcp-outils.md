# Ce que font les outils du serveur MCP

Vous savez quelles familles d'outils le serveur MCP expose, ce que chacune lit ou écrit, quels prompts et quelles ressources il sert, et ce qui reste volontairement hors de portée de l'assistant. Le serveur annonce lui-même la liste exacte des outils ; le paquet [`mcp`](repo:src/main/java/dev/sylvain/planning/mcp/) en est la source, une classe par famille. Les règles que tous suivent (édition en argument, refus métier, hints, écriture pendant un calcul) sont dans [les règles des outils](mcp-conventions-des-outils.md), et la connexion dans [le branchement d'un assistant](mcp-connexion.md).

Partout, un outil qui lit un plan lit le **plan enregistré**, et une personne sort par **id**, jamais par nom, dans une liste triée par id (un ordre alphabétique des noms en dirait encore quelque chose).

## Savoir où en est l'édition

`etat_edition` est le premier appel utile. Il rend la checklist du cycle que la page d'accueil affiche (référentiels, collecte, ouvertures, besoin, dernière résolution, publication, accusés de réception, foire), chaque ligne avec son statut (`A_FAIRE`, `ATTENTION`, `INFO`, `FAIT`) et les chiffres qui le décident, calculés côté serveur donc identiques pour l'écran et l'assistant. Une résolution en cours s'y lit comme un statut, sans faire échouer l'appel. Il ne contient que des comptes et des dates.

- Le bloc `coherence` compte les anomalies de saisie ; `lister_anomalies_referentiel` en donne le détail (famille, gravité, code, fiche concernée par `objet` et `objetId`) sans la phrase ni la date que l'écran affiche. C'est la règle de `WarningCodes` appliquée à la liste entière : l'avertissement sur un mineur date sa majorité.
- Le bloc `aTraiter` répond à « que dois-je faire ce matin ? » : déclarations et échanges en attente, journées non relues des sept jours à venir, silencieux à relancer. « Aujourd'hui » est celui de `JourJClock`, la date simulée comprise.
- `etat_planning` dit quand le solveur a tourné pour la dernière fois, `etat_publication` quand les animateurs ont été prévenus. Seule la seconde décrit ce que les gens ont sous les yeux : un plan calculé ce matin et publié la semaine dernière est, pour eux, celui de la semaine dernière. `etat_publication` dit aussi qui serait concerné par la prochaine publication, sans rien envoyer.
- `changements_journee` dit ce qui a bougé sur une date, depuis la dernière publication (`reference=publication`) ou depuis le plan d'avant la dernière résolution (`reference=resolution`), par siège et par animateur. Un siège gardé par la même personne à d'autres heures est une ligne `HORAIRES`. `referenceDisponible` faux veut dire « rien à comparer », jamais « aucun changement ».
- `comparer_realise_planifie` rend l'écran du même nom pour chaque stand et chaque journée **écoulée** : sièges publiés, tenus, absences, remplacements, vides, retirés (consigne, créneau supprimé : jamais une absence) ou ajoutés, minutes publiées, réalisées et perdues. Chaque journée est comparée à la publication en vigueur à son premier créneau ; une journée commencée avant toute publication (`lateReference`) n'entre dans aucun total. `nature` vaut `DECLARED` : ce qui a été enregistré, pas un pointage.
- `synthese_affectations` répond en quelques dizaines de lignes (postes pourvus au total, par stand et par jour), là où `lister_affectations` dépenserait le contexte de l'assistant avant qu'il voie qu'un stand est en sous-effectif le samedi.

## Résoudre et corriger le plan

`lancer_solveur` et `resoudre_incremental` démarrent un calcul, `statut_solveur`, `lister_jobs`, `arreter_solveur` et `supprimer_job` le suivent, `resultats_animateur` et `expliquer_echec_contraintes_dures` en lisent le résultat. Le périmètre de `resoudre_incremental` (animateurs, jours, stands) ne vaut que pour ce job et **ne touche pas aux verrouillages** : ce qui est figé durablement se pose avec `verrouiller`.

`diagnostiquer_plan` ne résout rien : il recalcule le score du plan enregistré, celui dont parlent `etat_planning` et `lister_affectations`. Il rend la **lecture du score** (`lecture`), les mêmes phrases que la page Solveur, écrites par gabarits : elles ne nomment que des règles, des jours et des nombres, jamais une personne. Chaque règle en défaut porte ses `actions` types (code, geste, explication), comme dans `expliquer_echec_contraintes_dures`.

Pour retoucher un poste sans relancer le calcul, `suggerer_reparations` cherche qui pourrait le tenir et chiffre chaque candidat, `affecter_poste` applique le choix. Deux outils et non un seul : un assistant qui évalue et applique dans le même appel ne laisse aucune étape où un humain puisse dire non. `affecter_poste` refuse un poste **verrouillé**, le verrou étant ce qu'on lui demande de respecter, et un poste dont le créneau est commencé. `accepter_demande_echange` est, avec lui, l'un des deux seuls outils qui écrivent dans le plan sans solveur : il applique l'échange puis le fige par des verrouillages `ANIMATEUR_CRENEAU`. D'où `analyser_impact_echange` à côté, car la prévalidation stockée avec la demande décrit le plan du jour où elle a été envoyée.

`modifier_niveau_contrainte` choisit le niveau d'une règle d'organisation pour l'édition : `OBLIGATOIRE`, `PREFERENCE_FORTE`, `PREFERENCE_NORMALE` ou `PREFERENCE_LEGERE` (une préférence écrit le poids de sa position). `lister_contraintes` donne `niveauReglable` et `niveauChoisi` ; une règle légale, d'affectation, ad hoc, de verrou ou une préférence souple refuse le niveau. Comme `modifier_poids_contrainte`, l'outil est accepté pendant un calcul et le dit (`RESOLUTION_EN_COURS`).

`consulter_historique_ponderation` rend ce que rend `GET /api/constraints/{name}/historique`, ou la vue globale sans `contrainte` : changements de poids et d'activation, valeurs avant et après, origine typée, puis les résolutions de l'édition avec le dosage de chacune. Un réglage fait par `modifier_poids_contrainte`, `activer_contrainte` ou `desactiver_contrainte` y porte l'origine `ASSISTANT`.

## Lire le plan sous différents angles

| Outil | Ce qu'il rend |
| --- | --- |
| `heures_travaillees` | par animateur désigné par id, heures par semaine ISO et total, amplitude journalière maximale, journées au-delà de 12 h (un affichage, aucune règle). `source=persiste` par défaut, `source=publie` pour la publication en vigueur, refusé tant que rien n'est publié ; la réponse dit quel plan elle a lu (`source`, `planDate`) |
| `equite_planning` | une ligne par animateur affecté (heures totales, par semaine, de soirée, de week-end, de jour férié, postes pénibles, séries) et, par colonne, médiane, minimum, maximum, écart-type. `colonnesSolveur` dit quelles colonnes une règle mesure et si elle est active : une colonne que le solveur ne pèse pas est une information, pas un défaut |
| `analyser_typologies` | la vue « par typologie » de la page Planning : heures et postes tenus, `heuresParJour`, et quatre listes de personnes réduites à `nombre` et `animateurIds` (`affectes`, `competents`, `competentsJamaisAffectes`, `affectesSansCompetence`) |
| `lister_typologies` | chaque typologie avec ses compteurs (`competents`, `polyvalents`, `souhaits`, `stands`) et son `etat` : `ORPHELINE`, `FRAGILE`, `INUTILISEE`, `SANS_COMPETENT_INUTILISEE`, `NORMALE` |
| `analyser_marge` | la marge par jour et tranche, en mode `avant` (défaut) ou `apres` ; le mode `tension` la croise avec la fragilité du plan, chaque case portant une `gravite` (`CRITIQUE`, `ELEVEE`, `SURVEILLEE`, `CALME`) et ses `motifs`, les sièges irremplaçables y étant tous comptés |
| `plan_formation` | par typologie en manque ou tenue par un spécialiste unique, les chiffres des analyses et les candidats classés (jours de tension disponibles, puis souhait, puis niveau) ; un candidat est `DEBUTANT` ou `AUTONOME`, jamais `REFERENT`. Sans plan enregistré, `planPersiste` vaut `false` et seule la partie besoin parle |
| `analyser_pauses` | par animateur et par jour, où tombe la pause due, sur quel stand, qui peut relayer (`relaisAnimateurIds`) ; une pause que personne ne peut relayer est un écart dur, le filtre `sansRelais` ne rend que celles-là |
| `analyser_enchainements` | deux postes consécutifs d'un animateur sur deux emplacements géolocalisés dont le battement ne laisse pas le temps de marcher, ou seulement en prenant sur la pause (`walkOnBreak`) ; `geolocated=false` dit qu'aucun trajet n'était calculable, plutôt qu'une liste vide qui se lirait « tout va bien ». Vitesse, détour et tolérance se règlent par `modifier_parametres` (section `organisation`) |
| `analyser_arrivees_groupees` | pour chaque groupe, jour par jour, qui travaille, qui manque, l'écart des arrivées et des départs |
| `comparer_editions` | le delta de la vue Éditions de l'écran Comparer deux versions, sans aucun nom : une ligne d'animateur porte ses ids dans chaque édition et le nom des champs qui diffèrent, jamais leurs valeurs. Rien n'est rapproché par id ; `matching` dit sur quoi deux lignes l'ont été (`CODE`, `NOM`, `EMAIL`, `IDENTITE`, `POSITION`) |

La pause se règle par `consulter_parametres` et `modifier_parametres` (section `legal`) : une seule durée, `dureePauseMinutes` (30 par défaut), refusée sous son plancher d'ordre public de 20 minutes et que rien ne plafonne. Les 30 minutes dues à un mineur s'appliquent à la lecture, pas au réglage : une édition à 25 donne 25 aux majeurs et 30 aux mineurs. Les plafonds, le rapport des heures, l'équité et les indicateurs déduisent cette même durée ([ADR 0048](repo:docs/decisions/0048-une-seule-regle-de-pause.md)). La même section porte `heureDebutSoiree` et `dureeVacationMaxMinutes`.

## Construire la grille et les horaires

Une grille est faite de vacations et ne se demande plus en mode : un créneau est une vacation, et deux créneaux qui se chevauchent le même jour sont deux relèves décalées, jamais une anomalie ([ADR 0037](repo:docs/decisions/0037-une-grille-est-toujours-des-vacations.md)). Le contrôle (`valider_creneaux`, `previsualiser_creneaux_recurrents`, `diagnostiquer_grille_creneaux`) signale toujours une vacation au-delà de `dureeVacationMaxMinutes` (6 h par défaut), une vacation que le repos quotidien rend intenable, un relais repas hors fenêtre, un doublon, un trou, une date isolée. `diagnostiquer_grille_creneaux` est le premier appel pour découvrir une édition.

Quand les stands ont leurs horaires, `previsualiser_derivation_creneaux` montre la grille qu'ils impliquent (une coupure à chaque heure où un stand ouvre ou ferme) et `generer_creneaux_depuis_stands` l'écrit, en ajout ou, avec `remplacer=true`, à la place de la grille et du plan avec elle. `creer_creneau` et `modifier_creneau` prennent un `couverturePause` facultatif : le relais repas garde le stand ouvert à la moitié de son effectif, arrondie au supérieur.

Une fenêtre d'ouverture (`ajouter_horaire_stand`, `creer_stand_complet`, `ajouter_ouverture_stand`) peut nommer son effectif par le suffixe `@N` (`10:00-12:00@2,14:00-@4`) ou l'argument `effectif` d'une ouverture datée ; sans lui elle reprend le minimum du stand. Zéro est refusé (un stand sans personne est une fermeture) et le suffixe est rejeté sur la récurrence d'un créneau.

`definir_journee_type` crée ou remplace une journée type par son nom et ses vacations sur une ligne (« 09:00-12:00, 12:00-13:00 R, 13:00-14:00 R, 14:00-20:00 », `R` marquant un relais repas aux sièges divisés par deux), `affecter_journee_type` la pose sur une plage ou des dates. Rien de cela n'écrit un créneau : `previsualiser_application_journees_types` dit ce qu'appliquer changerait et `materialiser_journees_types` l'écrit. `reconnaitre_journees_types` fait le chemin inverse depuis les créneaux, et `lister_journees_types` rend les dates en écart ou sous consigne, que la matérialisation ne touche pas ([ADR 0032](repo:docs/decisions/0032-journees-types-nommees-vacations-fixes.md)).

## Poser une consigne

Un arrêté ferme une bande horaire pour tous les stands, et la question est de savoir si le plan peut être retourné avant la nuit ([ADR 0043](repo:docs/decisions/0043-consigne-d-edition-fermer-une-bande-sans-rien-detruire.md)). Huit outils miroitent l'écran Consignes : `lister_consignes`, `consulter_preselection_consigne`, `simuler_consigne`, `appliquer_consigne`, `simuler_levee_consigne`, `lever_consigne`, `definir_prereglage_consigne`, `supprimer_prereglage_consigne`. Aucun ne nomme une personne.

Les **fenêtres** s'écrivent `HH:MM-HH:MM` séparées par des virgules, la fin omise ou à `00:00` valant « jusqu'à minuit » (`08:00-10:00,18:00-`). Les **ouvertures** nomment le stand, sa fenêtre et, après `*`, l'effectif de cette fenêtre : `BOURSE=08:00-10:00*2,BOURSE=18:00-22:00*7`. Sans `*N`, la fenêtre hérite du plus fort effectif que le stand perd dans la bande, sinon de son minimum. Le motif est obligatoire : il est imprimé partout où la journée est dite modifiée. Les fenêtres repas (`repasMidi`, `repasSoir`, `coupureRepasMinutes`, `justificationRepas`) ne valent que sur les dates de la consigne.

1. `consulter_preselection_consigne` sur chaque date : ce que la bande prend à chaque stand, les stands proposés, ceux écartés pour horaires posés à la main. N'écrit rien.
2. `simuler_consigne` : par jour, sièges et minutes avant et après, créneaux à ajouter, verrous et règles ad hoc touchés. C'est la validation complète sans écriture : une requête que la simulation accepte sera acceptée telle quelle.
3. `appliquer_consigne`, mêmes arguments : pose ou remplace la consigne d'une date et n'ajoute à la grille que les créneaux que les ouvertures exigent. Les dates doivent être à venir et s'écrivent toutes ensemble ou pas du tout.
4. `resoudre_incremental`, puis `etat_publication` et `publier_planning`.

Lever suit le même pli : `simuler_levee_consigne` nomme les créneaux ajoutés qui partiraient avec leurs sièges et avertit quand les journées figées épuisent les gens disponibles, `lever_consigne` les retire et refuse une date passée ou en cours, un jour travaillé gardant la consigne qui l'a gouverné. Un préréglage (« Plan canicule ») mémorise bande, motif et fenêtres et suit la duplication de l'édition. `consulter_alertes_meteo` rend les alertes levées le matin avec le préréglage suggéré pour le phénomène ; il ne pose rien, appliquer la suggestion reste un `appliquer_consigne` explicite.

## Décider ce que les animateurs ont demandé

Les déclarations de disponibilité et les demandes d'échange arrivent de l'espace animateur. `appliquer_declaration_disponibilite` écrit la déclaration sur la fiche par le même `write*` que l'écran de saisie : les données sont marquées modifiées, `etat_planning` signale le plan périmé, le journal porte les champs remplacés (`joursIndisponibles`, `souhaits`) et l'outil rend les avertissements en codes.

`consulter_foire_echanges` rend la fenêtre de la foire et ses statistiques (volumes, taux, délais, répartitions par jour et par stand) sur une période de création (`du`, `au`, ou `touteEdition`, refusé avec les deux autres). Aucun animateur n'y figure, pas même par id.

`synthese_confirmations` compte, parmi les animateurs qui ont un poste sur le plan publié, les confirmés, relancés, silencieux et les **échecs d'envoi** (comptés à part, sans adresse) et date la publication à laquelle ils répondent ; `jamaisPublie` vrai veut dire que la question n'a encore été posée à personne.

`lister_demandes_covoiturage` rend les « Je viens avec… » de la collecte, à part de la déclaration : membres, statut, `confirmedByAll`, jours où les indisponibilités divergent, ajustement créé, le tout par id et avec le statut `EN_ATTENTE` (non validée) ou `VALIDEE`. La validation, l'écart et l'annulation restent des gestes d'écran, parce que la validation prévient les animateurs par courriel ; un assistant crée directement un `ARRIVEE_GROUPEE` par `creer_contrainte_ad_hoc`. En revanche, `supprimer_contrainte_ad_hoc` sur une arrivée groupée qu'une demande validée soutient rend un résultat en erreur portant la phrase de l'écran Ajustements manuels (annulez-la depuis Disponibilités > Covoiturage).

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

La 4.0 renomme, regroupe ou retire des outils de la 3.x, sans alias : un outil n'est publié que sous son nom courant ([ADR 0100](repo:docs/decisions/0100-convention-de-nommage-des-outils-mcp.md)). Ce tableau est le rendu des lignes changées de [`mcp-renommages.tsv`](repo:src/main/resources/mcp-renommages.tsv) ; un nom absent n'a pas changé. Quand le fichier change, `McpRenommageTest` échoue et imprime le tableau à coller entre les deux repères.

<!-- mcp-renommages:debut -->
| Nom en 3.x | Nom en 4.0 |
| --- | --- |
| `capturer_instantane` | `creer_version` |
| `comparer_instantanes` | `comparer_versions` |
| `consulter_instantane` | `consulter_version` |
| `consulter_parametres_legaux` | `consulter_parametres` |
| `consulter_parametres_notifications` | `consulter_parametres` |
| `consulter_parametres_qualite` | `consulter_parametres` |
| `consulter_parametres_solveur` | `consulter_parametres` |
| `lister_instantanes` | `lister_versions` |
| `lister_kpi_historique` | `lister_mesures_edition` |
| `modifier_parametres_legaux` | `modifier_parametres` |
| `modifier_parametres_notifications` | `modifier_parametres` |
| `modifier_parametres_qualite` | `modifier_parametres` |
| `modifier_parametres_solveur` | `modifier_parametres` |
| `realise_vs_planifie` | `comparer_realise_planifie` |
| `restaurer_instantane` | `restaurer_version` |
| `supprimer_instantane` | `supprimer_version` |
| `supprimer_kpi_historique` | `supprimer_mesure_edition` |
<!-- mcp-renommages:fin -->

## Ce qui n'a pas d'outil

| Ce qui reste dehors | Pourquoi |
| --- | --- |
| Export de scénario YAML | il contient prénom, nom et date de naissance, puisqu'il doit être réimportable |
| Sauvegardes avant vidage | chacune est un export de scénario. `reinitialiser_donnees` et les imports en écrivent une, mais seul un administrateur la lit, la restaure ou la supprime, par l'API ([ADR 0084](repo:docs/decisions/0084-sauvegarder-l-edition-avant-de-la-vider.md)) |
| Dump SQL, exports PDF, ICS et CSV | toutes les données personnelles, ou des binaires destinés au téléchargement depuis l'interface |
| Import CSV des bénévoles | le tableur porte nom, prénom, date de naissance et adresse : un outil qui l'accepterait ferait entrer par MCP ce qui n'en sort pas |
| `/api/planning/sample`, `/persisted` | un `PlanningEvenement` entier, plusieurs dizaines de Mo avec les données personnelles ; `lister_affectations` couvre le besoin en restant filtré |
| Espace animateur et abonnement ICS | le côté animateur, derrière un jeton personnel : MCP voit les demandes et déclarations côté admin, jamais l'espace de quelqu'un |
| Rotation du jeton d'espace d'un animateur | elle invalide le lien déjà imprimé sur un PDF distribué, conséquence qu'on ne peut pas annuler depuis une conversation |
| Webhooks sortants | une adresse Slack, Discord ou Matrix est un secret, et un webhook ouvre une sortie réseau depuis le serveur : le configurer depuis une conversation serait une voie d'exfiltration toute tracée. Ils se règlent dans Paramètres › Instance |
| `/api/horloge` | l'horloge simulée déplace pour tout le monde le mode jour J, le passé figé et les espaces animateurs : un réglage d'instance, pas une question d'organisation |
| `/api/config`, `/api/auth`, `/api/branding`, `/api/mentions-legales`, `/api/debug` | clés publiques du navigateur, session admin, habillage et bacs à sable de développement : rien qu'un assistant puisse en faire |
