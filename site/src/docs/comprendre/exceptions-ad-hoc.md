# Les exceptions ad hoc et leurs contradictions

Vous savez ce que sont les trois types d'exceptions ad hoc, ce que sont devenues l'affectation forcée et l'indisponibilité forcée de la 3.x, comment une demande de covoiturage devient une exception, et quelles combinaisons l'application refuse à la saisie.

## Ce qu'est une exception ad hoc

`ContrainteAdHoc` dans le domaine et sur le fil, « Ajustements manuels » à l'écran (onglet Ajustements de « Règles du planning »). Ce sont des exceptions ponctuelles posées par l'administrateur, stockées en base et évaluées dynamiquement : **jamais de règle codée en dur pour un cas particulier**. Ce qu'un organisateur saisit là est une exception au plan, pas une règle du catalogue. Les cas limites côté utilisateur (sémantique « l'un de ces animateurs », périmètre vide, créneau supprimé) sont dans l'aide en ligne, section « Ajustements manuels ».

| Type (`TypeContrainteAdHoc`) | Ce qu'il dit | Niveau |
| --- | --- | --- |
| `INCOMPATIBILITE` | Deux animateurs ne tiennent jamais le même créneau, quel que soit le stand ; le périmètre peut la restreindre à un stand ou à un créneau | Dur |
| `AFFINITE` | Une paire à rapprocher : récompensée sur le même stand et le même créneau, jamais imposée | Souple |
| `ARRIVEE_GROUPEE` | Deux à quatre animateurs qui arrivent et repartent ensemble (un covoiturage), sans créneau ni stand | Souple |

L'incompatibilité est notée en dur, à la même priorité que les règles légales : ne la rétrogradez jamais en moyen ou en souple. `AFFINITE` est l'exception délibérée, une récompense souple, et la promouvoir en dur serait une affectation forcée déguisée.

Ces exceptions se posent **avant** le calcul : chaque résolution les honore, y compris en repartant de zéro. À ne pas confondre avec le [verrou](plan-verrous-et-publication.md#le-verrou-fige-des-sièges), qui fige *après coup* ce qu'une résolution a produit sans rien dire de ce qui devrait s'y trouver. L'aide porte un encart « Ajustement manuel ou verrouillage ? ».

## Ce que sont devenues l'affectation forcée et l'indisponibilité forcée

Elles n'existent plus depuis la 4.0 ([décision 0117](repo:docs/decisions/0117-deux-types-d-exception-les-forcees-sont-converties.md)) : un verrou et un jour indisponible de la fiche disent la même chose avec un geste que l'organisateur connaît déjà, et le solveur n'a plus qu'une règle de moins à écrire deux fois. Ce qu'il faut faire à la place est dans l'aide, « Comment faire sans affectation forcée », et dans [le guide](../regles/ajustements-et-verrous.md).

| Avant (3.x) | Après (4.0) |
| --- | --- |
| `AFFECTATION_FORCEE` sur un créneau | Un verrou `ANIMATEUR_CRENEAU` pour chaque animateur nommé qui tient une place du périmètre sur le plan enregistré |
| `INDISPONIBILITE_FORCEE` sur un créneau | Le **jour entier** ajouté aux jours indisponibles de la fiche : la fiche ne connaît pas le créneau |
| `INDISPONIBILITE_FORCEE` sans créneau | Tous les jours qui portent un créneau, ajoutés aux jours indisponibles de la fiche |
| `INDISPONIBILITE_FORCEE` sur un stand | Rien : « pas sur ce stand » n'est pas un jour d'indisponibilité |

La migration (V161) et la lecture d'un fichier ou d'une copie de la 3.x (`ScenarioMigrations.withRetiredAdHocTypesConverted`) font la même conversion et la disent. Le plan enregistré ne change pas. Ce qui ne se convertit pas (une indisponibilité visant un stand, une affectation que personne ne tient, une affectation sans créneau) est signalé et gardé dans l'archive `contrainte_ad_hoc_avant_4_0`, que la 5.0 supprimera. Le mode jour J marque une absence de la même façon : le jour entier sur la fiche (voir [l'API du jour J](api-jour-j-et-animateurs.md)).

## L'arrivée groupée et les demandes de covoiturage

L'arrivée groupée naît le plus souvent d'une demande. Dans l'onglet Covoiturage de son espace, pendant la fenêtre de collecte des disponibilités, un animateur nomme jusqu'à trois coéquipiers (« Je viens avec… »). La demande est **indépendante de la déclaration de disponibilités** : son propre envoi, sa propre décision. Elle est rangée dans `declaration_coequipier`, une table générique (`nature` = `COVOITURAGE`, `BINOME` réservé), et appliquer ou refuser une déclaration ne la touche pas. Une seule demande reste en attente par animateur.

L'administrateur la **valide** (onglet Covoiturage de la page Disponibilités), ce qui crée l'ajustement, ou l'**écarte**, ce qui supprime la demande : l'animateur peut en envoyer une autre. Seule la validation part par e-mail, au mieux, à chaque membre. Un groupe validé ne se change plus depuis l'espace : l'ajustement appartient à l'organisation, qui peut l'**annuler** depuis le même onglet. L'annulation supprime l'ajustement et, dans la même transaction, toutes les demandes validées avec lui (`contrainte_id`) ; personne n'est prévenu par e-mail, et une nouvelle demande obéit à la règle ordinaire (pendant la fenêtre de collecte seulement).

| `statut` (`StatutDemandeCoequipier`) | Sens |
| --- | --- |
| `EN_ATTENTE` | Envoyée depuis l'espace, en attente de l'administrateur |
| `VALIDEE` | Transformée en ajustement `ARRIVEE_GROUPEE` (`contrainte_id`) |

Une demande écartée ou annulée n'a pas d'état : elle est supprimée.

Tant qu'une demande `VALIDEE` pointe vers une arrivée groupée, **aucune autre écriture ne la modifie ni ne la supprime** : page Ajustements manuels, outils MCP, tout appel de `ContrainteAdHocService` répond `409` avec une phrase unique qui renvoie à l'onglet Covoiturage. Une arrivée groupée saisie à la main reste modifiable. L'import d'un scénario et la réinitialisation, qui remplacent les ajustements de toute l'édition, ne sont pas concernés. Une arrivée groupée compte 2 à 4 membres distincts, sans créneau ni stand : une forme mal remplie est refusée à la saisie comme à l'import.

## Les contradictions refusées à la saisie

L'incompatibilité s'applique en dur. Rien n'empêcherait donc d'en saisir une qui contredit une autre exception, et le seul symptôme serait un score dur négatif au calcul suivant, sans que rien désigne la cause. `ContrainteAdHocContradictions` refuse ces combinaisons à l'écriture, avec un message qui **nomme les exceptions en cause**. Deux familles sont détectées :

| Combinaison | Pourquoi elle est impossible |
| --- | --- |
| Même paire déclarée `INCOMPATIBILITE` **et** `AFFINITE` | La règle dure gagnerait toujours sur la récompense souple, sans que personne soit prévenu |
| Une `ARRIVEE_GROUPEE` dont **deux membres** forment une paire `INCOMPATIBILITE` **sans stand ni créneau** | Aligner leurs journées rend l'incompatibilité quasi intenable : la règle dure l'emporterait et le covoiturage serait pénalisé chaque jour sans que personne sache pourquoi |

La seconde est la seule qui ne soit pas *certainement* insatisfiable : une arrivée groupée est souple, et deux membres incompatibles pourraient arriver ensemble et ne jamais tenir le même créneau. Elle est refusée quand même, dans les deux sens (l'arrivée groupée après l'incompatibilité, ou l'inverse), parce que la combinaison ne décrit rien qu'un organisateur veuille : on ne met pas dans la même voiture deux personnes qu'on refuse de faire travailler ensemble. Seule l'incompatibilité **générale** est en cause. Une incompatibilité limitée à un stand ou à un créneau laisse la paire libre de tenir les mêmes horaires ailleurs, ce qui suffit à la voiture : elle passe.

### Ce qui passe délibérément

La validation ne refuse que ce qui est **certainement** contradictoire : une exception refusée à tort coûte à l'utilisateur une saisie légitime, sans contournement, là où une exception litigieuse laissée passer ne coûte qu'un calcul. Une incompatibilité limitée à un stand ou à un créneau, par exemple, passe à côté d'une arrivée groupée de la même paire.

### Où le contrôle s'applique

Il vaut pour **toute** écriture : la saisie unitaire (`POST /api/contraintes-ad-hoc`, outil MCP `creer_contrainte_ad_hoc`) comme l'import d'un scénario, qui écrit tout le jeu d'un coup. Un fichier ne peut pas installer une combinaison que le formulaire refuse. Réenregistrer une exception **sous son propre id** reste possible : la version sauvegardée remplace la précédente au lieu de coexister avec elle.

Un budget d'exceptions (« pas plus de N ») a été explicitement écarté au profit de ce contrôle : voir [la décision 0010](repo:docs/decisions/0010-contraintes-ad-hoc-contradiction-plutot-que-budget.md).

### Ce qui subsiste, et comment on le voit

Une contradiction saisie **avant** ce contrôle reste en base et n'apparaîtrait nulle part. `FeasibilityAnalyzer` la remonte donc comme cause bloquante `CONTRAINTES_AD_HOC_CONTRADICTOIRES`, avant tout calcul : elle s'affiche sur la page Problèmes et badge les lignes concernées de la page des ajustements.

Quand un calcul termine **quand même** en dur négatif, le diagnostic répond à la question suivante, « lesquelles de mes exceptions ? », avec `contraintesAdHocEnCause` : une ligne par exception encore en écart, son id, sa raison et le nombre d'écarts qu'elle porte. Les lignes d'écart nomment elles aussi l'exception par son id.
