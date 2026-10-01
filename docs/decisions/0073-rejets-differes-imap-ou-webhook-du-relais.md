# 0073 — Les rejets différés : boîte de retour en IMAP ou webhook du relais

- **Statut** : proposé — étude de faisabilité, rien n'est implémenté
- **Date** : octobre 2026
- **Portée** : envoi des courriels (`service/mail/`), table `envoi_mail`, sécurité HTTP

## Contexte

Chaque courriel adressé à un animateur laisse une ligne dans `envoi_mail` :
parti, ou en échec avec une catégorie lue sur la réponse **immédiate** du
relais SMTP (`MailFailureCategory`). Cela couvre les pannes du relais — serveur
injoignable, identifiants refusés — et le refus d'un destinataire au moment du
`RCPT TO`.

Ce n'est pas ce qui arrive le plus souvent. Un relais authentifié accepte
presque toujours le destinataire, puis le serveur du destinataire refuse
**plus tard** — adresse inexistante, boîte pleine, filtre — et l'avis de
non-remise part vers l'adresse de retour de l'enveloppe. Aujourd'hui, c'est
`MAIL_FROM`, et personne ne la relève : la ligne reste « parti », et la page
Animateurs compte la personne comme silencieuse alors qu'elle n'a rien reçu.

Deux mécanismes peuvent faire remonter ces rejets jusqu'à la ligne d'envoi.
Lequel tient dépend du relais, et aucun relais de production n'est choisi.

## Options envisagées

### A. Une boîte de retour dédiée, relevée en IMAP

Chaque courriel part avec une adresse de retour d'enveloppe propre à l'envoi
(VERP) : `retours+<jeton>@<domaine>`, posée par `Mail.setBounceAddress`. Une
tâche planifiée relève la boîte, lit les avis au format RFC 3464
(`multipart/report`, `Status: 5.1.1`), retrouve l'envoi par le jeton et passe
sa ligne en échec.

- **Pour** : indépendant du fournisseur ; marche avec un relais SMTP simple ou
  auto-hébergé.
- **Contre** :
  - beaucoup de relais transactionnels **imposent leur propre adresse de
    retour** pour traiter eux-mêmes les rejets : le jeton n'arrive alors
    jamais dans la boîte ;
  - il faut un client IMAP — une dépendance de plus, Vert.x n'en a pas —, un
    quatrième `@Scheduled`, et des identifiants de boîte à garder ;
  - les avis ne suivent pas tous la RFC 3464, et les réponses automatiques
    (absence, accusés) doivent être écartées sans les prendre pour des rejets ;
  - le jeton doit être **signé** (HMAC sur l'id de la ligne) : sinon,
    n'importe qui peut écrire à `retours+42@` et faire passer un envoi en
    échec ;
  - la boîte contient l'adresse refusée et souvent le message d'origine :
    c'est une donnée personnelle de plus, à purger dès qu'un avis est traité.

### B. Le webhook du relais transactionnel

Le relais appelle une route de l'application à chaque événement — rejet
définitif, rejet temporaire, blocage, plainte, parfois remise. L'envoi se
retrouve par un en-tête que l'application pose (`Message-ID` maîtrisé ou
en-tête propre au fournisseur) et qui porte l'id de la ligne.

- **Pour** : des événements structurés, sans analyse de courriel ; quasi
  instantané ; un rejet temporaire et un rejet définitif s'y distinguent
  nettement ; certains relais disent aussi « remis », ce qui permettrait de
  distinguer « parti » de « arrivé chez le destinataire ».
- **Contre** :
  - **un adaptateur par fournisseur** : format du corps et signature diffèrent
    d'un relais à l'autre ;
  - inutilisable avec un relais SMTP simple ;
  - c'est une **troisième route publique** hors session d'administration, après
    l'abonnement au calendrier et l'affichage mural : elle doit vérifier la
    signature du fournisseur, être limitée en débit, figurer dans
    `docs/securite.md`, et le reverse proxy doit l'excepter de son
    authentification sous un préfixe qui ne nomme qu'elle.

### Ce qui est commun aux deux

La ligne d'envoi existe déjà : un rejet différé la fait passer d'`ENVOYE` à
`ECHEC`, sans nouvelle donnée — un rejet définitif sur l'adresse rejoint
`ADRESSE_REFUSEE` (et donc « ne pas insister »), un rejet temporaire
`TEMPORAIRE`. Il faut en revanche une colonne de corrélation (jeton ou id
fournisseur) et une migration, et la mise à jour d'une ligne déjà écrite doit
rester bornée à son édition comme toute écriture.

## Décision

Aucun code tant qu'aucun relais de production n'est choisi : c'est le relais
qui décide lequel des deux mécanismes est seulement possible.

Une fois le relais choisi :

- s'il offre un webhook **signé** et garde la main sur l'adresse de retour —
  le cas des relais transactionnels courants —, **option B**, avec un seul
  adaptateur, celui de ce relais ;
- s'il s'agit d'un relais SMTP simple qui respecte l'adresse de retour posée
  par l'application, **option A**, avec un jeton signé et une purge de la boîte
  à chaque relève.

## Conséquences

- En attendant, « parti » veut dire « remis au relais » et rien de plus ; les
  écrans et `docs/exploitation.md` §4 le disent, et la catégorie
  `ADRESSE_REFUSEE` se verra rarement.
- Le lot déjà livré n'a rien à défaire : la décision ajoutera une colonne de
  corrélation et une écriture sur une ligne existante, pas un second registre.
- Le choix du relais doit être fait en connaissant cette conséquence : un
  relais qui n'offre ni webhook ni adresse de retour respectée laisse les
  rejets différés invisibles pour de bon.
