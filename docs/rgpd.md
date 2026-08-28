# RGPD : ce qu'un hébergeur d'instance doit écrire et tenir

[`securite.md`](securite.md) dit **comment durcir**, [`exploitation.md`](exploitation.md)
dit **comment exploiter**. Ce document dit **ce qu'il faut avoir écrit** avant
d'héberger l'instance de quelqu'un d'autre, et **ce qui se tient ensuite**.

Deux obligations, et elles ne se déclenchent pas au premier euro mais au
**premier hébergement, même gratuit** : une convention de sous-traitance
(art. 28) et un registre des traitements (art. 30). Aucune des deux n'est un
document qu'on produit une fois : la première se signe avant la mise en
production, le second se tient.

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
| Assistance | Aide à répondre aux demandes d'exercice de droits, et **notification de violation sans délai** — cette procédure-là (art. 33) reste à écrire, elle n'est pas couverte ici |
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
| Catégories de données | Nom, prénom, **date de naissance**, adresse électronique (facultative), compétences, souhaits d'affectation, jours d'indisponibilité, jeton d'accès à l'espace animateur, affectations et échanges, instantanés de planning, sessions et journaux d'accès |
| Traitements réalisés | Hébergement, planification et résolution, envoi d'e-mails (codes d'accès, plannings individuels, notifications d'échange), sauvegarde, purge |
| Destinataires | L'organisateur via l'interface d'administration ; l'animateur via son espace ; les autres animateurs pour la part visible du planning (voir `securite.md`) ; le relais SMTP |
| Mesures de sécurité | TLS et HSTS ; en-têtes CSP et `Referrer-Policy` — **le jeton d'espace voyage dans l'URL** ; chiffrement des sessions ; limitation de débit sur les codes d'espace et verrouillage du formulaire de connexion ; origine injoignable autrement que par le reverse proxy ; sauvegardes **chiffrées au repos et rangées hors machine** (`exploitation.md` §5) |

### Bloc variable, par instance

| Rubrique | Où le lire |
| --- | --- |
| Responsable de traitement et contact | `LEGAL_RESPONSABLE_TRAITEMENT`, `LEGAL_CONTACT` |
| Base légale déclarée | `LEGAL_BASE_LEGALE` |
| Durée de conservation annoncée | `LEGAL_CONSERVATION` — voir la limite du §6 |
| Hébergeur d'infrastructure | `LEGAL_HEBERGEUR` |
| Relais SMTP | `MAIL_HOST` |
| Suivi d'erreurs | `SENTRY_DSN` renseigné ⇒ Bugsink, hébergement UE, **pas de transfert à déclarer** |
| Mesure d'audience | `CLOUDFLARE_WEB_ANALYTICS_TOKEN` renseigné ⇒ **transfert hors UE à déclarer**. La variable a un défaut en profil `%prod` : ne rien poser ne suffit pas à l'éteindre, il faut la **vider explicitement** (`observabilite.md`) |
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
| Vérification | Espace animateur d'un jeton supprimé répondant bien `404` |

Une purge manuelle sans écrit ne prouve rien : c'est ce journal qui rend tenable
la durée annoncée par `LEGAL_CONSERVATION`, pas la procédure seule.

## 6. Les limites à consigner telles quelles

Le registre demande de **décrire** les mesures, pas de prétendre qu'elles sont
complètes. Trois points sont connus et se consignent :

- **aucune purge automatique n'existe** : la durée annoncée est tenue à la main,
  à date fixe (`exploitation.md` §6). C'est l'écart le plus exposant, parce
  qu'il porte sur un engagement public ;
- **le jeton d'accès voyage dans le chemin de l'URL** : il atterrit tel quel
  dans les journaux d'accès du reverse proxy, qui doivent donc être purgés ou
  écrits sans ces chemins (`securite.md`, dernière section) ;
- **l'effacement demandé par un animateur sur une édition encore active** n'a
  pas de procédure outillée : c'est une suppression manuelle de sa fiche.

## 7. Rythme de tenue

| Quand | Quoi |
| --- | --- |
| Avant chaque première mise en production | Convention de sous-traitance signée des deux côtés (§2), puis création de l'entrée de registre (§4) |
| À chaque changement de brique tierce | Mise à jour des sous-traitants ultérieurs et des transferts — un changement de variable d'environnement est un changement de registre |
| À chaque purge ou restauration | Une ligne dans le journal (§5) |
| Une fois par an, après l'événement | Relecture de toutes les entrées, en même temps que la purge |
| En fin d'hébergement | Sort des données tranché et exécuté, date portée au registre |

Le coût de ce document est faible s'il démarre au premier client, élevé s'il
démarre au cinquième.
