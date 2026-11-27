# Verrous, gel, replanification, échanges et plan publié

Vous savez ce que le solveur garde ou rouvre quand on relance un calcul sur un plan existant, ce qui distingue un verrou de la phase de l'édition et d'une exception ad hoc, comment un échange d'animateurs s'applique, et pourquoi l'espace animateur lit un autre plan que celui que l'administration manipule.

## Le verrou fige des sièges

`@PlanningPin` marque une place **validée et figée** : aucun mouvement ne peut en changer l'animateur. Le champ n'est ni positionné par le solveur ni persisté sur le poste : il est recalculé à chaque construction du problème depuis la table des verrous (`VerrouillagePlanning`).

Un verrou a trois types (`TypeVerrouillage`, [décision 0109](repo:docs/decisions/0109-trois-types-de-verrou.md)) : un animateur sur un créneau (`ANIMATEUR_CRENEAU`), un stand sur une journée (`STAND_JOUR`), une journée (`JOUR`). Le premier est aussi posé automatiquement quand un échange est validé. Figer « tout un animateur » ou « tout un stand » se fait en posant un verrou par créneau ou par journée : la fiche d'un animateur le fait d'un geste. Avant la 4.0, un verrou pouvait aussi cibler un animateur sur tout l'événement, un stand sur tout l'événement ou un créneau pour tous les stands ; la migration les a convertis en ce qu'ils figeaient réellement sur le plan enregistré.

**Un verrou n'est pas une exception ad hoc.** Les deux retirent de la liberté au solveur, mais pas au même moment ni sur la même matière. Une [exception ad hoc](exceptions-ad-hoc.md) est une règle sur mesure posée *avant* le calcul (placer ou écarter quelqu'un) que chaque résolution honore, y compris en repartant de zéro. Un verrou est un geste sur un plan *déjà calculé* : il conserve ce qu'une résolution a produit sur une partie du planning, sans rien dire de ce qui devrait s'y trouver, et ne vaut que pour le plan enregistré. Imposer ou interdire une affectation relève de l'exception ad hoc ; protéger une partie validée d'un plan que l'on relance relève du verrou. Côté organisateur, voir [Poser des exceptions et des verrous](../regles/ajustements-et-verrous.md).

**Une cible, jamais deux.** En base, un verrou est une ligne plate (cinq colonnes cibles nullables et un discriminant) que la contrainte `CHECK` garde cohérente. Dans le code, la cible est une hiérarchie scellée : une variante ne porte que ses propres champs et ne peut pas en désigner deux. Le `switch` qui écrit les colonnes est exhaustif sans `default`, donc ajouter une façon de verrouiller ne compile pas tant que personne n'a dit où elle atterrit.

Deux règles :

- **une place non pourvue n'est jamais figée.** Les places couvertes sont réamorcées avec l'animateur de la dernière résolution ; celles qui étaient vides restent mobiles, sinon geler un trou le rendrait définitivement non pourvu ;
- **une place figée est notée normalement.** Un verrou peut donc laisser un écart visible, il ne désactive silencieusement aucune règle.

**Épingler ne suffit pas pour verrouiller un animateur sur un créneau** : cela fige la place qu'il y tient, mais le solveur pourrait lui en attribuer une autre sur ce créneau. Une contrainte dure l'interdit (`animateurVerrouilleCreneauFige`), à partir des verrous transmis comme faits du problème. Les verrous de stand et de journée n'ont pas besoin de règle : ils n'épinglent que les places tenues.

**Un verrou qui vise un créneau nomme sa vacation** (jour, heure de début, heure de fin), pas son identifiant. `creneau.id` est une identité `BIGINT` : supprimer les créneaux d'une journée puis les recréer à l'identique (régénération de la grille, gabarit réappliqué) en donne de nouveaux. Avec un `ON DELETE CASCADE`, toute suppression emportait le verrou sans bruit, alors que valider un échange pose deux verrous précisément pour que la régénération suivante ne défasse pas l'échange. L'identifiant reste stocké comme un cache, réécrit à chaque lecture par une jointure sur la clé naturelle, et vaut `NULL` tant que la grille ne porte pas cette vacation. Un verrou dans cet état **attend** : l'écran Verrouillages le dit, et le contrôle de la grille le signale, y compris sur l'aperçu d'une dérivation, c'est-à-dire avant que « remplacer la grille » soit écrit. La portée d'une exception ad hoc suit la même règle, avec une conséquence de plus : une règle écrite pour un créneau disparu ne vaut **rien** au lieu de valoir pour toute l'édition.

Une journée a un état : à relire, relue ou figée ([décision 0121](repo:docs/decisions/0121-l-etat-de-la-journee-se-lit-sur-la-relecture-et-le-verrou.md)). Cet état n'est pas stocké : **figée est le verrou `JOUR` lui-même**, relue est une relecture (`validation_journee`) sans ce verrou. Le solveur, la section `plan:` d'un fichier et l'écran Verrouillages lisent donc une journée figée comme avant, et la mise à jour vers la 4.0 n'a réécrit aucune ligne. Relire ne pose jamais de verrou sans qu'on le demande ([décision 0039](repo:docs/decisions/0039-validation-de-relecture-distincte-du-verrou.md)) : par l'état, seul `PUT /api/etats-journee/{jour}` avec `FIGEE` en pose un, et seuls `RELUE` et `A_RELIRE` le retirent ; l'écran Verrouillages pose et retire le même verrou, et l'état suit. Les routes `/api/validations` (liste, création, retrait) restent jusqu'en 5.0 : elles écrivent la même relecture et le même verrou, et ne retirent jamais un verrou. L'état est indépendant de la phase de l'édition et de l'édition active.

## La phase de l'édition fige les fiches

Le verrou fige des **sièges** d'un plan calculé ; la phase de l'édition fige les **fiches** dont ce plan est calculé ([décision 0115](repo:docs/decisions/0115-la-phase-de-l-edition-remplace-le-gel.md), qui remplace le gel par famille de la [décision 0052](repo:docs/decisions/0052-gel-du-referentiel-distinct-du-verrou.md)). Une édition est dans l'une de quatre phases, et chacune fige des familles (`ReferentialFamily`) :

| Phase | Stands, créneaux, typologies et emplacements | Compétences des animateurs |
| --- | --- | --- |
| Préparation | modifiables | modifiables |
| Calcul | figés | figées |
| Publiée | figés, sauf structure débloquée | modifiables |
| Terminée | figés | figées |

Ce qu'une famille figée ne laisse plus écrire :

| Famille | Ce qui ne s'écrit plus |
| --- | --- |
| Stands | Création, suppression, effectifs min et max, réserve majeurs, typologies proposées, horaires (règles, ouvertures et fermetures datées) |
| Créneaux | Création, suppression, date, heures, couverture de pause ; gabarits appliqués, dérivation, séries |
| Typologies et emplacements | Création, suppression, plafond par typologie, typologie polyvalente |
| Compétences | Niveaux de compétence des animateurs déjà inscrits (fiche, grille, import) |

Une famille figée refuse l'écriture **par tous les chemins** (formulaire, édition en lot, import CSV des animateurs, grille des compétences, MCP) en `409` avec le code `REFERENTIEL_FIGE`, et le message dit comment rouvrir. Restent libres dans toutes les phases : le nom, le lieu et le niveau d'effort d'un stand, le libellé et la description d'une typologie, le nom et la position d'un emplacement, les données des personnes hors compétences (disponibilités, souhaits, déclarations, e-mail), la **création** d'un animateur avec ses compétences, les ajustements, les verrous et les **consignes**, l'outil prévu pour fermer une bande tard sans rien détruire. L'import de scénario, la restauration d'une copie avant vidage et la remise à zéro, qui remplacent tout, sont refusés dès qu'une famille est figée.

**La première publication fait passer l'édition en Publiée**, de Préparation comme de Calcul ; une publication suivante l'y laisse, et une édition terminée le reste. Les autres changements de phase sont des gestes explicites, retour en arrière compris (`PUT /api/editions/courant/phase`). En Publiée, la structure se **débloque** pour un changement de dernière minute, sans quitter la phase (`PUT /api/editions/courant/deblocage-structure`), et se refige par `DELETE` sur la même route ou, d'office, à la publication suivante. Ces gestes sont journalisés.

La duplication d'une édition repart en Préparation. Une phase ne touche pas un calcul en cours. Côté code, la garde est une annotation (`@RefusedWhileFrozen`) sur les méthodes de service, portée par `GelReferentielService`, qui lit la phase. Les routes de la 3.x (`PUT` et `DELETE /api/editions/courant/gel/{famille}`) restent jusqu'en 5.0 comme alias de la phase : figer une famille en Préparation fait passer en Calcul, lever en Calcul ramène en Préparation, et dans une édition publiée elles débloquent et refigent la structure. `GET /api/editions/courant/gel` dit l'état de chaque famille d'après la phase.

## Replanification incrémentale

Elle répond à « il est 9 h, untel se désiste, que fait-on ? ». Elle **repart du planning persisté**, épingle tout ce qui reste valable et ne rouvre que le reste, d'où un budget par défaut de 60 s (`SolveBudgetPolicy`). Elle se lance par `POST /api/solve/incremental/async` ou par l'outil MCP `lancer_correction`.

Le rapprochement est **positionnel**, sur stand × créneau, comme celui des verrous : les places d'un même stand sur un même créneau sont interchangeables, donc aucun identifiant de poste n'a besoin de survivre à un changement de référentiel.

| Situation d'une place | Ce qui est fait |
| --- | --- |
| Créneau déjà commencé | **Épinglée telle que travaillée**, avant toute autre règle : titulaire gardé même invalidé depuis, vide restée vide, jamais rouverte par le périmètre. Comptée à part (`postesPasses`). Voir [le passé figé](horaires-et-fenetres-des-stands.md#le-passé-est-figé) |
| Désignée par le périmètre demandé | Libérée quoi qu'il arrive : l'utilisateur dit « refais ça » |
| Titulaire encore valable | **Épinglée** |
| Titulaire invalidé par un changement tardif (supprimé, indisponible ce jour-là) | Libérée : l'épingler figerait un écart dur que plus personne ne pourrait corriger. Le test de validité réutilise le prédicat du solveur, pour que les deux ne divergent pas |
| Jamais pourvue, ou nouvelle | Laissée libre |

Deux choix à connaître :

- **le gel est volatil** : il vit le temps du job et n'écrit rien. Un verrou reste ce que l'utilisateur pose délibérément, jamais un artefact laissé par une replanification ;
- **tout ce qui est valable est épinglé**, y compris hors verrou explicite. Une replanification existe pour *stabiliser* le plan, pas pour le ré-optimiser. Rouvrir une zone est un acte explicite : la nommer dans le périmètre, ou lancer une résolution complète.

Le résultat porte les statistiques et le **diff des équipes** par stand × créneau, parce que replanifier partiellement n'a d'intérêt que si l'on peut dire qui est impacté. Une permutation entre places interchangeables d'un même stand n'y figure pas : le planning de personne n'a changé.

## Réamorçage d'une résolution complète

Une résolution complète **repart du plan persisté** quand il en existe un ([décision 0024](repo:docs/decisions/0024-repartir-du-plan-enregistre-par-defaut.md)). Chaque place reçoit l'animateur que le plan lui donnait, par le même rapprochement positionnel que les verrous et la replanification, et **reste mobile**. Rien n'est épinglé hormis les verrous explicites. C'est le contraire de la replanification incrémentale sur ce seul point : la replanification stabilise, le réamorçage ré-optimise sans jeter l'acquis.

Le paramètre `reamorcage` prend trois valeurs (`Reamorcage`) : `AUTO` (le défaut, repartir du plan s'il existe, de zéro sinon), `PLAN_COURANT` (échouer plutôt que démarrer à froid quand il n'y a pas de plan), `AUCUN` (départ à froid, le seul geste qui abandonne l'acquis et qui, pour cela, se confirme à l'écran).

| Place du plan persisté | Au réamorçage |
| --- | --- |
| Titulaire encore valable | Réamorcée avec lui, **mobile** |
| Couverte par un verrou | Laissée telle que les verrous l'ont mise (épinglée) |
| Titulaire disparu, ou indisponible ce jour-là | Repart vide, comptée « libérée » |
| Jamais pourvue, ou nouvelle | Libre |

Le solveur note la solution de départ en premier et garde la meilleure rencontrée : à données égales, le résultat ne descend jamais sous le plan de départ. Il peut descendre quand le référentiel a changé entre-temps (le plan de départ n'est plus une solution du nouveau problème) ou sur un départ à froid.

## Demandes d'échange

Un animateur propose un échange depuis son espace ; **rien n'est appliqué sans validation explicite de l'administration**. Les routes de l'espace sont sous `/api/espace-animateur/{jeton}/demandes` et celles de l'administration sous `/api/echanges`.

Le poste cédé est référencé par son couple **(créneau, stand)**, jamais par l'id du `poste_affectation` : ces ids sont renumérotés à chaque résolution, le couple survit.

**L'accord du collègue.** Une demande naît `EN_ATTENTE_CIBLE` (`StatutDemandeEchange`). Le collègue l'accepte, elle devient `PROPOSEE` et l'administration est notifiée, ou la décline (`REFUSEE_CIBLE`), ce qui est terminal. L'administration peut refuser une demande encore en attente, mais ne peut l'accepter qu'**après** l'accord du collègue (`ACCEPTEE`, `REFUSEE`).

**La simulation** juge sur le **score dur global**, pas sur les seuls postes touchés : un échange peut casser une contrainte ailleurs, heures hebdomadaires ou repos (`GET /api/echanges/{id}/impact`). L'échange est croisé si la cible tient aussi un poste sur le créneau, une reprise simple sinon. Un échange **dirigé** troque deux créneaux distincts (« je te laisse mon lundi, je prends ton mardi »).

**L'application** met à jour chirurgicalement les places concernées, **sans re-résolution**, puis pose deux verrous `ANIMATEUR_CRENEAU` sur le créneau que chacun **reçoit** : la régénération suivante ne défait pas l'échange, sans geler le reste du planning des deux animateurs.

**L'annonce attend la publication.** Trancher une demande ne mail personne sur le champ : l'échange a changé le planning de travail, pas celui que le demandeur a reçu, et son espace montre encore le précédent. La décision, acceptée ou refusée, voyage avec la publication qui la porte, aux côtés des demandes encore en attente.

**Foire fermée** : soumissions et annulations sont refusées côté serveur, et l'espace passe en consultation seule (planning visible et téléchargeable, historique conservé).

Le lien de l'espace et son authentification (jeton opaque, code envoyé par mail) sont décrits dans [les comptes et rôles](acces-et-comptes.md).

## Le plan publié

Deux plannings coexistent par édition, et il faut les nommer. Le **planning de travail** est celui que la résolution écrit et que l'administration manipule : `poste_affectation`, un seul par édition. Le **planning publié** est celui que les animateurs ont reçu : la dernière version d'origine `PUBLIEE` (table `version_plan`). C'est une version et rien d'autre, car son contenu dénormalisé ([décision 0007](repo:docs/decisions/0007-instantanes-contenu-denormalise.md)) est exactement « un planning complet, indépendant de ce qui bouge après ».

**L'espace animateur lit le planning publié.** Ce qu'une personne voit est ce qu'on lui a envoyé : un échange validé, un remplacement appliqué ou une nouvelle résolution ne déplacent son espace qu'une fois publiés. Tant que rien n'a été publié sur l'édition, l'espace est **vide** et le dit : replier sur le planning de travail recréerait l'incohérence que la distinction supprime.

**L'espace nomme son édition.** Le nom et les bornes de l'édition descendent jusqu'à la page (`editionNom`, `editionDebut`, `editionFin` de `GET /api/espace-animateur/{jeton}`), jusqu'à l'en-tête du PDF individuel et jusqu'au nom du calendrier d'abonnement (`X-WR-CALNAME`). Rien n'est stocké pour cela : le nom vient de l'`Edition`, les bornes se dérivent des créneaux, et une édition sans créneau porte son nom sans dates. Le cas qui l'a motivé : un animateur revenu d'une année sur l'autre a **deux** liens d'espace valides dans sa boîte mail et deux pages rigoureusement identiques, et se tromper de lien donnait un planning cohérent, mais pas celui de cette année.

**Publier n'écrit qu'aux personnes concernées** ([décision 0025](repo:docs/decisions/0025-stabilite-du-plan-publie.md), [décision 0011](repo:docs/decisions/0011-publier-plutot-qu-envoyer-a-tous.md)). L'unité de comparaison est la **vacation** (jour, heures effectives, stand) et l'identité comparée est celle de la personne, pas celle du siège : un `poste_affectation` renuméroté par une résolution ne déplace personne. Renommer un stand ne réveille donc personne, échanger deux vacations réveille exactement deux personnes. Un écart se dit en ajout, en retrait, ou en **déplacement** quand un ajout et un retrait se répondent le même jour : « Ninja 14h-18h remplace Cirque 14h-18h » est un changement, pas deux (`PublicationDiffService`).

**Une vacation supprimée se dit.** Le côté publié porte le jour et la fenêtre de chaque affectation, copiés à la capture : supprimer un créneau d'une journée déjà publiée ne retire le siège que du planning de travail, et l'écart qui reste est un retrait, avec un nom dessus. La vacation reste visible dans l'espace, le PDF, l'abonnement ICS et le rappel de la veille jusqu'à la publication qui annonce son retrait : ce qui l'a promise est aussi ce qui la reprend. Un instantané capturé avant que la date soit stockée ne porte pas cette information et se résout contre le référentiel du jour.

Ce calcul est volontairement **hors de la comparaison d'instantanés**, non nominative par choix (l'équité est une dispersion d'heures, jamais un classement de personnes nommées). Il répond à « votre emploi du temps a-t-il changé depuis ce qu'on vous a envoyé ? », une personne à la fois, et seulement pour celles à qui il va écrire. Les routes de publication sont décrites dans [api-planning-et-publication](api-planning-et-publication.md).
