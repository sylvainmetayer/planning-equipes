# Relire et retoucher le planning

Vous relisez le planning journée par journée, vous corrigez un siège à la main sans casser une règle, et vous marquez ce qui est relu.

Le calcul est une proposition : vous relisez, retouchez et décidez. Toutes les retouches passent par la page *Planning*.

## Corriger un siège avec le panneau Siège

Un clic sur n'importe quelle case de la page Planning ouvre à côté le **panneau de ce siège** : un nom ou un siège libre du tableau, une vacation du rail, un stand de la carte, une pause sans relais, une ligne des changements. Le panneau montre le stand, le créneau, la personne, les verrous et, en phrases, les règles en défaut.

### Sur un siège tenu

- *Remplacer* par un remplaçant viable ;
- *Déplacer vers…* un autre siège ;
- *Libérer*, en tenant la personne à l'écart de ce créneau au prochain calcul (case cochée par défaut) ;
- verrouiller ou déverrouiller, voir qui pourrait le tenir, ouvrir la fiche de la personne.

Remplacer et libérer ne touchent le siège que s'il est encore tenu par la personne affichée.

### Sur un siège vide

1. « Qui peut tenir ce siège ? » dit qui n'est de service nulle part, et quelle règle empêcherait chacun de tenir la place.
2. « Placer » affecte la personne et la garde au prochain calcul. L'action est refusée pour une personne qu'un verrou tient hors de ce créneau, et sur un créneau déjà commencé.
3. « Poser un ajustement » ouvre le formulaire prérempli.

### Ce que l'application vérifie pour vous

Un placement posé directement (depuis le panneau, l'écran de réparation ou un assistant) est **simulé avant d'écrire**. Il est refusé si le geste casse une règle impérative, en la nommant ; s'il coûte en qualité, l'application le dit. *Libérer* passe toujours : marquer une absence le jour J ne doit jamais être bloqué par le plan qu'on répare.

### Après le geste

Le panneau propose de prévenir les personnes concernées (le changement n'est pas publié) et, si une place est restée vide, de corriger le reste par un recalcul partiel.

## Déplacer ou échanger depuis le tableau

Dans la vue *Tableau*, chaque nom et chaque siège vide ouvrent le panneau du siège. « Déplacer vers… » déplace sur un siège libre, ou échange avec une personne ; l'action est refusée si une règle impérative serait cassée. Le *Rail* offre le même geste depuis une vacation, pour la confier à une autre personne ou l'échanger avec la sienne.

## Marquer une journée « relue et acceptée »

1. Sur la page Planning, repérez le compteur de ce qu'il reste à regarder (sièges vides et pauses sans relais) : il mène aux problèmes du Diagnostic.
2. Dans le menu de la journée, choisissez « Relu et accepté… ». Les prérequis de ce jour s'affichent : écarts durs, sièges vides, pauses sans relais, postes irremplaçables.
3. Acceptez, éventuellement quand même : l'application le dit alors.

L'avancement se lit dans le Solveur (« 3 journées sur 12 ») et par une coche sur chaque journée relue du mini-mois de la page Planning. La validation porte toujours sur la journée entière, quel que soit le filtre affiché.

Accepter ne fige rien : le verrouillage est proposé à côté, jamais imposé (voir [Poser des exceptions et des verrous](ajustements-et-verrous.md)).

### Les trois états d'une journée

Chaque journée est **à relire**, **relue** ou **figée**.

| État | Ce que cela veut dire | Ce qu'un nouveau calcul en fait |
| --- | --- | --- |
| À relire | Personne ne l'a acceptée | Il la recalcule librement |
| Relue | Quelqu'un l'a acceptée, rien n'est figé | Il peut la recalculer, et elle redevient alors à relire |
| Figée | Elle porte un verrouillage de journée entière | Il n'y touche plus |

Seul votre geste fige une journée : l'accepter ne la fige jamais. Retirer le verrouillage de journée entière la rend relue si elle avait été acceptée, à relire sinon. Une journée figée compte comme relue dans l'avancement, même verrouillée depuis l'écran des verrous sans avoir été acceptée. La phase de l'édition ne change pas l'état des journées : vous pouvez relire ou figer une journée en préparation, en calcul comme après publication.

### Quand un nouveau calcul retire une relecture

Une relecture ne survit à une résolution que si sa journée n'a pas été recalculée.

- Une journée déjà entièrement travaillée, ou qui porte un verrouillage de journée entière, garde la sienne.
- Une résolution partie du plan en place retire la relecture des journées où elle a déplacé un siège, ou retiré une place relue.
- « Recommencer de zéro » retire celle de toute journée recalculée, même retombée sur le même équipage.

Le récapitulatif dit combien de relectures sont retirées et combien sont gardées, avec leur motif (déjà travaillées, verrouillées, inchangées). La confirmation de « Recommencer de zéro » liste d'avance celles qui survivront, et le panneau de publication dit combien de journées non relues partiraient.

## Revenir en arrière

Une version du plan est prise automatiquement avant chaque résolution, et vous pouvez en enregistrer d'autres : voir [Calculer le planning](calculer-planning.md#garder-des-versions-du-plan).
