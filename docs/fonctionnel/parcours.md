# Une édition, de la préparation au jour J

Planning Équipes sert à organiser le travail d'une équipe de bénévoles ou de
salariés pendant un événement : qui tient quel stand, quel jour, à quelle heure,
dans le respect des règles légales (celles des mineurs en particulier), des
compétences, des disponibilités et d'une charge équitable entre les personnes.
Cette page suit le fil d'une **édition** — une année, un festival, un salon —
et dit, pour chaque moment, ce que l'application fait et où le trouver. Le détail
de chaque fonction est dans les pages voisines ; le mode d'emploi pas à pas est
dans l'application elle-même, page « Aide ».

## Le fil d'une édition

L'application s'ouvre sur l'**état de l'édition** : une liste d'étapes, chacune
dit si elle est faite, à vérifier ou à faire, avec le chiffre qui compte et un
lien vers l'écran qui la fait avancer. Le menu suit le même fil, en groupes :
*Préparer*, *Planifier*, *Diffuser*, *Aujourd'hui* et *Administrer*.

| Moment | Ce qu'on fait | Pages à lire |
| --- | --- | --- |
| Préparer | Décrire l'événement : les stands et leurs horaires d'ouverture, le calendrier des créneaux, les animateurs avec leurs compétences et leurs disponibilités. On peut tout saisir à la main, par fichier, ou partir d'une édition précédente | [Référentiels](referentiels.md), [Organiser l'année](organiser-annee.md), [Outils](outils.md) |
| Planifier | Lancer le calcul : l'application propose un planning qui respecte les règles, la progression se suit en direct. On relit, on retouche à la main, on verrouille ce qui ne doit plus bouger | [Planifier](planifier.md) |
| Comprendre | Quand un planning n'est pas réalisable ou laisse des places vides, l'application dit pourquoi et ce qu'il vaut mieux corriger en premier | [Décider et diagnostiquer](decider-diagnostiquer.md), [Consulter le planning](consulter-planning.md) |
| Diffuser | Publier le planning, envoyer à chaque animateur le sien (PDF, calendrier), puis suivre qui l'a reçu et confirmé | [Diffuser et échanger](diffuser-echanger.md) |
| Jour J | La page « Aujourd'hui » montre la journée en cours, les trous à combler et les imprévus ; l'écran mural affiche la journée sur un téléviseur du poste de contrôle | [Décider et diagnostiquer](decider-diagnostiquer.md), [Diffuser et échanger](diffuser-echanger.md) |
| Après | Comparer le prévu et le réalisé, archiver l'événement, repartir de cette édition pour la suivante | [Organiser l'année](organiser-annee.md), [Outils](outils.md) |

## Qui s'en sert

| Qui | Ce qu'il voit et fait |
| --- | --- |
| L'**organisateur** (administrateur) | Tout le cycle ci-dessus, édition par édition. C'est lui qui prépare, calcule, publie et règle l'application |
| L'**animateur** | Il n'a pas de compte : il reçoit un lien personnel vers son **espace** et s'y identifie par un code reçu par e-mail. Il y lit son planning, le garde sur son calendrier, accuse réception, signale un empêchement, propose ou accepte un échange de places (la foire au planning) et déclare ses disponibilités quand l'organisateur les collecte |
| Le **responsable de stand** | Un compte en lecture seule, limité à un ou plusieurs stands d'une édition : les horaires, les places pourvues et les places vides du plan publié, et selon le réglage de l'organisateur les noms des personnes. Il peut imprimer l'affichette de son stand |
| La personne des **ressources humaines** | Un compte limité au rapport de conformité d'une édition : le contrôle, sur le plan publié, du respect des règles légales, exportable en PDF ou en CSV |

L'application ne publie rien vers l'extérieur, n'envoie aucun message et n'ouvre
aucun espace pour une édition qui n'est pas **l'édition active** : on peut
préparer et recalculer une édition à l'abri, et ne la rendre visible qu'au
moment choisi.

## Les mots de l'application

Quelques mots reviennent dans ces pages et sur les écrans ; les voici en clair.

| Mot | Ce qu'il veut dire |
| --- | --- |
| Édition | Une occurrence de l'événement (« Année 2026 ») : ses stands, son équipe, son planning. Les éditions ne se mélangent jamais |
| Créneau, vacation | Une tranche horaire d'une journée ; une vacation est une tranche de travail réelle sur un stand |
| Siège, place | Une personne à trouver pour un stand sur un créneau : un stand qui demande trois animateurs a trois sièges |
| Solveur | Le nom de l'écran qui lance le **calcul automatique** du planning ; « résolution » veut dire un calcul |
| Règle impérative | Une règle que le planning ne doit jamais enfreindre (les règles légales, celles des mineurs, une indisponibilité). Les autres règles sont des préférences : le calcul fait de son mieux |
| Score, note | Le bilan d'un planning en trois niveaux : les règles impératives, les places restées vides, puis la qualité (équité, souhaits, continuité). L'application le traduit toujours en phrases |
| Plancher | La part d'une règle qu'aucun calcul ne peut améliorer, parce qu'une donnée manque (personne n'a déclaré de souhait, par exemple) |
| Recalcul partiel | Un calcul qui garde le planning en place et ne refait que ce qu'un changement a cassé |
| Verrou | Le planning d'une personne, d'un stand ou d'une journée, qu'aucun calcul ne touchera plus |
| Ajustement manuel | Une exception posée à la main : une personne indisponible tel jour, deux personnes à ne pas mettre ensemble, une affectation imposée |
| Consigne | Une fermeture imposée de l'extérieur (un arrêté préfectoral, une canicule) appliquée à tous les stands pour quelques dates |
| Instantané | Une copie du planning mise de côté, qu'on peut remettre en place plus tard |
| CSV, ICS, PDF | Les formats de fichier : CSV s'ouvre dans un tableur, ICS s'ajoute à un agenda (téléphone, Outlook, Google), PDF s'imprime |
| Fichier scénario | Un fichier qui contient toute une édition, pour la recopier ou la partager |
| Webhook | Un message que l'application envoie d'elle-même à un autre outil de l'équipe (Slack, Discord…) |
| MCP | La porte par laquelle un assistant IA peut consulter l'application, sans jamais voir les données personnelles |

## Ce qu'elle ne fait pas

- Le planning calculé est une proposition : l'organisateur relit, retouche et
  décide ; une journée relue peut être marquée comme telle.
- Un animateur n'est jamais affecté contre une règle légale : ces règles
  cèdent, au pire, par des places laissées vides et signalées, jamais par un
  mineur placé hors cadre.

Les règles elles-mêmes, ce qu'elles coûtent et pourquoi elles existent sont
dans la partie *Tech* : [Contraintes](../contraintes.md).
