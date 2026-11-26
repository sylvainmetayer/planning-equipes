# Tenir le registre RGPD de votre instance

Si vous hébergez l'instance d'une autre organisation que la vôtre, trois obligations naissent au premier hébergement, même gratuit : une convention de sous-traitance (art. 28), un registre des traitements (art. 30) et une procédure de notification d'atteinte (art. 33). Cette page vous fait tenir le registre ; la [convention](rgpd-sous-traitance.md) et la [procédure d'atteinte](rgpd-atteinte-aux-donnees.md) ont chacune leur guide, et [ce que l'instance conserve et n'efface pas](rgpd-limites-a-consigner.md) se consigne au registre tel quel. Aucune n'est un document qu'on produit une fois : la convention se signe avant la mise en production, le registre se tient et la procédure se décide **avant** d'en avoir besoin.

> **Ce n'est pas un avis juridique.** La qualification exacte du responsable de traitement dans une association et la portée de l'engagement d'assistance méritent un regard de juriste.

Le durcissement est dans [`securite.md`](securite.md), l'exploitation dans [`exploitation.md`](exploitation.md) ; ici, ce que vous devez avoir écrit avant d'héberger l'instance de quelqu'un d'autre, et ce qui se tient ensuite.

## Qui est quoi

| Rôle | Qui | Pourquoi |
| --- | --- | --- |
| Responsable de traitement | L'organisateur | C'est lui l'employeur ou le donneur d'ordre des personnes planifiées : il décide des finalités et des moyens. Ce sont **ses** informations qui remplissent les variables `LEGAL_*` (voir les mentions légales dans [`exploitation.md`](exploitation.md)) |
| Sous-traitant | Celui qui héberge l'instance | Il traite des données personnelles **pour le compte** du responsable, sans décider de la finalité |
| Sous-traitants ultérieurs | Hébergeur d'infrastructure, relais SMTP, Bugsink, Cloudflare | Chacun n'existe que si la brique correspondante tourne (voir [le gabarit d'entrée](rgpd.md#remplir-une-entrée-du-registre)) |

Les services que l'application utilise (relais SMTP, suivi d'erreurs) ont leur propre analyse dans la rubrique RGPD de [`observabilite.md`](observabilite.md#rgpd) ; ce qui suit traite l'autre sens : le rôle de l'hébergeur vis-à-vis de son client.

## Tenir le registre des traitements (art. 30)

L'exemption des organismes de moins de 250 salariés **ne joue pas** pour un
traitement régulier, et héberger un planning d'animateurs en est un.

Le registre à tenir est celui du **sous-traitant** (art. 30.2), qui n'a que cinq
rubriques obligatoires (les finalités, qui appartiennent au responsable, n'en font pas partie) :

1. l'identité et les coordonnées de l'hébergeur, et celles de **chaque**
   responsable de traitement pour lequel il agit ;
2. les catégories de traitements réalisés pour chacun ;
3. les transferts hors UE, le cas échéant ;
4. les sous-traitants ultérieurs ;
5. une description générale des mesures de sécurité.

Les catégories de données et les durées ne sont pas exigées d'un sous-traitant.
Le [gabarit d'entrée](rgpd.md#remplir-une-entrée-du-registre) les garde quand même : c'est le modèle de la CNIL, et il sert
de fiche de reprise quand quelqu'un d'autre récupère l'instance.

Le registre ne vit pas dans le dépôt, car il nomme des clients. Tenez-le dans un tableur ou
un document versionné hors dépôt, avec **une entrée par instance hébergée**.

## Remplir une entrée du registre

Le socle est **identique pour toutes les instances** : c'est la même
application, le même modèle de données, les mêmes mesures. Seul le bloc
« variable » se remplit par client, et il se remplit **au moment où l'on
renseigne le `.env.prod`** : les deux listes se recouvrent.

### Le socle commun

| Rubrique | Contenu |
| --- | --- |
| Catégories de personnes | Animateurs, **dont des mineurs** ; encadrants et managers |
| Catégories de données | Nom, prénom, **date de naissance**, adresse électronique (facultative), **numéro de téléphone (facultatif, pour appeler un remplaçant le jour J)**, compétences, souhaits d'affectation, jours d'indisponibilité, **deux jetons d'accès** (celui de l'espace animateur et celui de l'abonnement au calendrier), **les liens d'affichage mural de l'organisation (jeton haché, libellé, date du dernier accès)**, affectations et échanges, **déclarations de disponibilités en libre-service, dont un commentaire en champ libre**, **demandes de covoiturage (coéquipiers nommés, et le motif libre que l'organisation peut joindre en les écartant)**, instantanés de planning, sessions et journaux d'accès, **historique des actions (identifiants et noms de champs, sans valeurs)**, **résultat de chaque courriel adressé à un animateur (parti ou en échec, et pourquoi, sans adresse ni contenu)**, **le contact d'urgence et le représentant légal d'un animateur (nom et téléphone, facultatifs : données d'un tiers)**, **le téléphone et l'adresse de contact que l'organisation publie à ses animateurs, par édition, souvent le numéro personnel d'un coordinateur, donc une donnée personnelle de l'encadrement, montrée à tous les animateurs de l'édition** |
| Traitements réalisés | Hébergement, planification et résolution, **affichage du planning de travail du jour sur un écran de la salle de contrôle (prénom et initiale par défaut)**, **import d'un fichier tabulaire d'animateurs fourni par l'organisation (traité en mémoire, jamais conservé)**, envoi d'e-mails (codes d'accès, plannings individuels, notifications d'échange, **rappels et relances automatiques de nuit**) **et enregistrement de leur résultat**, sauvegarde, **journalisation des actions d'administration**, purge |
| Destinataires | L'organisateur via l'interface d'administration ; l'animateur via son espace **et via l'application d'agenda à laquelle il communique son adresse d'abonnement** ; les autres animateurs pour la part visible du planning (voir [`securite.md`](securite.md)) ; **toute personne présente devant un écran d'affichage mural** ; le relais SMTP |
| Mesures de sécurité | TLS et HSTS ; en-têtes CSP et `Referrer-Policy` (**les jetons voyagent dans l'URL**) ; chiffrement des sessions ; limitation de débit sur les codes d'espace et verrouillage du formulaire de connexion ; origine injoignable autrement que par le reverse proxy ; sauvegarde nocturne automatique par `pg_dump`, en rotation dans un volume dédié, dont l'**externalisation chiffrée hors machine reste à la charge de l'exploitant** (voir la sauvegarde dans [`exploitation.md`](exploitation.md)) |

### Le bloc variable, par instance

| Rubrique | Où le lire |
| --- | --- |
| Responsable de traitement et contact | clés `responsable-traitement` et `contact` de `LEGAL_MENTIONS` |
| Base légale déclarée | clé `base-legale` de `LEGAL_MENTIONS` |
| Durée de conservation annoncée | clé `conservation` de `LEGAL_MENTIONS` ; voir [la limite de conservation](rgpd-limites-a-consigner.md#aucune-purge-automatique) |
| Hébergeur d'infrastructure | clé `hebergeur` de `LEGAL_MENTIONS` |
| Relais SMTP | `MAIL_HOST` |
| Suivi d'erreurs | `SENTRY_DSN` renseigné ⇒ Bugsink, hébergement UE, **pas de transfert à déclarer** |
| Mesure d'audience | `CLOUDFLARE_WEB_ANALYTICS_TOKEN` renseigné ⇒ **transfert hors UE à déclarer**. Le défaut est vide : ne rien poser suffit à l'éteindre. Ce ne fut pas toujours le cas : le profil `%prod` portait le token de l'éditeur ([`observabilite.md`](observabilite.md)) |
| Vérification des mises à jour | Sur une version taguée, le navigateur de l'**administrateur connecté** demande à `api.github.com` s'il existe une release plus récente (voir [`versioning.md`](versioning.md)). Aucune donnée d'animateur ne part : l'appel n'a lieu qu'une fois la session administrateur confirmée, l'espace animateur ne le fait jamais, et seule l'adresse IP de l'administrateur atteint GitHub Inc. (États-Unis). Ce n'est donc pas un traitement de données d'animateur à déclarer au registre, mais la politique de confidentialité le dit néanmoins, puisque rien ne l'éteint par variable vide, contrairement aux deux outils ci-dessus. L'exploitant qui veut l'éteindre retire `https://api.github.com` de `connect-src` dans `CSP` (voir [les en-têtes du navigateur](securite-navigateur.md#réglage)) |
| Webhooks sortants | Configurés dans Paramètres › Instance ⇒ Slack, Discord, Telegram, un pont Matrix ou un outil d'automatisation reçoivent les événements de l'application. **Aucune donnée personnelle ne part** : des comptes et des identifiants techniques (édition, instantané, livraison), jamais un nom, un prénom, une adresse, une date de naissance ni un jeton, ce que `WebhookFormatsTest` vérifie sur chaque événement et chaque format. Pas de destinataire de données d'animateur à déclarer ; les livraisons sont gardées 30 jours, hors de l'export SQL |
| Service météo (`METEO_URL`) | Destinataire technique, **sans donnée personnelle** : chaque matin, les coordonnées arrondies (à 0,01°) des lieux de l'événement et des dates partent vers Open-Meteo, avec l'adresse IP du serveur, jamais celle d'un navigateur. Rien à ajouter aux catégories de données ; `METEO_ENABLED=false` l'éteint |
| Contact de notification et son suppléant | Nommés dans la convention (voir [qui on appelle](rgpd-atteinte-aux-donnees.md#qui-on-appelle)) : **une personne, pas une adresse générique** |
| Dates | Début et fin de l'hébergement |

## Tenir le journal des purges et des restaurations

La procédure de purge de [`exploitation.md`](exploitation.md) se termine par « noter la date et le périmètre dans le
registre des traitements ». Voici la forme attendue : une ligne par opération,
dans le même document que le registre :

| Colonne | Contenu |
| --- | --- |
| Date | Date de l'opération |
| Nature | Purge de conservation, suppression sur demande, ou restauration |
| Périmètre | Édition supprimée, ou fiche animateur ; la suppression est en cascade sur les tables portant `edition_id` |
| Dump archivé | Référence de la sauvegarde chiffrée prise avant l'opération |
| Vérification | Espace animateur **et adresse d'abonnement** d'un jeton supprimé répondant bien `404` |

Une purge manuelle sans écrit ne prouve rien. Seul ce journal rend tenable
la durée annoncée par la clé `conservation` de `LEGAL_MENTIONS`.

## Le rythme de tenue

| Quand | Quoi |
| --- | --- |
| Avant chaque première mise en production | Convention de sous-traitance signée des deux côtés (voir [la convention](rgpd-sous-traitance.md)), puis création de l'entrée de registre (voir [le gabarit d'entrée](rgpd.md#remplir-une-entrée-du-registre)) |
| À chaque changement de brique tierce | Mise à jour des sous-traitants ultérieurs et des transferts, car un changement de variable d'environnement est un changement de registre |
| À chaque purge ou restauration | Une ligne dans le journal (voir [le journal des purges](rgpd.md#tenir-le-journal-des-purges-et-des-restaurations)) |
| À chaque atteinte, notifiée ou non | Une ligne dans le journal des atteintes (voir [qui on appelle](rgpd-atteinte-aux-donnees.md#qui-on-appelle)) ; l'art. 33.5 ne distingue pas |
| Une fois par an, après l'événement | Relecture de toutes les entrées, en même temps que la purge |
| En fin d'hébergement | Sort des données tranché et exécuté, date portée au registre |

Ce registre coûte peu s'il démarre au premier client, beaucoup s'il
démarre au cinquième.
