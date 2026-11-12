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

## Ce que coûte une résolution chez un hébergeur

Relevé de **novembre 2026**, prix hors taxes, pour une machine de deux cœurs
virtuels et quatre gigaoctets environ — ce qu'une résolution demande. C'est une
photographie pour situer l'ordre de grandeur, pas un choix : la proposition
reste celle d'un contrat, et les tarifs bougent.

| Hébergeur (pays) | Offre | Prix à l'heure | Pas de facturation | Une résolution de 15 min | Source du prix |
| --- | --- | --- | --- | --- | --- |
| Hetzner (DE) | `cpx22`, cœurs partagés | 0,031 € | **l'heure entamée est due** ; une machine éteinte reste facturée | 0,031 € | API de l'hébergeur |
| Hetzner (DE) | `ccx13`, cœurs dédiés | 0,069 € | idem | 0,069 € | API de l'hébergeur |
| OVHcloud (FR) | `c3-4` | 0,046 € | **l'heure entamée est due** ; arrêtée ou en pause, le compteur tourne | 0,046 € | catalogue public |
| OVHcloud (FR) | `b3-8` | 0,051 € | idem | 0,051 € | catalogue public |
| Scaleway (FR) | `PLAY2-NANO`, cœurs partagés | 0,028 € | à l'heure ; l'arrondi n'est pas documenté | 0,028 € au plus | grille publique |
| Scaleway (FR) | `POP2-HC-2C-4G`, cœurs dédiés | 0,053 € | idem | 0,053 € au plus | grille publique |
| Scaleway (FR) | Serverless Jobs, 2 vCPU et 4 Go | 0,086 € | **à la seconde**, rien entre deux tâches | 0,022 €, sous la franchise mensuelle à notre volume | grille publique |
| Exoscale (CH) | Standard Medium | 0,047 € | **à la seconde** | 0,012 € | comparateur tiers |
| IONOS (DE) | deux cœurs dédiés | 0,09 à 0,11 $ | **à la minute** | 0,02 à 0,03 $ | grille publique, en dollars |
| UpCloud (FI) | General Purpose 2 cœurs | 0,039 € | à l'heure ; éteinte, elle reste facturée | 0,039 € | comparateur tiers |
| Infomaniak (CH) | `a2-ram4` | 0,009 à 0,016 € | au prorata dans l'heure | moins de 0,01 € | comparateur tiers |

Trois lectures :

- **Le prix ne départage personne.** Partout une résolution coûte quelques
  centimes, cinquante résolutions dans le mois un à trois euros, là où garder
  allumée la même machine coûte de vingt à quarante-cinq euros par mois. Le
  pas de facturation compte davantage que le tarif : à l'heure entamée, un
  worker qu'on éteint après chaque calcul paie une heure pour quinze minutes,
  et il vaut alors mieux le laisser servir toutes les résolutions de l'heure.
- **Ce qui départage est la vitesse d'un cœur, que le tarif ne dit pas.** Les
  grilles annoncent une fréquence, rarement une génération de processeur, et
  des cœurs partagés d'une génération récente peuvent valoir des cœurs dédiés
  d'une plus ancienne. Seul le banc sur la machine candidate répond (étape 1
  ci-dessous) ; les lignes relevées sur un comparateur tiers sont à
  reconfirmer du même geste.
- **Une tâche conteneur à la demande dispense du lanceur** : l'hébergeur
  démarre l'image, la facture à la seconde et l'éteint. C'est la forme la plus
  simple à exploiter, et celle dont la vitesse est la moins prévisible.

Sur site, le coût marginal est nul : la machine existe déjà.

## Ordre proposé, si l'étude est acceptée

| Étape | Charge estimée |
| --- | --- |
| 1. Mesurer le banc sur la machine candidate. Sans un gain net sur un cœur, le déport n'apporte rien et l'étude s'arrête là | une heure de location |
| 2. Extraire de `SolveRunner` l'interface que les deux exécutions honorent, sans changer de comportement | 0,5 jour |
| 3. Le mode worker et son contrat, éprouvés par un test qui résout le même problème sur place et à distance et compare les deux plans, à graine égale | 2 à 3 jours |
| 4. L'appel distant derrière l'adresse d'instance : suivi, arrêt, coupures, repli sur place, états de job | 3 à 4 jours |
| 5. Documentation, configuration, sécurité, registre des traitements | 1 jour |
| **Un worker toujours disponible — ce qui suffit sur site** | **7 à 9 jours** |
| 6. En dernier, et seulement pour qui loue à l'usage : un lanceur, par hébergeur | 2 à 3 jours |

L'étape 4 porte l'incertitude : l'arrêt, la reprise après redémarrage et
l'atterrissage du plan vivent dans `SolverJobService`, et ce qui y suppose le
solveur dans le même processus n'a pas été recensé. La résolution depuis zéro
enchaîne en outre deux solveurs — la faisabilité, puis le polissage une fois
la stabilité du plan publié rétablie : le contrat porte cet enchaînement, pas
un appel unique.

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
