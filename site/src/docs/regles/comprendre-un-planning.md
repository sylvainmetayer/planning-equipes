# Comprendre un planning qui coince

Un planning laisse des places vides ou une règle cède : vous savez pourquoi, où, qui est concerné, et quel geste corrige le problème en premier.

![Le diagnostic d'un planning](/images/fr/diagnostic.webp "Le Diagnostic : ce qui bloque, ce qui manque, ce qui est fragile")

Tout se lit dans *Diagnostic* (groupe *Planifier*) et, pour le résumé, en tête de l'onglet *Calculer* de la page Planning.

Pourquoi un calcul peut ne rien trouver, en termes simples : [quand le calcul ne trouve aucune solution](/comment-marche-le-calcul/).

## Lire le bilan en quelques phrases

Le score se dit sans vocabulaire technique : 

- les règles impératives respectées ou non ;
- les places restées vides, et le jour qui en compte le plus ;
- les deux ou trois règles qui pèsent sur l'organisation, avec leur part ;
- ce qui vient d'une donnée absente (voir le plancher dans [Régler les règles du planning](regles-du-planning.md#quand-une-règle-ne-peut-pas-saméliorer--le-plancher)) ;
- les ajustements manuels en cause ;
- juste après une résolution, ce qui a changé par rapport au plan d'avant.

Chaque règle et chaque jour cités mènent à leur ligne ; aucune personne n'est nommée. Le même texte figure en tête de l'onglet *Calculer* et du Diagnostic, et pour chaque plan comparé dans les Versions du plan.

## Trouver ce qui bloque : l'onglet Problèmes

Le premier onglet répond à « ce qui bloque, ce qui manque, ce qui est fragile ». Il réunit en une vue les blocages, triés par gravité : les causes d'infaisabilité détectées sans résolution et les règles encore en défaut après la dernière analyse.

Chaque carte dit :

- **où** : les trois stands ou jours les plus touchés. Chacun ouvre la page Planning sur ce stand et ce créneau, panneau du siège ouvert ;
- **qui** : les personnes les plus concernées, chacune ouvrant sa fiche.

Quand une règle a buté sur des exceptions saisies à la main, elles sont nommées une par une, avec les pauses légales que personne ne peut relayer. Sous les cartes, « Où se concentrent les écarts » croise les écarts avec les journées, les stands ou les personnes, filtré sur la règle choisie.

## Appliquer le geste qui règle le problème : « Que faire ? »

Sous chaque problème, l'application liste les gestes réels qui le règlent, dans l'ordre que le catalogue des règles leur donne, chacun avec sa raison.

- « Qui peut tenir ce siège ? » s'ouvre sur place, et « Placer » remplit le siège sans quitter le Diagnostic.
- Les autres gestes ouvrent l'écran déjà positionné : la grille Compétences sur la typologie du stand, la fiche du stand, la ligne de la règle pour régler son plafond, la charge par personne, les verrous, les ajustements en cause affichés seuls, la donnée manquante d'un plancher.
- « Baisser l'importance » vient toujours en dernier, et disparaît quand l'importance est déjà au plus bas.
- Une journée déjà commencée ne propose rien qui la modifierait.

D'autres liens relient un symptôme à l'écran qui le traite : un siège libre, une pause sans relais ou un stand de la carte ouvrent le panneau du siège sans quitter la page Planning ; un goulot de typologie ou une compétence rare mènent aux animateurs qui la détiennent ; un avertissement à l'enregistrement rouvre la fiche ; une cause d'infaisabilité ouvre le créneau ou le stand en cause.

## Repérer les points de défaillance : la section Fragilité

L'onglet *Risques* du Diagnostic réunit trois lectures, de haut en bas : la fragilité, « À former » (voir [Savoir combien de personnes recruter](dimensionner-l-equipe.md)) et la tension. La section *Fragilité* dit qui est un point de défaillance unique. Pour chaque personne, il liste les créneaux qui passeraient sous l'effectif minimum si elle se désistait, et surtout ceux que personne d'autre ne pourrait reprendre. Il liste aussi les stands tenus par une seule personne compétente.

Par personne, trois gestes :

- « Verrouiller » garde son planning tel quel au prochain calcul ;
- « Qui peut remplacer » ouvre le panneau du siège qu'elle laisserait le plus difficile à tenir ;
- « Former » ouvre la grille des compétences sur les typologies de ses stands.

Son nom mène à sa fiche.

## Voir la tension heure par heure : la section Tension

La section *Tension*, au pied de l'onglet *Risques*, croise le jour avec la tranche horaire, sur le planning enregistré. Dans chaque case : les personnes réellement libres moins les sièges restés vides, croisées avec la fragilité du plan.

- Une colonne par heure de début de la grille : un soir qui finit à 22 h en semaine et à minuit le samedi reste une seule colonne.
- La couleur suit le signe de la marge : rouge en dessous de zéro, vert au-dessus.
- Le cadre dit la gravité : calme, surveillée, élevée ou critique, par des règles nommées (sièges vides que personne ne peut tenir, siège qu'aucun autre ne pourrait reprendre, stand sans spécialiste, sièges fragiles au-delà de la marge).
- Une case ouvre le panneau du siège de sa tranche sur la page Planning. La pire tranche de chaque journée donne ses raisons, avec la fiche de la personne irremplaçable et la fragilité du stand. Une tranche déjà commencée est grisée.

La marge *avant* résolution est une colonne de l'onglet Besoin : voir [Savoir combien de personnes recruter](dimensionner-l-equipe.md).

## Lire la charge de chacun : l'onglet Charge

L'onglet *Charge* du Diagnostic dit ce que chaque animateur a reçu sur tout l'événement, une ligne par personne, chaque valeur triable d'un clic sur son titre. C'est là que vous choisissez qui alléger, et là que se lisent les heures pour la paie.

- les heures, leur écart à la médiane et les heures de chaque semaine, signalées au-delà de 35 h (le plafond d'un mineur) et de 48 h (le plafond légal de tous) ;
- la soirée (à partir de l'heure réglée dans *Paramètres*, onglet *Calcul*, la seule soirée de l'application), le week-end, les jours fériés, le dimanche et « Nuit (paie) » après 22 h ;
- l'amplitude maximale d'une journée (du premier début à la dernière fin, trous compris) et le nombre de journées au-delà de 12 h, signalées sans aucune règle derrière ;
- les postes et les postes exigeants ; les stands, typologies et emplacements distincts ;
- les souhaits et appréciations satisfaits ;
- les jours travaillés, les jours de repos et la plus longue série.

Les colonnes où tout le monde est à zéro sont masquées tant que vous ne cochez pas « Colonnes toujours vides » ; « Seulement sans jour de repos » ne garde que les personnes qui n'en ont aucun, et le filtre par nom réduit la liste. Le nom d'une personne mène à sa fiche, où son radar la compare à l'équipe.

Les heures, celles du tableau comme l'export pour la paie, se lisent au choix sur le **plan publié**, ce que les animateurs ont reçu, proposé d'office dès qu'il existe, ou sur le **plan enregistré**. Le fichier dit lequel et de quand. Sur le plan publié, l'écran rappelle que les autres compteurs restent ceux du plan enregistré.

Deux exports CSV : « Heures pour la paie » et « Équité ».

## Savoir ce qui a changé depuis le dernier calcul

Le Solveur compte les modifications faites depuis le calcul enregistré. « Corriger après un changement » les montre avant de proposer quoi rouvrir, par famille et avec les dernières lignes de l'historique. Voir [Calculer le planning](calculer-planning.md#choisir-comment-lancer).

## Lire les pauses et les repas : l'onglet Repas et pauses

L'onglet *Repas et pauses* du Diagnostic dit où tombent, un jour donné, les pauses légales et les repas. Tout est lu sur le planning enregistré, rien n'est recalculé ; le jour se choisit en tête et reste dans l'adresse.

- **Les pauses**, stand par stand : qui s'arrête, à quelle heure au plus tard, et qui relaie. Une pause que personne ne peut relayer est un écart dur ; « Ouvrir le siège » mène à la page Planning, sur ce siège, pour chercher qui peut venir. Dessous, la coupure repas due à qui travaille de part et d'autre d'une fenêtre de repas (celles dont la journée n'a pas la place passent en premier), et les pauses déjà prévues par la grille.
- **L'intendance** : combien de personnes sont en coupure repas, heure par heure et emplacement par emplacement, pour savoir quoi préparer et où le porter. Les mineurs sont comptés entre parenthèses, jamais nommés. « Tout l'événement en CSV » exporte tous les jours.
- **Les enchaînements serrés** : les trajets entre deux postes consécutifs qui laissent peu de temps, ceux qui ne tiennent pas en premier, avec le temps de marche et ce qu'il manque.

Le placement préféré de la coupure repas dans sa fenêtre se règle dans *Paramètres*, onglet *Organisation* ; la règle « Coupure repas » et son importance sont dans *Règles du planning*. L'onglet renvoie vers les deux.

## Quand un calcul dégrade le plan

Le score d'avant s'affiche à côté de celui d'après. Si le nouveau plan est moins bon sans qu'aucune règle impérative soit enfreinte, l'écran le dit et propose de revenir au plan précédent. Les versions et leur comparaison sont décrites dans [Calculer le planning](calculer-planning.md#garder-des-versions-du-plan).

## Retrouver qui a fait quoi

L'**historique des actions** garde ce qui a été fait dans l'édition et par qui : chaque écriture, chaque export, chaque envoi, qu'il vienne d'un écran, d'un assistant ou d'une tâche de nuit, avec les champs qu'une modification a réellement changés. Voir [Gérer les comptes et les droits](comptes-et-droits.md#lire-lhistorique-des-actions).
