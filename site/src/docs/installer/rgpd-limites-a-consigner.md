# Consigner au registre ce que l'instance conserve

Votre registre doit décrire les mesures telles qu'elles sont, sans prétendre qu'elles sont complètes. Cette page passe en revue, traitement par traitement, ce que l'application conserve, ce qui borne chaque conservation et ce qui ne la borne pas ; reportez chaque point à votre registre sous cette forme. Les limites connues se consignent d'abord, les traitements plus larges (calcul déporté, rapport de conformité, contacts d'urgence) suivent.

## Aucune purge automatique

Aucune purge automatique n'existe : la durée annoncée est tenue à la main,
à date fixe (voir la procédure de purge de [`exploitation.md`](exploitation.md)). C'est l'écart le plus exposant, parce
qu'il porte sur un engagement public. Le choix est délibéré et son échéance
est connue (voir
[décision 0016](repo:docs/decisions/0016-purge-manuelle-avant-automatisation.md)) : la
tâche planifiée, son journal et le test de cascade viennent quand plusieurs
instances tournent en parallèle. Ce qui se consigne au registre n'est donc pas
« purge automatique : non », mais « purge manuelle, à date fixe, tracée au
[journal des purges](rgpd.md#tenir-le-journal-des-purges-et-des-restaurations) ».

## Les jetons d'accès dans les journaux du proxy

Les jetons d'accès voyagent dans le chemin de l'URL : ils atterrissent
tels quels dans les journaux d'accès du reverse proxy, qui doivent donc être
purgés ou écrits sans ces chemins (voir [ce qui reste à la charge du reverse proxy](securite.md#ce-qui-reste-à-la-charge-du-reverse-proxy)).

## L'abonnement à l'agenda

L'abonnement au calendrier expose un planning nominatif de façon durable et
sans second facteur, et c'est le point le plus exposant de ce document
après la purge manuelle. Il se consigne au registre tel quel, avec ce qui le
borne et ce qui ne le borne pas.

Ce qu'il est : une adresse permanente qui, à elle seule, sert le planning
publié d'une personne nommée (ses vacations, ses stands, ses coéquipiers)
à qui la détient. **Elle est communiquée volontairement à un tiers** : le
fournisseur de l'application d'agenda de l'animateur (Google, Apple,
Microsoft, un serveur CalDAV…), qui la rappellera plusieurs fois par jour et
en conservera le contenu selon ses propres règles. Ce destinataire-là n'est
**pas** un sous-traitant de l'hébergeur : il est choisi par la personne
concernée, pour son propre compte. C'est pourquoi
l'abonnement doit rester un geste explicite de sa part, jamais un envoi
automatique.

Ce qui le borne : le jeton est distinct de celui de l'espace et n'ouvre
**qu'un document en lecture** (ni échanges, ni disponibilités, ni
trombinoscope, ni écriture) ; il fait 122 bits d'aléa ; il est **révocable
par la personne concernée elle-même**, depuis son espace, sans passer par
l'organisation ; et il disparaît avec sa fiche ou son édition. Le contenu
n'est jamais que le plan **publié** : il n'annonce rien que la personne n'ait
déjà reçu.

Ce qui ne le borne pas : rien n'expire, rien ne compte les accès, rien ne
distingue le propriétaire d'un tiers qui aurait récupéré l'adresse. Une
adresse d'abonnement qui a fuité reste exploitable **jusqu'à ce que
quelqu'un la remplace**, et la seule personne en position de s'en rendre
compte est celle à qui elle appartient. Le bouton de
remplacement, dans l'espace, est donc une mesure de sécurité : il se mentionne
au registre à ce titre, et l'invitation à s'en servir fait partie de ce qu'on
explique aux animateurs.

## L'affichage mural

L'affichage mural montre des noms à quiconque passe devant l'écran : qui
tient quel stand, maintenant et ensuite, pour la journée du jour. C'est son
objet, et il se consigne comme tel.

Ce qui le borne : par défaut, le **prénom et l'initiale du nom** (le nom
complet est une option choisie par l'organisation lien par lien) ; ni
téléphone, ni adresse, ni âge, ni motif d'absence ; seulement la journée en
cours, et seulement les emplacements du lien s'il en est restreint. Le jeton
n'ouvre que cette lecture, il est **haché** en base, **révocable** depuis les
Paramètres, rattaché à une édition et purgé avec elle ; il ne voyage ni dans
l'export SQL ni dans l'archive de fin d'événement, et pas davantage chez les
outils d'observabilité : la mesure d'audience n'est pas chargée sur l'écran,
et le suivi d'erreurs le reçoit masqué, comme le jeton d'espace.

Ce qui ne le borne pas : l'écran lui-même. Une adresse photographiée sur la TV
ou relevée dans un journal d'accès montre la même chose ailleurs, jusqu'à sa
révocation ; la date du dernier accès, dans la liste des liens, est ce qui
permet de le remarquer. Si l'écran est visible du public, la politique de
confidentialité de l'instance doit le mentionner.

## Le numéro de téléphone

Le numéro de téléphone est une donnée de l'organisation, pas un canal :
facultatif, saisi sur la fiche ou importé d'une colonne du fichier
d'animateurs, il sert à une chose : appeler un remplaçant le jour J. Il est
affiché sur la fiche et sur l'écran Aujourd'hui (session d'administration),
et **nulle part ailleurs** : ni sur l'affichage mural, ni dans un planning
PDF, un flux ICS ou un export CSV de référentiel, ni par MCP : le test de
confidentialité MCP refuse toute vue qui le porterait. Il voyage en revanche
avec la fiche là où la fiche voyage entière : l'export SQL, les sauvegardes
et le scénario YAML de l'édition, qui se traitent déjà avec les égards d'un
dump. Aucun SMS n'est envoyé par l'application.

## Les sauvegardes

Les sauvegardes sont un lieu de stockage à part entière : depuis qu'elles
sont automatiques, un dump complet, mineurs et jetons compris, existe en
permanence sur le volume. L'export SQL de l'application est plus étroit (il
laisse dehors les accès et sessions d'espace, le journal et l'horloge) mais
il porte désormais les déclarations, les confirmations et les destinataires de
publication, adresse comprise ; il se traite avec les mêmes égards. Le registre doit le dire, et l'entrée n'est
honnête que si l'externalisation chiffrée annoncée au socle existe
réellement.

## L'archive de fin d'événement

L'archive de fin d'événement sort une édition entière en un geste :
depuis l'écran Fichiers (onglet Archive), un seul ZIP peut réunir le planning global, les CSV
Équité et Heures, les six référentiels (animateurs compris, avec leurs dates
de naissance et leurs adresses), le scénario YAML de l'édition, la relecture
de publication et les plannings individuels, mineurs compris. Une fois
téléchargée, elle **échappe à toute conservation de l'application** : ni
`JOURNAL_RETENTION`, ni la [purge annuelle](rgpd.md#tenir-le-journal-des-purges-et-des-restaurations) ne l'atteignent, et sa garde
comme sa suppression reviennent à qui la détient. Trois bornes se consignent
telles quelles : elle ne couvre **que l'édition** de l'onglet (jamais le dump
SQL, qui emporte toutes les éditions et reste sur l'écran Paramètres) ; elle
ne contient **aucun jeton** : ni celui de l'espace, que les plannings
individuels de l'archive n'impriment pas (ni lien ni QR code), ni celui de
l'abonnement au calendrier, ce qu'un test vérifie ; et son manifeste
`LISEZMOI.txt` rappelle ce qu'elle contient, le responsable de traitement et
la durée de conservation déclarés (`LEGAL_RESPONSABLE_TRAITEMENT`,
`LEGAL_CONSERVATION`). C'est le geste naturel avant la purge annuelle d'une
édition terminée ; le registre le mentionne comme une **sortie de données**,
journalisée comme les autres.

## Les demandes de covoiturage

Une demande de covoiturage vit dans `declaration_coequipier` : les
identifiants des coéquipiers nommés et le statut, en attente ou validée.
L'organisation qui l'écarte, ou qui annule l'arrivée groupée validée, la
**supprime** : il ne reste que la ligne du journal des actions, qui ne garde
que l'identifiant de la demande. La table porte une clé étrangère sur le
demandeur : sa fiche supprimée emporte ses demandes.

**L'archive de la 3.x.** En 3.x, une demande écartée ou annulée restait en
base, avec un **motif en champ libre écrit par l'administrateur à
l'intention de l'animateur**. La migration de la 4.0 (`V142`) ne l'efface
pas : elle déplace ces demandes, motif compris, dans
`declaration_coequipier_avant_4_0`. C'est un texte sur une personne, tapé par
une autre, que l'application ne lit plus, ne montre plus et n'exporte pas (ni
export SQL, ni outils MCP) ; il reste dans le dump nocturne, comme toute la
base. **Durée de conservation : jusqu'à la 5.0, qui supprime la table.**
D'ici là, l'archive part avec l'édition (purge annuelle) ou avec la fiche du
demandeur, mêmes clés étrangères que la table d'origine. Vous pouvez la
supprimer plus tôt, une fois la 4.0 en place et le retour à la 3.x exclu
([purger et conserver](conservation-des-donnees.md#larchive-du-covoiturage-3x)).
Consignez-la au registre avec cette durée.

## Les déclarations de disponibilités

Une déclaration de disponibilités traitée survit à sa décision : appliquée
ou refusée, elle reste en base pour que l'organisation puisse dire *pourquoi*
la fiche de quelqu'un affirme ce qu'elle affirme, et pour que l'animateur
relise dans son espace ce qui lui a été répondu. Elle n'a **aucune purge
propre** : elle disparaît avec son édition, en cascade sur `edition_id`,
c'est-à-dire à la [purge annuelle](rgpd.md#tenir-le-journal-des-purges-et-des-restaurations). La durée de conservation proposée est
donc **celle de l'édition, pas davantage**, et elle se consigne telle quelle
au registre. Deux choses la rendent moins exposante que le reste : la table ne
porte **aucune donnée nouvelle** (des dates, des identifiants de typologie et
un mot libre, soit les mêmes catégories que la fiche animateur), et une seule
proposition en attente existe par personne. Le mot libre reste le point
sensible : c'est le seul champ où quelqu'un peut écrire une raison de santé
ou de famille que personne ne lui a demandée, et il part tel quel dans le dump
nocturne **et, depuis que l'export SQL de l'application emporte
`declaration_disponibilite`, dans le fichier qu'un administrateur télécharge
depuis l'écran Paramètres**. C'est la même donnée, dans la même finalité, mais
par une seconde voie et sous une forme qui quitte la machine à la main : le
registre la consigne comme telle, et la consigne de la rubrique sauvegarde de [`exploitation.md`](exploitation.md) sur le
traitement d'un dump vaut mot pour mot pour celui-ci. Le choix est fait de le conserver plutôt que de le supprimer ou
d'en borner le contenu : un animateur qui explique son indisponibilité aide
l'organisation à décider, et un champ contraint le pousserait à écrire la même
chose ailleurs. Il est donc consigné au registre **comme donnée en champ
libre**, et c'est à ce titre qu'il est traité : rien n'y est
demandé, exigé ni exploité au-delà de la décision qu'il éclaire.

## Les envois automatiques de nuit

Les envois automatiques de nuit écrivent sans qu'un humain relise : le
rappel de la veille, la relance de confirmation et la relance de la collecte
des disponibilités partent d'une tâche planifiée, vers des adresses
d'animateurs, mineurs compris. Trois bornes sont
posées et se consignent telles quelles : rien ne part que de l'**édition
active**, qu'un administrateur désigne explicitement (toute édition naît
inactive, et l'espace, le flux ICS et l'affichage mural d'une édition
inactive sont fermés) ; le
rappel ne dit que ce qui a **déjà été publié**, donc il n'annonce jamais rien
de neuf ; et le journal `notification_planifiee` ne stocke **ni nom ni
adresse**, seulement un identifiant d'animateur ; l'identité est jointe à la
lecture depuis le référentiel, si bien qu'une fiche supprimée laisse une
alerte qui ne nomme plus personne. Ce journal n'a pas de purge propre : il
disparaît avec son édition, en cascade sur `edition_id`, donc à la purge
annuelle.

## Les empêchements signalés

Un empêchement signalé depuis l'espace (`signalement_absence`) dit
qu'une personne ne sera pas là un jour donné, ou sur un poste. Il ne porte
**aucun texte libre** : le motif, facultatif, est une liste fermée (raison
personnelle, transport, autre), choisie précisément pour qu'aucune raison
de santé ou de famille n'y soit jamais écrite. Le courriel qui prévient
l'organisation nomme la personne, la journée, le poste et ce motif, comme la
notification d'une déclaration de disponibilités. Le courriel qui répond à
l'animateur, quand l'organisation tranche, ne porte que la journée et le
poste (pas le motif) et part à sa propre adresse. La ligne suit la fiche, le
créneau et le stand qu'elle nomme (suppression en cascade) et disparaît avec
l'édition.

## L'état de chaque envoi du planning

L'état de chaque envoi du planning est conservé (`envoi_planning`) :
pour chaque publication et chaque renvoi, une ligne par personne qui dit si
le courriel est parti, a échoué, n'a pas pu partir faute d'adresse ou a été
différé. Des **dates et des états seulement** : ni le contenu du message
(qui vit déjà dans `publication_destinataire`), ni l'adresse, ni le message
d'erreur du serveur de messagerie (il cite volontiers l'adresse refusée),
remplacé par une cause courte (adresse refusée, boîte pleine, serveur
injoignable, autre). Comme la trace des publications, la ligne survit à la
fiche qu'elle concerne, car c'est une preuve d'envoi, et cascade avec son
instantané et son édition, donc disparaît au plus tard à la purge annuelle.

## Le résultat de chaque courriel

Le résultat de chaque courriel adressé à un animateur est conservé
(`envoi_mail`), pas seulement le planning : code d'accès, invitation à
déclarer, relances, rappel de la veille, échanges, covoiturage, absences.
C'est ce qui distingue, sur la page Animateurs, un échec d'envoi d'un
silence, et ce qui empêche les relances de réécrire à une adresse que le
relais a refusée. La ligne porte **un identifiant d'animateur, le nom du
modèle de courriel, « parti » ou « en échec », une catégorie d'échec courte et
une date**, sans adresse, ni contenu, ni réponse du serveur de messagerie. Ce
n'est **pas un suivi de lecture** : aucun pixel, aucun lien traqué, « parti »
veut dire remis au relais ; la confirmation reste le geste de l'animateur. Ce
qui lève l'interdiction de relancer une adresse refusée est une modification
de la fiche, n'importe laquelle : aucune empreinte de l'adresse n'est stockée
pour savoir si c'est elle qui a changé. Les mineurs n'y gagnent aucune donnée
de plus qu'un majeur. La ligne a **la purge de l'historique des actions**
(`JOURNAL_RETENTION`, appliquée chaque nuit) et cascade avec son édition ;
elle survit à la fiche jusque-là, comme `notification_planifiee`, et ne
nomme alors plus personne.

## Le responsable de stand

Le responsable de stand (droit `RESPONSABLE_STAND`) est une catégorie de
destinataires de plus du planning publié, et c'est sa **minimisation** qui
la rend tenable. Par défaut il lit **les horaires et les places à pourvoir**
de ses stands, **sans aucun nom**. Un administrateur peut y ajouter le
**prénom et le nom** des animateurs prévus (réglage par défaut de l'édition,
surchargeable stand par stand dans les deux sens, chacun avec une date
d'ouverture facultative avant laquelle seuls les horaires s'affichent),
quand le planning est stabilisé et qu'il faut que le responsable sache qui
accueillir. **Jamais** d'adresse, de téléphone, de date de naissance, d'âge ni
de statut mineur, quel que soit le réglage. Le périmètre est borné trois fois :
les stands de son droit, l'**édition active** seulement (aucune lecture d'une
édition passée ou à venir), le plan **publié**. L'affichette imprimable suit
exactement le même réglage ; une fois imprimée elle sort du périmètre de
l'application, comme tout papier. Les réglages (`reglage_noms_responsable`,
`reglage_noms_stand`) ne portent aucune donnée personnelle ; ils reviennent
au défaut sûr (horaires seuls) au vidage de l'édition et dans une édition
dupliquée. Le périmètre d'un droit (`habilitation_stand`) suit `habilitation`,
hors édition, hors export SQL ; le vidage ou la suppression de l'édition
retire les droits de ses responsables, et un stand supprimé ne laisse qu'un
droit qui n'ouvre plus rien. Le plan lu est la **publication entière** en vigueur : une publication
différée (ou partielle) ne se découpe pas par stand, le responsable voit donc
ce que l'équipe a reçu, pas un état intermédiaire du plan de travail. Un nom
n'est jamais rendu à un responsable pour un stand hors de son droit, y compris celui d'une personne qui y travaille aussi
par ailleurs. À mentionner au registre : une catégorie de destinataires de plus,
internes à l'organisation, désignés par l'organisateur.

## Les comptes des organisateurs

Les comptes des organisateurs (`compte`, `habilitation`) gardent une
**adresse e-mail**, un nom facultatif, les dates de création, de dernière
connexion et de désactivation, les droits accordés (rôle, édition,
expiration) et, pour chaque désactivation ou retrait, l'**identifiant du
compte** qui l'a fait. Un compte **local** garde en plus un **hachage bcrypt**
de son mot de passe (jamais le mot de passe) et sa date de choix ; les liens
d'invitation ou de réinitialisation (`compte_jeton`, 72 h ou 1 h, un seul par
compte, supprimés à l'usage) et les défis de connexion (`compte_defi`, dix
minutes, avec le hachage du code reçu par mail) ne sont stockés que hachés, et
les lignes périmées sont retirées à l'écriture suivante ; aucune de ces tables
n'est dans l'export SQL. Le mail du lien et celui du code passent par le
relais SMTP de l'instance (sous-traitant déjà déclaré). Ce sont les données des
**personnes qui administrent l'instance**, pas celles des animateurs. L'application ne supprime rien : un
compte se désactive, un droit se retire, pour que le journal garde un sens.
**Effacement** : à la demande d'une personne, ou à l'expiration de la durée
ci-dessous, l'exploitant **pseudonymise** la ligne plutôt que de la
supprimer : l'adresse devient un identifiant opaque (`efface-<id>@invalide`),
le nom est vidé, `mot_de_passe_hash` et `mot_de_passe_le` passent à `NULL` et
les lignes de `compte_jeton` et `compte_defi` du compte sont supprimées. La ligne, ses droits datés et les références
du journal restent, sans désigner personne ; c'est une opération SQL, jamais un
`DELETE`. **Conservation** : un compte actif est gardé tant qu'il sert ; un
compte désactivé l'est **deux ans** après sa désactivation (le temps de
répondre à « qui avait accès ? » pour une saison), puis pseudonymisé. Tables
de l'instance, hors édition, **absentes de l'export SQL** (une restauration
ne transporte pas les comptes : restaurer ailleurs ouvrirait l'instance à des
inconnus, et ne rendrait pas non plus les droits de celle qui reçoit).
L'historique des actions les désigne par **identifiant de compte**, jamais
par adresse ni par nom.

## Le journal des connexions administrateur

Le journal des connexions administrateur (`journal_connexion`) garde,
pour chaque tentative sur le formulaire de connexion (réussie, échouée, ou
le verrouillage qu'elle déclenche), **l'horodatage et l'adresse IP du
client**, et rien d'autre : ni le mot de passe, ni l'identifiant saisis. Il
vise la sécurité de l'accès aux données des animateurs (« quelqu'un a-t-il
forcé le mot de passe ? »). Table de l'instance, hors édition ; elle a **la
purge de l'historique des actions** (`JOURNAL_RETENTION`) et ne voyage pas
dans l'export SQL.

## L'historique des actions

L'historique des actions (`journal_action`) trace qui a fait quoi, et
c'est un traitement à consigner comme tel. Il suit la même règle que le
journal ci-dessus, et deux de plus. Il ne stocke **ni nom, ni adresse, ni
date de naissance** : un identifiant d'animateur au plus, l'identité étant
jointe à la lecture. Il ne stocke **aucune valeur de champ** : une
modification y laisse les *noms* des champs qui ont bougé (« nom, email »),
jamais ce qu'ils sont devenus, ce qui suffit à retracer un geste sans
recopier la donnée. Et il porte, lui, **une purge propre** :
`JOURNAL_RETENTION`, quatre-vingt-dix jours par défaut, appliquée chaque nuit,
parce qu'un journal que personne ne relit deviendrait sinon un stockage de
plus, conservé sans limite et repris dans chaque sauvegarde. Il cascade en
outre avec son édition, donc disparaît au plus tard à la purge annuelle.

Il trace aussi **les sorties de données**, et c'est ce qui permet de répondre,
pendant toute la rétention, à « qui a sorti la liste des animateurs, mineurs
compris, et quand ? » : chaque export de l'administration (PDF, archives,
CSV nominatifs, référentiels, scénario YAML, dump de la base) et chaque
téléchargement de son planning (PDF ou ICS) par un animateur depuis son
espace laisse une ligne (refus compris côté administration), sous la même règle et **la même
rétention** `JOURNAL_RETENTION` pour la table vivante ; les lignes
voyagent aussi dans les sauvegardes de nuit (`BACKUP_RETENTION`) et dans
tout dump exporté, qui les gardent à leur propre rythme. Rien de plus n'y
entre : l'action, la date, l'auteur, le code HTTP et, pour un téléchargement
d'espace, l'identifiant de l'animateur ; pour l'archive des référentiels,
les noms des référentiels emportés ; pour l'archive de fin d'événement, les
noms des parties qu'elle portait ; jamais le contenu du fichier. Un
téléchargement d'espace **refusé avant toute preuve d'identité** (lien
inconnu, ou code reçu par e-mail jamais saisi) n'écrit **rien** : détenir le
lien ne prouve pas qu'on est l'animateur, et l'inscrire à son nom, souvent
celui d'un mineur, lui imputerait la tentative d'un autre, et une lecture
que n'importe qui peut répéter ne doit pas permettre de remplir la table.
Plus largement, l'animateur n'est l'**auteur** d'une ligne que si l'appel l'a
prouvé (session ouverte par le code e-mail) : une demande de code, un code
erroné ou une écriture refusée faute de session sont inscrits au nom
d'`ANONYME`, l'animateur restant nommé comme **cible**. L'auteur côté administration
reste « Administration » : l'application n'a qu'un compte, elle ne peut pas
dire laquelle des personnes qui le partagent a téléchargé. Seuls échappent à
la trace, avec leur motif dans `CatalogueActions`, les fichiers d'exemple
(qui ne portent personne) et **l'abonnement au calendrier** : l'agenda le
relit seul toutes les quelques heures, une ligne par relecture serait du
bruit, pas une trace, et rien ne compte ces relectures, comme le dit la rubrique sur l'agenda ci-dessus.

## L'import CSV des animateurs

L'import CSV des animateurs fait entrer des données personnelles par un
fichier que l'exploitant tient lui-même, et c'est une entrée à consigner :
ce tableur porte des noms, des dates de naissance (donc l'information qui
désigne les **mineurs**) et souvent des adresses e-mail. Ce que l'application
garantit de son côté : le fichier est reçu dans le corps de la requête, lu en
mémoire et **jamais écrit sur disque** (`service/backup/` reste le seul
endroit où l'application écrit), ni conservé entre les deux appels : la
prévisualisation et l'écriture le reçoivent chacune, et la mémoire est rendue
à la fin de la requête. Ce qu'elle ne peut pas garantir : le **fichier
source**, qui reste sur le poste de l'organisateur, dans sa messagerie et
dans ses sauvegardes. C'est cette copie-là qui se consigne au registre, avec
la consigne de la supprimer après import.

## Le journal du navigateur

Le navigateur de l'organisateur garde un journal de ce qu'on lui a
affiché, et c'est un lieu de stockage de plus, hors de la base et hors des
sauvegardes. Chaque bulle d'information est recopiée dans un journal de 200
entrées tenu dans le `localStorage` du profil, cloisonné par édition mais
**non effacé à la déconnexion** et relisible depuis les *Messages récents* de l'accueil
par quiconque rouvre ce profil, typiquement un poste partagé de régie. Deux
bornes sont posées et se consignent telles quelles : les messages
d'avertissement de saisie **nomment un animateur par son identifiant seul**,
jamais par ses nom et prénom ni par sa date de naissance ; et la phrase qui
dit qu'une personne est **mineure** (la seule qui, en nommant le jour de ses
18 ans, laisserait recalculer sa date de naissance) est **affichée sans être
journalisée**. Ce qui ne le borne pas : le journal n'expire pas de lui-même,
il se vide au bouton depuis les *Messages récents* de l'accueil, et rien n'empêche un
autre message de l'application d'y écrire un nom. La consigne d'exploitation
qui va avec est celle d'un poste partagé : vider le journal, ou fermer la
session du navigateur, en quittant le poste.

## Les brouillons de formulaires

Les formulaires longs gardent un brouillon de la saisie en cours dans le
navigateur, et c'est un autre lieu de stockage hors de la base : la fiche
animateur, la fiche stand et la consigne enregistrent, une seconde après la
dernière frappe, ce qui n'a pas encore été enregistré, pour le proposer à la
réouverture après un rechargement, une session expirée ou un onglet fermé.
Les bornes posées se consignent telles quelles : le brouillon d'une **fiche
animateur** (identité, date de naissance, donc statut de mineur, adresse
électronique) n'est écrit que dans le **`sessionStorage`**, qui meurt avec
l'onglet, jamais dans le `localStorage` ; ceux du stand et de la consigne,
qui ne portent aucune donnée personnelle, vont dans le `localStorage`. Chaque
brouillon est cloisonné par édition et par fiche (un au plus), **effacé** à
l'enregistrement, à « Ignorer », à l'abandon confirmé du formulaire et au
bouton *Se déconnecter* (tous les brouillons, toutes éditions), et purgé
**au-delà de 24 h** à l'ouverture de l'interface ; celui d'une fiche
supprimée entre-temps est effacé sans être reproposé, avec un message qui
la désigne par son identifiant. Le `sessionStorage` d'un onglet n'étant
lisible que par lui, la déconnexion est **annoncée aux autres onglets** du
navigateur (canal de diffusion, ou à défaut un événement de stockage sur une
clé qui ne porte aucune donnée) : chacun efface alors ses brouillons de
fiche animateur. La purge fait foi : un formulaire ouvert avant elle
n'écrit plus aucun brouillon, pas même la sauvegarde en attente que la
navigation vers la page de connexion déclencherait en le fermant. Une
session **expirée** ne les efface pas, et c'est voulu : reprendre une saisie
après l'expiration est la raison d'être du brouillon, et celui d'une fiche
animateur reste dans le `sessionStorage` de l'onglet, que nul autre ne lit.
Aucun brouillon n'est envoyé au serveur, journalisé, exporté ni exposé au
MCP. Ce qui ne le borne pas : un onglet fermé sans déconnexion emporte son
`sessionStorage`, mais un onglet resté ouvert, session expirée comprise,
garde la fiche en cours jusqu'à la prochaine déconnexion ou purge des
24 h ; et un
navigateur qui refuse les deux mécanismes de diffusion ne purge que l'onglet
qui se déconnecte.

## Les copies avant vidage

L'édition est recopiée avant chaque vidage, et ces copies ne sont pas
purgées (`sauvegarde_avant_vidage`,
[décision 0084](repo:docs/decisions/0084-sauvegarder-l-edition-avant-de-la-vider.md)) :
une réinitialisation, un import de scénario ou la restauration d'une
sauvegarde range d'abord l'export de scénario de l'édition (nom, prénom,
date de naissance, adresse et téléphone de chaque animateur compris) et son
planning (sièges et verrous, par identifiants seulement). **Une copie par
vidage (sauf quand l'état est exactement celui de la copie précédente),
conservée aussi longtemps que l'édition** : la seule purge est la
suppression de l'édition, en cascade sur `edition_id`, donc la purge annuelle
du [journal des purges](rgpd.md#tenir-le-journal-des-purges-et-des-restaurations) ; la durée de conservation de ces copies est donc **celle de
l'édition**, et elle se consigne telle quelle au registre. Un administrateur
peut en supprimer une (`DELETE /api/planning/sauvegardes-avant-vidage/{id}`,
journalisé). Elles ne partent ni dans l'export SQL ni par MCP ; seul un
administrateur en liste, en télécharge, en restaure ou en supprime une, par
l'API ; le téléchargement, la restauration et la suppression sont
journalisés, la simple liste (dates et origines, aucune donnée de personne)
ne l'est pas. Ce qui ne les borne pas : une fiche
supprimée reste dans les sauvegardes prises avant sa suppression, et
restaurer l'une d'elles la fait revenir, d'où le point suivant.

## L'effacement demandé par un animateur

L'effacement demandé par un animateur sur une édition encore active n'a
pas de procédure outillée : c'est une suppression manuelle de sa fiche, **puis
de chaque sauvegarde avant vidage de son édition qui la porte**, par
`DELETE /api/planning/sauvegardes-avant-vidage/{id}` (le plus simple : toutes
celles prises avant la suppression de la fiche). Une
demande d'effacement ne se refuse pas au motif que l'événement n'est pas
terminé, et automatiser la purge par édition échue ne le réglera pas : c'est
une opération distincte.

## La duplication d'une édition

Dupliquer une édition recopie ses personnes, et c'est voulu pour l'usage
qui a fait naître la duplication : l'édition « plan canicule » créée en cours
de festival doit garder son équipe et son « Envoyer à all ».
L'usage *modèle d'une année sur l'autre* est l'inverse : préparer 2027 depuis
2026 recopierait noms, dates de naissance et courriels de personnes qui ne se
sont pas réinscrites, c'est-à-dire ferait démarrer une nouvelle durée de
conservation sur des données que plus rien ne justifie de détenir. La
duplication accepte donc `avecAnimateurs=false` : la structure
passe, les personnes restent, et avec elles compétences, indisponibilités,
souhaits et ajustements manuels. Ce qui ne le borne pas : rien n'oblige à
choisir ce mode, et une duplication faite avec les gens par habitude produit
exactement la copie qu'on voulait éviter : c'est une case à cocher, pas un
garde-fou.

## Le calcul déporté envoie un problème pseudonymisé

Quand l'instance résout sur une machine louée
([`exploitation.md`](exploitation.md), rubrique « Résoudre sur une autre machine »), le
problème quitte l'application le temps du calcul. **Ne partent jamais** : nom,
prénom, adresse électronique, téléphone, jetons, textes libres. **Partent** :
les compétences, souhaits et jours d'indisponibilité de chaque animateur, sous
un **identifiant tiré au hasard à chaque résolution**, et une date de
naissance **de substitution** de la même tranche d'âge (moins de 16 ans, 16-17
ans, majeur), et la date réelle seulement pour qui franchit 16 ou 18 ans pendant
l'événement, parce que la règle change alors de réponse en cours de route.

Cela reste une donnée personnelle : des disponibilités rattachées à une
tranche d'âge, que la table de correspondance (gardée dans la base de
l'application, le temps du calcul) permet de rattacher à une personne. À
consigner : l'hébergeur de la machine de calcul comme **sous-traitant
ultérieur**, la région choisie, et le fait que la machine ne garde rien sur
disque et est détruite après usage. Un worker installé sur le site même ne
change rien au registre.

## Le rapport de conformité nomme les personnes planifiées

Le rapport de conformité de l'inspection du travail (onglet *Conformité* du
Diagnostic) liste, par nom, les heures, les plafonds applicables, les écarts aux
règles légales et le statut de mineur de chaque personne planifiée. Il ne porte
ni date de naissance, ni adresse, ni coordonnées. Il n'est lisible que des
administrateurs et des comptes titulaires d'un droit `RH` sur l'édition ;
**chaque consultation et chaque export est écrit dans le journal des actions**
(`CONFORMITE_CONSULTEE`, `EXPORT_CONFORMITE_PDF`, `EXPORT_CONFORMITE_CSV`), qui
suit la purge de conservation du journal. Le PDF et le CSV téléchargés sortent
de l'application : leur conservation est celle de l'organisation qui les
produit, et ils ne sont jamais exposés par MCP.

## Le contact d'urgence et le représentant légal : un tiers, une table à part

Un animateur (ou son représentant légal) peut déposer le nom et le téléphone de la
personne à prévenir en cas de malaise, et, pour un mineur, de son représentant
légal. Ce sont des **données d'un tiers qui n'a rien demandé** : la politique de
confidentialité l'annonce, avec sa finalité (la sécurité des personnes pendant
l'événement).

| Question | Réponse |
| --- | --- |
| Où ça vit | Table `animateur_contact`, à part de la fiche : l'animateur est lu par le scénario, les exports, le MCP et l'espace des collègues, et rien de tout cela ne doit en porter une ligne |
| Qui lit | Les administrateurs et le droit `RH` de l'édition ; l'animateur lit et modifie les siens dans son espace (*Mes contacts*) |
| Jamais dans | Scénario YAML, CSV et PDF d'export, ICS, archive, mails, MCP, espace d'un collègue, responsable de stand, liste des fiches |
| Journal | Chaque lecture et chaque modification par l'organisation (`CONTACTS_CONSULTES`, `CONTACTS_MODIFIES`) et chaque modification par l'animateur (`CONTACTS_MODIFIES_PAR_ANIMATEUR`) : des identifiants, jamais une valeur |
| Mineur sans représentant | Facultatif : un avertissement à la saisie (par identifiant, jamais l'identité ; il ne va pas dans le journal des notifications du navigateur) et une ligne dans le rapport de conformité |
| Conservation | La fiche supprimée emporte ses contacts (clé étrangère en cascade) ; la purge de conservation de l'édition fait de même. Une **duplication d'édition ne les recopie pas** : une année nouvelle demande de nouveau, sans reporter le numéro d'un tiers d'une édition à l'autre. Un import de scénario garde les contacts des fiches qu'il conserve et emporte ceux des fiches qu'il retire ; une réinitialisation de l'édition les efface tous, et sa restauration ne les ramène pas (ils ne figurent pas dans le scénario sauvegardé) |
| Sauvegarde | La sauvegarde nocturne `pg_dump` les contient, comme le reste de la base ; elle n'est jamais téléchargeable. L'export SQL de l'outillage de test ne les emporte pas (`DELIBERATELY_NOT_DUMPED`) |
