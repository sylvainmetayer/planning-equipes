# Relire et retoucher le planning

Vous relisez le planning journée par journée, vous corrigez un siège à la main sans casser une règle, et vous dites où en est chaque journée.

Le calcul est une proposition : vous relisez, retouchez et décidez. Toutes les retouches passent par la page *Planning*.

## Corriger un siège avec le panneau Siège

Un clic sur n'importe quelle case de la page Planning ouvre à côté le **panneau de ce siège** : un nom ou un siège libre du tableau, une vacation du rail, un stand de la carte. Le panneau montre le stand, le créneau, la personne et les verrous, puis ses deux gestes.

### Simuler un changement

« Simuler un changement » essaie un changement sans rien écrire.

1. Le panneau propose les meilleurs remplaçants (ou, sur un siège vide, les meilleures personnes à placer), avec leur effet sur le score. Sur un siège tenu, « Vers un autre siège… » choisit plutôt un siège où déplacer la personne : un siège libre, ou celui d'une personne avec qui échanger.
2. « Simuler » sur l'un d'eux montre **l'avant et l'après** : le score avant et après, ce que le changement règle sur ce siège et ce qu'il y abîme. Un changement qui casserait une règle impérative est annoncé « Refusé », en la nommant, et ne s'applique pas.
3. « Appliquer » écrit exactement ce changement. Sur un siège vide, la case « La garder au prochain calcul » (cochée par défaut) le protège aussi du prochain calcul.

« Voir tout le banc » montre tout le monde : qui n'est de service nulle part, et quelle règle empêcherait chacun de tenir la place. Sur un siège vide, « Placer » y affecte directement une personne.

### Expliquer

Sur un siège tenu, « Expliquer » dit pourquoi cette personne est là : les règles en défaut sur ce siège, en phrases, lues avec les réglages de l'édition.

### Les autres gestes d'un siège tenu

- *Libérer*, en tenant la personne à l'écart de ce créneau au prochain calcul (case cochée par défaut) ;
- *Marquer absent* sur ce créneau ;
- verrouiller ou déverrouiller, ouvrir la fiche de la personne.

Appliquer et libérer ne touchent le siège que s'il est encore tenu par la personne affichée.

### Ce que l'application vérifie pour vous

Un changement appliqué (depuis le panneau, l'écran Aujourd'hui ou un assistant) est **simulé à nouveau avant d'écrire**. Il est refusé si le geste casse une règle impérative, en la nommant, sur un créneau déjà commencé, sur un siège verrouillé et pendant un calcul ; s'il coûte en qualité, l'application le dit. *Libérer* passe toujours : marquer une absence le jour J ne doit jamais être bloqué par le plan qu'on répare.

Un lecteur simule et fait expliquer comme tout le monde, mais n'a pas de bouton pour appliquer.

### Après le geste

Le panneau propose de prévenir les personnes concernées (le changement n'est pas publié) et, si une place est restée vide, de corriger le reste par un recalcul partiel.

## Déplacer ou échanger depuis le tableau

Dans la vue *Tableau*, chaque nom et chaque siège vide ouvrent le panneau du siège. « Simuler un changement », puis « Vers un autre siège… », déplace sur un siège libre ou échange avec une personne, après avoir montré l'avant et l'après ; l'action est refusée si une règle impérative serait cassée. Le *Rail* offre le même geste depuis une vacation, pour la confier à une autre personne ou l'échanger avec la sienne.

## Changer l'état d'une journée

1. Sur la page Planning, repérez le compteur de ce qu'il reste à regarder (sièges vides et pauses sans relais) : il mène aux problèmes du Diagnostic.
2. Cliquez sur le bouton d'état de la journée (« À relire », « Relue le… » ou « Figée »). Un contrôle à trois valeurs s'ouvre, avec les prérequis de ce jour : écarts durs, sièges vides, pauses sans relais, postes irremplaçables.
3. Choisissez « Relue », éventuellement quand même, avec un commentaire, puis « Enregistrer l'état ». Choisir « Figée » demande une confirmation : le calcul ne touchera plus à cette journée, et le commentaire de relecture est gardé.

Un lecteur voit l'état de la journée, sans bouton pour le changer.

L'avancement se lit dans le Solveur (« 3 journées sur 12 ») et par une coche sur chaque journée relue du mini-mois de la page Planning. L'état porte toujours sur la journée entière, quel que soit le filtre affiché.

Relire ne fige rien : seule la valeur « Figée » pose le verrouillage de journée (voir [Poser des exceptions et des verrous](ajustements-et-verrous.md)).

### Les trois états d'une journée

Chaque journée est **à relire**, **relue** ou **figée**.

| État | Ce que cela veut dire | Ce qu'un nouveau calcul en fait |
| --- | --- | --- |
| À relire | Personne ne l'a relue | Il la recalcule librement |
| Relue | Quelqu'un l'a relue, rien n'est figé | Il peut la recalculer, et elle redevient alors à relire |
| Figée | Elle porte un verrouillage de journée entière | Il n'y touche plus |

Seul votre geste fige une journée : la relire ne la fige jamais. Retirer le verrouillage de journée entière la rend relue si elle avait été relue, à relire sinon. Une journée figée compte comme relue dans l'avancement, même verrouillée depuis l'écran des verrous sans avoir été relue. La phase de l'édition ne change pas l'état des journées : vous pouvez relire ou figer une journée en préparation, en calcul comme après publication.

### Quand un nouveau calcul retire une relecture

Une relecture ne survit à une résolution que si sa journée n'a pas été recalculée.

- Une journée déjà entièrement travaillée, ou qui porte un verrouillage de journée entière, garde la sienne.
- Une résolution partie du plan en place retire la relecture des journées où elle a déplacé un siège, ou retiré une place relue.
- « Recommencer de zéro » retire celle de toute journée recalculée, même retombée sur le même équipage.

Le récapitulatif dit combien de relectures sont retirées et combien sont gardées, avec leur motif (déjà travaillées, verrouillées, inchangées). La confirmation de « Recommencer de zéro » liste d'avance celles qui survivront, et le panneau de publication dit combien de journées non relues partiraient.

## Revenir en arrière

Une version du plan est prise automatiquement avant chaque résolution, et vous pouvez en enregistrer d'autres : voir [Calculer le planning](calculer-planning.md#garder-des-versions-du-plan).
