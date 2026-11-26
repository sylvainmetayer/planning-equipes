# Faire arriver vos e-mails

Vous réglez le relais SMTP et le domaine d'envoi de façon que les codes d'accès, les plannings et les relances arrivent en boîte de réception, puis vous apprenez à lire ce que l'application garde de chaque envoi. C'est le point le plus facile à rater, parce qu'il échoue en silence : un lien d'espace classé en indésirable ne produit ni erreur ni alerte, seulement un animateur qui « n'a rien reçu » la veille de l'événement.

## 1. Brancher le relais SMTP

Dans le `.env.prod` :

```properties
MAIL_HOST=smtp.example.org
MAIL_PORT=587
MAIL_USERNAME=...
MAIL_PASSWORD=...
MAIL_START_TLS=REQUIRED
MAIL_FROM=planning@<domaine>
```

`MAIL_HOST` et `MAIL_FROM` sont obligatoires. Le compose de production prend `587` et `REQUIRED` par défaut, là où l'application seule part sur un Mailpit local (`1025`, sans TLS). Renseignez aussi `MAIL_ADMIN` : c'est l'adresse qui reçoit les demandes d'échange, la fin de résolution si l'édition le demande, l'alerte météo et l'échec de la sauvegarde de nuit.

## 2. Régler le domaine

Côté domaine, l'application n'y peut rien, et c'est pourtant ce qui décide si le message est accepté :

- SPF : autorisez le relais utilisé ;
- DKIM : signez, et publiez la clé ;
- DMARC : au minimum une politique de surveillance, alignée sur le domaine de `MAIL_FROM`.

## 3. Tester avant la première diffusion

Envoyez un planning de test vers **trois boîtes différentes**, dont Gmail et Outlook, qui sont les plus stricts. Vérifiez que le message arrive en boîte de réception, pas seulement qu'il part. La page *Débogage* (Ctrl+K, ou l'adresse `/debug`, onglet *Vérifications*) propose « Envoyer un mail de test ».

Chaque mail part en **deux parties** : un texte brut, et une version HTML habillée aux couleurs du déploiement (clés `pdf-*` de `BRANDING_COLORS`, `BRANDING_ORGANISATION` au pied de page). Le logo (clé `pdf-logo` de `BRANDING_IMAGES`) est embarqué dans le message lui-même : aucune image n'est chargée depuis un serveur à l'ouverture, ce que les clients de messagerie bloquent et que les filtres antispam pénalisent. Un client qui refuse le HTML affiche le texte, identique mot pour mot.

Avec `MAIL_MOCK=true`, rien ne part : les mails sont journalisés, et l'écran dit « simulé » pour qu'une recette ne passe pas pour une diffusion.

## 4. Lire ce qui est gardé de chaque envoi

Chaque courriel adressé à un animateur (planning publié ou renvoyé, code d'accès, invitation à déclarer, relances, rappel de la veille, échanges, covoiturage, absences) laisse **une ligne** dans la table `envoi_mail` : l'identifiant de la personne, le modèle du courriel, « parti » ou « en échec », la catégorie de l'échec et la date. Ni l'adresse, ni le contenu, ni la réponse du serveur de messagerie, qui cite volontiers l'adresse refusée.

C'est ce que lit la colonne *Accusé de réception* de la page Animateurs pour afficher « échec d'envoi » au lieu de « silencieux », le filtre « Échec d'envoi » (`?envoi=echec`) et le signal de la fiche animateur.

| Catégorie | Ce que le relais a répondu | Ce que vous faites |
| --- | --- | --- |
| Serveur injoignable | rien : connexion refusée, nom inconnu, délai dépassé, TLS en échec | vérifier `MAIL_HOST`, `MAIL_PORT`, le réseau |
| Authentification | 530, 535, 5.7.8, et un 5.7.x qui parle d'authentification (« relaying denied, authentication required ») | vérifier `MAIL_USERNAME` et `MAIL_PASSWORD` |
| Adresse refusée | 5.1.x ; 550, 551, 553 quand aucun code étendu ne précise | corriger l'adresse de la fiche |
| Refus temporaire | tout 4xx, et la boîte pleine (552, 5.2.2) | rien : l'envoi suivant est autorisé |
| Autre | le reste : expéditeur ou relais refusés (550 ou 553 5.7.1), rejet pour spam, erreur avant l'envoi… | lire le journal du serveur, vérifier `MAIL_FROM` |

Un 550 ou un 553 ne veut donc pas toujours dire « adresse refusée » : quand le relais précise un code étendu, seul un `5.1.x` vise le destinataire. Un `553 5.7.1 Sender address rejected` ou un `550 5.7.1 Relaying denied` vise l'application (un `MAIL_FROM` que le relais n'accepte pas, un relais qui n'autorise pas l'envoi). Classer toute la liste en « adresse refusée » bloquerait les relances de tout le monde pour une erreur de configuration.

**Une adresse refusée n'est plus relancée**, ni à la main ni la nuit, tant que la fiche n'a pas été modifiée depuis l'échec : le même courriel rencontrerait le même refus. N'importe quelle modification de la fiche lève la consigne, et le signal avec ; aucune empreinte de l'adresse n'est gardée pour savoir si c'est elle qui a changé. Une fois la fiche modifiée, le passage suivant des envois automatiques relance la personne, et « Relancer maintenant » le peut aussitôt.

### Ce que « parti » veut dire

« Parti » signifie « remis au relais », pas « reçu » ni « lu ». Un relais authentifié accepte presque toujours le destinataire et renvoie le refus **plus tard**, par un avis de non-remise adressé à `MAIL_FROM` ; personne ne le relève aujourd'hui. Ces rejets différés attendent le choix d'un relais de production ([décision 0073](repo:docs/decisions/0073-rejets-differes-imap-ou-webhook-du-relais.md)). En pratique, la catégorie « adresse refusée » se verra rarement : ce que l'écran montre surtout, ce sont les pannes du relais.

### Combien de temps les lignes restent

Les lignes vivent aussi longtemps que l'historique des actions (`JOURNAL_RETENTION`, quatre-vingt-dix jours par défaut, voir [la conservation des données](conservation-des-donnees.md)) : la purge de nuit retire les plus anciennes, toutes éditions confondues, et elles disparaissent avec leur édition. Elles voyagent dans l'export SQL, comme `envoi_planning`, et ne sont pas recopiées quand on duplique une édition.

## Et les envois de nuit ?

Les rappels de la veille et les relances partent d'une tâche planifiée qui a ses propres règles : [les envois automatiques](envois-automatiques.md).
