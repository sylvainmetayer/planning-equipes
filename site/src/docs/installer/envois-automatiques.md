# Régler les envois automatiques

Vous réglez la tâche planifiée qui envoie le rappel de la veille, les relances et les alertes, vous activez la bonne édition, et vous savez dans quel ordre vérifier quand « personne n'a rien reçu ».

Quatre envois partagent une tâche unique : le rappel de la veille, la relance des animateurs qui n'ont pas accusé réception de leur planning, l'alerte sur les demandes d'échange qui attendent une décision, et la relance de la collecte des disponibilités.

## 1. Régler le rythme et le fuseau

```properties
NOTIFICATIONS_CRON=0 5 * * * ?
NOTIFICATIONS_TIMEZONE=Europe/Paris
```

![L'envoi horaire des notifications planifiées](repo:docs/diagrammes/notifications-planifiees.svg)

<sub>Source : [`diagrammes/notifications-planifiees.puml`](repo:docs/diagrammes/notifications-planifiees.puml).</sub>

Le cron est **horaire**, et ce n'est pas une erreur de réglage. L'heure d'envoi du rappel se décide **par édition**, dans *Paramètres*, onglet *Édition*, carte *Rappels et relances automatiques* ; une tâche unique ne peut pas partir à quatre heures différentes pour quatre éditions. Elle se réveille donc souvent, et chaque édition envoie quand son heure à elle est passée. C'est sans danger parce que chaque envoi est réservé en base avant de partir : tourner douze fois dans la soirée n'écrit à personne deux fois.

Le fuseau est celui de l'événement, réglé à part (`NOTIFICATIONS_TIMEZONE`), et non celui du conteneur (`TZ`, voir [sauvegarder et restaurer](sauvegarde-et-restauration.md#1-laisser-la-sauvegarde-de-nuit-tourner)) : « 18 h » sur l'écran doit vouloir dire 18 h pour la personne qui lit, quel que soit le fuseau de la machine.

**L'heure d'envoi du rappel est plafonnée à 23h00**, et l'écran refuse plus tard. La fenêtre d'envoi va de l'heure réglée à minuit : après, le jour annoncé a commencé et « demain » serait faux. Une heure plus tardive laisserait une fenêtre plus courte que l'intervalle entre deux exécutions, et le rappel tomberait entre deux réveils sans partir ni rien dire. Si vous espacez `NOTIFICATIONS_CRON` au-delà d'une exécution par heure, ce plafond ne suffit plus à le garantir.

## 2. Activer l'édition qui doit écrire

**Rien ne part que de l'édition active** ([ADR 0072](repo:docs/decisions/0072-une-seule-edition-active.md)). La page *Éditions* porte le geste « Activer » ; au plus une édition est active, et aucune entre deux événements. C'est le seul garde-fou : une édition ne porte ni dates ni drapeau « en cours », donc sans lui la première nuit après une mise en production rappellerait aux bénévoles de l'an dernier qu'ils sont attendus demain. Toute édition naît inactive (création, duplication, import) : quelqu'un doit décider de l'activer.

**À la mise à jour vers `V118`**, l'édition active est choisie ainsi : la seule édition armée s'il y en avait exactement une, sinon l'édition par défaut, sinon la plus ancienne. Le choix est écrit dans le journal de démarrage (un `NOTICE` de Flyway) : vérifiez-le sur la page *Éditions* avant la première nuit.

Le bandeau « Action requise » de l'administration signale une édition active dont le dernier jour est passé, ou une édition inactive qui commence dans les `EDITIONS_RAPPEL_JOURS` prochains jours (7 par défaut).

## 3. Vérifier quand rien n'arrive

Dans l'ordre où ça casse :

1. **L'interrupteur de l'édition.** C'est la cause de « je n'ai rien reçu » dans neuf cas sur dix, et il est silencieux par construction.
2. **Le plan doit être publié.** Le rappel lit le planning **publié**, jamais le plan de travail : personne n'est rappelé pour un créneau qu'on ne lui a pas communiqué.
3. **Les fiches sans adresse.** Elles sont sautées, comptées dans « À traiter aujourd'hui » de l'accueil et nommées dans ses « Messages récents » : ce sont les gens à prévenir à la main.
4. **Les envois en échec.** Un rappel ou une relance que le relais a refusé laisse une alerte rouge dans les « Messages récents » de l'accueil, et la personne **n'est pas comptée comme relancée** : elle reste « échec d'envoi » sur la page Animateurs. La nuit ne réessaie pas, parce qu'un relais en panne à 19 h l'est souvent à 20 h et que réécrire chaque heure n'arrangerait rien. « Relancer maintenant » reste ouvert pour elle une fois le relais rétabli, quel que soit le courriel parti depuis, et deux clics simultanés n'écrivent qu'une fois. Seule exception : une adresse refusée, dont la relance reprend d'elle-même au passage qui suit la correction de la fiche ([les catégories d'échec](courriels.md#4-lire-ce-qui-est-gardé-de-chaque-envoi)).

## 4. La relance de la collecte des disponibilités

Elle s'allume avec la fenêtre de collecte, dans *Paramètres*, onglet *Édition*, carte *Guichets*. Elle est **éteinte par défaut**, y compris sur une collecte déjà ouverte avant la mise à jour. Allumée, elle écrit une fois à chaque animateur **invité** et **sans aucune déclaration**, avec le lien de la page « Mes disponibilités » de son espace. Le guide côté organisateur est [Collecter les disponibilités](../regles/collecter-disponibilites.md).

| Règle | Pourquoi |
| --- | --- |
| Invité = une invitation à déclarer **partie** (`envoi_mail`, modèle `invitation-declaration`, état `ENVOYE`) | C'est la seule trace de l'invitation ; une invitation en échec n'a rien appris à la personne. Au-delà de `JOURNAL_RETENTION`, la ligne est purgée et la personne ne compte plus comme invitée |
| Sans déclaration = aucune, quel que soit son statut | Une déclaration appliquée ou refusée est une réponse |
| Au premier passage de 9 h ou plus tard (`NOTIFICATIONS_TIMEZONE`) d'un jour où la fin est dans 0 à 3 jours | Un courriel de minuit n'est lu par personne. Avec le cron livré, horaire, c'est le passage de 9 h 05 ; un `NOTIFICATIONS_CRON` qui ne passe jamais à 9 h ou après (une seule fois la nuit, par exemple) **n'envoie aucune relance**. Un jour sans passage est rattrapé le lendemain, jusqu'au dernier jour de la collecte |
| Une fois par personne et par date de fin (clé `animateur\|fin`) | Déplacer la fin, c'est une nouvelle échéance : une nouvelle relance devient possible |
| Rien sans date de fin, collecte fermée ou édition inactive | Pas d'échéance à rappeler, personne à qui l'on puisse encore répondre, ou pas l'événement en cours ([ADR 0072](repo:docs/decisions/0072-une-seule-edition-active.md)) |

Un envoi en échec n'est pas retenté chaque heure : chaque jour réserve sa propre tentative (clé `animateur|fin|jour`), mais l'échec rend la réservation de l'échéance, si bien que le passage de 9 h du lendemain réessaie, jusqu'au dernier jour de la collecte. Il laisse une alerte rouge dans les « Messages récents » de l'accueil, une par échéance. Une adresse que le serveur de messagerie a refusée n'est plus essayée tant que la fiche n'a pas été modifiée, et une fiche sans adresse ne l'est pas du tout : chacun de ces deux cas laisse un avertissement, une fois par échéance, qui désigne la fiche sans la nommer. Chaque envoi, parti ou en échec, s'inscrit dans `envoi_mail` sous le modèle `relance-collecte`.
