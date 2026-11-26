# Les exceptions ad hoc et leurs contradictions

Vous savez ce que sont les cinq types d'exceptions ad hoc, comment une demande de covoiturage en devient une, quelles combinaisons l'application refuse à la saisie, lesquelles elle laisse passer volontairement, et comment elle signale une exception qu'aucune place ne peut tenir.

## Ce qu'est une exception ad hoc

`ContrainteAdHoc` dans le domaine et sur le fil, « Ajustements manuels » à l'écran (onglet Ajustements de « Règles du planning »). Ce sont des exceptions ponctuelles posées par l'administrateur, stockées en base et évaluées dynamiquement : **jamais de règle codée en dur pour un cas particulier**. Ce qu'un organisateur saisit là est une exception au plan, pas une règle du catalogue. Les cas limites côté utilisateur (sémantique « l'un de ces animateurs », périmètre vide, créneau supprimé) sont dans l'aide en ligne, section « Ajustements manuels ».

| Type (`TypeContrainteAdHoc`) | Ce qu'il dit | Niveau |
| --- | --- | --- |
| `INDISPONIBILITE_FORCEE` | Un animateur ne travaille pas sur un périmètre | Dur |
| `INCOMPATIBILITE` | Deux animateurs ne tiennent jamais le même créneau, quel que soit le stand ; le périmètre peut la restreindre à un stand ou à un créneau | Dur |
| `AFFECTATION_FORCEE` | Au moins une place du périmètre est tenue par **l'un** des animateurs nommés, jamais par tous | Dur |
| `AFFINITE` | Une paire à rapprocher : récompensée sur le même stand et le même créneau, jamais imposée | Souple |
| `ARRIVEE_GROUPEE` | Deux à quatre animateurs qui arrivent et repartent ensemble (un covoiturage), sans créneau ni stand | Souple |

Les types prescriptifs sont notés en dur, à la même priorité que les règles légales : ne les rétrogradez jamais en moyen ou en souple. `AFFINITE` est l'exception délibérée, une récompense souple, et la promouvoir en dur serait une affectation forcée déguisée.

Ces exceptions se posent **avant** le calcul : chaque résolution les honore, y compris en repartant de zéro. À ne pas confondre avec le [verrou](plan-verrous-et-publication.md#le-verrou-fige-des-sièges), qui fige *après coup* ce qu'une résolution a produit sans rien dire de ce qui devrait s'y trouver. L'aide porte un encart « Ajustement manuel ou verrouillage ? ».

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

Les exceptions saisies à la main s'appliquent en dur. Rien n'empêcherait donc d'en saisir deux qui ne peuvent pas être satisfaites ensemble, et le seul symptôme serait un score dur négatif au calcul suivant, sans que rien désigne la cause. `ContrainteAdHocContradictions` refuse ces combinaisons à l'écriture, avec un message qui **nomme les exceptions en cause**. Cinq familles sont détectées :

| Combinaison | Pourquoi elle est impossible |
| --- | --- |
| Même paire déclarée `INCOMPATIBILITE` **et** `AFFINITE` | La règle dure gagnerait toujours sur la récompense souple, sans que personne soit prévenu |
| `AFFECTATION_FORCEE` dont tout le périmètre est couvert par une `INDISPONIBILITE_FORCEE` visant chacun des animateurs nommés | Tout poste qui satisferait la première est pénalisé par la seconde |
| Deux `AFFECTATION_FORCEE` fixant **le même animateur** sur des périmètres qui se chevauchent dans le temps | `pasDeChevauchementHoraire` interdit de tenir les deux postes, et aucun poste unique ne satisfait les deux |
| Deux `AFFECTATION_FORCEE` plaçant une paire `INCOMPATIBILITE` sur **le même créneau** | `incompatibiliteAdHoc` joint sur le créneau seul : deux postes sur deux stands de ce créneau sont tout aussi infaisables |
| Une `ARRIVEE_GROUPEE` dont **deux membres** forment une paire `INCOMPATIBILITE` **sans stand ni créneau** | Aligner leurs journées rend l'incompatibilité quasi intenable : la règle dure l'emporterait et le covoiturage serait pénalisé chaque jour sans que personne sache pourquoi |

La quatrième ligne se lit **au créneau, pas au stand**, c'est ce que la règle applique réellement : restreindre la détection au même stand laisserait passer des combinaisons que le solveur déclarerait infaisables.

La cinquième est la seule qui ne soit pas *certainement* insatisfiable : une arrivée groupée est souple, et deux membres incompatibles pourraient arriver ensemble et ne jamais tenir le même créneau. Elle est refusée quand même, dans les deux sens (l'arrivée groupée après l'incompatibilité, ou l'inverse), parce que la combinaison ne décrit rien qu'un organisateur veuille : on ne met pas dans la même voiture deux personnes qu'on refuse de faire travailler ensemble. Seule l'incompatibilité **générale** est en cause. Une incompatibilité limitée à un stand ou à un créneau laisse la paire libre de tenir les mêmes horaires ailleurs, ce qui suffit à la voiture : elle passe.

### Ce qui passe délibérément

La validation ne refuse que ce qui est **certainement** insatisfiable : une exception refusée à tort coûte à l'utilisateur une saisie légitime, sans contournement, là où une exception litigieuse laissée passer ne coûte qu'un calcul. Passent donc :

- une `AFFECTATION_FORCEE` nommant deux animateurs, qu'un seul d'entre eux suffit à satisfaire : elle ne contredit qu'une indisponibilité qui les couvre tous les deux ;
- une indisponibilité **plus étroite** que le périmètre forcé : le poste peut se poser ailleurs dans ce périmètre ;
- deux affectations forcées du même animateur sur le même créneau quand au plus une des deux précise un stand : un seul poste les satisfait toutes les deux.

Les chevauchements se calculent sur la fenêtre **nominale** du créneau. Une fermeture de stand peut rétrécir la fenêtre réellement couverte par un poste : deux affectations forcées sur des créneaux qui se recouvrent, mais sur deux stands fermés à des heures complémentaires, sont donc refusées alors que le solveur aurait pu les poser. Le périmètre d'une exception est ce que l'utilisateur a saisi, et refuser cette combinaison en nommant les deux est la réponse honnête.

### Où le contrôle s'applique

Il vaut pour **toute** écriture : la saisie unitaire (`POST /api/contraintes-ad-hoc`, outil MCP `creer_contrainte_ad_hoc`) comme l'import d'un scénario, qui écrit tout le jeu d'un coup. Un fichier ne peut pas installer une combinaison que le formulaire refuse. Réenregistrer une exception **sous son propre id** reste possible : la version sauvegardée remplace la précédente au lieu de coexister avec elle.

Un budget d'exceptions (« pas plus de N ») a été explicitement écarté au profit de ce contrôle : voir [la décision 0010](repo:docs/decisions/0010-contraintes-ad-hoc-contradiction-plutot-que-budget.md).

### Ce qui subsiste, et comment on le voit

Une contradiction saisie **avant** ce contrôle reste en base et n'apparaîtrait nulle part. `FeasibilityAnalyzer` la remonte donc comme cause bloquante `CONTRAINTES_AD_HOC_CONTRADICTOIRES`, avant tout calcul : elle s'affiche sur la page Problèmes et badge les lignes concernées de la page des ajustements.

Quand un calcul termine **quand même** en dur négatif, le diagnostic répond à la question suivante, « lesquelles de mes exceptions ? », avec `contraintesAdHocEnCause` : une ligne par exception encore en écart, son id, sa raison et le nombre d'écarts qu'elle porte. Les lignes d'écart nomment elles aussi l'exception par son id.

## Les affectations forcées qu'on ne peut pas tenir

Une `AFFECTATION_FORCEE` peut être intenable sans contredire aucune autre exception : elle oppose une exception à une donnée qui, elle, ne se refuse pas. Trois lectures, trois classes pures, une même doctrine : **avertir à l'écriture, reporter comme cause bloquante tant que ça tient, ne jamais refuser**. La donnée qui casse l'exception arrive presque toujours après elle, et un refus ne tiendrait que jusque-là ([décision 0046](repo:docs/decisions/0046-un-placement-intenable-est-dit-avant-le-calcul.md)).

| Lecture | Ce qui rend l'exception intenable | Avertissement et cause |
| --- | --- | --- |
| `ForcedAssignmentOnDayOff` | **Tous** les animateurs nommés se sont déclarés indisponibles à **chaque** date du périmètre | `AFFECTATION_FORCEE_JOUR_INDISPONIBLE` |
| `ForcedAssignmentOnExcludedSeats` | Aucune place du périmètre n'accepte l'un des animateurs nommés au titre d'une règle dure du couple place × animateur, les motifs d'`EligibleAnimateurMoveFilter` : mineur la nuit, jour férié, stand réservé aux majeurs, plafond quotidien dépassé | `AFFECTATION_FORCEE_MOTIF_LEGAL` ; le message nomme les règles du catalogue |
| `ForcedAssignmentOnLockedSchedule` | L'emploi du temps de chacun des animateurs nommés est verrouillé (`ANIMATEUR` ou `ANIMATEUR_CRENEAU`) sur tout le périmètre, et aucun n'y tient déjà de place : le solveur ne peut ni bouger le verrou ni ignorer l'exception | `AFFECTATION_FORCEE_SIEGE_VERROUILLE` |

Les deux premières ne se doublent jamais : le jour déclaré indisponible est *aussi* un motif d'exclusion dur, donc la seconde se tait sur un cas que la première rapporte, parce que la lecture en termes de déclaration est celle que l'organisateur sait traiter, en parlant à la personne. La troisième peut s'ajouter aux deux autres, et c'est voulu : un verrou et une règle légale sont deux blocages indépendants, qui se lèvent par deux gestes différents.

Deux précautions évitent d'inventer un conflit. Les places du périmètre sont construites comme `ProblemBuilder` les génère, une par (stand ouvert sur le créneau, créneau) : un périmètre qui n'ouvre nulle part, ou qui nomme un stand inconnu du référentiel, ne porte aucune place et ne dit rien. Et les plafonds de durée se lisent avec la lecture la plus permissive de la pause, de sorte qu'un conflit rapporté est un conflit que le solveur rencontrerait quoi qu'en dise l'édition.

Seuls les verrous `ANIMATEUR` et `ANIMATEUR_CRENEAU` entrent dans la troisième lecture. Un verrou de stand, de créneau ou de journée épingle les places qui **tiennent quelqu'un** et laisse les places vides remplissables : il ne s'oppose jamais à l'affectation que l'exception réclame.

Enfin, le calcul garde le jour d'indisponibilité, le verrou et la règle légale : c'est l'exception qui n'est pas tenue. Elle est alors comptée en écart et nommée par `contraintesAdHocEnCause`.
