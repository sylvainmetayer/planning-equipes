# Mémoire du projet

Ce dépôt s'ouvre sur un commit unique. Les six cents commits qui l'ont
précédé — développement privé de juillet et août 2026 — ne le suivent pas :
leurs messages citaient un client, des jeux de données réels et un backlog
qui reste fermé.

Ce document retient ce que cet historique portait et que le code ne dit pas.

Il ne remplace rien. [`decisions/`](decisions/README.md) porte les décisions
d'architecture, une par fichier ; [`docs/`](README.md) décrit ce que le système
fait ; [`AGENTS.md`](../AGENTS.md) porte les conventions. **Ce qui suit est le
reste** : les mesures qui ont tranché un réglage, les pistes abandonnées, et
l'incident précis derrière chaque garde-fou. C'est-à-dire ce qu'on ne
retrouverait pas en relisant l'arbre, et ce qu'on ré-instruirait de zéro faute
de l'avoir écrit.

Une règle a guidé sa rédaction : **ne rien affirmer qui ne soit reproductible
sur une fixture versionnée**. Les mesures citées portent sur les scénarios de
`src/main/resources/scenarios/`, `festival-realiste.yaml` en tête (153
animateurs, 65 stands dont 45 premium, ≈3 500 sièges après découpage) — la
copie anonymisée du jeu qui a servi de banc pendant tout le développement.

---

## 1. Six temps

**Juillet — l'amorçage.** Squelette Quarkus + Timefold + Angular, montée en
Java 25, migration du frontend vers Angular puis vers les signaux. L'IHM passe
en français, le code reste en anglais : la règle est posée là et ne bougera
plus, mais rien ne la relit encore — elle dérivera pendant six semaines.

**Première semaine d'août — le droit du travail.** Un audit de conformité RH
cartographie les contraintes existantes contre le Code du travail et conclut à
une dizaine de manquements. La vague de correctifs qui suit fait passer le référentiel de
« quelques règles de bon sens » à un catalogue qui cite ses articles. C'est le
moment où le projet cesse d'être un solveur pour devenir un outil dont la
sortie engage quelqu'un.

**Deuxième semaine — le découpage.** Les journées d'ouverture cessent d'être
saisies à la main : `VacationGeneratorService` découpe les amplitudes en
vacations, et les pauses deviennent les trous entre elles. C'est aussi la
semaine où un déficit de couverture tenace se révèle n'être ni un manque
d'animateurs ni un manque de budget de résolution, mais **un défaut d'une ligne
dans la génération de la grille** (§2.d).

**Troisième semaine — le passage à l'échelle.** Cloisonnement par édition,
serveur MCP, espace animateur, foire au planning, verrouillages, instantanés.
Le produit gagne ses utilisateurs non-administrateurs, donc ses questions
d'authentification, de RGPD et de mobile.

**Quatrième semaine — le nettoyage.** Découpage des classes de 1 000 à 1 600
lignes, remédiation du franglais, campagne de tests vérifiés par mutation, puis
la marque blanche et la préparation de l'ouverture. Une bonne moitié des
garde-fous mécaniques du §5 naît ici, chacun d'un défaut réellement rencontré
pendant la semaine.

**Fin août — l'exploitation.** Planning publié distinct du planning de travail,
mode jour J, sauvegarde de nuit, migration Timefold 2.5. Le vocabulaire cesse
de parler de festival pour parler d'événement.

---

## 2. Le solveur — ce que chaque réglage a coûté à trouver

Le solveur est déterministe à budget donné (`randomSeed` épinglé) : deux runs
au même budget rendent le même score. Toutes les comparaisons ci-dessous sont
donc propres, et aucune ne couvre une variance entre exécutions. En revanche
**le déterminisme ne protège pas d'une variance entre versions du modèle** :
retirer une contrainte change la trajectoire de recherche, et c'est ce qui a
imposé plus d'une fois de remesurer un budget qu'on croyait acquis.

### a. Le plateau sur les sièges non pourvus

Les mouvements de recherche locale étaient échantillonnés uniformément sur des
milliers de sièges : la poignée encore vide à un instant donné était tirée trop
rarement pour être comblée. Le solveur plafonnait à quelques sièges vides
malgré un budget complet, sur un problème que l'analyse de faisabilité
déclarait soluble.

Correctif : `UnassignedPosteFilter` et une seconde paire de sélecteurs
change/swap **restreints aux sièges non pourvus**, à part égale de tentatives.
Ces deux sélecteurs ne sont pas repris en phase 2 (§2.e) : à ce stade il n'y en
a plus.

### b. La passe de performance : filtrer avant d'apparier

Les flux de contraintes construisaient de grands nombres de paires avant de
consulter les faits qui les auraient écartées. Quatre réécritures, toutes de la
même forme — restreindre le côté gauche d'abord, rejoindre par un joiner
indexé plutôt que par un filtre :

| Contrainte | Avant | Après |
| --- | --- | --- |
| `incompatibiliteAdHoc` | ~59 500 tuples par solve, même sans aucune incompatibilité déclarée | pilotée depuis la contrainte ad hoc |
| `eviterRoulementStandsPremium` | ~115 600 paires, 100 % de déchet sans stand premium | filtre premium avant appariement |
| `reposQuotidienMineur`, `eviterChangementEmplacementEloigne` | appariement de tous les postes d'un animateur | côté gauche restreint, joiner indexé |

Résultat sur `scenario-complet` : heuristique de construction 4 848 → 2 086 ms,
recherche locale 22 041 → 7 313 ms. **Le nombre de pas et tous les scores
intermédiaires sont identiques** — seul le coût par mouvement a baissé, ce qui
est la preuve que rien n'a changé de sens.

La même passe a révélé deux défauts de correction que la performance seule
n'aurait jamais montrés : une contrainte inscrite au catalogue mais jamais
enveloppée par le mécanisme d'activation (son interrupteur d'IHM ne faisait
rien), et une analyse de faisabilité qui surestimait le besoin de 31 % en
sommant `effectifMax` là où les sièges se génèrent sur `effectifMin`.

### c. Un gradient tronqué à zéro

`equilibrerCharge` tronquait en `int` une mesure de déséquilibre qui vaut
presque toujours moins de 1 : les petits déséquilibres valaient exactement
zéro, et le solveur n'avait aucune pente à descendre. Multipliée par dix avant
troncature, la règle redevient visible sans changer d'ordre de grandeur face
aux autres.

### d. Le clamp à deux bords — la vraie cause du déficit de couverture

Le découpage génère plusieurs variantes décalées de la grille de vacations
(« familles »), pour que tous les stands ne changent pas d'équipe au même
instant. Le décalage était appliqué **avant** le bornage dans la fenêtre
autorisée. Or un bornage a deux bords : la rampe négative échappait au mur de
14 h pour reconstruire exactement le même mur à 18 h 30, sur l'autre bord.

Conséquence mesurée : à l'instant de la relève, équipe sortante et équipe
entrante sont toutes deux en poste, donc **86 sièges réellement ouverts
devenaient 172 simultanés** pour 153 animateurs. Infaisable par construction,
sans la moindre pénurie.

Trois corrections ensemble : étaler les familles *dans* la plage retenue plutôt
que les y replaquer, ne retenir la fenêtre repas comme plage que si elle peut
contenir tout l'éventail, et répartir les stands entre familles par
round-robin — un `hash(id) mod n` donnait 19/21/36/15, donc une famille qui
relevait 40 % de la demande d'un bloc, exactement ce que l'étalement doit
éviter.

Score dur du scénario de référence : **−260 → −5**, puis 0 après réglage. La
leçon vaut au-delà du correctif : ce qui ressemblait à un manque de budget ou à
un manque d'animateurs était un défaut de génération de données — et **la borne
de faisabilité hors solveur le disait avant de lancer quoi que ce soit**. La
marge de cette borne est le bon indicateur de pilotage : à marge 1 le solveur
finit à −5, à marge 22 il atteint 0.

### e. `acceptedCountLimit` : deux fois retourné, deux fois mesuré

Le nombre de mouvements candidats échantillonnés par pas était réglé à 20 pour
la qualité de chaque pas, sur un scénario de 2 000 sièges. Sur le jeu réaliste
(3 500 sièges), le compromis s'inverse — un pas coûte assez cher pour que 20
candidats affament la recherche :

| `acceptedCountLimit` | 1 | 2 | 3 | 4 | 10 | 20 | 40 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Score dur à 180 s | 0 | 0 | −1 | −3 | −6 | −14 | −27 |
| 0 hard atteint à | 95 s | 137 s | jamais dans le budget | | | | |

Le réglage « prudent » n'atteignait donc **jamais** la faisabilité, y compris
sur un run de production de 1 800 s.

Mais 2 seul achetait la faisabilité trop cher : il faisait passer
`appreciationIncompatible` de 1 125 à 2 322, soit deux tiers des sièges tenus
par quelqu'un sans appréciation sur la typologie du stand, contre un tiers. Les
deux objectifs — trouver, puis polir — n'appellent pas le même réglage. D'où le
découpage de la recherche locale en deux phases, la première terminée par
`bestScoreFeasible` :

| Réglage (600 s, même graine) | 0 hard atteint | Medium final |
| --- | --- | --- |
| 2 seul | 146 s | −7 987 |
| 20 seul | jamais (−2) | −6 878 |
| 2 puis 20 | 145 s | −6 876 |
| **2 puis 40** | **145 s** | **−6 704** |

Timefold conservant toujours la meilleure solution rencontrée, la phase 2 ne
peut pas reperdre la faisabilité acquise en phase 1.

**Ce réglage n'a pas survécu à Timefold 2.5** (§2.i). Il est consigné ici pour
sa méthode, pas pour ses valeurs.

### f. L'arrêt anticipé, conditionné à la faisabilité

Une limite de plateau nue coupait la recherche sur un plateau de score *dur* :
elle abandonnait des minutes trop tôt sur un planning qui avait encore des
sièges vides. Elle est donc désactivée un temps, puis remise à 300 s **et
conjointe à `bestScoreFeasible`** : le solveur ne peut s'arrêter avant l'heure
qu'une fois le planning faisable et sans progrès depuis cinq minutes. L'arrêt
ne peut donc rogner que du polissage, jamais du temps passé à atteindre la
faisabilité.

Conséquence à connaître : **un problème structurellement infaisable consomme
toujours son budget plein**, réamorcé ou non.

### g. Compter des têtes plutôt que des paires

`eviterRoulementStandsPremium` pénalisait chaque paire de sièges d'un même
stand premium tenus par des animateurs différents. C'est quadratique en nombre
de sièges par stand, donc ingérable dès qu'un événement marque plus d'une
poignée de stands premium.

Mesuré sur le jeu réaliste (45 stands premium sur 65) : 48 567 paires
possibles, une pénalité de 38 591 sur 46 284 de score medium — **84 % du
total** — dont environ 25 000 structurellement inatteignables, un stand ouvert
douze jours ne pouvant pas légalement être tenu par une seule personne. Le
solveur descendait une pente qui s'arrête très au-dessus de zéro pendant que
des sièges restaient vides.

La règle compte désormais les animateurs *distincts* passés sur le stand,
au-delà d'un équipage. Même intention, même gradient monotone, ordre de
grandeur des autres règles medium, et une jointure quadratique de moins à
maintenir à chaque mouvement.

Corollaire mesuré au passage : **68 % du score medium était une constante
insatisfiable**, deux contraintes matchant l'intégralité des sièges faute de
donnée renseignée au référentiel. Les contraintes étaient correctes ; c'était
un décalage entre le référentiel et les données, et il ne se voit qu'en
regardant la décomposition du score, jamais le score seul.

### h. La seule contrainte supprimée sur décision produit

`favoriserRotationDesStands` pénalisait chaque paire de sièges tenus par le
même animateur sur le même stand. Elle entrait en conflit frontal avec deux
règles de niveau supérieur qui poussent à la stabilité — continuité sur un
stand premium, et plafond de typologies distinctes par animateur. Le medium
l'emportant sur le soft, elle ne faisait que du bruit dans le score.

Sa suppression a fait retomber un solve de 300 s à −1 hard : non pas parce
qu'elle servait, mais parce que **la trajectoire de recherche avait changé**.
Le budget est passé à 600 s, délibérément au-dessus du seuil mesuré, avec un
meilleur medium à l'arrivée — ce qui confirme la décision après coup.

### i. Timefold 1.34 → 2.5 : un arbitrage, pas un réglage perdu

Les cinq scénarios atteignent 0 hard en 2.5, mais la recherche locale y évalue
environ **278 mouvements par pas contre 24 en 1.34**, à réglage inchangé.
L'heuristique de construction, elle, est identique. Le medium final est 25 %
meilleur ; la faisabilité arrive six fois plus tard.

Conséquences appliquées : budget par défaut porté de 180 à **900 s** (à 180 s
le solveur rendait des sièges vides sans lever d'exception, l'arrêt sur plateau
étant conditionné à la faisabilité), et `acceptedCountLimit` ramené à **1** —
l'argument 1.x qui lui préférait 2 ne survit pas à un pas dix fois plus large,
où 1 gagne à la fois le temps et le medium.

Deux pièges de migration : la recette OpenRewrite officielle réécrit vers une
API qui n'existe pas en 2.5, et `environmentMode=REPRODUCIBLE` a disparu — une
documentation qui le conseillait encore faisait échouer la construction de la
fabrique de solveurs.

Le seul verrou fonctionnel de l'édition Community portait sur **une façade**,
`SolutionManager.analyze()`. Le calcul qu'elle appelle, justifications
comprises, est du code ordinaire : le diagnostic est passé par le score
director, ce qui a levé le verrou (décisions
[0013](decisions/0013-diagnostic-par-le-score-director.md) et
[0014](decisions/0014-analyser-le-plan-persiste.md)). Le prix est une
dépendance à un paquet `impl`, hors semver — d'où le confinement dans une
classe unique et un test de contrat qui compare les deux implémentations à
chaque montée de version.

### j. Le plafond matériel

`moveThreadCount` appartient à Timefold Enterprise : le solveur refuse de
démarrer sans licence. Ce n'est pas la seule raison de ne pas y compter — sur
une machine à quatre cœurs, **un seul** solveur consomme déjà près de quatre
cœurs, non par parallélisme d'évaluation (elle est mono-thread) mais par le
ramasse-miettes, vu le taux d'allocation. Lancer N solveurs ne diviserait pas
le temps par N, et multiplierait une empreinte d'environ 450 Mo par solveur.
C'est le plafond de performance du déploiement, et aucune optimisation de
contrainte ne le contourne.

---

## 3. D'où viennent les 43 contraintes

Les contraintes légales ne sont pas nées d'une intuition mais d'un **audit daté
du référentiel contre le Code du travail** (audit interne, hors dépôt),
qui a relevé une dizaine de manquements numérotés. La forme de cet audit — un constat, le
texte légal qui le fonde, une gravité — est ce qui a permis de les traiter un
par un, chaque commit citant son constat et son article.

Ce qui mérite d'être retenu au-delà de la liste :

**Trois régimes d'âge, jamais stockés.** Le droit distingue les moins de 16
ans, les 16-18 ans et les majeurs ; le modèle n'en connaissait que deux, et un
animateur de 15 ans était traité comme un animateur de 17. La tranche est
désormais **dérivée de la date de naissance à la date de chaque créneau**,
jamais persistée — c'est un invariant du projet, et il vaut aussi pour l'indice
de saisie affiché sur la fiche.

**Un prérequis technique déguisé en règle métier.** `pasDeDoubleAffectation`
comparait l'identité du créneau, pas ses horaires : deux créneaux distincts qui
se recouvrent pouvaient être attribués à la même personne. Les plafonds de
durée sommaient bien les minutes, mais le planning était physiquement
inexécutable — et toute règle de repos ajoutée ensuite aurait hérité du même
angle mort. `pasDeChevauchementHoraire` le remplace, sur les instants réels,
pour qu'un créneau franchissant minuit se termine bien le lendemain.

**Un défaut sûr quand le droit est incertain.** L'interdiction de travail des
mineurs les jours fériés est posée **sans dérogation sectorielle et non
paramétrable** : savoir si l'animation figure dans les secteurs fixés par
décret n'a pas été établi, donc le comportement le plus protecteur s'applique
et la dérogation reste à instruire. Même logique pour les deux jours fériés
d'Alsace-Moselle, exclus faute de notion de région dans le modèle plutôt
qu'appliqués partout.

**Deux règles explicitement hors périmètre**, et c'est écrit : la moyenne de 44
heures sur douze semaines (incalculable par un solveur qui ne voit qu'un
événement) et le travail de nuit des majeurs (à instruire par un juriste). Une
absence assumée et documentée vaut mieux qu'une implémentation approximative
d'un texte qu'on n'a pas lu.

**Un plafond n'est pas une protection si l'écran de saisie n'en est pas une.**
Rien n'empêchait un administrateur d'enregistrer 100 h/semaine : le solveur
produisait alors un planning « valide », score dur à zéro, manifestement
illégal. Les plafonds d'ordre public sont désormais refusés côté serveur, une
valeur plus protectrice restant libre.

**La compétence a changé de nature en cours de route.** Retour métier : ce que
l'administrateur saisit n'est pas une qualification objective mais une
**appréciation** formulée après formation. La contrainte dure
`competenceCompatible` disparaît au profit de deux règles medium séparées —
l'appréciation réelle (poids 3) et les souhaits déclarés par l'animateur (poids
1), pour que le solveur privilégie toujours le réel sur le souhaité. Le filtre
d'éligibilité a dû suivre : il excluait ces paires, si bien que les nouvelles
règles medium n'auraient jamais eu de mouvement à évaluer.

**Une trace d'audit qui n'en est pas une doit disparaître.** Désactiver une
règle légale a un temps exigé un motif obligatoire, horodaté et attribué. Sans
authentification, l'auteur ne pouvait valoir qu'une constante ; personne n'a
jamais saisi de motif ; et la table étant une table d'état, réactiver la règle
effaçait la trace. Le dispositif entier a été retiré et remplacé par ce qui
sert vraiment : l'état est affiché en permanence — « 3 règle(s) légale(s)
désactivée(s) » — en tête des écrans où l'on juge et où l'on lance un calcul.

La distinction entre obligation légale et politique de l'organisateur est
formalisée dans la décision
[0006](decisions/0006-obligation-legale-ou-politique-organisateur.md).

---

## 4. Ce qui a été retiré, et pourquoi

Une fonctionnalité retirée coûte moins cher qu'une fonctionnalité qu'on
maintient sans usage. Sept l'ont été, chacune pour une raison différente qui
vaut d'être connue avant de la réintroduire.

| Retiré | Motif |
| --- | --- |
| Import CSV (v1) | Jamais utilisé : le chargeur de scénario YAML et le dump SQL couvraient déjà les deux besoins. *(Un import CSV d'animateurs est revenu bien plus tard, avec un tout autre cahier des charges : aperçu ligne à ligne, écriture partielle, jamais de suppression — décision [0021](decisions/0021-import-tabulaire-partiel-et-previsualise.md).)* |
| Groupes de créneaux | Second niveau de variante à l'intérieur d'une édition. L'édition suffit ; le double niveau imposait un vocabulaire, une bannière, un sélecteur et une garde de restauration pour rien — décision [0009](decisions/0009-edition-unique-porteur-de-variantes.md). |
| Simulation « et si ? » | Livrée, jamais sortie du groupe « en cours de développement » ni entrée dans l'usage. |
| Simulateur de remplacement manuel | Rendu inutile par l'assistant de réparation : il laissait désigner un collègue puis annonçait *après coup* ce que l'échange cassait, quand l'assistant n'énumère que les remplacements qui tiennent. L'endpoint survit, l'outil MCP s'en sert. |
| PostHog | Analytics produit jamais consultée, qui coûtait une dépendance dans le bundle, deux variables, une clé exposée et un traitement de données à justifier. La trace du retrait est conservée dans [`observabilite.md`](observabilite.md) avec les candidats évalués à l'époque, pour que la question ne soit pas ré-instruite de zéro. |
| `favoriserRotationDesStands` | Conflit avec deux règles de niveau supérieur (§2.h). |
| Confirmation motivée d'une désactivation légale | Ne prouvait rien et ne survivait pas à son annulation (§3). |

Un cas voisin mérite d'être noté : le **contrôle DCO** en CI a été retiré comme
prématuré — une règle que seul son auteur pouvait enfreindre — mais avec une
issue ouverte pour le remettre à la première contribution externe, parce que
c'est le genre de chose qui ne se rattrape pas après coup.

---

## 5. Les garde-fous, et l'incident qui a créé chacun

C'est la partie de ce document la plus coûteuse à reconstituer, et la plus
utile : chacun de ces tests existe parce qu'un défaut précis est passé. Les
supprimer parce qu'ils « ne trouvent jamais rien » serait exactement l'erreur
qu'ils préviennent.

| Garde-fou | Ce qui l'a fait naître |
| --- | --- |
| `IsolationEditionStructurelleTest` — lit le SQL de tout le backend, échoue sur une requête visant une table métier sans prédicat d'édition | Marquer une typologie « ninja » sur une édition **effaçait silencieusement celle de toutes les autres**. L'auteur avait contourné le helper d'édition parce qu'il liait son paramètre en position 1 ; le prédicat est parti avec, sans bruit, pendant deux versions. |
| `LanguagePolicyStructuralTest` — relit les sources, refuse la prose française et les noms bâtis sur un mot français hors glossaire | La règle « code et commentaires en anglais » existait depuis longtemps et **les quatorze derniers commits avaient à eux seuls ajouté un tiers du français du dépôt**. À sa première exécution, le test relevait 315 blocs de prose et 522 noms. |
| `JsonContractTest` — gèle les clés JSON de chaque type exposé | La remédiation du franglais a déplacé **13 clés d'un coup**, dont une poignée seulement était assertée quelque part. Les autres ont changé de forme sur le fil avec un build vert, et le frontend aurait lu `undefined` en silence. |
| `McpToolNamesTest` — tout nom d'outil cité hors du code Java doit désigner un outil réel | Le prompt prêt à copier de la page MCP nommait un outil que l'application n'a jamais exposé, dans les deux langues. Le contrôle d'i18n compare les identifiants, jamais le texte : rien ne pouvait le voir. |
| `ConstraintToggleStructurelleTest` — les 43 contraintes, pas 6 | Le nom d'une contrainte est écrit à trois endroits sans qu'aucun compilateur ne relie les trois. Une faute de frappe rendait l'interrupteur d'IHM inopérant — c'était déjà arrivé. |
| `ScenarioSchemaGeneratorTest` — échoue dès que le schéma publié diverge des DTO | Deux champs ajoutés au DTO sans relancer le profil de génération (opt-in, hors CI) : un éditeur validant contre le schéma signalait deux clés valides comme inconnues. |
| `McpConfidentialiteStructurelleTest` — parcourt les outils par réflexion, échoue si l'un expose un champ personnel | Les messages de violation partaient bruts vers MCP, formatés pour l'IHM, donc porteurs de « Prénom Nom (id) ». |
| `npm run i18n-check` | `$localize` retombe silencieusement sur la source française : un écran à moitié traduit ne lève d'erreur ni au build, ni à l'exécution, ni dans les tests. **56 identifiants s'étaient accumulés** avant que le contrôle n'existe, et il a trouvé six messages divergents à sa première exécution. |
| `scripts/check-csp-index.js` | Le build de production inlinait le CSS critique en servant la vraie feuille via un attribut `onload` ; la politique de sécurité du contenu bloquait ce gestionnaire, la feuille restait en `media="print"` et **toutes les icônes disparaissaient en production**. HTML valide, build vert, tests verts : seule la console d'un vrai navigateur le montrait. |
| `eslint` + CI frontend | L'audit a mesuré un frontend en très bon état — 69 composants sur 69 en `OnPush`, zéro `any`, zéro `@for` sans `track` — et **rien ne le défendait**. Dix erreurs à la première exécution, dont trois imports morts laissés par les refactorings de la veille. |

Deux détails de conception se retrouvent dans presque tous : un test structurel
porte **un contrôle qui vérifie qu'il trouve encore quelque chose** (un scan qui
ne regarde plus rien passe au vert pour la pire des raisons), et **ses
exceptions sont vérifiées** — une exemption périmée doit sortir de la liste, ce
qu'un test dédié impose.

Une leçon de méthode enfin, payée deux fois : `McpToolNamesTest` lit les
annotations par réflexion parce que deux expressions rationnelles successives
sur le même code ont menti — la première en inventant deux outils, la seconde
en en manquant deux. **Un filet tissé sur une analyse fragile est pire que pas
de filet, parce qu'on le croit.**

---

## 6. Les contrats gelés

Ce qui suit ne se renomme pas, ou pas sans migration. Chaque entrée a une
raison qui n'est pas de la prudence de principe.

- **Les 42 noms de contraintes Timefold.** Ils sont clé primaire de
  `constraint_toggle` : un renommage sans migration **réactiverait en silence
  une contrainte désactivée**. Les méthodes qui les portent gardent donc leur
  nom, et `LanguagePolicyStructuralTest` porte une exemption vérifiée pour ça.
- **Les clés JSON.** Le nom d'un accesseur ou d'un composant de record *est* la
  clé sur le fil. Le renommage de `jeton` en `token` s'est arrêté à la
  frontière Java pendant plusieurs versions pour cette raison, avant d'être
  mené jusqu'au bout **une fois le contrat gelé par un test** (§5).
- **Le paramètre `{jeton}` de l'espace animateur.** Il nomme une variable,
  jamais un segment publié : les liens imprimés sur les PDF déjà distribués
  doivent continuer d'ouvrir l'espace de leur propriétaire. Le chemin de
  *rotation*, lui, était un segment publié et a pu être renommé.
- **La section `festival:` d'un scénario et `dateDebutFestival`.** Le
  vocabulaire du produit est passé de « festival » à « événement » partout
  ailleurs ; ces deux-là restent, un renommage cassant les fichiers déjà
  exportés.
- **Les noms d'outils MCP et leurs arguments.** Leur risque est comportemental
  et non compilable : un client qui les a appris ne les réapprend pas.
- **Une migration Flyway déjà appliquée.** `FLYWAY_REPAIR_AT_START` existe pour
  débloquer une instance, pas pour normaliser une modification après coup —
  laissée active, elle ferait passer sans un mot une migration réécrite.

Le dépôt en compte 63 à ce jour, et deux ruptures assumées sont documentées à
l'endroit où elles se paient plutôt que contournées par du code de
compatibilité — faire vivre deux noms en parallèle rend permanent le décalage
qu'on supprime.

---

## 7. Les pièges déjà payés

Ceux-là ne s'inventent pas et coûtent chacun une soirée.

**Le runner auto-hébergé et l'espace de travail partagé.** Un job conteneurisé
tourne en root, sur un espace de travail persistant et partagé : le job suivant,
sous l'utilisateur du runner, ne peut plus le nettoyer. **Toute la CI se bloque
au `checkout`**, avec des messages qui parlent tous de git et jamais de
permissions. Le job restitue désormais l'espace en sortant, quel que soit son
résultat.

**Isoler le port n'isole pas la base.** Le profil de développement pointe sur
la même base quelle que soit le port de l'application : une suite de tests de
bout en bout écrase la base qu'elle vise. La recette d'une pile jetable est
documentée dans [`developpement.md`](developpement.md).

**Un stub global survit au fichier qui le pose.** Deux specs stubbaient
`ResizeObserver` sans sa méthode `unobserve` ; le stub restait en place pour
tous les fichiers suivants du même worker, et la répartition entre workers
dépendant du nombre de cœurs, la collision se produisait sur le runner et pas
en local. Le correctif qui compte n'est pas la méthode ajoutée mais le
nettoyage systématique après chaque fichier.

**Un test qui échoue dans le vide n'est pas un test.** Six champs de formulaire
sont restés sans libellé accessible pendant six jours alors qu'un test écrit
exprès pour ça était rouge — hors CI, donc personne ne le regardait. La suite
de bout en bout est passée en exécution nocturne, puis **à chaque poussée** :
trois défauts d'interface avaient été trouvés par un humain qui cliquait, et
« un test qui n'arrête pas une PR se lit comme un test qui passe ».

**Un en-tête de sécurité peut casser une carte.** `Referrer-Policy:
no-referrer`, posé pour que le jeton d'espace ne fuie pas dans le `Referer`,
faisait refuser les tuiles OpenStreetMap. La correction est un attribut sur les
images de tuiles seulement — un attribut d'élément l'emportant sur la politique
du document — et non un relâchement global.

**Le déploiement casse les onglets ouverts.** Le shell servi 24 h et marqué
`immutable` réclame après un redéploiement des chunks supprimés : chaque lien du
menu meurt en silence. Tout ce qui porte un nom stable est passé en `no-cache`,
et un shell déjà périmé se répare au premier clic.

---

## 8. Les règles de méthode qui ont émergé

Elles ne sont écrites nulle part comme telles, et elles expliquent la forme de
beaucoup de commits.

1. **Vérifier un test dans le mauvais sens avant de le croire.** Un test écrit
   après le correctif ne prouve rien tant qu'il n'a pas été vu rouge contre le
   code d'avant. La campagne de tests de la dernière semaine a systématisé la
   mutation : casser volontairement la production, constater le rouge, remettre
   en état. Plusieurs fois, c'est **le test qui était en tort**, et le noter a
   plus de valeur que de le corriger en silence.
2. **Le plus petit levier d'abord.** Face à un scénario qui ne converge pas :
   la donnée avant la contrainte, la contrainte avant le budget, le budget
   avant l'architecture. La cause racine du plus long épisode de ce projet
   était une ligne de génération de grille (§2.d).
3. **« Flake » n'est pas une cause racine.** Chaque échec intermittent de ce
   dépôt s'est révélé être un vrai défaut : une fixture qui ne garantissait pas
   un repos hebdomadaire, un test qui attendait le mauvais chargement de page,
   un stub incomplet.
4. **Une documentation fausse est pire qu'une documentation absente.** Le motif
   revient une dizaine de fois dans l'historique, toujours formulé pareil : une
   règle qui décrit un état faux cesse d'être respectée, et une affirmation
   périmée se lit comme une consigne.
5. **Ce qu'un écran montre doit être ce que le solveur reçoit.** L'écran des
   ouvertures de stands ne réinterprète rien : il lit les sièges que la
   construction du problème remettrait au solveur. Un écran de validation qui
   proposerait une seconde lecture de la donnée ne validerait rien.
6. **Une phrase adressée aux personnes concernées est un engagement.** La
   politique de confidentialité affirmait qu'aucune donnée n'était transmise à
   un tiers alors qu'un beacon rapportait le chemin de chaque page vue — chemin
   qui porte le jeton d'un animateur, souvent mineur. Trois canaux de fuite ont
   été fermés et le masquage rendu exhaustif plutôt que champ par champ : « soit
   le code tient la phrase, soit il faut réécrire la phrase ».

---

## 9. Ce que ce document ne dit pas

- **Les numéros d'issues.** L'historique en citait des centaines ; ils
  pointeraient vers un backlog qui reste privé. Les décisions de
  [`decisions/`](decisions/README.md) se suffisent à elles-mêmes pour la même
  raison.
- **Les chiffres d'exploitation d'un client.** Les mesures citées ici portent
  toutes sur des fixtures versionnées. Les documents qui portaient les chiffres
  réels — analyse de conformité d'une organisation nommée, mesures de
  performance sur son jeu de données, fiche d'installation d'une machine —
  restent hors de ce dépôt.
- **Les SHA courts.** L'historique réécrit les invalide tous ; aucun n'est cité.
- **Le détail des écrans.** Il vit dans l'aide intégrée à l'application, à jour
  par construction, et le README se limite à un inventaire.
