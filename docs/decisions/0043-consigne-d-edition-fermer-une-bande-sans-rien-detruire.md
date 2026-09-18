# 0043 — Consigne d'édition : fermer une bande horaire sans rien détruire

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : résolution des horaires de stand, grille de créneaux,
  résolution, validation de relecture, instantanés, publication, espace
  animateur, PDF, journal, MCP, IHM

## Contexte

Un **arrêté** — canicule, orage — tombe la veille au soir et impose, pour
**tous** les stands, la fermeture d'une bande horaire, typiquement 12 h-18 h,
sur deux à cinq jours d'un événement qui en dure dix. Il se prolonge, se lève,
revient : un aller-retour, pas une bascule. L'organisateur décale l'ouverture :
les stands qui perdent leur après-midi rouvrent le soir, parfois le matin,
individuellement ou en masse. Le planning est adapté par résolution
incrémentale puis publié. Une journée modifiée le reste pour toujours — les
statistiques et l'historique décrivent ce qui a été travaillé — et la suite de
l'événement doit tenir compte des heures réelles : cumuls, repos, « fermer tard
puis ouvrir tôt ».

Le rituel prévu pour ce cas était celui de
[0001 § 6 bis](0001-cloisonnement-par-edition.md) : dupliquer l'édition,
appliquer le delta dans la copie, résoudre la nuit, basculer le matin, envoyer
à tous, puis revenir. Il ne tient pas pour un arrêté, et pour des raisons qui
ne se corrigent pas :

- **les liens ne suivent pas.** Les jetons d'espace et d'abonnement sont
  refrappés à la duplication : chaque lien déjà distribué, chaque abonnement
  ICS déjà posé dans un agenda continue de servir l'édition abandonnée, pour
  toujours ;
- **les rappels et les notifications partent deux fois**, depuis deux
  éditions, avec des contenus contradictoires, à moins de couper à la main
  celle qu'on quitte ;
- **ce qui est saisi pendant la vague reste dans la mauvaise édition** :
  confirmations de lecture, déclarations de disponibilité, verrous et
  ajustements posés sur la copie ne reviennent pas quand on re-bascule ;
- **deux référentiels à tenir en phase** pendant l'alerte, pour un delta qui
  tient en une bande horaire ;
- **la stabilité du plan publié est muette** : elle compare le plan de travail
  au plan publié *de la même édition*
  ([0025](0025-stabilite-du-plan-publie.md)), et la copie n'en a aucun. Le
  solveur de la copie repart donc de zéro sur les gens déjà prévenus, et le
  retour à l'édition nominale ne rend rien à personne.

La duplication reste le bon geste pour une **variante** — une autre grille,
une autre équipe. Un arrêté n'est pas une variante : c'est la même édition,
amputée d'une bande pendant quelques jours.

## Options envisagées

**(A) Une seconde édition dupliquée**, le rituel ci-dessus. Écarté pour les
cinq raisons du contexte, dont trois — liens, rappels, saisies — sont des
promesses faites aux animateurs que la bascule rompt.

**(B) Une journée type « canicule » appliquée, puis un instantané restauré à
la levée.** Écarté. Appliquer la journée type **supprime** les créneaux de
l'après-midi avec leurs sièges — verrous, confirmations, ajustements qui les
nommaient compris. Le code a depuis réparé une partie du dégât (une vacation
disparue s'annonce par ses date et heures, les verrous visent une clé
naturelle, la stabilité reconnaît un créneau recréé), mais la restauration
demande un remappage des ids que rien ne borne, et la réouverture d'un stand
le soir s'écrit en **exception datée**, qui *remplace* la journée entière du
stand ([0033](0033-un-stand-qui-declare-ses-ouvertures-est-ferme-ailleurs.md)) :
rouvrir 18 h-22 h fait ressaisir 10 h-12 h. Et cela reste destructeur par
construction : la levée est une reconstruction, jamais un retour.

**(C) Une consigne datée, quatrième couche du résolveur d'horaires.** Retenu.
Le modèle sait déjà fermer sans détruire : un stand fermé sur tout un créneau
n'y engendre aucun siège, un stand fermé sur une partie engendre les sièges du
reste, aux heures effectives ([`domaine.md`](../domaine.md#fenêtre-effective)),
et aucun créneau n'est touché. Il suffit d'une couche qui dise, pour une date,
« tout le monde est fermé de 12 h à 18 h, et voici qui rouvre quand ».

## Décision

### Le modèle

Une **consigne** est **une ligne par date** de l'édition. Elle porte :

- la **bande interdite** : un début obligatoire, une fin facultative — `null`
  se lit « jusqu'à minuit », la convention de toutes les fenêtres datées ;
  `00:00` → `null` est la journée entière ;
- un **motif obligatoire**, repris tel quel partout où la journée est dite
  modifiée : courriel, espace, PDF, jour J ;
- le **nom du préréglage** dont elle est issue, facultatif. Un nom, pas une
  clé étrangère : supprimer le préréglage ne réécrit pas l'histoire des dates
  qu'il a gouvernées ;
- des **fenêtres de compensation par défaut**, plusieurs possibles, avant ou
  après la bande — ce que reçoit un stand coché avant tout réglage individuel ;
- des **ouvertures** : un stand, une fenêtre, un effectif facultatif ;
  plusieurs fenêtres par stand ;
- les **créneaux ajoutés** à la grille, marqués comme tels
  (`consigne_edition_creneau`).

Un **préréglage** nommé par édition (« Plan canicule : 12 h-18 h fermé, soir
18 h-22 h ») mémorise bande, motif et fenêtres par défaut. Il est validé à
froid — une vague d'alerte ne doit pas être son premier essai — et **copié à
la duplication** d'édition, parce qu'il décrit la forme de l'événement. Les
consignes datées, elles, **ne sont pas copiées** : elles appartiennent aux
jours d'une édition, comme le plan.

### Les règles du résolveur

`ConsigneResolver` s'applique après les règles récurrentes et les exceptions
datées, sur les fenêtres effectives seulement — les listes persistées ne sont
jamais écrites. Pour chaque stand d'une date sous consigne, dans cet ordre :

1. **La bande ferme tous les stands, sans exception**, au-dessus de leurs
   règles et de leurs exceptions datées — c'est l'arrêté. **Et elle ne ferme
   que cela** : un stand garde ses horaires hors de la bande, qu'on l'ait
   coché ou non. Un 14 h-20 h devient un 18 h-20 h.
2. **Les créneaux que la consigne a ajoutés n'appartiennent aux horaires de
   personne.** Un stand y est fermé, sauf si une de ses propres fenêtres de
   consigne les couvre. Sans cette règle, un stand ouvert par défaut serait
   doté sur une soirée que personne n'a choisie pour lui.
3. **Une ouverture est une extension, choisie stand par stand.** Le stand
   ouvre sur ses fenêtres *en plus* de ses horaires, jamais dans la bande, à
   l'effectif saisi ; sinon au **plus fort effectif qu'il perd dans la
   bande** ; sinon à son minimum. Là où une fenêtre recouvre des heures que le
   stand avait déjà, le plus haut des deux effectifs s'applique, comme entre
   deux fenêtres du stand.

La journée en sort énoncée en **ouvertures explicites** — fermé par défaut,
dans le vocabulaire des trois états — ou en **fermeture de journée entière**
quand il ne reste rien. Un seul mode par jour : l'invariant de 0033 est
intact, et rien en aval — segments, contraintes, exports — n'a à connaître la
consigne.

### Ce qui s'écrit, ce qui ne s'écrit jamais

**Rien n'est détruit.** Les créneaux nominaux gardent id, sièges et verrous ;
les vacations de la bande deviennent des vacations **sans siège**, qui
réapparaissent telles quelles à la levée. La seule écriture sur la grille est
**additive** : quand aucun créneau ne couvre une fenêtre choisie — 20 h-22 h
après une journée qui finit à 20 h — le créneau manquant est **ajouté** et
marqué. Il n'est jamais ajouté sans qu'une ouverture l'exige.

**Jours à venir seulement.** Poser, modifier et lever refusent une date passée
ou en cours (`date > aujourd'hui`, horloge simulée du jour J respectée). Une
date déjà travaillée garde pour toujours la consigne qui l'a gouvernée : les
statistiques d'une journée sont celles de ce qui a été fait.

**Modifier, c'est remplacer en place.** Une date déjà sous consigne reçoit la
nouvelle ligne ; un créneau ajouté par la précédente est **conservé** — avec
ses sièges — quand une fenêtre l'exige encore exactement, retiré sinon.
L'aperçu dit le delta : stands entrants et sortants, créneaux à ajouter, à
retirer. **Prolonger** est le même appel avec des dates en plus. **Lever**
retire la consigne des dates choisies et supprime **toujours** les créneaux
ajoutés, avec leurs sièges : la publication suivante annonce le retrait depuis
l'instantané publié, qui nomme ces vacations par leur jour et leurs heures.
Le prototype avait envisagé de les laisser « fantômes » quand ils étaient
publiés ; la publication sachant déjà dire « vacation retirée », les garder
n'aurait servi qu'à encombrer la grille.

**Poser ou lever retire la validation de relecture**
([0039](0039-validation-de-relecture-distincte-du-verrou.md)) des journées
touchées, **verrou ou non** — là où une résolution la conserve sur une journée
verrouillée. Un verrou fige des sièges ; les sièges de la bande n'existent
plus, et la journée relue n'est plus celle qui sera travaillée. L'aperçu
l'annonce.

**Les journées types ignorent les créneaux ajoutés** : ni écart, ni
suppression. Une journée type ne nomme jamais une soirée qu'un arrêté a rendue
nécessaire, et l'appliquer ne doit pas détruire la compensation au milieu
d'une alerte. La carte affiche la date « sous consigne ».

### La pré-sélection et l'aperçu

Les stands **pré-cochés** sont ceux qui perdent des minutes dans la bande, sur
les créneaux du jour. Un stand qui a posé ses horaires **à la main** ce
jour-là — une exception datée — est écarté avec son motif, mais reste
cochable : quelqu'un a décidé quelque chose pour cette date, et la consigne
ne le contredit pas en silence. La liste est **recalculée à chaque
ouverture** du formulaire, jamais reprise par inertie : un stand fermé entre
temps pour une panne ne reste pas coché ; l'écran signale ce qui change par
rapport à la liste retenue.

L'**aperçu** vaut validation complète sans écriture, jour par jour : sièges et
minutes avant et après, créneaux à ajouter et à retirer, vacations qui perdent
leurs sièges, stands ouverts, entrants, sortants, exceptions cochées, mineurs
disponibles ce jour-là face aux majeurs, validation retirée, verrous et règles
ad hoc qui nomment une vacation de la bande, personnes du plan enregistré
assises dans la bande — l'ordre de grandeur de ce que la publication
annoncera. Poser renvoie le même aperçu, **calculé avant l'écriture** : une
fois les créneaux ajoutés, rien ne les distingue plus de la grille nominale.

### Ce qui en découle ailleurs

- **Statistiques.** Tout compte déjà les heures effectives. Chaque journée
  sous consigne expose ses indicateurs — sièges nominaux et sous consigne,
  minutes fermées, minutes rouvertes, animateurs concernés — et le KPI du
  plan gagne `journeesSousConsigne` et `heuresFermeesParConsigne`, `null` sur
  une mesure antérieure.
- **Instantanés.** Un instantané porte les consignes en vigueur à sa capture
  ([0007](0007-instantanes-contenu-denormalise.md) : il doit se lire sans le
  référentiel du moment, et rien ne disait *pourquoi* un après-midi n'avait
  pas ses heures). `null` sur un instantané antérieur veut dire « inconnu »,
  jamais « aucune » ; le comparateur signale `consignesDifferentes` et se tait
  dès qu'un côté ne sait pas.
- **Publication ordinaire.** Le diff par personne et la stabilité du plan
  publié font le travail : le titulaire d'un 14 h-20 h reste sur le 18 h-20 h
  pendant l'alerte et **retrouve son après-midi à la levée**, sans qu'on lui
  ait rien rendu à la main. Le courriel « planning publié » liste les
  « Journées aux horaires modifiés par décision de l'organisation » ; l'espace
  animateur et le jour J affichent un bandeau ; le PDF individuel imprime
  « horaires modifiés — motif » sous la date.
- **Journal.** `CONSIGNE_POSEE` et `CONSIGNE_LEVEE` changent ce qu'une
  résolution reçoit ; `PREREGLAGE_CONSIGNE_ENREGISTRE` et
  `PREREGLAGE_CONSIGNE_SUPPRIME` non. Les aperçus n'écrivent rien et ne sont
  pas journalisés.
- **Garde structurel.** Un appelant qui déroule les règles d'horaires lui-même
  et s'arrête là lit une journée nominale, et dote 12 h-18 h une date qu'un
  arrêté a fermée — en silence, exactement comme un prédicat `edition_id`
  oublié servait autrefois une autre édition. `StandService.resolve` est le
  point d'entrée unique (règles → exceptions → consignes), et
  `ConsigneCoucheStructurelleTest` refuse tout autre appelant du résolveur de
  règles qui n'est pas argumenté nommément : compactage, élagage, dérivation
  de grille, cohérence, lecture et export de scénario, et le calcul de la
  journée nominale de la consigne elle-même — tous raisonnent sur ce qu'un
  stand *déclare*, jamais sur les sièges qu'une résolution reçoit.

## Conséquences

- **Ce qui tient.** Rejoué de bout en bout — résolution, publication,
  consigne sur deux jours avec une soirée rouverte sur un stand, résolution
  incrémentale, publication, levée d'un jour — les créneaux nominaux gardent
  leurs ids ; personne n'est prévenu hors des dates sous consigne ; la soirée
  ajoutée est publiée, puis annoncée retirée à la levée ; et la règle de
  stabilité rend l'après-midi à son titulaire sans intervention.
- **Ce que cela coûte.** Six tables, une colonne sur `plan_snapshot`, une
  quatrième couche à connaître pour qui lit des fenêtres effectives — et le
  test structurel, qui est le prix de ne pas avoir à y penser. Une page et
  huit outils MCP de plus.
- **Limites assumées.**
  - **La bande est obligatoire.** Une consigne sans bande — ajouter une
    nocturne — n'est pas une consigne ; c'est une journée type.
  - **Une bande par jour.** Deux fermetures le même jour demandent une
    exception datée sur les stands concernés.
  - **Les disponibilités des animateurs restent à la journée.** L'aperçu
    compte les mineurs et les majeurs disponibles ce jour-là ; les fenêtres
    tardives se ferment aux mineurs par les règles de nuit et de repos, pas
    par une indisponibilité horaire, qui n'existe pas.
  - **La règle de nuit des mineurs lit le créneau entier**, pas la fenêtre
    effective ([`contraintes.md`](../contraintes.md#fenêtre-effective--ce-qui-la-lit-et-ce-qui-ne-la-lit-pas-délibérément)) :
    un créneau 18 h-23 h dont la consigne ne rouvre que 18 h-20 h reste
    interdit à un mineur. Délibéré : une règle de sécurité ne devient pas plus
    permissive par effet de bord.
  - **Une fenêtre du soir qui prolonge l'après-midi double le besoin en
    personnes.** Derrière un 14 h-20 h devenu 18 h-20 h, une compensation
    18 h-22 h ajoute un 20 h-22 h que la coupure repas du soir (19 h-21 h par
    défaut, dure) interdit d'enchaîner avec le 18 h-20 h : chaque siège du
    soir demande deux personnes distinctes. Mesuré sur l'édition de test
    (154 animateurs, 114 stands rouverts) : 120 à 129 sièges à 18 h-20 h plus
    56 à 20 h-22 h, soit 176 à 185 personnes pour 153 à 154 disponibles, et
    23 à 35 sièges de fin de soirée vides par jour — un plafond que 900 s de
    résolution ne franchissent pas. Le levier est un paramètre légal, pas un
    temps de repos : aligner la fenêtre du repas du soir sur toute la
    compensation — 18 h-22 h pour un soir rouvert de 18 h à 22 h — puisque
    les gens ont mangé pendant la bande. Un service qui commence à
    l'ouverture de la fenêtre ou finit à sa fermeture ne doit rien : plus
    personne ne doit de coupure un jour sous consigne, et les nocturnes
    ordinaires gardent la leur, leur trou 20 h-21 h restant dans la fenêtre.
    Les deux autres réglages essayés d'abord ne tiennent pas : repoussée à
    21 h-23 h, la fenêtre remplit le soir sous consigne mais rend les
    nocturnes infaisables (−720 dur) ; à 18 h-21 h, une personne du matin
    qui tient 18 h-22 h doit une coupure qu'elle ne peut pas prendre, et la
    semaine bute sur les six jours travaillés (922 jours-personnes sur 924 :
    −782 dur). L'autre levier est de rouvrir moins de stands le soir. Que la
    consigne porte elle-même sa fenêtre repas est une évolution possible, non
    retenue ici : le paramètre suffit et se voit. L'aide admin le dit sous « Consignes ». Faire lire à la
    coupure la bande fermée comme le repas pris est une évolution possible,
    non retenue ici : le paramètre suffit et se voit.
  - **Le diff de publication fait deux lignes** quand la bande retire aussi
    un relais : la personne lit sa vacation raccourcie et son relais
    « retiré », deux phrases pour une seule décision. Le motif dans la liste
    des journées modifiées est ce qui les relie.
- **Hors périmètre** : consigne sans bande, plusieurs bandes par jour,
  indisponibilités horaires par animateur, équité de rattrapage des heures
  perdues. Non vérifié : la faisabilité pratique du geste — consigne,
  résolution, publication — le soir même d'un arrêté, qui se mesurera à la
  première vague.

## Révisions

- **[0001 § 6 bis](0001-cloisonnement-par-edition.md)** : le cas canicule qui
  motivait le rituel de bascule est désormais une consigne dans l'édition ; la
  duplication reste pour une vraie variante.
- **[0033](0033-un-stand-qui-declare-ses-ouvertures-est-ferme-ailleurs.md)** :
  une quatrième couche au-dessus des trois, qui préserve l'invariant à un mode
  par jour.
