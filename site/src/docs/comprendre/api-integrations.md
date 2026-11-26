# Intégrer l'application : journal, alertes, webhooks, exports et sauvegarde

Vous saurez lire le journal des actions et ce qu'il refuse de contenir, régler les envois de nuit et l'alerte météo, brancher un outil extérieur sur les webhooks en vérifiant leur signature, et connaître les contours de la marque, des exports, de la sauvegarde et de l'import de scénario côté API.

## Journal des actions

`GET /api/historique?limite=200` rend ce qui a été fait dans l'édition courante, du plus récent au plus ancien, par pages de 500 lignes au plus.

| Paramètre | Effet |
| --- | --- |
| `nature=exports` | ne rend que les fichiers sortis de l'application |
| `nature=donnees` | ne rend que les modifications qu'un calcul recevrait |
| `depuis`, `jusqua` | bornent une période (deux instants ISO-8601), **`depuis` exclu, `jusqua` inclus** |
| `avant` | curseur : l'id de la dernière ligne affichée, pour la page suivante |

La recherche par `nature` est **choisie par la base** et porte sur toute la rétention, non sur la dernière page toutes actions confondues : un export enfoui sous deux cents modifications plus récentes est retrouvé. Les bornes de période sont celles de `GET /api/historique/changements`, si bien que `nature=donnees&depuis=<fin de la résolution>` rend exactement les lignes que l'écran Solveur a comptées (abouties seulement : une écriture refusée n'a rien changé). Une période n'est pas plafonnée. L'écran sait qu'une page suivante existe en demandant une ligne de plus qu'il n'en montre. Un curseur dont la ligne a disparu rend une page vide plutôt qu'un refus : seule la purge de nuit retire une ligne, et elle emporte avec elle toutes les plus anciennes.

`GET /api/historique/actions` rend l'inventaire des actions que l'application sait décrire, pour que l'écran propose un filtre qu'il n'a pas inventé. Son drapeau `export` est la classification que `nature` applique, tenue par `CatalogueActions` et non devinée d'après l'orthographe du code.

Le journal est en lecture seule, et cette forme est définitive : un journal qu'on peut modifier n'est pas un journal. Rien ne se supprime ; la table est bornée par une **rétention** (`JOURNAL_RETENTION`, 90 jours par défaut) appliquée par la tâche de nuit.

### Ce qu'une ligne porte, et ce qu'elle ne portera jamais

Une ligne dit quand, qui, l'action et sa phrase en français, ce qu'elle visait, **les noms des champs qu'une modification a réellement changés** (pour l'archive CSV des référentiels, les noms des référentiels emportés) et si elle a abouti ou été refusée, avec son code HTTP. L'auteur est :

| Auteur | Quand |
| --- | --- |
| `ADMIN` | une action de l'administration |
| `ANIMATEUR` | l'appel a **prouvé** l'être : session ouverte par le code e-mail, code juste échangé contre elle, adresse attestée par le proxy, ou jeton d'abonnement dédié |
| `ANONYME` | une route ouverte sans cette preuve |
| `ASSISTANT` | un appel MCP |
| `SYSTEME` | la tâche de nuit |

L'auteur se décide sur cette preuve, jamais sur le code HTTP : un mauvais code est un 400, une demande de code bridée un 429. Le lien de l'espace seul ne prouve pas qui le tient ; l'animateur désigné par le jeton reste la cible de la ligne.

Deux choses n'y figurent pas, et c'est le contrat :

- **aucune valeur** : « nom, email » dit ce qui a bougé, jamais ce que c'est devenu ;
- **aucune identité** : la table stocke un identifiant ; `acteurNom` et `entiteNom` sont résolus depuis le référentiel **à la lecture**, si bien qu'une fiche supprimée laisse une ligne qui ne nomme plus personne.

### Ce qui est tracé

Les écritures et les sorties de données (exports PDF, ICS, CSV, archive des référentiels, scénario YAML, dump de base, envois de courriel) sont tracées, jamais les simples consultations. Un `POST` qui ne fait que calculer (prévisualisation, simulation, analyse préalable d'un fichier) est déclaré **sans trace, avec son motif**, dans `CatalogueActions`.

Un téléchargement est un `GET` et il est tracé quand même : il emporte des données de personnes. Les actions `EXPORT_*` (administration) et `TELECHARGEMENT_ESPACE_*` (planning téléchargé par l'animateur depuis son espace, attribué à l'animateur) ne comptent **pas** dans `/api/historique/changements` : sortir une copie ne périme aucune résolution.

Un téléchargement d'espace n'est tracé **qu'une fois l'identité prouvée**. Refusé avant (jeton inconnu en 404, session absente en 401), il n'écrit rien : c'est une lecture gratuite que n'importe qui peut répéter, et une ligne par tentative livrerait la table au premier venu. Ses réussites, et les refus rencontrés par un animateur authentifié, restent tracés. Deux familles de `GET` restent sans trace, avec leur motif : les fichiers d'exemple, qui ne portent personne, et `/api/abonnements/{token}/planning.ics`, relu seul par l'agenda toutes les quelques heures.

## Notifications planifiées

`GET` et `PUT /api/parametres/notifications` règlent les délais des envois de nuit **de cette édition**. Qu'ils partent ou non ne se règle pas ici : seule l'édition active envoie ([ADR 0072](repo:docs/decisions/0072-une-seule-edition-active.md)).

| Champ | Rôle |
| --- | --- |
| `heureRappelVeille` | heure locale à partir de laquelle le rappel J-1 peut partir. **23h00 au plus tard** : la tâche s'exécute une fois par heure et la fenêtre se ferme à minuit (après, « demain » serait faux). Une heure plus tardive tomberait entre deux exécutions et ne partirait jamais, donc elle est refusée (`400`) plutôt qu'acceptée en silence |
| `delaiRelanceHeures` | silence toléré après la publication avant une relance (1 à 720) |
| `ancienneteEchangeJours` | attente d'une demande d'échange avant alerte (1 à 60) |

L'activation est explicite parce qu'une `Edition` ne porte **ni dates ni drapeau « en cours »** : aucune tâche ne peut deviner que les animateurs de l'édition 2025 ne sont pas ceux à prévenir pour demain. Une édition neuve, dupliquée ou importée naît inactive. Le rythme de la machine n'est pas dans l'API : `NOTIFICATIONS_CRON` et `NOTIFICATIONS_TIMEZONE`, voir [l'exploitation](../installer/exploitation.md). Le mécanisme des tâches est décrit dans [tâches de fond et notifications](taches-de-fond-et-notifications.md).

`GET /api/alertes` rend ce que ces tâches ont laissé sur le bureau, plus récent d'abord. `limite` s'applique **par type** (50 par défaut, 500 au maximum) : une fiche sans adresse produit une alerte chaque soir, et un plafond global laisserait une poignée d'injoignables enterrer les `ALERTE_ECHANGE`, les seules qui appellent une **décision** et dont cet écran est l'unique point de sortie. La lecture est seule : une alerte se referme en traitant ce qu'elle signale. Le journal ne stocke qu'un `animateurId` ; `nomAffiche` est résolu à la lecture.

**Chaque envoi est réservé en base avant de partir**, sur une clé primaire `(edition_id, type, cle)` : la tâche insère et n'envoie que si l'insertion a écrit une ligne. Tourner toutes les heures, redémarrer au milieu ou rejouer à la main n'écrit donc à personne deux fois, et une demande d'échange n'est signalée **qu'une fois**, quel que soit son âge ensuite.

## Alerte météo

`GET` et `PUT /api/parametres/meteo` portent les réglages de l'édition : `actif`, `horizonJours` (1 à 14), les seuils de température et de rafales, l'orage et un préréglage de consigne suggéré par phénomène. S'y ajoutent l'état de la dernière interrogation (`state.outcome` : `READ`, `OUT_OF_FORECAST`, `NO_PLACE`, `UNREACHABLE`), `serviceEnabled` (`METEO_ENABLED`) et `editionMayEmit`. Le `PUT` porte `modifieLe` comme précondition, comme une ligne de référentiel : `409` si quelqu'un a enregistré entre-temps. Supprimer un préréglage suggéré laisse la suggestion vide.

`POST /api/parametres/meteo/test` interroge **maintenant**, avec les seuils de l'édition, que l'alerte soit active ou non, et résume la prévision par date (maxima, seuils franchis), sans lever d'alerte ni rien écrire. C'est la seule façon d'essayer l'alerte sur une date simulée : la tâche du matin prend « aujourd'hui » à l'horloge du jour J mais ne garde que les dates qu'Open-Meteo prévoit réellement (les seize jours à venir), si bien qu'une recette figée loin de la date réelle répond `outOfForecast`.

Une alerte ne pose jamais de consigne. Elle est une ligne de `GET /api/alertes` (`type` `METEO_ALERTE`, clé `date|PHÉNOMÈNE|palier`) dont `lien` mène à `/regles?onglet=consignes&date=…&nouvelle=1&prereglage=…` : le formulaire s'ouvre rempli, et l'admin relit les stands et enregistre lui-même.

- Une consigne déjà posée sur la date est nommée par l'alerte au lieu d'une suggestion.
- Une alerte sur **aujourd'hui** (à l'horloge du jour J) ne suggère rien et n'a pas de `lien` : une consigne ne se pose que sur un jour à venir.
- Une prévision stable ne relève pas d'alerte ; une prévision qui s'aggrave d'un palier (deux degrés, dix km/h, la grêle avec l'orage) en lève une nouvelle.

L'outil MCP `consulter_alertes_meteo` lit les mêmes alertes. Le choix de ces deux tâches : [ADR 0074](repo:docs/decisions/0074-deux-jobs-planifies-de-plus-webhooks-et-meteo.md).

## Webhooks sortants

`/api/webhooks` (administration) configure ce que l'application annonce à un outil extérieur. La configuration est **d'instance**, pas d'édition : le payload dit de quelle édition il parle, et seule l'édition active émet ([ADR 0072](repo:docs/decisions/0072-une-seule-edition-active.md)). Les formats et la garde anti-SSRF sont dans [la sécurité des appels sortants](../installer/securite-appels-sortants.md).

| Code d'événement | Quand | `donnees` |
| --- | --- | --- |
| `planning.publie` | une publication est partie | `instantaneId`, `destinataires`, `envoyes`, `sansEmail`, `echecs`, `differes`, `planningsChanges` |
| `echange.soumis` | une demande d'échange arrive sur le bureau | `nombre` |
| `echanges.en_attente` | la tâche de nuit signale des demandes qui dorment | `nombre`, `ancienneteMaxJours` |
| `disponibilites.declaree` | une déclaration attend d'être appliquée | `joursIndisponibles`, `souhaits` |
| `resolution.terminee` | une résolution est finie (écran, MCP ou file) | `score`, `faisable` |
| `sauvegarde.echec` | la sauvegarde nocturne a échoué (événement d'instance, `edition` vaut `null`) | `tentativeLe`, `raison`, `echecsConsecutifs` |
| `meteo.alerte` | l'interrogation du matin a levé des alertes | `nombre`, `alertes` : pour chacune `date`, `phenomene` (`chaleur`, `rafales`, `orage`), `valeur`, `seuil`, `palier`, `lieux` (un nombre), `prereglageSuggere` |
| `test` | « Envoyer un test » | aucune |

Un flux n8n filtre sur ces codes : ils ne se renomment pas. **Aucun ne porte de personne** (ni nom, ni adresse, ni jeton), seulement des comptes et des identifiants. La `raison` d'une sauvegarde en échec est une **catégorie** (« pg_dump a échoué », « pg_dump n'a pas terminé dans le délai imparti »), jamais le message de `pg_dump`, qui nomme l'hôte, l'utilisateur et des chemins du serveur ; le détail reste à l'écran Paramètres.

### Format générique et signature

```http
POST /webhook/planning HTTP/1.1
Content-Type: application/json
X-Planning-Evenement: planning.publie
X-Planning-Livraison: 7d1e…-…
X-Planning-Horodatage: 1752343380
X-Planning-Signature: sha256=2c6d5dd0…

{"id":"7d1e…","evenement":"planning.publie","survenuLe":"2026-07-12T18:03:00Z",
 "edition":{"id":"2026","nom":"Année 2026"},"donnees":{…},"lien":"https://…/publication"}
```

- `X-Planning-Signature` vaut `sha256=` suivi du HMAC-SHA256 hexadécimal de `horodatage + "." + corps`, avec le secret affiché **une fois** à la création du webhook ou à sa régénération. Le corps signé est, à l'octet près, celui reçu : vérifiez sur le corps brut, jamais sur un JSON re-sérialisé.
- `X-Planning-Horodatage` est en secondes Unix et fait partie de ce qui est signé. **Refusez au-delà de cinq minutes d'écart** : un corps rejoué plus tard ne passe plus.
- `X-Planning-Livraison` (et `id` dans le corps) identifie la livraison, **pas la tentative** : un réessai et un « Renvoyer » gardent le même. C'est la clé d'idempotence du récepteur.
- `survenuLe` est l'heure **réelle** de l'événement, même quand l'instance simule une autre date (mode jour J de démonstration).

Vérification en Python :

```python
import hashlib, hmac, time

def verifier(secret: str, horodatage: str, signature: str, corps: bytes) -> bool:
    if abs(time.time() - int(horodatage)) > 300:
        return False
    attendu = hmac.new(secret.encode(), horodatage.encode() + b"." + corps,
                       hashlib.sha256).hexdigest()
    return hmac.compare_digest("sha256=" + attendu, signature)
```

Dans n8n, le nœud *Webhook* doit garder le corps brut (option *Raw Body*), puis un nœud *Code* ; le module `crypto` y demande `NODE_FUNCTION_ALLOW_BUILTIN=crypto` dans l'environnement de n8n :

```javascript
const crypto = require('crypto');
const horodatage = $input.first().json.headers['x-planning-horodatage'];
const signature = $input.first().json.headers['x-planning-signature'];
const corps = Buffer.from($input.first().binary.data.data, 'base64');
const attendu = 'sha256=' + crypto.createHmac('sha256', $env.PLANNING_SECRET)
  .update(horodatage + '.').update(corps).digest('hex');
const frais = Math.abs(Date.now() / 1000 - Number(horodatage)) <= 300;
if (!frais || !crypto.timingSafeEqual(Buffer.from(attendu), Buffer.from(signature))) {
  throw new Error('Signature invalide');
}
return [{ json: JSON.parse(corps.toString('utf8')) }];
```

### Livraison et journal

Chaque événement crée une livraison par webhook actif abonné. Un premier essai part aussitôt, hors du fil de l'opération. Ensuite, une erreur réseau, un `408`, un `429` (dont le `Retry-After` est respecté, plafonné à 12 h) ou un `5xx` sont réessayés après 1 min, 5 min, 30 min, 2 h et 12 h : six tentatives au plus, puis `FAILED`. Un nom qui ne se résout pas (DNS) est réessayé de même.

Toute autre réponse, une redirection comprise, est définitive : `ABANDONED`. Il en va de même d'une adresse que la garde refuse, d'un secret que la clé n'ouvre plus et d'une livraison sur une édition qui n'est plus active : chaque essai, réessai et « Renvoyer » compris, repose la question.

| Route | Rôle |
| --- | --- |
| `GET /api/webhooks/{id}/livraisons` | le journal : les 100 dernières, gardées 30 jours (statut, tentative, code, durée, phrase fixe en cas d'erreur, jamais le corps de la réponse) |
| `POST /api/webhooks/livraisons/{id}/renvoi` | remet une livraison au début de son calendrier, sous le même identifiant |
| `POST /api/webhooks/{id}/test` | envoie l'événement `test` et attend la réponse, sans réessai : le seul appel synchrone (`maxAttempts` vaut `1`) |
| `POST /api/webhooks/{id}/secret` | régénère le secret HMAC |

Les secrets ne se relisent jamais. Le secret HMAC est dans la réponse de l'appel qui l'a généré (`POST /api/webhooks`, `POST /api/webhooks/{id}/secret`, ou un `PUT` qui fait passer un webhook au format générique), et l'adresse d'un webhook Slack, Discord ou Matrix revient réduite à son hôte. Sur un `PUT`, une adresse ou un jeton laissé vide **conserve** le précédent.

## Marque et mentions légales

`GET /api/branding` et `GET /api/mentions-legales` sont **publics**, comme `/api/config` : une application qui ne sait pas dire son nom avant connexion accueille ses visiteurs avec une barre vide, et une mention légale réservée aux connectés manque ses lecteurs (celui qui décide s'il fait confiance au site, l'animateur dont le lien a expiré). Le frontend lit la marque **une fois, avant son démarrage**.

- `productName` n'est jamais vide.
- **`logoUrl` vide signifie « aucun logo »**, jamais « le logo par défaut » : une instance par client, et aucun client n'hérite de la marque d'un autre.
- Les champs légaux valent la chaîne vide quand la clé correspondante de `LEGAL_MENTIONS` manque, et la page affiche alors **ce qui manque** plutôt qu'un éditeur inventé. Ils vivent en configuration et non dans le code : le dépôt est public, et pour un éditeur personne physique l'adresse et le numéro d'immatriculation sont des données personnelles.
- `mesureAudience` et `suiviErreurs` sont vrais quand le token Cloudflare et le DSN Sentry ont une valeur, celle que lisent vraiment le beacon et le SDK, non le fait d'avoir posé la variable d'environnement. La politique de confidentialité y accroche les paragraphes qui nomment Cloudflare et Bugsink : sans eux, un déploiement qui laisse les deux vides annoncerait deux traitements qui n'ont pas lieu. Ces drapeaux passent par cet endpoint et non par `/api/config`, qui porte pourtant les mêmes clés : sa lecture échoue en silence vers une configuration désactivée, ce qui **masquerait** ces paragraphes là où les outils tournent vraiment. Ici, un échec affiche une erreur au lieu d'une page silencieusement fausse.
- `demoInstance` dit si l'instance s'est déclarée de démonstration (`LEGAL_DEMO_INSTANCE`). La page ne l'affiche pas : `scripts/verifier-deploiement.sh` le lit pour savoir si des mentions obligatoires vides sont un défaut. Il le demande à l'instance plutôt qu'au `.env.prod`, qui ne voit ni une valeur posée ailleurs ni les graphies (`True`, guillemets) que l'application accepte.

## Exports

La génération se fait côté serveur. Le planning est envoyé dans le corps de la requête (`POST /api/planning/export/pdf/animateur/{animateurId}`, `POST /api/planning/export/ics/animateur/{animateurId}`, `POST /api/planning/export/pdf/all`, `POST /api/planning/export/ics/all`, `POST /api/planning/export/bundle/all`), **sauf l'export global** `GET /api/planning/export/pdf/global` : un planning de la taille de l'événement pèse plusieurs mégaoctets en JSON, que l'appelant n'a pas à téléverser pour récupérer un document. Cet export lit donc la même source que les calendriers. Les formats et l'archive de fin d'événement : [exports et archives](exports-et-archives.md).

L'envoi par courriel est le renvoi individuel `POST /api/planning/envoi/animateur/{animateurId}`, qui répond un compte rendu nommant ceux sans adresse et les échecs. L'envoi collectif est la publication ([API de publication](api-planning-et-publication.md)).

## Sauvegarde automatique

La sauvegarde de nuit est réglée par l'hébergeur, pas par l'API ; deux routes, en lecture pour l'essentiel.

`GET /api/backups` rend l'état complet : emplacement et rétention (variables d'environnement), interrupteur, prochaine exécution, compte rendu de la dernière tentative et liste des dumps présents (nom, taille, date). `PUT /api/backups/active` avec `{ "active": false }` **suspend** la sauvegarde de nuit, `true` la reprend. C'est la seule valeur que l'écran écrit.

Trois absences sont voulues et durables :

- **Pas de téléchargement d'un dump.** Il porte les noms, dates de naissance et adresses de tous les animateurs, mineurs compris : le navigateur n'est pas un canal de diffusion pour cela.
- **Pas de restauration.** C'est une opération sur la base PostgreSQL, faite par l'exploitant avec `pg_restore` ; voir [la sauvegarde et la restauration](../installer/sauvegarde-et-restauration.md).
- **Pas d'écriture de l'emplacement ni de la rétention.** Un chemin de disque et le nombre de copies qu'un volume porte se décident avec ce volume (`BACKUP_DIR`, `BACKUP_RETENTION`), pas depuis un écran.

Comme le dump SQL, ces routes ignorent `X-Edition-Id` : une sauvegarde prend l'instance entière.

## Import de scénario

L'import est un diff, non un remplacement aveugle. Les stands et animateurs du fichier sont mis à jour **en place** : un animateur conservé garde son `access_token` (les liens imprimés survivent), ses sessions, ses demandes hors créneaux remplacés et son e-mail si le fichier n'en porte pas. Seuls les absents du fichier sont supprimés, en cascade. Sont en revanche **toujours** effacés le planning résolu, les verrous, les contraintes ad hoc et la trace de résolution ; les créneaux sont remplacés par ceux du fichier.

`GET /api/reference-data/impact-import` chiffre ce périmètre **avant** d'importer : c'est ce qu'affiche le dialogue de confirmation, qui enregistre aussi un instantané quand un planning résolu existe. Les sections optionnelles du fichier (`parametresLegaux`, `parametresSolveur`, `typologies`, `contraintes`, `edition`) sont appliquées si présentes et laissées telles quelles sinon. `edition:` route l'import vers l'édition désignée, créée vide au besoin. Les routes d'import sont `POST /api/reference-data/import-scenario` et `POST /api/reference-data/import-scenario-fichier`; le format est décrit dans [le scénario YAML](scenario-yaml.md).

Le validateur, `POST /api/reference-data/valider-scenario-fichier`, contrôle la structure sans rien importer et répond **toujours 200**, y compris pour un fichier vide ou mal formé : le verdict est dans le corps. Il ne remplace pas les vérifications de références croisées d'un import réel. Les autres formats d'import sont dans [les imports CSV](imports-csv.md) et la reprise d'une édition dans [restaurer une édition](restaurer-une-edition.md).

## Deux pièges

**Un dump pris avant la migration `V52` ne se réimporte plus.** Le dump nomme ses colonnes une à une, et `V52` a renommé `animateur.jeton_acces` en `access_token` : l'import refuse avec `column "jeton_acces" ... does not exist`. Faire vivre les deux noms rendrait permanent le décalage que `V52` supprime. Pour récupérer un tel fichier, remplacez cette chaîne avant de le charger : c'est sa seule occurrence.

**`POST /api/planning/reset` vide l'édition courante**, pas la base. Les autres éditions ne sont pas touchées.
