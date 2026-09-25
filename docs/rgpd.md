# RGPD : ce qu'un hébergeur d'instance doit écrire et tenir

[`securite.md`](securite.md) dit **comment durcir**, [`exploitation.md`](exploitation.md)
dit **comment exploiter**. Ce document dit **ce qu'il faut avoir écrit** avant
d'héberger l'instance de quelqu'un d'autre, et **ce qui se tient ensuite**.

Trois obligations, et elles ne se déclenchent pas au premier euro mais au
**premier hébergement, même gratuit** : une convention de sous-traitance
(art. 28), un registre des traitements (art. 30) et une procédure de
notification de violation (art. 33). Aucune n'est un document qu'on produit une
fois : la convention se signe avant la mise en production, le registre se
tient, et la procédure se décide **avant** d'en avoir besoin — un dimanche de
festival n'est pas le moment de chercher qui appeler.

> Ce document décrit ce que le dépôt permet d'affirmer et ce qu'il oblige à
> écrire. **Ce n'est pas un avis juridique** : la qualification exacte du
> responsable de traitement dans une association et la portée de l'engagement
> d'assistance méritent un regard de juriste.

## 1. Qui est quoi

| Rôle | Qui | Pourquoi |
| --- | --- | --- |
| Responsable de traitement | L'organisateur | C'est lui l'employeur ou le donneur d'ordre des personnes planifiées : il décide des finalités et des moyens. Ce sont **ses** informations qui remplissent les variables `LEGAL_*` (voir `exploitation.md` §3) |
| Sous-traitant | Celui qui héberge l'instance | Il traite des données personnelles **pour le compte** du responsable, sans décider de la finalité |
| Sous-traitants ultérieurs | Hébergeur d'infrastructure, relais SMTP, Bugsink, Cloudflare | Chacun n'existe que si la brique correspondante tourne — voir §4 |

Le dépôt raisonnait déjà juste sur la sous-traitance, mais **seulement pour les
services que l'application utilise** (`observabilite.md` § RGPD). Le présent
document traite l'autre sens : le rôle de l'hébergeur vis-à-vis de son client.

## 2. La convention de sous-traitance (art. 28)

**Échéance : avant la première mise en production**, y compris pour un
hébergement gratuit. L'article 28 n'exige pas un contrat commercial ; il exige
un **écrit signé des deux parties, à contenu imposé**. Pour un intégrateur seul
et une association, ça tient en deux pages.

Aujourd'hui, un **document autonome** valant convention de sous-traitance
suffit. Le jour où des conditions générales de vente existent, il en devient
l'annexe sans être réécrit.

### Les six points imposés par l'art. 28.3

| Point | Ce qu'il contient ici |
| --- | --- |
| Objet, durée, nature et finalité | Hébergement et exploitation d'un outil de planification d'équipes, pour la durée de la mise à disposition |
| Catégories de données et de personnes | Voir le socle du §4 — dont des **mineurs**. Ce mot n'est pas décoratif : il justifie les mesures renforcées |
| Obligations du sous-traitant | N'agir que sur instruction documentée, confidentialité des personnes autorisées, mesures de sécurité (§4), **pas de sous-traitance ultérieure sans autorisation** — donc les briques du §1 sont nommées dans l'écrit |
| Sort des données en fin de contrat | Restitution ou suppression, **au choix du responsable**, à trancher explicitement. La restitution s'appuie sur l'export SQL (`import-export.md`), la suppression sur la procédure de purge (`exploitation.md` §6) |
| Assistance | Aide à répondre aux demandes d'exercice de droits, et **notification de violation sans délai** — la procédure et le délai engagé sont au §6, et c'est de là que se recopie la clause |
| Audit | Mise à disposition des informations nécessaires pour démontrer la conformité : ce document, le registre, et `securite.md` |

### Ce qu'il ne faut pas signer

**Pas de modèle de DPA générique recopié.** Ceux qui circulent sont écrits pour
des prestataires SaaS multi-clients et promettent ce qu'un intégrateur seul ne
peut pas tenir : astreinte, audit sur site, sous-traitants certifiés. Signer une
obligation qu'on ne tiendra pas est **pire** que ne rien signer — c'est un
engagement opposable. Le modèle de la CNIL pour les petites structures est le
bon point de départ ; le tableau ci-dessus dit ce qu'il faut y mettre.

## 3. Le registre des traitements (art. 30)

L'exemption des organismes de moins de 250 salariés **ne joue pas** pour un
traitement régulier, et héberger un planning d'animateurs en est un.

Le registre à tenir est celui du **sous-traitant** (art. 30.2), qui n'a que cinq
rubriques obligatoires — pas les finalités, qui appartiennent au responsable :

1. l'identité et les coordonnées de l'hébergeur, et celles de **chaque**
   responsable de traitement pour lequel il agit ;
2. les catégories de traitements réalisés pour chacun ;
3. les transferts hors UE, le cas échéant ;
4. les sous-traitants ultérieurs ;
5. une description générale des mesures de sécurité.

Les catégories de données et les durées ne sont pas exigées d'un sous-traitant.
Le gabarit du §4 les garde quand même : c'est le modèle de la CNIL, et cela sert
de fiche de reprise quand quelqu'un d'autre récupère l'instance.

**Le registre ne vit pas dans le dépôt** : il nomme des clients. Un tableur ou
un document versionné hors dépôt, avec **une entrée par instance hébergée**.

## 4. Gabarit d'une entrée

Le socle est **identique pour toutes les instances** : c'est la même
application, le même modèle de données, les mêmes mesures. Seul le bloc
« variable » se remplit par client, et il se remplit **au moment où l'on
renseigne le `.env.prod`** — les deux listes se recouvrent, c'est le bon
crochet.

### Socle commun

| Rubrique | Contenu |
| --- | --- |
| Catégories de personnes | Animateurs, **dont des mineurs** ; encadrants et managers |
| Catégories de données | Nom, prénom, **date de naissance**, adresse électronique (facultative), compétences, souhaits d'affectation, jours d'indisponibilité, **deux jetons d'accès** — celui de l'espace animateur et celui de l'abonnement au calendrier —, affectations et échanges, **déclarations de disponibilités en libre-service — dont un commentaire en champ libre**, instantanés de planning, sessions et journaux d'accès, **historique des actions (identifiants et noms de champs, sans valeurs)**, **résultat de chaque courriel envoyé à un animateur (identifiant, type, statut, catégorie d'échec, date — ni adresse ni contenu)** |
| Traitements réalisés | Hébergement, planification et résolution, **import d'un fichier tabulaire d'animateurs fourni par l'organisation (traité en mémoire, jamais conservé)**, envoi d'e-mails (codes d'accès, plannings individuels, notifications d'échange, **rappels et relances automatiques de nuit**) **et conservation de leur résultat**, sauvegarde, **journalisation des actions d'administration**, purge |
| Destinataires | L'organisateur via l'interface d'administration ; l'animateur via son espace **et via l'application d'agenda à laquelle il communique son adresse d'abonnement** ; les autres animateurs pour la part visible du planning (voir `securite.md`) ; le relais SMTP |
| Mesures de sécurité | TLS et HSTS ; en-têtes CSP et `Referrer-Policy` — **les deux jetons voyagent dans l'URL** ; chiffrement des sessions ; limitation de débit sur les codes d'espace et verrouillage du formulaire de connexion ; origine injoignable autrement que par le reverse proxy ; sauvegarde nocturne automatique par `pg_dump`, en rotation dans un volume dédié, dont l'**externalisation chiffrée hors machine reste à la charge de l'exploitant** (`exploitation.md` §5) |

### Bloc variable, par instance

| Rubrique | Où le lire |
| --- | --- |
| Responsable de traitement et contact | `LEGAL_RESPONSABLE_TRAITEMENT`, `LEGAL_CONTACT` |
| Base légale déclarée | `LEGAL_BASE_LEGALE` |
| Durée de conservation annoncée | `LEGAL_CONSERVATION` — voir la limite du §7 |
| Hébergeur d'infrastructure | `LEGAL_HEBERGEUR` |
| Relais SMTP | `MAIL_HOST` |
| Suivi d'erreurs | `SENTRY_DSN` renseigné ⇒ Bugsink, hébergement UE, **pas de transfert à déclarer** |
| Mesure d'audience | `CLOUDFLARE_WEB_ANALYTICS_TOKEN` renseigné ⇒ **transfert hors UE à déclarer**. Le défaut est vide : ne rien poser suffit à l'éteindre. Ce ne fut pas toujours le cas — le profil `%prod` portait le token de l'éditeur (`observabilite.md`) |
| Vérification des mises à jour | Sur une version taguée, le navigateur de l'**administrateur connecté** demande à `api.github.com` s'il existe une release plus récente (`versioning.md` §3). Aucune donnée d'animateur ne part : l'appel n'a lieu qu'une fois la session administrateur confirmée, l'espace animateur ne le fait jamais, et seule l'adresse IP de l'administrateur atteint GitHub Inc. (États-Unis). Ce n'est donc pas un traitement de données d'animateur à déclarer au registre — la politique de confidentialité le dit néanmoins, puisque rien ne l'éteint par variable vide, contrairement aux deux outils ci-dessus. L'exploitant qui veut l'éteindre retire `https://api.github.com` de `connect-src` dans `CSP` (`securite.md`) |
| Contact de notification et son suppléant | Nommés dans la convention (§6) — **une personne, pas une adresse générique** |
| Dates | Début et fin de l'hébergement |

## 5. Le journal des purges et des restaurations

`exploitation.md` §6 se termine par « noter la date et le périmètre dans le
registre des traitements ». Voici la forme attendue — une ligne par opération,
dans le même document que le registre :

| Colonne | Contenu |
| --- | --- |
| Date | Date de l'opération |
| Nature | Purge de conservation, suppression sur demande, ou restauration |
| Périmètre | Édition supprimée, ou fiche animateur ; la suppression est en cascade sur les tables portant `edition_id` |
| Dump archivé | Référence de la sauvegarde chiffrée prise avant l'opération |
| Vérification | Espace animateur **et adresse d'abonnement** d'un jeton supprimé répondant bien `404` |

Une purge manuelle sans écrit ne prouve rien : c'est ce journal qui rend tenable
la durée annoncée par `LEGAL_CONSERVATION`, pas la procédure seule.

## 6. Notification de violation (art. 33)

Décider **qui on appelle et sous quel délai** coûte dix minutes maintenant.
L'improviser un dimanche de festival coûte la relation.

Le sous-traitant ne notifie **jamais la CNIL** : il alerte le responsable de
traitement, **sans délai injustifié**, et c'est le responsable qui décide de
notifier l'autorité — il dispose de 72 heures **à compter du moment où
l'hébergeur l'a prévenu**. Tout retard pris ici est pris sur son délai à lui :
c'est la seule raison pour laquelle l'engagement doit être chiffré.

### Ce qui compte comme violation

Une violation n'est pas seulement une intrusion : c'est toute atteinte à la
**confidentialité**, à l'**intégrité** ou à la **disponibilité** des données,
accidentelle ou non. Les trois se notifient, même si elles ne se traitent pas
pareil.

| Cas | Ce qu'il faut savoir avant d'appeler |
| --- | --- |
| Dump égaré : sauvegarde déposée en clair, envoyée par un canal non maîtrisé, oubliée sur une machine cédée | Ce qu'il contenait — un dump est **complet** : noms, dates de naissance de mineurs, adresses, jetons d'espace (`exploitation.md` §5) |
| Accès non autorisé à l'administration | Depuis quand, ce qui a été consulté ou modifié, et si le mot de passe d'administration a servi ailleurs |
| Fuite de jetons d'espace animateur ou d'abonnement au calendrier | Les jetons voyagent **dans le chemin de l'URL** : des journaux d'accès de reverse proxy partagés, indexés ou transmis sont une violation, pas une négligence sans suite (`securite.md`, dernière section). Un jeton d'abonnement fuité est **immédiatement exploitable sans second facteur**, et il apparaît dans ces journaux à chaque synchronisation d'un agenda, donc bien plus souvent que l'autre |
| Base ou sauvegarde perdue sans copie | C'est une violation de **disponibilité** : elle se notifie, même sans le moindre accès d'un tiers |
| Envoi d'un planning individuel à la mauvaise adresse | Violation aussi, à sa mesure — l'erreur de destinataire est le cas le plus fréquent en pratique |

Deux cas qui n'en sont **pas** : une panne de l'instance sans perte de données
(c'est un incident d'exploitation, §7 d'`exploitation.md`), et un animateur qui
voit le planning d'un collègue par une fonction prévue pour ça
(`securite.md` § *Lecture des créneaux d'un collègue*).

### Le délai qu'on s'engage à tenir

| Étape | Engagement |
| --- | --- |
| Alerter le contact client | **Dans les 24 heures** suivant la prise de connaissance, même sans diagnostic complet — le premier appel dit *ce qu'on sait*, pas *ce qu'on a compris* |
| Premier écrit qualifiant les faits | Dans les 48 heures |
| Compte rendu final | Une fois l'investigation close, sans échéance fixée à l'avance |

Notifier tôt et incomplet est explicitement prévu par le règlement : l'art. 33.4
autorise une information **par phases**. Attendre d'avoir tout compris est la
seule faute vraiment coûteuse.

### Qui on appelle

**Une personne nommée et un suppléant**, pas une adresse générique : un
`contact@` n'est lu par personne un dimanche. Ils sont nommés dans la
convention de sous-traitance (§2) et reportés au registre (§4), avec un numéro
de téléphone — l'incident peut être précisément celui qui empêche l'e-mail
d'arriver.

### Ce que contient l'alerte

L'art. 33.3 fixe le contenu ; le responsable reprendra ces éléments tels quels
pour la CNIL :

- la nature de la violation, et les **catégories et le nombre approximatif** de
  personnes et d'enregistrements concernés ;
- les conséquences probables ;
- les mesures prises ou proposées, y compris pour en atténuer les effets ;
- le point de contact côté hébergeur.

Le nombre de personnes concernées se lit dans l'application : c'est l'effectif
des éditions touchées. Le rappeler ici évite d'avoir à le chercher sous
pression.

### Le journal des violations

L'art. 33.5 impose de **documenter toute violation, y compris celle qu'on ne
notifie pas** — c'est ce registre qui permet de démontrer qu'on a bien qualifié
les faits. Il vit avec le registre des traitements et le journal des purges
(§5) :

| Colonne | Contenu |
| --- | --- |
| Découverte | Date et heure de la **prise de connaissance**, pas de la survenue : c'est elle qui déclenche les délais |
| Faits | Ce qui s'est passé, et par quel canal on l'a appris (sonde, suivi d'erreurs, signalement d'un animateur) |
| Périmètre | Éditions et catégories de données touchées, volume approximatif |
| Qualification | Violation ou non — **et pourquoi**, y compris quand la réponse est non |
| Notification | Qui a été prévenu, quand, par quel moyen |
| Suites | Mesures correctives, et ce qu'elles changent aux mesures de sécurité déclarées au registre |

### Ce qui manque pour tenir cet engagement

Un délai de 24 heures suppose de **savoir** qu'il s'est passé quelque chose.
`exploitation.md` §7 le dit déjà pour les pannes, et c'est la même dépendance
ici : sans `SENTRY_DSN` renseigné ni sonde externe, une instance peut être
tombée — ou pire — sans que personne ne l'apprenne avant le lundi. S'engager
sur un délai de notification sans avoir posé ces deux briques, c'est signer
une obligation qu'on ne tiendra pas.

## 7. Les limites à consigner telles quelles

Le registre demande de **décrire** les mesures, pas de prétendre qu'elles sont
complètes. Les points suivants sont connus et se consignent :

- **aucune purge automatique n'existe** : la durée annoncée est tenue à la main,
  à date fixe (`exploitation.md` §6). C'est l'écart le plus exposant, parce
  qu'il porte sur un engagement public. Le choix est délibéré et son échéance
  est connue — voir
  [décision 0016](decisions/0016-purge-manuelle-avant-automatisation.md) : la
  tâche planifiée, son journal et le test de cascade viennent quand plusieurs
  instances tournent en parallèle. Ce qui se consigne au registre n'est donc pas
  « purge automatique : non », mais « purge manuelle, à date fixe, tracée au
  journal du §5 » ;
- **les jetons d'accès voyagent dans le chemin de l'URL** : ils atterrissent
  tels quels dans les journaux d'accès du reverse proxy, qui doivent donc être
  purgés ou écrits sans ces chemins (`securite.md`, dernière section) ;
- **l'abonnement au calendrier expose un planning nominatif de façon durable et
  sans second facteur**, et c'est le point le plus exposant de ce document
  après la purge manuelle. Il se consigne au registre tel quel, avec ce qui le
  borne et ce qui ne le borne pas.

  Ce qu'il est : une adresse permanente qui, à elle seule, sert le planning
  publié d'une personne nommée — ses vacations, ses stands, ses coéquipiers —
  à qui la détient. **Elle est communiquée volontairement à un tiers** : le
  fournisseur de l'application d'agenda de l'animateur (Google, Apple,
  Microsoft, un serveur CalDAV…), qui la rappellera plusieurs fois par jour et
  en conservera le contenu selon ses propres règles. Ce destinataire-là n'est
  **pas** un sous-traitant de l'hébergeur : il est choisi par la personne
  concernée, pour son propre compte, et c'est précisément pour ça que
  l'abonnement doit rester un geste explicite de sa part — jamais un envoi
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
  quelqu'un la remplace** — et la seule personne en position de s'en rendre
  compte est celle à qui elle appartient. C'est ce qui rend le bouton de
  remplacement, dans l'espace, une mesure et non un confort : il se mentionne
  au registre à ce titre, et l'invitation à s'en servir fait partie de ce qu'on
  explique aux animateurs ;
- **les sauvegardes sont un lieu de stockage à part entière** : depuis qu'elles
  sont automatiques, un dump complet — mineurs et jetons compris — existe en
  permanence sur le volume. L'export SQL de l'application est plus étroit — il
  laisse dehors les accès et sessions d'espace, le journal et l'horloge — mais
  il porte désormais les déclarations, les confirmations et les destinataires de
  publication, adresse comprise ; il se traite avec les mêmes égards. Le registre doit le dire, et l'entrée n'est
  honnête que si l'externalisation chiffrée annoncée au socle existe
  réellement ;
- **l'archive de fin d'événement sort une édition entière en un geste** :
  depuis l'écran Exports, un seul ZIP peut réunir le planning global, les CSV
  Équité et Heures, les six référentiels (animateurs compris, avec leurs dates
  de naissance et leurs adresses), le scénario YAML de l'édition, la relecture
  de publication et les plannings individuels — mineurs compris. Une fois
  téléchargée, elle **échappe à toute conservation de l'application** : ni
  `JOURNAL_RETENTION`, ni la purge annuelle du §5 ne l'atteignent, et sa garde
  comme sa suppression reviennent à qui la détient. Trois bornes se consignent
  telles quelles : elle ne couvre **que l'édition** de l'onglet (jamais le dump
  SQL, qui emporte toutes les éditions et reste sur l'écran Paramètres) ; elle
  ne contient **aucun jeton** — ni celui de l'espace, que les plannings
  individuels de l'archive n'impriment pas (ni lien ni QR code), ni celui de
  l'abonnement au calendrier, ce qu'un test vérifie ; et son manifeste
  `LISEZMOI.txt` rappelle ce qu'elle contient, le responsable de traitement et
  la durée de conservation déclarés (`LEGAL_RESPONSABLE_TRAITEMENT`,
  `LEGAL_CONSERVATION`). C'est le geste naturel avant la purge annuelle d'une
  édition terminée ; le registre le mentionne comme une **sortie de données**,
  journalisée comme les autres ;
- **une déclaration de disponibilités traitée survit à sa décision** : appliquée
  ou refusée, elle reste en base pour que l'organisation puisse dire *pourquoi*
  la fiche de quelqu'un affirme ce qu'elle affirme, et pour que l'animateur
  relise dans son espace ce qui lui a été répondu. Elle n'a **aucune purge
  propre** : elle disparaît avec son édition, en cascade sur `edition_id`,
  c'est-à-dire à la purge annuelle du §5 — la durée de conservation proposée est
  donc **celle de l'édition, pas davantage**, et elle se consigne telle quelle
  au registre. Deux choses la rendent moins exposante que le reste : la table ne
  porte **aucune donnée nouvelle** (des dates, des identifiants de typologie et
  un mot libre — les mêmes catégories que la fiche animateur), et une seule
  proposition en attente existe par personne. Le mot libre reste le point
  sensible : c'est le seul champ où quelqu'un peut écrire une raison de santé
  ou de famille que personne ne lui a demandée, et il part tel quel dans le dump
  nocturne — **et, depuis que l'export SQL de l'application emporte
  `declaration_disponibilite`, dans le fichier qu'un administrateur télécharge
  depuis l'écran Paramètres**. C'est la même donnée, dans la même finalité, mais
  par une seconde voie et sous une forme qui quitte la machine à la main : le
  registre la consigne comme telle, et la consigne d'`exploitation.md` §5 sur le
  traitement d'un dump vaut mot pour mot pour celui-ci. **Le choix est fait de le conserver** plutôt que de le supprimer ou
  d'en borner le contenu : un animateur qui explique son indisponibilité aide
  l'organisation à décider, et un champ contraint le pousserait à écrire la même
  chose ailleurs. Il est donc consigné au registre **comme donnée en champ
  libre**, et c'est à ce titre qu'il est traité — sans que rien n'y soit
  demandé, ni exigé, ni exploité au-delà de la décision qu'il éclaire ;
- **les envois automatiques de nuit écrivent sans qu'un humain relise** : le
  rappel de la veille et la relance de confirmation partent d'une tâche
  planifiée, vers des adresses d'animateurs, mineurs compris. Trois bornes sont
  posées et se consignent telles quelles : rien ne part d'une édition qui n'a
  pas été **armée explicitement** (l'absence de réglage vaut « muet ») ; le
  rappel ne dit que ce qui a **déjà été publié**, donc il n'annonce jamais rien
  de neuf ; et le journal `notification_planifiee` ne stocke **ni nom ni
  adresse**, seulement un identifiant d'animateur — l'identité est jointe à la
  lecture depuis le référentiel, si bien qu'une fiche supprimée laisse une
  alerte qui ne nomme plus personne. Ce journal n'a pas de purge propre : il
  disparaît avec son édition, en cascade sur `edition_id`, donc à la purge
  annuelle ;
- **l'historique des actions (`journal_action`) trace qui a fait quoi**, et
  c'est un traitement à consigner comme tel. Il suit la même règle que le
  journal ci-dessus, et deux de plus. Il ne stocke **ni nom, ni adresse, ni
  date de naissance** : un identifiant d'animateur au plus, l'identité étant
  jointe à la lecture. Il ne stocke **aucune valeur de champ** : une
  modification y laisse les *noms* des champs qui ont bougé — « nom, email » —
  jamais ce qu'ils sont devenus, ce qui suffit à retracer un geste sans
  recopier la donnée. Et il porte, lui, **une purge propre** :
  `JOURNAL_RETENTION`, quatre-vingt-dix jours par défaut, appliquée chaque nuit
  — parce qu'un journal que personne ne relit deviendrait sinon un stockage de
  plus, conservé sans limite et repris dans chaque sauvegarde. Il cascade en
  outre avec son édition, donc disparaît au plus tard à la purge annuelle ;
- **le résultat de chaque courriel envoyé à un animateur est conservé**
  (`envoi_mail`) : planning publié ou individuel, code d'accès, invitation à
  déclarer, relance manuelle, rappel de la veille, relance de nuit,
  sollicitation ou refus d'échange. C'est une
  donnée nouvelle par personne, et elle se consigne comme telle. Elle est
  minimisée sur le modèle des deux journaux ci-dessus : un **identifiant
  d'animateur**, le type d'envoi, le statut (parti, sans adresse, échec), la
  catégorie d'un échec (serveur d'envoi injoignable, authentification, adresse
  refusée, échec temporaire, autre) et la date — **ni adresse, ni contenu, ni
  réponse brute du serveur**, l'adresse se relisant sur la fiche au moment
  d'agir. Elle sert une seule finalité : distinguer une personne que personne
  n'a pu joindre d'une personne silencieuse, et ne pas réécrire à une adresse
  refusée tant qu'elle n'a pas changé. Pour le savoir, la fiche porte **la date
  du dernier changement de son adresse** (`animateur.email_modifie_le`) : ce
  n'est pas une donnée personnelle de plus — une date, sans l'adresse d'avant
  ni celle d'après —, elle ne sort par aucune API ni aucun outil MCP, et elle
  disparaît avec la fiche. **Ce n'est pas un suivi de
  lecture** : aucun pixel, aucun lien traqué, et « parti » veut dire accepté
  par le relais, jamais reçu ni lu. Les mineurs n'y ont rien de plus que les
  autres. Elle n'a pas de purge propre : elle disparaît avec la fiche de
  l'animateur et avec son édition, en cascade, donc à la purge annuelle ; elle
  voyage dans le dump nocturne et dans l'export SQL comme les confirmations
  qu'elle qualifie.

  Il trace aussi **les sorties de données**, et c'est ce qui permet de répondre,
  pendant toute la rétention, à « qui a sorti la liste des animateurs, mineurs
  compris, et quand ? » : chaque export de l'administration — PDF, archives,
  CSV nominatifs, référentiels, scénario YAML, dump de la base — et chaque
  téléchargement de son planning (PDF ou ICS) par un animateur depuis son
  espace laisse une ligne — refus compris côté administration —, sous la même règle et **la même
  rétention** `JOURNAL_RETENTION` pour la table vivante — les lignes
  voyagent aussi dans les sauvegardes de nuit (`BACKUP_RETENTION`) et dans
  tout dump exporté, qui les gardent à leur propre rythme. Rien de plus n'y
  entre : l'action, la date, l'auteur, le code HTTP et, pour un téléchargement
  d'espace, l'identifiant de l'animateur ; pour l'archive des référentiels,
  les noms des référentiels emportés ; pour l'archive de fin d'événement, les
  noms des parties qu'elle portait ; jamais le contenu du fichier. Un
  téléchargement d'espace **refusé avant toute preuve d'identité** — lien
  inconnu, ou code reçu par e-mail jamais saisi — n'écrit **rien** : détenir le
  lien ne prouve pas qu'on est l'animateur, l'inscrire à son nom — souvent
  celui d'un mineur — lui imputerait la tentative d'un autre, et une lecture
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
  bruit, pas une trace — et rien ne compte ces relectures, comme le dit le
  point sur l'abonnement plus haut ;
- **l'import CSV des animateurs fait entrer des données personnelles par un
  fichier que l'exploitant tient lui-même**, et c'est une entrée à consigner :
  ce tableur porte des noms, des dates de naissance — donc l'information qui
  désigne les **mineurs** — et souvent des adresses e-mail. Ce que l'application
  garantit de son côté : le fichier est reçu dans le corps de la requête, lu en
  mémoire et **jamais écrit sur disque** (`service/backup/` reste le seul
  endroit où l'application écrit), ni conservé entre les deux appels — la
  prévisualisation et l'écriture le reçoivent chacune, et la mémoire est rendue
  à la fin de la requête. Ce qu'elle ne peut pas garantir : le **fichier
  source**, qui reste sur le poste de l'organisateur, dans sa messagerie et
  dans ses sauvegardes. C'est cette copie-là qui se consigne au registre, avec
  la consigne de la supprimer après import ;
- **le navigateur de l'organisateur garde un journal de ce qu'on lui a
  affiché**, et c'est un lieu de stockage de plus, hors de la base et hors des
  sauvegardes. Chaque bulle d'information est recopiée dans un journal de 200
  entrées tenu dans le `localStorage` du profil, cloisonné par édition mais
  **non effacé à la déconnexion** et relisible depuis la page *Notifications*
  par quiconque rouvre ce profil — un poste partagé de régie, typiquement. Deux
  bornes sont posées et se consignent telles quelles : les messages
  d'avertissement de saisie **nomment un animateur par son identifiant seul**,
  jamais par ses nom et prénom ni par sa date de naissance ; et la phrase qui
  dit qu'une personne est **mineure** — la seule qui, en nommant le jour de ses
  18 ans, laisserait recalculer sa date de naissance — est **affichée sans être
  journalisée**. Ce qui ne le borne pas : le journal n'expire pas de lui-même,
  il se vide au bouton depuis la page *Notifications*, et rien n'empêche un
  autre message de l'application d'y écrire un nom. La consigne d'exploitation
  qui va avec est celle d'un poste partagé : vider le journal, ou fermer la
  session du navigateur, en quittant le poste ;
- **les formulaires longs gardent un brouillon de la saisie en cours dans le
  navigateur**, et c'est un autre lieu de stockage hors de la base : la fiche
  animateur, la fiche stand et la consigne enregistrent, une seconde après la
  dernière frappe, ce qui n'a pas encore été enregistré, pour le proposer à la
  réouverture après un rechargement, une session expirée ou un onglet fermé.
  Les bornes posées se consignent telles quelles : le brouillon d'une **fiche
  animateur** — identité, date de naissance, donc statut de mineur, adresse
  électronique — n'est écrit que dans le **`sessionStorage`**, qui meurt avec
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
  `sessionStorage`, mais un onglet resté ouvert — session expirée comprise —
  garde la fiche en cours jusqu'à la prochaine déconnexion ou purge des
  24 h ; et un
  navigateur qui refuse les deux mécanismes de diffusion ne purge que l'onglet
  qui se déconnecte ;
- **l'effacement demandé par un animateur sur une édition encore active** n'a
  pas de procédure outillée : c'est une suppression manuelle de sa fiche. Une
  demande d'effacement ne se refuse pas au motif que l'événement n'est pas
  terminé, et automatiser la purge par édition échue ne le réglera pas — c'est
  une opération distincte ;
- **dupliquer une édition recopie ses personnes**, et c'est voulu pour l'usage
  qui a fait naître la duplication : l'édition « plan canicule » créée en cours
  de festival doit garder son équipe et son « Envoyer à all » (issue #172).
  L'usage *modèle d'une année sur l'autre* est l'inverse — préparer 2027 depuis
  2026 recopierait noms, dates de naissance et courriels de personnes qui ne se
  sont pas réinscrites, c'est-à-dire ferait démarrer une nouvelle durée de
  conservation sur des données que plus rien ne justifie de détenir. La
  duplication accepte donc `avecAnimateurs=false` (issue #90) : la structure
  passe, les personnes restent, et avec elles compétences, indisponibilités,
  souhaits et ajustements manuels. Ce qui ne le borne pas : rien n'oblige à
  choisir ce mode, et une duplication faite avec les gens par habitude produit
  exactement la copie qu'on voulait éviter — c'est une case à cocher, pas un
  garde-fou.

## 8. Rythme de tenue

| Quand | Quoi |
| --- | --- |
| Avant chaque première mise en production | Convention de sous-traitance signée des deux côtés (§2), puis création de l'entrée de registre (§4) |
| À chaque changement de brique tierce | Mise à jour des sous-traitants ultérieurs et des transferts — un changement de variable d'environnement est un changement de registre |
| À chaque purge ou restauration | Une ligne dans le journal (§5) |
| À chaque violation, notifiée ou non | Une ligne dans le journal des violations (§6) — l'art. 33.5 ne distingue pas |
| Une fois par an, après l'événement | Relecture de toutes les entrées, en même temps que la purge |
| En fin d'hébergement | Sort des données tranché et exécuté, date portée au registre |

Le coût de ce document est faible s'il démarre au premier client, élevé s'il
démarre au cinquième.
