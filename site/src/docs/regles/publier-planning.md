# Publier le planning

Vous envoyez à chaque animateur son planning, vous voyez qui l'a reçu et confirmé, et vous relancez ceux qui ne répondent pas.

![L'écran Diffuser](/images/fr/diffuser.webp "L'écran Diffuser : qui a reçu quelle version")

Prérequis : l'édition doit être **l'édition active** (voir [Enchaîner les éditions](enchainer-les-editions.md#activer-une-édition)), et le serveur de messagerie doit être configuré par l'hébergeur. Tout se passe dans *Diffuser* (groupe *Diffuser*), onglets *Envoyer* et *Documents*.

## Publier et prévenir

La publication envoie leur planning et le lien de leur espace aux seules personnes dont l'emploi du temps a changé, en leur disant ce qui change. Quand une vacation disparaît de la grille après coup, elle se dit « retirée » au lieu de s'effacer en silence de leur espace.

### Relire avant d'envoyer

Avant de publier, vous voyez une ligne par personne à prévenir : ce qui change pour elle, sa dernière confirmation. Vous pouvez :

- trier par ampleur et replier les changements mineurs ;
- exporter le détail en CSV.

Toutes les personnes de la liste reçoivent leur planning : on ne peut plus en retirer une de la publication. Pour appeler quelqu'un d'abord, passez par « Prévenir seulement quelques personnes » ci-dessous.

Le panneau de publication dit aussi combien de journées non relues partiraient.

### Prévenir seulement quelques personnes

Après un échange accepté ou un remplacement le jour J, « Prévenir les N personnes » publie pour ces personnes seulement ; les autres restent à prévenir à la publication suivante.

## Suivre qui a reçu quelle version

Sur l'écran Diffuser, une table permanente couvre toute l'édition. Elle montre, pour chacun :

- la version reçue ;
- l'état de l'envoi : envoyé, échec avec sa cause, sans e-mail, différé ;
- le rappel de la veille, la relance et l'accusé de réception.

Chaque ligne offre « Renvoyer son planning » et « Relancer ». Des filtres existent par état et par jour (« ce qui change le 06/09 », compté comme dans la Journée). Un envoi en échec se voit et empêche l'accueil de dire « à jour ». Les documents à imprimer disent chacun pour qui ils sont.

### Quand un envoi échoue

Un courriel que le serveur de messagerie a refusé ne se confond pas avec un silence. La colonne d'accusé de réception dit « échec d'envoi », avec la date et la raison : serveur injoignable, identifiants refusés, adresse refusée, refus temporaire. La synthèse le compte à part, un filtre de la page Animateurs les rassemble et la fiche animateur le signale jusqu'à ce que vous la modifiiez.

- Une adresse refusée n'est plus relancée, ni la nuit ni à la main, tant que la fiche n'a pas été corrigée.
- Un rappel ou une relance de nuit qui échoue laisse une alerte sur l'accueil et ne compte pas comme une relance.
- C'est un état d'envoi, pas un suivi de lecture. Un serveur qui n'envoie rien pour de vrai le dit « simulé ».

## Les accusés de réception

Sur le planning publié, l'animateur dispose d'un bouton « J'ai lu et je serai là ». Côté organisation, une colonne affiche confirmé, relancé ou silencieux, avec la synthèse en tête de page et la date de la dernière publication. Republier ne redemande une confirmation qu'aux personnes dont l'emploi du temps a réellement changé.

### Relancer ceux qui n'ont pas confirmé

1. Ouvrez *Animateurs* et appliquez un des deux filtres (portés par l'adresse de la page) : « jamais confirmés » ou « silencieux depuis N jours ». Chacun peut se restreindre aux jamais relancés.
2. Lancez l'action groupée « Relancer maintenant » : elle envoie le même rappel que la nuit, sans l'attendre.
3. Lisez le compte rendu : il nomme ceux qui n'ont pas été écrits et pourquoi.

Il n'y a qu'une relance par personne et par publication, que la nuit ou vous l'ayez envoyée.

### Les rappels automatiques de la nuit

- La veille au soir, chacun reçoit la liste de ses postes du lendemain.
- Les silencieux sont relancés une fois, sauf si vous l'avez déjà fait à la main.
- Les demandes d'échange qui dorment remontent à l'organisation.

Rien ne part d'une édition qui ne l'a pas explicitement demandé, et personne ne reçoit deux fois le même message.

## Envoyer les documents

L'onglet *Documents* regroupe ce qui s'imprime ou se télécharge. Chaque document dit pour qui il est.

| Document | Contenu |
| --- | --- |
| PDF global | Toutes les affectations dans un seul document : un sommaire cliquable, la grille « qui tient quel stand chaque jour », puis les mêmes affectations journée par journée, stand par stand et animateur par animateur, places vides signalées |
| PDF individuel | Le planning d'un animateur, ou de tous en une archive : une vue d'ensemble de ses journées sur une frise, le détail de chaque jour, puis ses coéquipiers et ses lieux. Les journées sans affectation y figurent explicitement comme jours de repos |
| Feuille recto-verso | Le même planning individuel plié sur une seule feuille A4 paysage : calendrier au recto, coéquipiers et lieux au verso. Un tirage par personne au lieu de cinq. L'animateur choisit son format depuis son espace, vous depuis l'écran de diffusion |
| QR code | Les deux PDF individuels portent un QR code vers l'espace personnel : on scanne le papier au lieu de recopier une adresse |
| Fichier ICS | Le planning individuel, importable dans Google Calendar, Apple Calendar ou Outlook |
| Abonnement au calendrier | Une adresse d'abonnement permanente, donnée une fois à son agenda : il se met à jour tout seul à chaque republication. Elle est personnelle, et l'animateur la remplace lui-même en un clic si elle a fuité |

## Afficher le planning du jour sur un téléviseur

L'affichage mural se crée et se révoque dans Paramètres : voir [Suivre le jour J](suivre-le-jour-j.md#afficher-la-journée-sur-un-téléviseur).

## Ce que les autres lisent de ce planning

- Les animateurs lisent leur planning dans leur espace : [L'espace des animateurs](espace-animateur.md).
- Un responsable de stand ou une personne des ressources humaines lit le plan publié depuis un compte limité : [Gérer les comptes et les droits](comptes-et-droits.md).
