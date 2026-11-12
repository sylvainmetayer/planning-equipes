# 0085 — Déporter le calcul sur un worker, par un contrat et non par un hébergeur : étude

- **Statut** : **proposé** · étude ; aucune ligne de production n'est écrite
- **Date** : novembre 2026
- **Portée** : résolution (`service/solve`), déploiement
- **Voisine de** : [0008](0008-file-sequentielle-avant-parallelisme.md) (un calcul à la fois), [0075](0075-quota-de-calcul-de-l-instance-dans-l-application.md) (le quota de l'exploitant), [0080](0080-un-solveur-exact-pour-la-seule-verification-du-besoin-etude.md) (l'autre étude de calcul hors de l'application)

## Contexte

La recherche de Timefold Community tient sur **un fil**. Ce qui fait la durée
d'une résolution est donc la vitesse d'un cœur de la machine, non son nombre de
cœurs ni sa mémoire : le profil d'une résolution (`-Dbench.jfr` du banc) ne
passe qu'une part marginale de son temps au ramasse-miettes, et changer de
collecteur n'y gagne rien.

Or la machine qui convient à l'application — une base, quelques écrans, des
courriels — est petite, et ses cœurs sont ceux qu'on trouve sur les offres
d'entrée de gamme ou sur un serveur ancien. Le même solveur, la même graine, la
même trajectoire de recherche vont plusieurs fois plus vite sur un cœur récent :
le premier plan sans écart dur arrive plus tôt dans la même proportion, et le
budget de quinze minutes polit d'autant plus loin. `SolverBenchTest` le mesure
sur n'importe quelle machine candidate, avec un scénario versionné
(`festival-hivernal.yaml`, `-Dbench.stopWhenFeasible=true` pour la faisabilité
seule).

Deux réponses existent sans code : une machine plus rapide en permanence, ou
relancer depuis le plan en place plutôt que de zéro, ce que l'application fait
déjà par défaut. Cette étude pose la troisième : **laisser l'application sur
une petite machine et confier le calcul à une autre**, louée à l'usage chez un
hébergeur ou déjà présente sur le réseau d'une installation sur site.
L'hébergement final d'une instance n'est pas une donnée de l'application :
rien ici ne doit en dépendre.

## Ce qui rend le déport possible

- **La couture est étroite.** Tout ce qui cherche passe par `SolveRunner` :
  un problème préparé et un budget entrent, un plan sort. La file, le quota,
  les refus pendant un calcul, l'atterrissage du plan, le journal et les
  notifications vivent dans `SolverJobService` et n'ont pas à bouger.
- **Le problème est autonome une fois préparé.** `SolveRunner.prepareProblem`
  complète le problème depuis le référentiel de l'édition (paramètres légaux,
  fenêtres repas, états et poids des contraintes, quotas, horizon du passé) ;
  après elle, plus rien ne lit la base. Le banc résout déjà ainsi, sans
  Quarkus ni PostgreSQL.
- **Le problème voyage déjà en JSON** : la route de résolution qui prend un
  planning en corps le reçoit ainsi.

## Proposition

1. **Un contrat, pas une intégration.** Le worker est **la même image**,
   lancée dans un mode où elle n'ouvre ni base ni écran et n'expose que ce
   contrat :
   - recevoir un problème **déjà préparé**, son budget et sa graine — il ne
     complète rien, faute de référentiel, et refuse un problème incomplet
     plutôt que de le résoudre sous d'autres règles ;
   - publier la courbe du meilleur score, ce que l'écran Solveur lit
     aujourd'hui de `SolverScoreTrace` ;
   - accepter l'arrêt anticipé et rendre alors le meilleur plan connu ;
   - rendre le plan final, avec son score.
2. **L'application sait une seule chose : l'adresse d'un worker**, par une
   variable d'environnement d'instance, et un secret partagé. **Sans adresse,
   elle résout sur place comme aujourd'hui** : le défaut ne change pour
   personne.
3. **Démarrer le worker à la demande est une pièce à part**, hors de
   l'application : un lanceur propre à chaque hébergeur, ou rien du tout quand
   le worker reste allumé sur une machine du réseau. L'application ne détient
   jamais de jeton capable de créer une machine.
4. **Restent sur place**, quel que soit le réglage : la résolution
   incrémentale (une minute de budget, que le démarrage d'un worker mangerait),
   le diagnostic et les analyses sans résolution, la vérification du besoin
   tant qu'elle cède la place à tout calcul qui démarre.
5. **Le worker indisponible ne bloque pas** : l'application le dit sur le job
   et résout sur place, plutôt que de laisser une file en attente d'une machine
   qui ne viendra pas.

## Ce que cela coûte

| Sujet | Aujourd'hui | Avec un worker |
| --- | --- | --- |
| Suivi en direct, bouton Arrêter | en mémoire, même processus | un flux et un appel à travers le réseau, à reprendre après coupure |
| Pannes | le processus tombe, la file est rejouée au démarrage | worker injoignable, coupure en plein calcul, résultat qui n'arrive pas : chacune doit finir en un état de job lisible |
| Version | une seule | le worker doit être **exactement** celle de l'application : un score calculé sous d'autres contraintes ne vaut pas ici. Le contrat porte la version, le worker refuse l'écart |
| Reproductibilité | graine fixe, même plan | conservée si la graine et le problème voyagent ; la vitesse change, pas la trajectoire |
| Données personnelles | ne quittent pas la machine | le problème porte les dates de naissance (le statut mineur en dépend) et les disponibilités. Nom, prénom et adresse n'ont rien à y faire et ne partent pas ; chez un second prestataire, c'est un sous-traitant de plus au registre |
| Quota et file | comptés au lancement | inchangés : ils sont décidés avant l'appel au worker |

## Ordre proposé, si l'étude est acceptée

1. Mesurer le banc sur la machine candidate. Sans un gain net sur un cœur, le
   déport n'apporte rien et l'étude s'arrête là.
2. Extraire de `SolveRunner` l'interface que les deux exécutions honorent, sans
   changer de comportement.
3. Le mode worker et son contrat, éprouvés par un test qui résout le même
   problème sur place et à distance et compare les deux plans, à graine égale.
4. L'appel distant derrière l'adresse d'instance, avec le repli sur place.
5. En dernier, et seulement pour qui loue à l'usage : un lanceur.

## Options écartées

- **Une intégration à l'API d'un hébergeur dans l'application** : elle lierait
  le produit à un fournisseur et mettrait dans l'application un jeton qui crée
  des machines.
- **Plusieurs solveurs en parallèle sur la même machine** : écarté par 0008 et
  par la mémoire d'un petit hôte ; le déport ne le rouvre pas, un worker
  résout un problème à la fois.
- **Un worker qui lit la base de l'application** : il faudrait lui ouvrir la
  base, et il redeviendrait une seconde instance — celle qui envoie les
  rappels en double.
- **Changer de moteur pour le calcul déporté** : c'est l'objet de 0080, et
  d'elle seule.
