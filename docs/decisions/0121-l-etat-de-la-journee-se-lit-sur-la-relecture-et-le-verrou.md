# 0121 — L'état de la journée se lit sur la relecture et le verrou de journée

- **Statut** : accepté, implémenté (côté serveur ; l'écran Journée et les outils MCP de l'état suivent)
- **Date** : novembre 2026
- **Portée** : `domain/DayState`, `service/validation/ValidationJourneeService`, `ValidationPrerequisService`, API (`/api/etats-journee`), routes et outils de validation gardés en alias, guide de migration 4.0
- **Prolonge** : [0039](0039-validation-de-relecture-distincte-du-verrou.md) (la relecture ne pose jamais de verrou sans qu'on le demande), [0069](0069-une-relecture-survit-a-ce-que-le-calcul-n-a-pas-recalcule.md) (ce qu'un calcul fait d'une relecture), inchangées sur ces deux points
- **Applique** : [0091](0091-refonte-fonctionnelle-4-0.md), point 3 (trois mécanismes de gel, dont l'état de la journée) ; voisine de [0109](0109-trois-types-de-verrou.md) (le verrou de journée) et de [0115](0115-la-phase-de-l-edition-remplace-le-gel.md) (la phase)

## Contexte

La 3.x avait deux objets pour une même journée : une relecture (« relu et
accepté », table `validation_journee`) et, à côté, un verrou de journée
(`verrouillage_planning`, type `JOUR`). 0039 les a voulus distincts, et la
raison tient toujours : relire n'est pas figer, et figer chaque journée relue
empêche de recalculer le reste. Mais l'organisateur se demande une seule chose
d'une journée : *où en est-elle ?* Deux objets indépendants donnaient quatre
combinaisons à lire là où il n'y a que trois réponses : à relire, relue,
figée. Et une journée figée depuis l'écran des verrous restait « à relire »
pour la bannière de relecture, alors qu'aucun calcul ne pouvait plus la
changer.

## Options envisagées

**(A) Une table d'état neuve (`etat_journee`), le verrou `JOUR` y déménage.**
Une ligne par journée, trois valeurs, et le type `JOUR` retiré des verrous.
Écarté : le verrou de journée est lu par la construction du problème (les
places épinglées), par la section `plan:` d'un fichier et la copie avant
vidage, par l'écran Verrouillages, par la levée de consigne (0089), par les
exports SQL et la duplication. Le déménager touchait tout cela pour ne rien
figer de plus, avec un risque sur les scores qu'aucun gain ne paie.

**(B) Une colonne d'état sur la relecture.** Écarté : `FIGEE` y dirait ce que
le verrou dit déjà, et deux vérités divergent au premier verrou posé ou retiré
depuis l'écran des verrous.

**(C) Un état dérivé des deux lignes existantes.** Retenu.

## Décision

**D1 — L'état d'une journée n'est pas stocké : il se lit.** `DayState.of(relue,
verrouillée)` :

| Relecture | Verrou `JOUR` | État |
| --- | --- | --- |
| non | non | à relire (`A_RELIRE`) |
| oui | non | relue (`RELUE`) |
| non ou oui | oui | figée (`FIGEE`) |

**Figée, c'est le verrou de journée lui-même** : le même mécanisme, pas un
drapeau qui le double. Le solveur, le fichier et l'écran des verrous lisent
donc une journée figée comme avant, et aucun score ne peut bouger. La
relecture d'une journée figée garde sa date, son auteur et son commentaire ;
une journée figée depuis l'écran des verrous n'en a pas.

**D2 — Aucune migration, aucun maillon de fichier.** Les trois conversions que
la 4.0 doit aux journées de la 3.x (relue ⇒ relue, verrou de journée ⇒ figée,
les deux ⇒ figée) sont exactement ce que D1 lit sur les lignes en place.
Aucune ligne n'est réécrite, aucun numéro de migration n'est pris. De même
pour le YAML : la section `plan:` porte les verrous, donc les journées figées,
et n'a jamais porté les relectures (une relecture décrit un plan que le
fichier ne remet pas en place) ; la copie avant vidage d'une 3.x, lue par
`readPlan`, aussi. Il n'y a rien à convertir à la lecture.

**D3 — Un seul geste d'écriture.** `PUT /api/etats-journee/{jour}` avec
`A_RELIRE`, `RELUE` ou `FIGEE` (et le commentaire de la relecture) :

- `FIGEE` enregistre la relecture et pose le verrou de journée s'il manque ;
- `RELUE` enregistre la relecture et retire le verrou de journée ;
- `A_RELIRE` retire les deux.

**On ne pose jamais de verrou sans qu'on l'ait demandé** (0039) : seul `FIGEE`
en pose un, et un test le dit pour chaque autre geste (relire, l'alias sans
case cochée, retirer une relecture, l'atterrissage d'un calcul). Retirer un
verrou n'arrive aussi que sur demande : quitter l'état figé. Retirer le verrou
depuis l'écran des verrous laisse la journée relue si elle avait une
relecture, à relire sinon.

**D4 — 0069 reste la règle du calcul.** Une relecture survit à un calcul quand
sa journée n'a pas été recalculée : passé figé, journée figée, ou journée
rendue à l'identique par un calcul parti du plan en place. Une journée figée
n'est jamais recalculée, avec ou sans relecture : le récapitulatif la compte
parmi les journées gardées « verrouillées », et la confirmation de
« Recommencer de zéro » la nomme.

**D5 — L'avancement compte ce qui n'est plus à relire.** La bannière, le
panneau de publication, l'état de l'édition et l'archive comptent les
journées relues ou figées. Une journée figée sans relecture compte donc
désormais : c'est le seul changement visible à la mise à jour.

**D6 — Les routes et outils de validation restent, en alias, jusqu'en 5.0.**
`GET`, `POST /api/validations`, `DELETE /api/validations/{id}` (dépréciés) et
les outils `lister_validations_journee`, `ajouter_validation_journee`,
`retirer_validation_journee` font ce qu'ils faisaient : ils écrivent la même
relecture et le même verrou, donc l'état suit. Ils ne retirent jamais un
verrou : retirer la relecture d'une journée figée la laisse figée, comme en
3.x. Les prérequis, l'avancement et les relectures gardées à froid restent
sous `/api/validations` ; le panneau des prérequis porte en plus l'état.

**D7 — L'état est indépendant du reste.**

- **Phase** (0115) : poser ou retirer un état est permis dans toutes les
  phases, comme un verrou. Une journée figée le reste quand l'édition passe en
  Calcul, en Publiée ou en Terminée, et la phase ne fige ni ne défige aucune
  journée. Les deux répondent à deux questions : la phase dit ce qu'on peut
  encore modifier du référentiel, l'état de la journée ce qu'un calcul peut
  encore déplacer dans le plan.
- **Édition active** (0072) : l'état ne sort pas de l'application ; une
  édition inactive a ses états comme les autres.
- **Calcul en cours** : comme le verrou et la relecture, changer l'état est
  accepté pendant une résolution. L'atterrissage n'écrit ni relecture ni
  verrou ; il retire, à son arrivée, les relectures des journées qu'il a
  recalculées. Limite connue, héritée du verrou de la 3.x : une journée
  figée pendant une résolution n'est pas vue par celle-ci, qui peut encore
  y déplacer des places à son arrivée ; la journée reste figée sur ce
  nouveau plan.
- **Lecteur** : lit les états (`GET`), ne les change pas, comme toute écriture.
- **Journal** : le changement d'état est l'action `JOURNEE_ETAT_CHANGE`,
  rangée parmi celles qui changent les données d'un calcul (elle peut poser ou
  retirer un verrou), la journée pour cible.

## Conséquences

- Une consigne retire toujours les relectures des journées qu'elle touche ;
  une journée figée le reste, puisque le verrou demeure (0089).
- La relecture et le verrou restent deux lignes, écrites l'une après l'autre :
  un échec entre les deux laisse une relecture sans verrou, que le même geste
  répété complète.
- L'écran Journée et les outils MCP de l'état viennent ensuite ; jusque-là
  l'écran garde ses gestes de 3.x, qui passent par les alias.
