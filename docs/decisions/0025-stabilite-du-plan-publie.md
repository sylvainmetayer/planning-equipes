# 0025 — La stabilité après publication est une règle dosée, pas un gel

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : solveur, résultat des calculs, IHM

## Contexte

Le cas d'usage du produit est de préparer le planning en amont, puis
d'absorber les changements tardifs — un stand ajouté trois semaines avant,
une personne malade, une démission — **sans changement total la veille**.
Après [0024](0024-repartir-du-plan-enregistre-par-defaut.md), un calcul
repart du plan enregistré, mais rien ne dit au solveur que déplacer une
personne déjà prévenue a un coût : il remanie volontiers des dizaines de
sièges pour quelques points d'équité. Les deux autres mécanismes sont du
tout ou rien — la replanification incrémentale fige tout ce qui tient et ne
remplit que les trous, les verrouillages figent ce qu'on nomme. Il manquait
le milieu.

## Options envisagées

**(A) Élargir la replanification incrémentale** à un « rouvrir le minimum
nécessaire ». Écarté : trouver quoi rouvrir est exactement le travail du
solveur, refait à la main dans un service ; et un périmètre rouvert redevient
un départ à froid sur ce périmètre.

**(B) Verrouiller automatiquement les sièges publiés.** Écarté : un verrou
est une décision de l'opérateur, et un plan intégralement verrouillé rend un
stand ajouté trois semaines avant impossible à pourvoir sans tout déverrouiller.

**(C) Une règle de niveau medium** qui coûte un point par personne déplacée
d'un siège qu'elle tenait dans le plan publié, dosée contre les autres règles
de qualité. Le solveur arbitre lui-même entre le dérangement et le gain, au
poids près.

## Décision

(C). Sept règles :

1. **La référence est le plan publié seulement** — ce que les gens ont reçu.
   Avant la première publication, la règle est muette et le solveur reste
   libre ; c'est en amont qu'on optimise.
2. **Un point par siège dont le titulaire n'est pas celui du plan publié**
   sur le même stand et le même créneau — vide compris : le titulaire a été
   prévenu qu'il y travaillait, le trou est le sien. Un stand ou un créneau
   créé depuis est libre ; un siège ajouté sur une ligne publiée compte son
   nouvel occupant. (La première version ne comptait pas le siège vide, « déjà
   une violation dure » ; le banc a tranché, voir l'annexe.)
3. **Les faits sont chargés côté serveur** avant chaque résolution et chaque
   diagnostic (`prepareProblem`), jamais envoyés par un client : un planning
   posté ne décide pas de ce que les gens ont reçu.
4. **Medium, jamais plus** : une règle dure ou une indisponibilité l'emporte
   toujours ; un test le vérifie (`publishedSeatsNeverOutweighAHardRule`).
5. **Active par défaut, dosable et désactivable** sur l'écran Contraintes
   comme les autres règles de « Qualité d'organisation ». Le poids par défaut
   sort d'un banc, pas d'une intuition (annexe).
6. **Le compte est montré avant la publication** : chaque résultat porte
   `impactPublication.personnes`, calculé comme la publication le calcule ;
   la confirmation de « Recommencer de zéro » cite le plan publié.
7. **Ce n'est pas un gel.** Pour figer, il y a les verrouillages et
   « Corriger après un changement » ; cette règle arbitre.

## Conséquences

- Une liste de faits de plus sur la solution (`affectationsPubliees`), une
  contrainte de plus au catalogue (treize règles dosables).
- Le score medium d'une édition publiée n'est plus comparable, tel quel, à
  celui d'avant publication : il porte un terme de plus. Le comparateur A/B
  compare des instantanés du même régime.
- Le poids se règle par édition ; le défaut ci-dessous vaut pour la fixture
  anonymisée et se rediscute sur le réel.

## Annexe — banc de comparaison

Fixture anonymisée `festival-realiste` (153 animateurs, 65 stands, 354 créneaux sur 5 familles de relais, 3 503 sièges), 600 s par résolution, réamorçage depuis le plan publié (`reamorcage=AUTO`). Base à froid, 600 s : `0hard/-6975medium/-881soft`, publiée telle quelle.

| Changement après publication | Règle | Score | Personnes qui changeraient |
|---|---|---|---|
| Trois personnes absentes les deux premiers jours | inactive | `0hard/-6576medium/-938soft` | 149 / 153 |
| Trois personnes absentes les deux premiers jours | poids 1 | `0hard/-6902medium/-907soft` | 74 / 153 |
| Trois personnes absentes les deux premiers jours | poids 5 | `0hard/-7020medium/-891soft` | 10 / 153 |
| Stand ajouté (2 sièges, 10 h-18 h, 16 jours, id classé dernier) | inactive | `0hard/-6865medium/-1002soft` | 153 / 153 |
| Stand ajouté (2 sièges, 10 h-18 h, 16 jours, id classé dernier) | poids 1 | `-2hard/-7297medium/-990soft` | 143 / 153 |
| Stand ajouté (2 sièges, 10 h-18 h, 16 jours, id classé dernier) | poids 5 | `-5hard/-7496medium/-888soft` | 66 / 153 |
| Les deux à la fois | inactive | `0hard/-6889medium/-1006soft` | 153 / 153 |
| Les deux à la fois | poids 1 | `-3hard/-7308medium/-984soft` | 135 / 153 |
| Les deux à la fois | poids 5 | `-5hard/-7533medium/-896soft` | 67 / 153 |
| Stand ajouté, id classé **premier** (familles de relais décalées) | inactive | `0hard/-7306medium/-1006soft` | 153 / 153 |
| Stand ajouté, id classé **premier** (familles de relais décalées) | poids 1 | `0hard/-7306medium/-1007soft` | 153 / 153 |
| Stand ajouté, id classé **premier** (familles de relais décalées) | poids 5 | `0hard/-7222medium/-1005soft` | 153 / 153 |

**Lecture.** Le réamorçage seul ne stabilise rien : règle inactive, un
calcul de 600 s repart du plan publié et finit par déplacer tout le monde
(149 à 153 personnes sur 153), parce que rien ne lui dit qu'un siège tenu a
de la valeur. La règle fait ce qu'on lui demande dès le poids 1 et
nettement au poids 5 : trois absences se règlent en touchant 10 personnes
au lieu de 149, pour 444 points medium concédés ailleurs (6 % du score
medium, équité et rotation comprises).

**Le poids retenu : 5.** Le poids 1 laisse encore 74 personnes bouger pour
trois absences ; 5 donne l'ordre de grandeur attendu (« quelques personnes
prévenues, pas tout le monde »). Il se règle par édition sur l'écran
Contraintes.

**La limite mesurée : de la demande en plus, aux heures pleines.** Quand le
changement ajoute des sièges (un stand de deux sièges de 10 h à 18 h tous
les jours, une centaine de sièges), la règle laisse des écarts durs au bout
de 600 s — 2 à 5 selon le poids — là où le calcul sans la règle finit à 0.
La raison est mécanique : pourvoir un siège neuf aux heures pleines demande
une chaîne de déplacements dont chaque maillon est neutre en dur et coûte
un point medium ; sans la règle la chaîne est gratuite, avec elle la
recherche locale ne la franchit pas dans le budget. Le poids n'y change pas
grand-chose (le poids 1 bloque aussi), c'est l'existence du coût qui compte.

**Ce que sont ces écarts, et quoi faire.** Rejoué au poids 5 avec le détail :
les 5 écarts sont 5 sièges non pourvus (`posteDoitEtrePourvu`) sur des stands
**existants** (Stand 06 le 08/09, Stand 08 le 11/09…), pas sur le stand
neuf ; la règle n'a lâché que 7 sièges publiés (35 points), et le stand neuf
a été pourvu sur le temps libre des gens — 63 personnes gagnent une vacation,
c'est ce que « 63 personnes changeraient » compte. Un second « Calculer le
planning » depuis ce plan, 600 s de plus : un seul trou de comblé (−4 dur, 10 sièges publiés lâchés), la recherche locale reste devant le même mur. Les recours dans l'ordre :
« Corriger après un changement » (ne remplit que les trous, ne bouge
personne), un second calcul, et enfin désactiver la règle pour ce calcul en
acceptant de prévenir tout le monde.

**Deuxième passe : le siège publié laissé vide compte aussi.** Rejoué au
poids 5 avec cette symétrie (même fixture, base à froid `0hard/-6810medium`
puis publiée) :

| Changement | Avant (vide gratuit) | Après (vide = 1 point) |
|---|---|---|
| Trois absents | 10 personnes, 0 dur | 10 personnes, 0 dur, 9 sièges lâchés |
| Stand ajouté | 66 personnes, 5 sièges non pourvus | 65 personnes, 4 sièges non pourvus |
| Les deux | 67 personnes, 5 sièges non pourvus | 67 personnes, 4 sièges non pourvus |

Jamais pire, un peu mieux sur les deux cas durs, et plus juste métier :
adoptée. Mais les trous restent sur des lignes publiées (les 4 écarts de
stabilité du cas « stand » sont les 4 sièges vides) : la symétrie ne
déplace pas le mur, elle l'expose.

**Troisième passe : franchir le mur d'un seul mouvement.** Le mur est une
chaîne — A quitte son siège pour le trou, B, libre à cette heure, reprend
celui de A — dont chaque maillon est neutre en dur et coûte medium. Plutôt
qu'un mouvement composite maison, la phase de faisabilité reçoit un
sélecteur **ruin and recreate** de Timefold (édition Community), restreint
par `HoleNeighbourPosteFilter` aux sièges dont le créneau chevauche celui
d'un siège vide : 3 à 10 sièges de cette heure-là sont défaits et
reconstruits par l'heuristique de construction, la chaîne est évaluée
comme un seul mouvement. Même protocole, poids 5, symétrie comprise :

| Changement | Sans ruin and recreate | Avec |
|---|---|---|
| Stand ajouté | 65 personnes, 4 sièges non pourvus | **0 dur** en 158 s, 77 personnes, 10 sièges lâchés |
| Les deux | 67 personnes, 4 sièges non pourvus | **0 dur**, 77 personnes, 18 sièges lâchés |
| Trois absents | 10 personnes, 0 dur | identique : rien à défaire sans trou |

La faisabilité revient, au prix d'une dizaine de personnes de plus que le
plan à trous — celles de la chaîne — et toujours à moitié de ce que fait la
règle inactive (153). Retenu : c'est exactement l'arbitrage demandé, un
planning tenable qui dérange le moins possible.

**Ce que la revue a corrigé.** La première version du filtre balayait tout
le plan à chaque appel, et se croyait inoffensive parce qu'« il n'y a plus
de trou quand la phase s'arrête ». C'est faux : la phase 1 s'arrête sur la
**faisabilité**, et la plupart des règles dures se cassent par excès
d'affectation — « tous les sièges pourvus, toujours infaisable » est un
état ordinaire, celui d'un réamorçage. Dans cet état le filtre n'acceptait
rien, Timefold tentait `nombre de postes × 10` tirages avant d'abandonner,
et 80 % d'un budget de 60 s passait à prouver qu'il n'y avait rien à
défaire (mesuré sur 2 112 sièges : 48 s sur 60, et 18 % de medium en moins
qu'avec le sélecteur retiré). Le filtre mémorise donc les heures des trous,
rafraîchies au plus une fois par balayage complet :

| trous dans le plan | coût par appel avant | après |
|---|---|---|
| aucun | 40 700 ns | 69 ns |
| un | 8 080 ns | 154 ns |
| vingt | 253 ns | 213 ns |

Deux limites notées au passage : la reconstruction interne au ruin
n'applique pas `EligibleAnimateurMoveFilter` (Timefold 2.5 n'expose pas le
réglage), ce qui explique la chute du débit de mouvements ; et le
chevauchement se compare désormais en instants absolus, donc un créneau qui
franchit minuit voisine bien avec le matin suivant.

**Ce que le banc ne dit pas.** Une mesure par case, sur une fixture
anonymisée : les ordres de grandeur sont fiables, pas les unités. Le réel
(edition-1708) a un pic 14 h-19 h plus tendu ; à
recalibrer là-bas sur un cas réel, le poids par édition est fait pour ça.
