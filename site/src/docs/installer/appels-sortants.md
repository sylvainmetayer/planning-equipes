# Autoriser les webhooks et l'alerte météo

Vous ouvrez au conteneur de l'application les sorties HTTPS dont deux fonctions ont besoin, les webhooks (n8n, Slack, Discord, Matrix, Telegram) et l'alerte météo, et vous réglez leurs variables. Le conteneur a besoin d'un accès **HTTPS sortant** vers ces services ; `docker-compose.prod.yml` n'impose rien, et un pare-feu d'egress peut se limiter aux hôtes des webhooks configurés (`hooks.slack.com`, `discord.com`, `api.telegram.org`, votre n8n…). Ce que la garde anti-SSRF refuse, et pourquoi, est dans [la maîtrise des sorties](securite-appels-sortants.md).

## Les webhooks

Ils se configurent dans *Paramètres › Instance* (derrière « Réglages avancés »), carte *Webhooks*.

![Les webhooks, dans Paramètres, onglet Instance](/images/fr/docs/webhooks.webp "La carte Webhooks des paramètres de l'instance")

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `WEBHOOKS_ENABLED` | `true` | `false` coupe toute sortie : rien n'est mis en file ni envoyé, le test est refusé |
| `WEBHOOKS_SECRET_KEY` | — | Clé AES-256 (32 octets en base64, `openssl rand -base64 32`) qui chiffre au repos les secrets des webhooks |
| `WEBHOOKS_RESEAUX_AUTORISES` | — | Adresses et blocs CIDR internes qu'un webhook peut viser (un n8n sur le réseau Docker) ; `http://` et tout port y sont acceptés. Mal formé : démarrage refusé |
| `WEBHOOKS_CRON` | `0 * * * * ?` | La reprise des livraisons en attente de réessai |

### Poser et ranger la clé

Sans `WEBHOOKS_SECRET_KEY`, l'écran refuse de créer un webhook ; et **dès qu'un webhook existe, l'application refuse de démarrer sans elle**, ou avec une autre clé que celle qui a chiffré ses secrets, parce que des secrets illisibles laisseraient croire que les livraisons partent.

Rangez-la avec `DB_PASSWORD` et `SESSION_ENCRYPTION_KEY`, **hors** de la sauvegarde de la base : la sauvegarde nocturne (`pg_dump` de tout le cluster) ne contient que le chiffré des secrets, et c'est tout l'intérêt, car un dump volé ne donne accès à aucun salon. Perdre la clé, c'est perdre les secrets : il faut alors recréer chaque webhook (ou, sur une base où l'on a perdu la clé, supprimer les lignes de la table `webhook` pour que l'application redémarre). Changer de clé n'est pas prévu : il n'y a pas de rotation, une nouvelle clé ne lit pas les anciens secrets.

### Comment une livraison se passe

Un premier essai part aussitôt, hors du fil de l'opération qui l'a causé. En cas d'erreur réseau, de `408`, de `429` ou de `5xx`, la tâche de chaque minute réessaie après 1 min, 5 min, 30 min, 2 h et 12 h ; un nom qui ne se résout pas aussi, donc une panne DNS passe. Toute autre réponse est définitive. Les livraisons en attente reprennent après un redémarrage.

Le journal (bouton *Journal des livraisons*) est purgé au-delà de 30 jours par la tâche de nuit des envois automatiques. Seule l'édition **active** (page Éditions) émet ses événements ; l'échec de la sauvegarde nocturne, événement de l'instance, part toujours.

## L'alerte météo

Chaque matin, l'édition active interroge Open-Meteo sur les lieux géolocalisés de ses stands ouverts, et suggère un préréglage de consigne quand un seuil est franchi (*Paramètres › Édition*, carte *Alerte météo*). Le conteneur doit joindre `api.open-meteo.com:443`, ou l'adresse de `METEO_URL`.

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `METEO_ENABLED` | `true` | `false` coupe la fonctionnalité pour l'instance : aucune interrogation, le test est refusé |
| `METEO_URL` | `https://api.open-meteo.com/v1/forecast` | Le service interrogé : l'API gratuite, l'offre commerciale ou une instance auto-hébergée |
| `METEO_CRON` | `0 0 6 * * ?` | L'heure de l'interrogation, dans le fuseau `NOTIFICATIONS_TIMEZONE` |

- **L'API gratuite d'Open-Meteo est réservée à un usage non commercial.** Un hébergeur qui facture l'instance passe par l'offre payante d'Open-Meteo, ou par une instance auto-hébergée (image Docker officielle) qu'il désigne dans `METEO_URL`.
- **Attribution** : les données d'Open-Meteo sont sous licence CC BY 4.0 ; l'écran de réglage et le courriel d'alerte portent « Données météo : Open-Meteo.com (CC BY 4.0) ». Ce n'est pas une dépendance du logiciel : elle n'a pas sa place dans [l'inventaire des licences tierces](licences-tierces.md).
- Seule l'édition active est interrogée, et seulement si son alerte est activée ; entre deux événements, aucune ne l'est. « Aujourd'hui » suit l'horloge simulée d'une recette, mais Open-Meteo ne prévoit que les seize jours réels à venir : une date simulée hors de cette fenêtre n'interroge rien, et l'écran dit « hors prévision ». Le bouton *Tester maintenant* interroge à la demande, sans lever d'alerte.
- Un service injoignable n'arrête rien : l'état « injoignable depuis… » s'affiche, la tâche réessaie le lendemain, et un seul avertissement part au deuxième jour d'échec consécutif.

## Vérifier

Depuis l'écran, le test d'un webhook (bouton de la carte) et le bouton *Tester maintenant* de l'alerte météo disent si la sortie passe. Un refus de la garde est expliqué dans le message ; un blocage de pare-feu apparaît comme une erreur réseau dans le *Journal des livraisons*.
