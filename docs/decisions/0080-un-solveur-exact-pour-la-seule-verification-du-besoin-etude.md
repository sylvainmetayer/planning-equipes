# 0080 — Un solveur exact (CP-SAT) pour la seule vérification du besoin : étude

- **Statut** : **proposé** · étude de faisabilité ; le banc lui-même viendra dans un travail dédié
- **Date** : octobre 2026
- **Portée** : vérification du besoin en animateurs (`StaffingVerificationService`), et rien d'autre du solveur
- **Voisine de** : [0071](0071-le-besoin-en-animateurs-un-minimum-exact-verifie-par-une-equipe-fictive.md), [0079](0079-la-phase-de-faisabilite-tire-sur-une-liste-par-pas-et-reconstruit-rarement.md)

## Contexte

La vérification du besoin ([0071](0071-le-besoin-en-animateurs-un-minimum-exact-verifie-par-une-equipe-fictive.md))
résout une équipe fictive jusqu'à la faisabilité pour confirmer qu'un effectif
suffit. C'est la seule question **oui / non** que l'application pose au
solveur, et la recherche locale de Timefold ne sait pas répondre « non » : un
échec dans le budget signifie « pas trouvé », jamais « impossible ». Elle
occupe en outre les cœurs du serveur aussi longtemps qu'une résolution.

Un solveur exact — CP-SAT d'OR-Tools, Apache-2.0, avec des liaisons Java —
sait prouver l'infaisabilité, et c'est ce qui rend des outils de planification
de soignants rapides sur de petits problèmes. Sur notre taille (150 à 320
animateurs, 3 500 à 5 000 sièges, 16 à 30 jours), rien ne dit qu'il tient dans
le temps d'une vérification. Ce document pose ce qu'il faudrait mesurer, et
tranche ce qui peut l'être sans mesure.

## Ce que la vérification demande au modèle

Les règles **dures** seules, la stabilité du plan publié et les exceptions
ad hoc écartées (la vérification les retire déjà, 0071), `posteDoitEtrePourvu`
imposée quoi qu'en dise l'édition. Traduites en variables booléennes
*x*(siège, animateur) :

| Règle dure | Traduction CP-SAT | Difficulté |
| --- | --- | --- |
| `posteDoitEtrePourvu` | Σ_a *x*(s, a) = 1 par siège | triviale |
| `pasDeChevauchementHoraire` | pour chaque animateur et chaque paire de sièges qui se chevauchent, *x* + *x* ≤ 1 (ou `AddNoOverlap` sur des intervalles optionnels) | moyenne : O(sièges²) par jour sans intervalles, linéaire avec |
| `animateurDisponible`, `standReserveAuxMajeurs`, `travailDeNuitInterditPourMineur`, `travailInterditJourFerieMineur` | *x*(s, a) = 0 d'avance : ce sont les exclusions d'`EligibleAnimateurMoveFilter` | triviale (le domaine se réduit, le problème rétrécit) |
| `dureeQuotidienneMax*`, `dureeHebdomadaireMax*` | Σ durée × *x* ≤ plafond par (animateur, jour) et (animateur, semaine ISO) | simple **tant que la pause n'est pas déduite** : la déduction de 0048 dépend des séquences ininterrompues, donc de la combinaison des sièges tenus — un terme non linéaire. Approximation sûre : ne rien déduire (plus strict, donc un « oui » reste un « oui ») |
| `dureeHebdomadaireMaxDeuxSemaines` | pour chaque paire de semaines voisines : *pleine*(w) + *pleine*(w+1) ≤ 1, avec *pleine*(w) ⇔ Σ ≥ plafond | simple (une variable par semaine et par personne) |
| `maxJoursTravaillesParSemaine`, `maxJoursConsecutifsTravaillesDur` | *t*(a, j) ⇔ ∃ s du jour j : *x*(s, a) ; Σ_j∈semaine *t* ≤ 6 ; Σ sur toute fenêtre glissante de (plafond + 1) jours ≤ plafond | simple |
| `reposQuotidienMinimal` | paires de sièges de jours voisins dont l'écart est sous le repos dû : *x* + *x* ≤ 1 | moyenne, quadratique mais creuse (soir × matin suivant) |
| `reposHebdomadaireMinimal` (35 h consécutives créditées à chaque semaine, crédit chevauchant le lundi) | une variable par fenêtre de repos candidate et par semaine, au moins une fenêtre libre par semaine travaillée | **difficile** : la lecture de `deficitReposHebdomadaireMinutes` (crédit de la journée adjacente, plancher sur la semaine) ne se linéarise pas sans énumérer les fenêtres ; approximation sûre : exiger 35 h pleines dans la semaine civile |
| `reposHebdomadaireMineur` | deux *t*(a, j) consécutifs à 0 par semaine | simple |
| `travailContinuMaxMajeur` / `Mineur` (trou ou **relais** par un collègue du même stand) | la pause due dépend de la séquence tenue ; le relais, d'un autre siège du même stand couvrant la pause | **difficile** : la règle de 0048 est la plus combinatoire du catalogue. Approximation sûre : interdire toute séquence au-delà du plafond sans trou (donc sans relais), plus stricte |
| `coupureRepasObligatoire` | pour chaque (animateur, jour, fenêtre repas) : au moins un créneau de la fenêtre libre, par des *x* à 0 sur les sièges qui le couvrent | moyenne : il faut énumérer les placements de coupure possibles de la fenêtre (une poignée par jour) |
| `mineurNecessiteEncadrementMajeur` | pour chaque siège tenu par un mineur, Σ_{majeurs} *x*(s', a') ≥ 1 sur les sièges simultanés du stand | simple (implication) |
| `plafondCreneauxParTypologie` | Σ sur les sièges de la typologie ≤ plafond par animateur | triviale |
| `repartition` des mineurs, `standComplexe`, qualité, préférences | hors périmètre : ce ne sont pas des règles dures | — |

Deux règles ne se traduisent pas sans approximation : la pause relayée et le
repos hebdomadaire crédité. Les approximations proposées sont **plus strictes**
que les règles : un « N suffisent » du modèle exact reste vrai sous les vraies
règles, un « N ne suffisent pas » ne l'est pas forcément. C'est l'inverse du
défaut de Timefold (un « pas trouvé » n'est pas un « non ») — les deux
réponses se complètent plutôt qu'elles ne se remplacent.

## Protocole du banc

- `festival-hivernal`, `festival-realiste-canicule`, `gamme-20`, `gamme-22`,
  `gamme-25`, à l'effectif N que l'écran Besoin annonce et à N − 1.
- Pour chaque : le temps de CP-SAT pour trouver une affectation faisable (N) et
  pour prouver l'infaisabilité (N − 1), sa mémoire de crête, et l'accord avec le
  verdict de la vérification actuelle (`StaffingVerificationService`), avec son
  temps.
- Les deux approximations jouées **séparément** : d'abord la traduction stricte
  (pause sans relais, repos pleins), puis, si le temps le permet, la pause
  relayée par énumération des relais possibles sur les stands à plusieurs
  places.

## Ce qui est tranché sans mesure

- **Pas de dépendance dans l'application.** Quel que soit le résultat, OR-Tools
  (bibliothèques natives, ~100 Mo) n'entre pas dans l'image : 0071 l'avait
  écarté pour la borne et la raison tient. Le banc vit dans un module Maven
  séparé, hors du build par défaut, ou dans un outil hors Java ; s'il concluait
  à l'adoption, la vérification appellerait un **service à part**, et cette
  décision serait un nouvel enregistrement.
- **Le banc n'est pas dans cette livraison.** Le travail de vitesse du solveur
  ([0079](0079-la-phase-de-faisabilite-tire-sur-une-liste-par-pas-et-reconstruit-rarement.md)
  et la terminaison) a ramené la phase de faisabilité sous la minute sur les
  éditions réelles ; la vérification en profite directement, et l'urgence d'un
  second moteur a baissé d'autant.
- **Trois issues possibles**, à écrire quand le banc aura tourné : adopter
  CP-SAT pour la seule vérification (si N − 1 se prouve en moins d'une minute
  sur `gamme-25`), l'écarter (si la traduction stricte refuse des effectifs que
  Timefold tient), ou le garder comme **oracle de test** des bornes de 0071
  (un `scenario-lent` qui confronte les bornes au verdict exact).
