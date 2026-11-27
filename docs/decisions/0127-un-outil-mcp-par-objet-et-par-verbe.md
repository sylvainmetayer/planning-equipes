# 0127 — Un outil MCP par objet et par verbe, les anciens noms en alias jusqu'en 5.0

- **Statut** : accepté, implémenté pour toutes les familles ; l'inventaire de clôture est en annexe
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
6. **Un onglet ou un rapport est un objet, sa partie une `section`** : `consulter_besoin` et `consulter_risques` suivent les onglets Besoin et Risques du Diagnostic, `consulter_charge` et `consulter_repas_pauses` les rapports du même nom. Un argument qui ne vaut que pour une partie (`mode` de la marge, `source` des heures, filtres des pauses) est refusé hors d'elle plutôt qu'ignoré, comme les filtres de `consulter_planning`. Une partie lue sur un rapport qui nomme des personnes passe par une vue à elle, par ids : la fragilité, que MCP ne lisait pas, perd le nom de chaque ligne et la phrase de l'écran.
7. **Deux outils pour un même objet quand leurs appelants diffèrent.** Un argument ne change pas non plus qui peut appeler l'outil (ADR 0097) : `modifier_phase_edition` reste ouvert à l'organisateur connecté par OAuth, `modifier_edition` (`nom`, `active`) et `creer_edition` (`source` pour une copie) sont réservés à l'administrateur de l'instance. Les fondre aurait fermé la phase à l'organisateur, ou ouvert l'activation à tous.
8. **Les paramètres sont un outil à sections** (`consulter_parametres` et `modifier_parametres`, `section` = `legal`, `organisation`, `notifications` ou `calcul`) : deux outils et non un, parce qu'un outil qui lit et écrit ne peut pas se déclarer en lecture seule. Les anciens noms de la 3.x reviennent en alias, sauf `modifier_parametres_solveur` : le budget de calcul qu'il réglait est passé à l'instance (ADR 0110), un alias n'aurait su que refuser.
9. **Un envoi de courriel n'est jamais un mode d'un autre outil.** Trois outils écrivent aux animateurs, chacun avec son verbe : `publier_planning` (publie, à toute la liste, sans argument de mode), `envoyer_planning_animateur` (renvoie le planning publié à **une** personne) et `envoyer_relance_animateurs` (relance les silencieux désignés). Un assistant qui oublie ou se trompe d'un argument `mode` écrirait à toute l'équipe au lieu d'une personne : la portée d'un envoi se lit dans le nom de l'outil, que l'humain voit avant d'approuver. Chacun garde `openWorldHint = true` et passe par une méthode de service gardée par `@RequiresActiveEdition`. `envoyer_planning_animateur` respecte déjà 0100 et reste tel quel ; `relancer_animateurs` (verbe hors de la liste fermée de 0100) devient l'alias de `envoyer_relance_animateurs`, gardé jusqu'en 5.0. **À confirmer** : l'option prudente a été retenue sans avis ; l'alternative, un seul `publier_planning` à `mode`, est écartée ci-dessous.
10. **Un sous-objet est un argument de son objet, lire une fiche est lister avec son id.** Les horaires d'un stand sont un objet sous deux formes, `creer_horaire_stand` et `supprimer_horaire_stand` avec `forme` = `plage` ou `modele` ; `lister_stands` et `lister_animateurs` rendent une seule fiche avec `id`, ce que faisaient `consulter_stand` et `consulter_animateur` ; `creer_stand` prend les arguments de `creer_stand_complet`. Un nom gardé ne change ni ses arguments ni sa réponse : la réponse ne gagne que des clés, et quand elle devrait en perdre, le regroupement prend un nouveau nom (`supprimer_creneaux` pour `supprimer_creneau`, `diagnostiquer_creneaux` pour `diagnostiquer_grille_creneaux`). Un outil qui peut écrire aux animateurs reste à part (`configurer_collecte_disponibilites`, à côté de `consulter_disponibilites` et `modifier_declaration_disponibilite`).

## Options écartées

- **Pas d'alias** (0100 § 5) : une invite écrite pour la 3.x casserait au premier appel, sans que l'opérateur sache quoi changer ; le coût des doublons dans les tests structurels est une ligne par ancien nom, retirée en 5.0.
- **`publier_planning` à trois `mode` (`publier`, `renvoyer`, `relancer`)** : gain de deux outils, mais un `mode` oublié ou faux change le nombre de destinataires, de un à tous.
- **Un outil qui simule et applique selon un drapeau** : il suffirait d'un argument oublié pour écrire sans étape de relecture.

## Conséquences

- Plan, calcul, règles et échanges passent de 33 outils à 21, plus 23 anciens noms publiés jusqu'en 5.0.
- Paramètres, éditions, scénarios, publication, météo et sauvegardes passent de 37 outils à 23, plus 21 anciens noms publiés jusqu'en 5.0.
- Diagnostic et rapports passent de 11 outils à 5, plus 11 anciens noms publiés jusqu'en 5.0 ; la faisabilité, que l'écran montre dans Problèmes, est une section de `consulter_besoin` comme le prévoyait l'onglet Besoin.
- Stands, emplacements, typologies, animateurs, disponibilités et créneaux passent de 36 outils à 26, plus 11 anciens noms publiés jusqu'en 5.0 ; trois outils de plages nés en 4.0 (`creer_plage_stand`, `supprimer_plage_stand`, `supprimer_horaires_stand`) disparaissent sans alias, selon le point 4.
- L'audit de clôture (annexe) ne fusionne rien de plus : aucun outil restant ne répond à coup sûr à la question d'un autre ni n'en est un sous-ensemble ; les deux cas discutables sont en annexe. Il remet dans la convention de 0100 les trois noms dont le verbe n'est pas de la liste : `lever_consigne` devient `supprimer_consigne`, `definir_prereglage_consigne` devient `modifier_prereglage_consigne` (comme `definir_journee_type` est devenu `modifier_gabarit`), `configurer_collecte_disponibilites` devient `modifier_collecte_disponibilites` (comme `configurer_foire_echanges` est devenu `modifier_foire_echanges`), chacun gardant son ancien nom jusqu'en 5.0. `verrouiller` et `deverrouiller` gardent le leur : 0109 l'a jugé conforme, le verbe nommant déjà son objet, le planning.
- `McpRenommageTest` tient désormais tous les noms publiés : un outil courant hors convention, un ancien nom sans `@Deprecated` ou dont la description ne désigne pas un outil courant, ou que la table renvoie ailleurs, font échouer la construction.
- En 5.0, les méthodes `@Deprecated` des classes `mcp` partent avec leurs lignes des listes structurelles.

## Annexe — inventaire de clôture

Le serveur publie 103 outils courants et 82 anciens noms gardés jusqu'en 5.0, soit 185 outils. Des 157 noms de la 3.1, 52 n'ont pas changé, 82 restent publiés comme anciens noms, 20 ont un nouveau nom sans que l'ancien soit encore publié, et 3 sont retirés ; `mcp-renommages.tsv` donne la ligne de chacun. Une famille est une classe du paquet `mcp`.

| Famille (classe) | Courants | Anciens noms | Outils courants |
| --- | --- | --- | --- |
| Animateurs (`AnimateurMcpTools`) | 4 | 1 | `lister_animateurs`, `creer_animateur`, `modifier_animateur`, `supprimer_animateur` |
| Calcul (`SolveurMcpTools`) | 6 | 8 | `lancer_calcul`, `lancer_correction`, `arreter_calcul`, `consulter_calcul`, `supprimer_calcul`, `diagnostiquer_plan` |
| Changements d'une journée (`ChangementsJourneeMcpTools`) | 1 | 1 | `comparer_journee` |
| Consignes (`ConsigneMcpTools`) | 8 | 2 | `lister_consignes`, `consulter_preselection_consigne`, `simuler_consigne`, `appliquer_consigne`, `simuler_levee_consigne`, `supprimer_consigne`, `modifier_prereglage_consigne`, `supprimer_prereglage_consigne` |
| Créneaux (`CreneauMcpTools`) | 5 | 7 | `lister_creneaux`, `creer_creneau`, `modifier_creneau`, `diagnostiquer_creneaux`, `supprimer_creneaux` |
| Diagnostic et mesures (`DiagnosticMcpTools`) | 9 | 8 | `consulter_besoin`, `consulter_risques`, `consulter_repas_pauses`, `analyser_arrivees_groupees`, `lister_anomalies_referentiel`, `lister_mesures_edition`, `supprimer_mesure_edition`, `analyser_usages_suppression`, `previsualiser_import` |
| Disponibilités (`DisponibiliteMcpTools`) | 3 | 6 | `consulter_disponibilites`, `modifier_declaration_disponibilite`, `modifier_collecte_disponibilites` |
| Échanges (`EchangeMcpTools`) | 5 | 3 | `lister_demandes_echange`, `consulter_foire_echanges`, `modifier_foire_echanges`, `modifier_demande_echange`, `analyser_impact_echange` |
| Éditions (`EditionMcpTools`) | 7 | 6 | `lister_editions`, `consulter_edition`, `modifier_phase_edition`, `creer_edition`, `comparer_editions`, `modifier_edition`, `supprimer_edition` |
| État des journées (`ValidationJourneeMcpTools`) | 3 | 0 | `consulter_etats_journee`, `consulter_prerequis_validation`, `modifier_etat_journee` |
| Générateur de créneaux (`GenerateurCreneauxMcpTools`) | 5 | 0 | `lister_gabarits`, `modifier_gabarit`, `supprimer_gabarit`, `previsualiser_generateur_creneaux`, `appliquer_generateur_creneaux` |
| Journées types (3.x) (`JourneeTypeMcpTools`) | 0 | 9 | — |
| Météo (`WeatherMcpTools`) | 1 | 0 | `consulter_alertes_meteo` |
| Paramètres et ajustements (`ParametresMcpTools`) | 5 | 7 | `consulter_parametres`, `modifier_parametres`, `lister_contraintes_ad_hoc`, `creer_contrainte_ad_hoc`, `supprimer_contrainte_ad_hoc` |
| Plan et charge (`PlanningMcpTools`) | 5 | 11 | `consulter_planning`, `analyser_affectation`, `simuler_affectation`, `modifier_affectation`, `consulter_charge` |
| Publication (`PublicationMcpTools`) | 4 | 4 | `consulter_publication`, `publier_planning`, `envoyer_planning_animateur`, `envoyer_relance_animateurs` |
| Réalisé (`RealiseMcpTools`) | 1 | 0 | `comparer_realise_planifie` |
| Règles du calcul (`ContrainteMcpTools`) | 2 | 3 | `lister_contraintes`, `modifier_contrainte` |
| Sauvegardes (`SauvegardeMcpTools`) | 2 | 1 | `consulter_sauvegardes`, `modifier_sauvegardes` |
| Scénarios (`ScenarioMcpTools`) | 4 | 3 | `lister_scenarios`, `importer_scenario`, `diagnostiquer_scenario`, `supprimer_donnees_edition` |
| Stands, emplacements, typologies (`StandMcpTools`) | 14 | 2 | `lister_stands`, `creer_stand`, `modifier_stand`, `supprimer_stand`, `creer_horaire_stand`, `supprimer_horaire_stand`, `lister_emplacements`, `creer_emplacement`, `modifier_emplacement`, `supprimer_emplacement`, `lister_typologies`, `creer_typologie`, `modifier_typologie`, `supprimer_typologie` |
| Verrous (`VerrouillageMcpTools`) | 3 | 0 | `lister_verrouillages`, `verrouiller`, `deverrouiller` |
| Versions du plan (`VersionMcpTools`) | 6 | 0 | `lister_versions`, `consulter_version`, `creer_version`, `restaurer_version`, `supprimer_version`, `comparer_versions` |
| **Total** | **103** | **82** | 185 outils publiés |

Deux cas ont été lus comme des doublons possibles et gardés, à confirmer :

- `analyser_impact_echange` et `simuler_affectation` (mode `deplacer`) chiffrent tous deux un échange de sièges, mais le premier part d'une demande d'échange, son objet, et refait le calcul que la prévalidation a fait à l'envoi ; le second part de deux sièges. Les fondre ferait d'une demande un argument de l'affectation.
- `verrouiller` (type `JOUR`) et `modifier_etat_journee` (état `FIGEE`) posent le même verrou de journée, mais le second porte aussi la relecture et son commentaire, et le premier fige d'autres cibles (un animateur sur un créneau, un stand sur un jour). Ni l'un ni l'autre n'est un sous-ensemble de l'autre.

