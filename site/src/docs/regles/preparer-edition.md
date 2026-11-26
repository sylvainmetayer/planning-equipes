# Préparer votre première édition

Vous partez d'une édition vide et vous la décrivez : ses stands, leurs typologies, leurs lieux. Les animateurs et le calendrier ont chacun leur guide.

Une édition se prépare dans l'ordre où elle se construit : les stands et ce qu'ils proposent, le calendrier sur lequel ils s'ouvrent, les personnes, puis les fichiers qui remplissent le tout. Le groupe *Préparer* du menu suit cet ordre (Stands, Calendrier, Animateurs, Fichiers).

Le type d'événement change les priorités : voir [un planning de festival](/planning-festival/), [un planning de salon ou de foire](/planning-salon-foire/) ou [un planning d'événement sportif](/planning-evenement-sportif/).

## Avant de commencer

- Choisissez l'édition de travail dans la barre d'édition, au-dessus de chaque page. Deux onglets du navigateur peuvent travailler sur deux éditions à la fois ; elles ne se mélangent jamais. Pour en créer une, voir [Enchaîner les éditions](enchainer-les-editions.md).
- Sur une édition vide, l'accueil propose un bloc « Démarrer » : importer un fichier, repartir d'une édition précédente ou charger un exemple. Chaque liste de référentiel vide dit ce qu'elle contiendra, mène à l'étape précédente quand elle manque, et propose elle aussi un exemple.
- Si vous avez déjà un tableur, [Importer et exporter des fichiers](importer-exporter.md) évite la saisie à la main.

## Créer vos typologies

Une **typologie** est un genre de jeu ou d'activité. C'est le vocabulaire commun entre les compétences des animateurs et ce que propose un stand. Elles se gèrent dans l'onglet *Typologies* de la page Stands.

1. Ouvrez *Stands*, onglet *Typologies*.
2. Créez chaque typologie. Vous pouvez y fixer le plafond de créneaux qu'un animateur peut y tenir, et cocher la typologie « polyvalente », celle des polyvalents, directement dans le tableau.
3. Lisez les chiffres de chaque ligne : combien de personnes la maîtrisent, combien l'ont souhaitée et combien de stands la proposent. Chaque chiffre mène à la liste qu'il compte.

Un badge signale une typologie **orpheline** (proposée par un stand, maîtrisée par personne hors polyvalents), **fragile** (une seule personne) ou **inutilisée** (aucun stand). L'état de l'édition compte les orphelines avant tout calcul. Une fois un planning calculé, trois colonnes disent ce qu'il en a fait : les postes, les heures, et les personnes affectées sans la compétence.

## Placer vos stands sur la carte (les lieux)

Les **lieux** sont les emplacements géolocalisés rattachés aux stands. Ils servent à éviter les déplacements lointains d'un créneau à l'autre.

1. Ouvrez *Stands*, onglet *Lieux*. Une carte montre tous les lieux avec leurs stands.
2. Déplacez un marqueur à la souris : la nouvelle position est enregistrée.
3. Le tableau dit quels stands sont rattachés à chaque lieu. Un lieu se crée aussi depuis le formulaire d'un stand.

## Ajouter un stand

1. Ouvrez *Stands* et cliquez sur « Ajouter ». L'assistant guide la création : identité, typologies, lieu, puis les créneaux de l'édition, à décocher, avec l'effectif de chacun. Aucune règle à écrire.
2. Renseignez au moins une typologie (toujours), les effectifs minimum et maximum, et si le stand est réservé aux majeurs. Les indicateurs *premium* et *effort* se règlent aussi dans la fiche.
3. Réglez les horaires : des plages datées, d'ouverture ou de fermeture, jour par jour. Chaque fenêtre d'ouverture peut nommer son propre effectif, pour un stand qui n'a pas le même besoin le matin, l'après-midi et en nocturne. Le détail est dans [Construire le calendrier et les horaires](calendrier-et-horaires.md).

Le tableau des stands se trie sur chaque colonne et dit, pour chacun, ses jours ouverts, ses postes et, après calcul, sa couverture.

### La fiche d'un stand

Chaque stand a sa fiche, qui réunit :

- sa grille jour × créneau, modifiable sur place comme dans la grille des Horaires des stands ;
- ses anomalies d'ouverture, dont une fenêtre déclarée à une heure qu'aucune vacation ne couvre ;
- ses sièges après calcul ;
- la comparaison avec un autre stand ;
- l'historique de ses modifications ;
- ses règles en forme condensée.

Les horaires d'un stand se copient sur un autre depuis sa fiche, ou sur toute une sélection depuis la modification en masse. Les règles, les exceptions datées et les effectifs de fenêtre sont copiés avec un signal quand une fenêtre dépasse l'effectif maximum du stand cible.

## Travailler dans les tableaux

Chaque liste de référentiel se manie comme un tableur.

- Toutes les colonnes se trient, les identifiants dans leur ordre naturel (T2 avant T10).
- Le nom d'une ligne l'ouvre ; un menu la modifie, la duplique ou la supprime.
- « Exporter cette liste » télécharge en CSV ce que le tableau montre.
- Un bloc copié d'un tableur se colle sur les lignes, après un aperçu des cases modifiées.
- Un avertissement d'enregistrement reste marqué sur sa ligne.

Au clavier : « / » saisit le filtre de la page, puis Flèche bas entre dans le tableau ; les flèches haut et bas déplacent la ligne courante, Entrée l'ouvre et Espace la coche pour une action groupée. Un clic sur une ligne la focalise de la même façon, et la tabulation entre dans le tableau et en ressort sans y rester coincée.

## Ce que l'application vous signale à la saisie

À la création comme à la modification, la saisie est enregistrée, puis un message signale ce qui mérite un second regard :

- une indisponibilité hors des dates de l'événement, ou posée sur un jour sans créneau ;
- une date de naissance qui rend l'animateur mineur pendant l'événement (le message dit à partir de quand il devient majeur) ;
- un créneau qu'aucun stand ouvert ne couvre.

Une modification ne signale que ce qu'elle change : une donnée que vous n'avez pas touchée ne déclenche rien.

## Quand deux personnes modifient la même fiche

Si deux onglets ou deux personnes ouvrent la même fiche, la seconde à enregistrer est prévenue que la fiche a bougé depuis son ouverture. Elle choisit de recharger ou d'écraser en connaissance de cause : rien n'est écrasé en silence.

## Changer de phase quand la préparation est terminée

Une édition avance par phases : Préparation, Calcul, Publiée, Terminée. Tant qu'elle est en Préparation, tout se modifie. Dès qu'elle passe en Calcul, le référentiel est figé : stands, créneaux, typologies et emplacements, compétences des animateurs. Plus aucun chemin (formulaire, modification en masse, import, assistant) ne modifie alors ces fiches, et les champs figés s'affichent en lecture seule avec un cadenas.

Pour changer de phase, ouvrez l'accueil (ligne « Phase de l'édition ») ou Administrer › Éditions : les quatre phases y sont côte à côte, la courante est marquée, et chaque changement demande une confirmation qui dit ce que la nouvelle phase fige ou ouvre. Tout retour en arrière est permis : repasser en Préparation rouvre le référentiel. Un compte en lecture seule voit la phase et n'a aucun bouton.

La première publication fait passer l'édition en Publiée. La structure (stands, créneaux, typologies et emplacements) y reste figée, mais les fiches des animateurs se modifient de nouveau. Pour un changement de dernière minute, comme ajouter un stand la veille, cliquez sur « Débloquer la structure » : elle se rouvre sans quitter la phase Publiée, un bandeau en haut de l'écran le rappelle tant qu'elle est débloquée, et la publication suivante la refige d'elle-même (« Refiger » le fait à la main).

Dans toutes les phases, les disponibilités, les ajustements, les verrous et les consignes restent libres. Quand la phase rouvre le référentiel, l'application rappelle si le planning est déjà publié.

## Et ensuite

Décrivez [les animateurs et leurs compétences](animateurs-et-competences.md), puis [le calendrier et les horaires](calendrier-et-horaires.md). Quand tout est en place, [calculez le planning](calculer-planning.md).
