-- L'importance d'une règle de qualité en trois positions : faible (1),
-- normale (5), forte (25) — ADR 0057.
--
-- Jusqu'ici le défaut livré d'une règle moyenne ou souple valait 1, le
-- minimum : « baisser son poids » n'y menait nulle part, et l'écran ne pouvait
-- proposer qu'un seul sens. Le défaut passe à 5 (`application.properties`,
-- `ConstraintCatalog.defaultWeight`), la stabilité du plan publié de 5 à 25.
-- Pour que chaque édition garde exactement le même classement des plans, les
-- poids qu'elle a stockés suivent le même facteur : tout est multiplié par 5,
-- les scores medium et soft des prochaines résolutions aussi.
--
-- Seules les règles moyennes et souples changent d'échelle. Une règle dure
-- garde 1 : un écart dur se compte, il ne se dose pas (V95), et un poids dur
-- stocké par une édition — que l'ancien écran permettait — reste tel quel.
--
-- L'historique des réglages suit, pour se lire dans l'unité de l'écran :
-- « 1 → 4 » devient « 5 → 20 ». Le dosage recopié dans chaque résolution
-- (`planning_resolution.dosage`) et les scores enregistrés ne sont PAS
-- touchés : ils disent sous quels poids et avec quel score ces plans ont été
-- calculés, et le Comparateur signale à juste titre qu'un plan d'avant ne se
-- compare pas à poids égaux avec un plan d'après.
--
-- La liste est celle du catalogue au moment de cette migration : une règle
-- qui changerait de niveau plus tard aurait sa propre migration (voir V95).

UPDATE ponderation_contrainte
SET poids = poids * 5
WHERE nom IN (
    'coupureRepasPlacementPrefere',
    'affiniteAdHoc',
    'arriveeGroupee',
    'standComplexeAvecReferent',
    'equilibrerCharge',
    'stabiliteDuPlanPublie',
    'repartitionMineursParCreneau',
    'experienceRequisePourStandsPremium',
    'eviterRoulementStandsPremium',
    'eviterChangementEmplacementEloigne',
    'trajetInsuffisantEntrePostes',
    'limiterEmplacementsParJour',
    'eviterEnchainementStandsEpuisants',
    'eviterFermeturePuisOuverture',
    'appreciationIncompatible',
    'souhaitsIncompatibles',
    'limiterTypologiesDistinctesParAnimateur',
    'maxJoursConsecutifsTravailles',
    'favoriserMixiteDesNiveaux',
    'equilibrerCreneauxPenibles',
    'preserverBufferPolyvalents'
);

UPDATE ponderation_contrainte_historique
SET poids_avant = poids_avant * 5,
    poids_apres = poids_apres * 5
WHERE nom IN (
    'coupureRepasPlacementPrefere',
    'affiniteAdHoc',
    'arriveeGroupee',
    'standComplexeAvecReferent',
    'equilibrerCharge',
    'stabiliteDuPlanPublie',
    'repartitionMineursParCreneau',
    'experienceRequisePourStandsPremium',
    'eviterRoulementStandsPremium',
    'eviterChangementEmplacementEloigne',
    'trajetInsuffisantEntrePostes',
    'limiterEmplacementsParJour',
    'eviterEnchainementStandsEpuisants',
    'eviterFermeturePuisOuverture',
    'appreciationIncompatible',
    'souhaitsIncompatibles',
    'limiterTypologiesDistinctesParAnimateur',
    'maxJoursConsecutifsTravailles',
    'favoriserMixiteDesNiveaux',
    'equilibrerCreneauxPenibles',
    'preserverBufferPolyvalents'
);
