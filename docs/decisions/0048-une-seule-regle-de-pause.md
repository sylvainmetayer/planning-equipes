# 0048 — Une seule règle de pause : un trou ou un relais, en dur, et la même heure comptée partout

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : domaine (`ParametresLegaux`, `PauseSurPoste`, `EffectiveWork`), solveur (catalogue, contraintes légales, filtre d'éligibilité), services d'analyse, API, MCP, scénarios, IHM
- **Révise** : [0006](0006-obligation-legale-ou-politique-organisateur.md) (frontière légal / politique), [0034](0034-exclusions-eligibilite-plus-lourdes-que-tout.md) (forfait d'éligibilité)
- **Prolonge** : [0037](0037-une-grille-est-toujours-des-vacations.md) (rupture nette sur les clés retirées), [0044](0044-le-passe-est-fige.md)

## Contexte

Le système de pause s'était construit en couches, chacune raisonnable à son
tour.

1. **Le modèle d'origine** : une pause est un **trou entre deux vacations**.
   `travailContinuMaxMajeur` et `travailContinuMaxMineur` refusaient toute
   séquence de plus de six heures (4 h 30 pour un mineur), et le trou qui la
   coupait était la pause.
2. **La grille réelle ne rentrait pas.** L'organisation enchaîne la relève de
   midi 13 h-14 h avec l'après-midi 14 h-20 h : sept heures d'affilée, refusées
   par le modèle, licites en fait — le Code exige que la pause soit **réelle**,
   pas qu'elle soit planifiée, et vingt minutes prises par relais entre
   collègues remplissent l'art. L3121-16. Une case « pause prise sur le poste »
   a donc été ajoutée. Elle **éteignait** les deux règles ci-dessus.
3. **Trois correctifs ont bouché ce que cette case ouvrait** : la coupure repas
   (issue #438), parce qu'une journée 10 h-20 h passait alors inaperçue ; le
   relais, vérifié par une règle séparée `pauseSurPosteSansRelais` ; la durée
   de pause rendue réglable (issue #592), puis portée en dur (issue #31).

Le résultat, sur `main` avant cette décision : **quatre notions appelées
« pause »** (le trou entre vacations, l'écart minimal entre vacations, la pause
légale, la coupure repas), **huit champs de paramètres**, **six règles**, et
**deux modes dont l'un éteignait des règles de l'autre**.

Quatre choses étaient fausses, pas seulement compliquées :

1. **Deux « pauses minimales » se contredisaient.** Un trou de 20 minutes
   terminait légalement une séquence de travail, et le même trou coûtait
   10 points durs à `pauseMinimaleEntreVacations`. Quatorze des quinze
   scénarios livrés mettaient ce réglage à zéro, et l'aide en ligne le
   conseillait : un défaut que tout le monde contourne n'est pas un défaut,
   c'est une erreur avec un mode d'emploi.
2. **La case éteignait aussi la règle des mineurs.** Cocher « pause sur le
   poste » rendait `travailContinuMaxMineur` muette, alors que les 4 h 30 de
   l'art. L3162-3 sont d'ordre public.
3. **Un plan à zéro écart dur pouvait rester illégal de fait.** Sur l'édition
   2026 anonymisée, dix-huit pauses dues n'avaient personne pour relayer —
   une relève enchaînée à un après-midi entier sur un stand à une place. Le
   relais n'était alors vérifié qu'en medium, donc dosable, donc négociable.
4. **Le filtre d'éligibilité et les règles ne lisaient pas la même durée.**
   `onPostBreakMinutes` à un argument et `PauseSurPoste.dues(day)` sans
   paramètres lisaient les constantes ; une édition réglant plus rendait le
   filtre **plus strict que les règles**, ce que [0034](0034-exclusions-eligibilite-plus-lourdes-que-tout.md)
   dit vouloir rendre impossible — un filtre plus strict cache des solutions
   faisables sans que rien ne le signale.

Et une chose qui n'était pas fausse mais illisible : la **déduction** de la
pause n'existait que sur les plafonds. Écran Heures, équité, Besoin, KPI
comptaient l'amplitude. Une demi-heure par jour d'écart entre deux écrans,
assez petit pour passer pour un arrondi et assez grand pour qu'un organisateur
se méfie des deux.

## Décision

### 1. Une seule règle, dure

Toute pause légale due est **soit un trou dans la grille d'au moins la durée
paramétrée, soit relayée par un collègue du même stand** tenant une place
pendant toute la pause, à son heure limite. Sinon, c'est un écart dur : un
point par pause non relayable, plus le forfait d'exclusion d'éligibilité de
[0034](0034-exclusions-eligibilite-plus-lourdes-que-tout.md) pour un mineur.

Plus de case, plus de mode. `travailContinuMaxMajeur` et
`travailContinuMaxMineur` **gardent leur nom, leur niveau et leur catégorie** —
donc les ancres de l'écran Contraintes et les lignes de `constraint_toggle` et
`ponderation_contrainte` d'une édition qui les avait touchées. Seul ce qu'elles
font change. `pauseSurPosteSansRelais` est absorbée par elles.

La pénalité **compte des pauses**, pas des minutes : « trois pauses sans
relais » est ce que l'écran Contraintes doit pouvoir dire, et c'est la seule
unité dans laquelle la réponse — ouvrir une place — se lit.

### 2. L'écart minimal entre deux vacations est supprimé, sans remplaçant

`pauseMinimaleEntreVacations` et son paramètre disparaissent. Un trou plus
court que la pause compte comme travaillé — il allonge la séquence, et c'est la
séquence qui est jugée —, un trou plus long est une pause. Rien d'autre. Deux
vacations qui se touchent s'enchaînent.

### 3. Une seule durée de pause

`dureePauseMajeurMinutes` et `dureePauseMineurMinutes` fusionnent en
**`dureePauseMinutes`**, plancher 20 (art. L3121-16), **défaut 30**, le cadre
que l'organisation a arrêté (issue #31).

Le plancher du mineur ne se stocke pas, il **s'applique à la lecture** :
`dureePauseMinutes(true)` rend `max(valeur, 30)`. Refuser une édition à 25
minutes l'aurait forcée à donner 30 à tout le monde, ce que l'art. L3162-3 ne
demande pas ; l'appliquer à la lecture donne 25 aux majeurs et 30 aux mineurs,
ce qu'il demande exactement. Tout lecteur d'une durée de pause passe par cet
accesseur, ce qui est la seule façon de garantir qu'aucun ne soit plus strict
qu'un autre — et c'est ce que `EligibleAnimateurMoveFilterTest` vérifie sur une
durée que nul défaut ne produit (45 minutes).

Le motif `TRAVAIL_CONTINU_MINEUR` du filtre d'éligibilité **disparaît** : un
siège long est licite dès qu'un collègue relaie, ce qui est un fait sur le
reste du plan et non sur la paire (siège, animateur). La règle garde son
forfait.

### 4. La pause est déduite partout

Une pause due est du repos (art. L3121-1), donc retranchée de l'amplitude :
plafonds quotidien, hebdomadaires et deux semaines, écran Heures,
`heures_travaillees`, équité, plancher Besoin, volumétrie, KPI. Sans condition.

**Rien n'est caché par là.** Dans un plan sans écart dur, toute pause déduite a
bien été prise — c'est exactement ce que la règle 1 garantit. Pendant la
recherche, une pause non relayée est déjà pénalisée en dur ; la déduire ne
rachète rien. Les deux décisions ne tiennent qu'ensemble : c'est pour cela
qu'elles sont dans la même ADR.

`EffectiveWork` est l'unique endroit où ce calcul vit, et
`HeuresCoherentesTest` verrouille l'égalité des quatre lectures pour un
animateur.

Les planchers de l'écran Besoin déduisent la pause **de l'édition**, jamais une
constante. Le sens de la prudence est fixé par ce qu'ils annoncent : un
plancher peut être lâche, il ne doit jamais dépasser la vérité. Supposer une
pause plus courte que celle accordée rétrécit ce qu'une personne couvre et
gonfle donc le plancher — sur le défaut de 30 minutes, une constante à 20
réclamait 120 personnes là où 119 suffisent.

Trois exceptions, toutes nommées dans `docs/contraintes.md` : les **compteurs
de prime** (dimanche, férié, après 22 h) restent à l'amplitude, parce qu'une
prime se paie sur la présence et qu'une pause ne se range pas d'un côté de
minuit sans inventer quand elle a été prise ; un **siège vide** compte son
amplitude, parce que ce qu'il coûtera en pauses n'est pas connu tant que
personne ne le tient ; les **KPI d'un instantané ancien**, dont les sièges ne
portent pas d'horaire.

### 5. Rupture nette sur les clés retirées

Comme au retrait du découpage ([0037](0037-une-grille-est-toujours-des-vacations.md)),
un scénario portant `pauseSurPoste`, `pauseMinimaleEntreVacationsMinutes`,
`dureePauseMajeurMinutes` ou `dureePauseMineurMinutes` est **refusé par le nom
de la clé**, avec ce qui la remplace. Ignorer la clé serait pire que refuser :
un fichier vérifié sous un mode où la pause devait être un trou serait jugé
sous une règle qui accepte le relais, et son auteur l'apprendrait du solveur.

La migration V98 supprime les trois colonnes, renomme la quatrième, et efface
les lignes de toggle et de poids des deux règles retirées — une ligne qui ne
nomme plus aucune règle réapparaîtrait sous ce nom s'il revenait un jour, avec
une intention vieille de plusieurs éditions (précédent V95).

## Ce qui a été mesuré avant de retirer la case

La condition posée avant le retrait était que la règle dure atteigne zéro écart
dur sur `festival-hivernal` — l'édition réelle anonymisée, 153 animateurs, 65
stands, la grille de l'organisateur — dans le budget du test existant, faute de
quoi c'est la **grille** qui devait être retaillée, pas la règle affaiblie.

Départ à froid, graine 0 :

| Étape | Temps | Score |
|---|---|---|
| Heuristique de construction | 55 s | −117 dur |
| Recherche locale, zéro dur atteint | **210 s** | 0 dur |
| Fin (plafond de sécurité : 900 s) | 210 s | `0hard/-7019medium/-17869soft` |

La grille n'a rien eu à céder : les places de relais y sont déjà, ce que
l'organisation affirmait en arrêtant son cadre. Une passe `FULL_ASSERT` de 20 s
sur la même fixture ne trouve aucune corruption de score ;
`PlafondsHebdomadairesFullAssertTest` fait la même passe sur `gamme-10`.

Là où la règle mord reste le **stand à une seule place tenu plus de six heures
d'affilée** : le barreau `gamme-30`, le cas que l'organisation affirme ne jamais
produire. La réponse est d'ouvrir une place ou de couper la journée — pas de
baisser un poids, qui n'existe pas ici.

## Conséquences

- **Ce qui devient plus simple** : une notion de pause légale au lieu de deux,
  un paramètre de durée au lieu de trois, deux règles au lieu de quatre, une
  grandeur d'heures au lieu de deux. Une grille en blocs jointifs ne demande
  plus aucun réglage.
- **Ce qui devient plus strict** : la règle des mineurs ne s'éteint plus, et le
  relais n'est plus négociable. Une édition qui tenait un stand seul plus de
  six heures et s'en tirait avec un écart medium dosable aura maintenant un
  écart dur — c'est le but.
- **Ce qui change sans qu'on l'ait demandé** : le défaut de durée passe de 20 à
  30 minutes. Une édition existante garde sa valeur (V98 renomme la colonne
  sans toucher les lignes) ; une édition nouvelle part à 30. Les scores des
  scénarios livrés bougent en conséquence, et leurs empreintes avec.
- **Ce qui reste à faire** : la borne « pic avec tampon » de l'écran Besoin
  prolongeait chaque vacation de l'écart minimal ; sans écart, elle rejoint le
  pic simultané, jour pour jour. Elle est conservée et le dit — la
  démonstration du plancher est écrite sur elle —, mais elle n'est plus la
  borne *retenue* : à égalité, `BorneRetenue` crédite le pic simultané, sans
  quoi l'écran nommerait une contrainte qui ne contraint plus rien. Deux lignes
  qui donnent toujours le même nombre restent une de trop, à retirer quand le
  contrat de `BorneRetenue` pourra changer.
- **Ce que cette ADR ne tranche pas** : la convention collective de l'Animation
  (IDCC 1518), qui imposerait 45 minutes quelle que soit la durée de la journée
  (art. 5.3). L'organisation a confirmé ne pas en relever ; le jour où une autre
  en relèverait, c'est `dureePauseMinutes` qu'elle règlerait à 45, et
  `coupureRepasObligatoire` qui resterait à instruire pour une journée
  14 h-22 h ne traversant aucune fenêtre repas.
