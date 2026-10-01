# 0074 — Deux jobs planifiés de plus : la reprise des webhooks et l'alerte météo

- **Statut** : accepté, implémenté
- **Date** : octobre 2026
- **Portée** : planification (`@Scheduled`), appels sortants, webhooks, alerte météo
- **Révise** : [0070](0070-un-troisieme-job-planifie-fige-le-realise.md), qui présentait le réalisé figé comme le troisième **et dernier** `@Scheduled`
- **S'appuie sur** : [0072](0072-une-seule-edition-active.md), l'édition active, seule à parler dehors

## Contexte

L'application avait trois `@Scheduled` — la sauvegarde de nuit (0015), les
envois de nuit, le réalisé figé (0070) — et l'ADR 0070 écrivait que le
troisième serait le dernier : chaque planificateur est un cron, un fuseau, un
comportement en test et une ligne d'exploitation de plus, et la purge de
l'historique s'était greffée sur les envois de nuit pour ne pas en ajouter un.

Deux fonctionnalités en demandent un chacune, et pour des raisons que la
greffe ne sert pas :

- les **webhooks sortants** (Slack, Discord, Matrix, Telegram, ou n8n en JSON
  signé) réessaient une livraison échouée après 1 min, 5 min, 30 min, 2 h et
  12 h. Le balayage horaire des envois de nuit passerait à côté des trois
  premiers délais ;
- l'**alerte météo** interroge Open-Meteo une fois par jour pour l'édition
  active, à une heure d'instance. Elle n'envoie pas de rappel aux animateurs et
  ne partage pas le rythme horaire des envois de nuit.

Ce sont aussi les deux premiers **appels HTTP sortants** du serveur, ce qui
pose les questions que SMTP et Sentry ne posaient pas : le SSRF sur une
adresse tapée par un administrateur, et des secrets — une adresse Slack en est
un — que la sauvegarde nocturne emporte.

## Décision

**Deux `@Scheduled` de plus, le quatrième et le cinquième**, construits sur les
conventions des trois autres : cron configurable, `SKIP` en cas de
chevauchement, échec journalisé et jamais propagé, désactivés sous `%test`
comme les autres (les tests appellent les services directement).

### Le quatrième : la reprise des webhooks, chaque minute

`WebhookDeliverer` (`WEBHOOKS_CRON`, `0 * * * * ?`) reprend les livraisons
dont le prochain essai est échu, y compris celles qu'un redémarrage a
interrompues. Le **premier essai**, lui, part aussitôt, sur un petit groupe de
fils à lui — jamais sur le fil de l'opération qui a causé l'événement. Un bail
en base (`en_cours_depuis`) empêche l'essai immédiat et le balayage de prendre
la même ligne ; un bail plus vieux que deux minutes est celui d'un redémarrage
et se reprend.

Autour de lui, quatre choix ferment des alternatives :

- **Configuration d'instance, pas d'édition.** Les tables `webhook` et
  `webhook_livraison` n'ont pas d'`edition_id` ; le payload dit de quelle
  édition il parle. Une configuration par édition aurait été à refaire chaque
  fois qu'une autre édition prend le relais — ou, pire, se serait tue sans
  bruit. Seule l'**édition active** (0072) produit ses événements, et cette
  autorisation se lit à un seul endroit, `OutboundEditionPolicy`, qui pose la
  question à `EditionContext` — à la mise en file puis **à chaque essai** :
  une livraison réessayée ou renvoyée après le passage de relais parlerait
  pour une édition qui s'est tue, elle est abandonnée. L'échec de la
  sauvegarde nocturne est un événement d'instance et part toujours.
- **Pas d'observateur `AFTER_SUCCESS`.** L'application n'ouvre aucune
  transaction JTA (`JdbcEditionScope` gère les siennes en JDBC) ; sans
  transaction active, CDI appelle un observateur transactionnel tout de suite,
  et l'annotation promettrait un commit qui n'existe pas. Chaque fait est tiré
  après son écriture : un `@Observes` simple **insère** les livraisons dans un
  `try/catch` qui ne propage rien, et l'envoi a lieu ailleurs. Les faits
  observés sont les `Notification` existantes, par un `switch` exhaustif qui
  oblige à décider, pour chaque nouveau cas, s'il sort de l'application ; la
  publication, qui n'a pas de courriel à elle, tire un événement à part
  (`PlanningPublished`) plutôt qu'un cas de plus du type scellé — qui devrait
  alors un courriel.
- **Le client Vert.x, connecté à l'adresse vérifiée.** La garde résout le nom,
  vérifie chaque adresse (bouclage, RFC 1918, lien local et métadonnées cloud,
  ULA, multicast… sauf `WEBHOOKS_RESEAUX_AUTORISES`), et la requête part vers
  **l'adresse vérifiée** tandis que TLS vérifie le certificat contre le nom :
  le *DNS rebinding* n'a plus de fenêtre. Le client HTTP du JDK n'offre aucune
  prise sur la résolution, et se connecter à une IP avec lui imposerait de
  couper la vérification du nom dans le certificat ; son résolveur global
  s'appliquerait aussi à PostgreSQL. `vertx-web-client` devient une
  dépendance directe — il était déjà dans la fermeture d'exécution.
- **Les secrets chiffrés au repos** (AES-256-GCM, `WEBHOOKS_SECRET_KEY` dans
  l'environnement) : le secret HMAC, l'adresse d'un salon Slack, Discord ou
  Matrix, le jeton d'un bot Telegram. L'export SQL ne les emporte pas, mais la
  sauvegarde nocturne prend tout le cluster : chiffrer est ce qui rend vrai
  « un dump ne contient que du chiffré ». Sans la clé, rien ne se crée ; dès
  qu'un webhook existe, l'application refuse de démarrer sans elle.

Les payloads sont **toujours anonymes** — des comptes et des identifiants.
Une option « inclure les noms » a été écartée pour cette version : envoyer le
nom d'un mineur à une plateforme hébergée hors UE est le pire cas du registre,
et aucun événement n'en a besoin.

### Le cinquième : l'alerte météo, une fois par jour

`WeatherAlertJob` (`METEO_CRON`, `0 0 6 * * ?`, fuseau
`NOTIFICATIONS_TIMEZONE`) interroge Open-Meteo pour l'édition active, que
`OutboundEditionPolicy` désigne, si son alerte météo est activée ; entre deux
événements, aucune n'est active et rien n'est interrogé. Il
**ne pose jamais de consigne** : il suggère un préréglage, et l'organisateur
décide. L'heure est **d'instance** : une heure par édition aurait obligé le
job à tourner toutes les heures pour attendre la sienne, ce qui est
exactement la contrainte des envois de nuit — et il n'y a qu'une édition qui
émet.

« Aujourd'hui » est celui de `JourJClock` — une recette sur une date simulée
voit les jours qu'elle simule —, mais Open-Meteo ne prévoit que le futur
réel : les dates interrogées sont l'intersection de
`[aujourd'hui simulé ; + horizon]`, `[aujourd'hui réel ; + 15 jours]` et des
jours de l'événement. Une intersection vide n'interroge rien et l'écran dit
« hors prévision ». `METEO_URL` vient de l'exploitant : la garde anti-SSRF des
webhooks ne s'y applique pas, sans quoi une instance Open-Meteo auto-hébergée
sur le réseau Docker serait refusée.

## Options écartées

- **Greffer la reprise des webhooks sur les envois de nuit**, ou sur un
  `ScheduledExecutorService` interne rejoué au démarrage. Le premier rate les
  délais courts ; le second est un planificateur de plus, mais caché — sans
  cron, sans désactivation en test, sans ligne dans l'exploitation.
- **Greffer l'alerte météo sur le balayage horaire des envois de nuit**, avec
  une heure par édition : c'est la seule raison d'être horaire de ce job, et
  l'alerte ne la partage pas.
- **Accepter le TOCTOU** (résoudre, vérifier, puis laisser le client du JDK
  résoudre à nouveau) en le documentant : simple, mais la fenêtre est
  précisément celle qu'un rebinding exploite.
- **Un proxy de sortie imposé** : il déplace la garde hors de l'application
  sans l'écrire, et rien ne garantit qu'un déploiement en a un.
- **Des secrets en clair**, la sauvegarde étant déjà à protéger comme la base :
  un secret de webhook ouvre un salon d'équipe, et il n'y a aucune raison qu'un
  dump volé l'ouvre aussi.

## Conséquences

- Cinq `@Scheduled`, et pas de dernier annoncé : un sixième se justifie par un
  rythme ou un périmètre qu'aucun des cinq ne sert, et s'écrit dans une ADR qui
  révise celle-ci.
- Le conteneur a besoin d'un accès HTTPS sortant (salons, n8n, Open-Meteo) ;
  `WEBHOOKS_ENABLED=false` et `METEO_ENABLED=false` coupent chacun le leur.
- `WEBHOOKS_SECRET_KEY` rejoint les secrets à garder hors de la sauvegarde de
  la base ; la perdre, c'est recréer chaque webhook.
- `OutboundEditionPolicy` est le seul endroit qui décide qu'une édition peut
  émettre : si la règle de 0072 change, c'est lui qu'on réécrit.
