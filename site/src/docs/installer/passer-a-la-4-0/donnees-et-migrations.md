---
ordre: 1000
---

### Données et migrations

Au premier démarrage, la 4.0.0 applique les migrations `V140` à `V170`. Chacune est décrite dans la section de son changement ; ce tableau les rassemble, avec ce qu'elle retire ou renomme et l'archive qu'elle garde. Une archive `…_avant_4_0` n'est ni lue ni exportée par l'application, et la 5.0 la supprimera : d'ici là, elle permet un retour à la 3.x sur une copie de la base, et elle reste à consigner dans votre registre des traitements ([durées de conservation](conservation-des-donnees.md)).

| Migration | Ce qu'elle change dans le schéma | Archive gardée jusqu'en 5.0 | Section |
| --- | --- | --- | --- |
| `V140` | colonne `typologie.ninja` renommée `polyvalent` | aucune | La typologie « ninja » devient « polyvalent » |
| `V142` | demandes de covoiturage écartées ou annulées supprimées, colonne `motif` retirée | `declaration_coequipier_avant_4_0` | Le covoiturage se réduit à validé ou non |
| `V143` | table `plan_snapshot` renommée `version_plan`, colonne `automatique` remplacée par l'origine de la version | aucune | Les instantanés deviennent des versions |
| `V145` | durée et plateau de calcul retirés des réglages de l'édition et des tâches de calcul | `solve_budget_avant_4_0` | Le budget de calcul, la file et l'instance publique |
| `V147` | colonne `plan` de la copie avant vidage rendue facultative, rien n'est réécrit | aucune | La copie avant vidage devient un export YAML |
| `V148` | `kpi_historique` et `kpi_realise` réunies dans `mesure_edition` | `kpi_historique_avant_4_0`, `kpi_realise_avant_4_0` | Les mesures d'une édition n'ont plus qu'une table et qu'un écran |
| `V149` | niveau choisi par édition pour chaque règle d'organisation, aucun score ne change | aucune | Le niveau d'une règle d'organisation se choisit par édition |
| `V150` | colonne `modifie_le` sur les quatre tables de paramètres | aucune | Les paramètres de l'édition en quatre sections |
| `V151` | verrous `ANIMATEUR`, `STAND` et `CRENEAU` convertis en verrous animateur + créneau et stand + jour | aucune | Le verrou n'a plus que trois types |
| `V152`, `V154` | réglages des règles majeur / mineur fusionnés sur la lecture la plus protectrice | aucune | Noms des règles du planning |
| `V155` | gel du référentiel remplacé par la phase de l'édition | `gel_referentiel`, gardée sous son nom, ni lue ni exportée | Le gel du référentiel devient la phase de l'édition |
| `V158` | historique des pondérations sorti des tables lues | `ponderation_contrainte_historique_avant_4_0` | L'historique des pondérations rejoint le journal des actions |
| `V159` | règles récurrentes des stands dépliées en plages datées | aucune (les règles restent le modèle du stand) | Horaires des stands |
| `V160` | exclusions de publication en cours ignorées | `exclusion_publication_avant_4_0` | « Exclure à la publication » est retiré |
| `V161` | affectations et indisponibilités forcées converties en verrous et en jours indisponibles, puis supprimées | `contrainte_ad_hoc_avant_4_0` | L'affectation forcée et l'indisponibilité forcée sont converties |
| `V162` | forme dure des jours d'affilée fondue dans la règle à niveau réglable | aucune | Une seule règle de jours d'affilée |
| `V165` | interrupteur du placement de la coupure repas repris en paramètre de l'édition | aucune | Les deux règles de repas n'ont plus qu'une ligne |
| `V168` | consignes posées réécrites en plages de fermeture datées | aucune | La consigne s'écrit en plages datées |
| `V169` | `journee_type` et ses tables renommées `gabarit` | `journee_type_avant_4_0` (ce qui ne se convertit pas) | Un seul générateur de créneaux |
| `V170` | colonne `couverture_pause` retirée des créneaux et des gabarits | aucune (le nombre de vacations touchées est écrit au journal de migration) | Le relais repas à effectif réduit est retiré |

**Ce que vous faites** : une sauvegarde complète avant de monter, et un export YAML de chaque édition ; le schéma ne redescend pas. Après la montée, lisez le journal de démarrage : `V161` y nomme chaque exception forcée qu'elle n'a pas pu convertir ou qu'elle a élargie à la journée entière, et `V170`, s'il y en a, le nombre de créneaux et de vacations de gabarit qui ont perdu leur relais repas.
