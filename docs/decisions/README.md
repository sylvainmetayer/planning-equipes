# Décisions d'architecture

Ce dossier consigne les **décisions structurantes** du projet : ce qui a été
choisi, contre quelles alternatives, et ce que ça engage. Il ne remplace pas la
documentation de référence de [`docs/`](../README.md), qui décrit *ce que fait*
le système — ici on trouve le **pourquoi**.

## Convention

Un fichier par décision, nommé `NNNN-titre-court.md`, numéroté dans l'ordre où
la décision a été prise. Chaque document ouvre sur son statut, sa date et sa
portée, puis suit le même plan : contexte, options envisagées, décision,
conséquences.

Une décision n'est jamais réécrite après coup. Quand elle est révisée, son
statut le dit et renvoie vers celle qui la remplace — c'est la chaîne des
révisions qui a de la valeur, pas la dernière version seule.

Le format reste **court**, une page. Les décisions étayées par des mesures
portent celles-ci en annexe, à condition qu'elles soient reproductibles sur un
scénario versionné de `src/main/resources/scenarios/`.

## Index

| # | Décision | Statut |
| --- | --- | --- |
| [0001](0001-cloisonnement-par-edition.md) | Cloisonner le référentiel et les résultats de solveur par édition | Accepté · révisé par 0009 |
| [0002](0002-diagnostic-decouple-du-solveur.md) | Découpler le diagnostic de contraintes de l'édition du solveur | **Remplacé** par 0013 |
| [0003](0003-verrouillage-par-pin-natif.md) | Figer les affectations validées par le mécanisme natif du solveur | Accepté |
| [0004](0004-competence-transverse-dans-les-typologies.md) | Une compétence transverse réutilise l'énumération des typologies | Accepté |
| [0005](0005-polyvalence-reserve-plutot-que-reservation.md) | Polyvalence : garantir une réserve, plutôt que réserver les personnes | Accepté |
| [0006](0006-obligation-legale-ou-politique-organisateur.md) | Distinguer l'obligation légale de la politique de l'organisateur | Accepté · principe transverse |
| [0007](0007-instantanes-contenu-denormalise.md) | Instantanés de plan : un contenu dénormalisé, pas une copie de lignes | Accepté |
| [0008](0008-file-sequentielle-avant-parallelisme.md) | Résoudre les variantes en file séquentielle avant d'envisager le parallélisme | **Retiré** · remplacé par 0009 |
| [0009](0009-edition-unique-porteur-de-variantes.md) | L'édition est l'unique porteur de variantes | Accepté · révise 0001, remplace 0008 |
| [0010](0010-contraintes-ad-hoc-contradiction-plutot-que-budget.md) | Contraintes ad hoc : détecter la contradiction, pas plafonner le nombre | Accepté |
| [0011](0011-publier-plutot-qu-envoyer-a-tous.md) | Publier, plutôt qu'envoyer à tous : le planning publié est distinct du planning de travail | Accepté |
| [0012](0012-etat-de-vue-dans-l-url.md) | L'état de vue d'un écran est porté par l'URL | Accepté · précisé par 0018 |
| [0013](0013-diagnostic-par-le-score-director.md) | Diagnostiquer par le score director, sans mode dégradé | Accepté · remplace 0002 |
| [0014](0014-analyser-le-plan-persiste.md) | Analyser le plan persisté, plutôt que résoudre pour jeter | Accepté · prolonge 0013 |
| [0015](0015-sauvegarde-par-pg-dump-restauration-hors-application.md) | Sauvegarder par `pg_dump`, restaurer hors de l'application | Accepté |
| [0016](0016-purge-manuelle-avant-automatisation.md) | Purger à la main d'abord, automatiser quand la charge le justifie | Accepté |
| [0017](0017-fragilite-le-ninja-est-un-renfort-pas-un-specialiste.md) | Fragilité : le ninja est un renfort, jamais un spécialiste | Accepté · prolonge 0005 |
| [0018](0018-ecrire-l-url-de-vue-sans-naviguer.md) | Écrire l'URL de vue sans naviguer | Accepté · précise 0012 |
| [0019](0019-jeton-et-chemin-dedies-pour-l-abonnement-ics.md) | Un jeton et un chemin dédiés pour l'abonnement au calendrier | Accepté |
| [0020](0020-avertir-dans-la-reponse-d-ecriture.md) | Avertir dans la réponse d'écriture, pas dans un contrôle à part | Accepté · prolonge 0010 |
| [0021](0021-import-tabulaire-partiel-et-previsualise.md) | L'import tabulaire d'animateurs est partiel, prévisualisé et rejoué | Accepté |
| [0022](0022-import-de-la-grille-des-stands.md) | La matrice des stands s'importe sous le contrat de 0021 | Accepté · transpose 0021 |
| [0023](0023-modification-concurrente-par-horodatage.md) | Détecter une modification concurrente par horodatage, sans verrou | Accepté |
| [0024](0024-repartir-du-plan-enregistre-par-defaut.md) | Une résolution complète repart du plan enregistré par défaut, sans l'épingler | Accepté |
| [0025](0025-stabilite-du-plan-publie.md) | La stabilité après publication est une règle dosée, pas un gel | Accepté · prolonge 0024 · échelle révisée par 0057 |
| [0026](0026-famille-de-relais-attribut-du-stand.md) | La famille de relais est un attribut du stand | Remplacé par 0029 · issue #390 |
| [0027](0027-pas-de-compilation-native.md) | Pas de compilation native | Accepté · issue #392 |
| [0028](0028-transactions-declaratives-narayana.md) | Transactions déclaratives (`@Transactional`, Narayana) pour les unités de travail composées | **Proposé** · mesuré, non tranché · issue #448 |
| [0029](0029-retrait-des-familles-de-relais.md) | Les familles de relais sont retirées | Accepté · remplace 0026 |
| [0030](0030-grille-competences-import-additif.md) | La grille des compétences transpose 0021 : identité par identifiant, case vide inchangée, jamais de suppression | Abandonné · retiré par 0050 |
| [0031](0031-signaler-le-plancher-sans-le-decider.md) | Une règle qui pénalise tout faute de donnée est signalée comme plancher, jamais désactivée | Accepté |
| [0032](0032-journees-types-nommees-vacations-fixes.md) | Des journées types nommées génèrent les créneaux par différence ; les créneaux restent la vérité | Accepté |
| [0033](0033-un-stand-qui-declare-ses-ouvertures-est-ferme-ailleurs.md) | Un stand qui déclare ses ouvertures est fermé les jours qu'il ne déclare pas ; l'élagage retire les fermetures devenues muettes | Accepté |
| [0034](0034-exclusions-eligibilite-plus-lourdes-que-tout.md) | Un siège qui enfreint une exclusion d'éligibilité (jour d'indisponibilité, règles des mineurs) coûte plus que tout autre écart ; l'affectation forcée un jour d'indisponibilité est signalée, pas refusée | Accepté |
| [0035](0035-mineur-seul-au-forfait.md) | Un mineur sans majeur à ses côtés coûte le même forfait que les exclusions d'éligibilité | Accepté · conditionné par 0041 |
| [0036](0036-construction-echantillonnee-des-tres-gros-problemes.md) | Au-delà de 15 millions de couples sièges × animateurs, la construction n'évalue que 50 candidats éligibles tirés au hasard par siège | Accepté |
| [0037](0037-une-grille-est-toujours-des-vacations.md) | Une grille de créneaux est toujours faite de vacations : le découpage des amplitudes est retiré | Accepté |
| [0038](0038-fraicheur-du-referentiel-persistee.md) | La date de dernière mutation du référentiel est persistée sur `edition` ; restaurer un instantané périmé est refusé sauf `forcer` | Accepté |
| [0039](0039-validation-de-relecture-distincte-du-verrou.md) | Une journée se marque « relue et acceptée » sans être figée ; une résolution qui la déplace retire la relecture | Accepté |
| [0040](0040-mentions-legales-exigees-au-demarrage.md) | Les mentions légales d'une instance en service sont exigées au démarrage, pas listées dans une case à cocher | Accepté |
| [0041](0041-encadrement-des-mineurs-eteint-par-defaut.md) | L'encadrement des mineurs est une règle éteinte par défaut | Accepté · révise 0035 |
| [0042](0042-quota-par-typologie-sur-la-typologie.md) | Le quota par typologie se pose sur la typologie, pas sur une contrainte ad hoc | Accepté |
| [0043](0043-consigne-d-edition-fermer-une-bande-sans-rien-detruire.md) | Une consigne d'édition ferme une bande horaire pour tous les stands, en quatrième couche du résolveur d'horaires, sans rien détruire de la grille | Accepté · révise 0001 § 6 bis, complète 0033 |
| [0044](0044-le-passe-est-fige.md) | Le passé est figé : les places des créneaux déjà commencés sont reprises du plan enregistré et épinglées par toute résolution, comptées par les règles et reprochées par aucune | Accepté · prolonge 0003 et 0024, complète 0043 · assoupli par 0066 |
| [0045](0045-le-niveau-de-la-regle-des-jours-d-affilee.md) | Les jours d'affilée restent une règle dosée, avec une forme dure éteinte et un seuil réglable | Accepté · prolonge 0006 et 0041 · échelle révisée par 0057 |
| [0046](0046-un-placement-intenable-est-dit-avant-le-calcul.md) | Un placement intenable est dit au moment du geste et reporté avant le calcul ; seule l'écriture directe d'un siège, qui n'attend plus rien, est refusée | Accepté · complète 0003 |
| [0047](0047-differer-le-message-d-une-personne-sans-la-perdre-de-vue.md) | Exclure quelqu'un d'une publication diffère son message sans l'oublier : la référence de comparaison devient une propriété de la personne, la capture reste commune | Accepté · complète 0011 |
| [0048](0048-une-seule-regle-de-pause.md) | Une seule règle de pause : toute pause due est un trou dans la grille ou un relais du même stand, sinon un écart dur ; une seule durée, déduite partout | Accepté · révise 0006 et 0034, prolonge 0037 |
| [0049](0049-la-regle-dure-des-jours-d-affilee-se-cherche-par-jours-entiers.md) | Sous la forme dure des jours d'affilée, la chaîne libère un jour de la série, les jours se regroupent et le *ruin and recreate* de la phase de faisabilité devient rare | Accepté · prolonge 0045, complète 0025 |
| [0050](0050-identifiants-generes-par-edition.md) | Les identifiants métier sont générés par l'application, numérotés par édition ; un code lisible pour les stands, typologies et emplacements ; dans un scénario, un identifiant n'est qu'une référence locale | Accepté · prolonge 0001 |
| [0051](0051-budget-de-calcul-par-edition-sous-plafond-d-exploitant.md) | Le budget de calcul — durée et arrêt sur plateau conditionné à la faisabilité — se règle par édition et s'applique côté serveur au lancement, sous deux plafonds d'exploitant qui refusent au lieu de rogner | Accepté |
| [0052](0052-gel-du-referentiel-distinct-du-verrou.md) | Le gel du référentiel fige par familles (stands, créneaux, typologies et emplacements, compétences) les fiches d'une préparation terminée, refusé au service par annotation sur tous les chemins ; distinct du verrou de planning, il laisse ouvertes les consignes et les données des personnes | Accepté · voisine de 0003, 0039 et 0043 |
| [0053](0053-affichage-mural-par-jeton-dedie.md) | L'affichage mural de la salle de contrôle s'ouvre par un jeton dédié, haché et révocable, sur un préfixe à lui — jamais par une session admin laissée sur une TV | Accepté · prolonge 0019 |
| [0054](0054-placer-sur-un-siege-vide-ecrit-le-siege-puis-le-verrou.md) | « Placer » sur un siège vide passe par les contrôles de l'écriture directe plus les gardes du déplacement (siège encore vide, personne non verrouillée), puis l'écran pose un verrou `ANIMATEUR_CRENEAU` tant que « La garder au prochain calcul » reste cochée | Accepté · voisine de 0044 et 0046 |
| [0055](0055-une-seule-soiree-la-nuit-de-la-paie-a-part.md) | Une seule soirée dans l'application, l'heure réglable que lisent les rapports des heures et d'équité ; la borne fixe de 22 h ne survit que comme la « Nuit (paie) » | Accepté · voisine de 0048 |
| [0056](0056-indisponible-ce-jour-ecrit-toujours-le-jour-indisponible.md) | « Indisponible ce jour », un clic sur la frise de la fiche animateur, écrit toujours le jour indisponible et libère dans le même appel ses sièges de ce jour dans le planning enregistré — un siège commencé ou verrouillé gardé en place, et nommé ; l'annulation ne rend aucun siège | Accepté · voisine de 0044 et 0054 |
| [0057](0057-importance-d-une-regle-en-trois-positions.md) | L'importance d'une règle de qualité se règle en trois positions — faible 1, normale 5, forte 25 — et l'échelle des poids est multipliée par cinq, défauts, poids stockés et scénarios livrés compris | Accepté · révise l'échelle de 0025 et 0045 |
| [0064](0064-le-geste-d-une-regle-en-defaut-vient-du-catalogue.md) | Le geste qui corrige une règle en défaut est un levier typé porté par `ConstraintCatalog` et traduit en écran par le playbook ; la baisse du poids n'est jamais un levier, seulement le dernier geste de toute règle pesée, masqué au plus bas | Accepté · voisine de 0054 |
| [0065](0065-empechement-signale-declaration-constatee.md) | « Je ne pourrai pas être là », signalé depuis l'espace sur une journée ou un poste, est une déclaration constatée : rien ne s'écrit au planning tant que l'organisation n'a pas marqué l'absence ou classé le signalement ; motif en liste fermée, jamais de texte libre | Accepté · voisine de 0047 |
| [0066](0066-le-passe-est-fige-a-la-minute.md) | Le passé est figé à la minute, pas au créneau : le siège d'un créneau en cours est scindé à « maintenant » en deux sièges réels — l'origine écourtée garde qui l'a tenue, la suite se répare — et les reconstructions rejouent la scission | Accepté · assouplit 0044 |

**0002** et **0013** se lisent ensemble : la première pose le blocage du
diagnostic par l'édition du solveur et retient deux modes de qualité inégale,
la seconde montre que le verrou ne portait que sur une façade et n'en garde
qu'un seul. **0014** en tire la conséquence : l'analyse ne coûtant plus qu'un
calcul de score, elle se dérive du plan persisté au lieu d'une résolution dont
on jette le résultat.

Deux décisions se lisent ensemble : **0001** pose le cloisonnement par édition,
**0009** le révise en supprimant le second niveau qu'il avait retenu. **0038**
le révise sur un autre point : la fraîcheur du référentiel, que 0001 rangeait
en mémoire vive, descend en base le jour où un badge en dépend. **0008**
documente une orchestration livrée puis retirée par 0009 — son raisonnement est
conservé parce que ses mesures gardent leur valeur pour toute réflexion future
sur le parallélisme.
