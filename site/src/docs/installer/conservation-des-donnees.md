# Purger et conserver les données

Vous savez ce que l'application efface toute seule, ce qu'elle garde jusqu'à ce que vous le supprimiez, et vous tenez la purge annuelle qui fait respecter la durée annoncée aux animateurs.

## Ce qui s'efface tout seul

Trois choses s'effacent seules : l'historique des actions, le journal des connexions administrateur et le résultat des courriels envoyés aux animateurs ([ce qu'on en garde](courriels.md#4-lire-ce-qui-est-gardé-de-chaque-envoi)).

`JOURNAL_RETENTION` (90 jours par défaut, au format ISO 8601 : `P30D`, `P1Y`) borne les tables `journal_action`, `journal_connexion` et `envoi_mail`, que la tâche de nuit purge au passage. Hors des bornes admises, un jour au minimum et dix ans au maximum, **l'application refuse de démarrer** plutôt que de rogner la valeur en silence, comme pour `BACKUP_RETENTION` : à `P0D`, chaque nuit viderait l'historique entier sans autre trace qu'une ligne de journal applicatif.

La page *Historique* la lit ; personne ne l'écrit à la main et rien ne l'efface au bouton. Allonger cette durée est un choix à assumer : la table entre dans chaque sauvegarde, et c'est un traitement consigné au registre ([ce qu'un hébergeur doit tenir](rgpd.md)).

## Ce qui reste jusqu'à ce que vous le supprimiez

Pour tout le reste, la durée annoncée par la clé `conservation` de `LEGAL_MENTIONS` s'affiche dans la politique de confidentialité et **rien dans le code ne l'applique** : aucune purge automatique n'existe aujourd'hui. Un engagement écrit qui n'est pas tenu expose plus qu'une absence d'engagement, donc la purge se fait à la main, à date fixe. C'est voulu, et ce que l'automatisation devra porter le jour venu est écrit dans la [décision 0016](repo:docs/decisions/0016-purge-manuelle-avant-automatisation.md).

### La purge annuelle

À faire une fois par an, après l'événement :

1. exportez un dump ([sauvegarder à la main](sauvegarde-et-restauration.md#2-sauvegarder-à-la-main)) et archivez-le **chiffré** : c'est la trace ;
2. supprimez, depuis l'écran *Éditions*, les **éditions** dont la durée de conservation est échue. La suppression est en cascade sur les tables portant `edition_id` : animateurs, plannings, instantanés et jetons partent avec ;
3. vérifiez que l'espace animateur d'un jeton supprimé répond bien `404` ;
4. notez la date et le périmètre dans le registre des traitements, dans la forme attendue par [le journal des purges](rgpd.md).

### L'effacement demandé en cours d'édition

La procédure ci-dessus ne couvre pas les données d'une édition **encore active** dont un animateur a demandé l'effacement. Aujourd'hui, c'est une suppression manuelle de sa fiche, **puis des sauvegardes avant vidage de son édition qui la portent encore**, soit toutes celles prises avant la suppression de la fiche. Vous les supprimez une à une par `DELETE /api/planning/sauvegardes-avant-vidage/{id}` (journalisé), après les avoir listées par `GET /api/planning/sauvegardes-avant-vidage` (le cadre RGPD est dans [les limites à consigner](rgpd.md)).

Ces sauvegardes ne sont **jamais purgées automatiquement** ([décision 0084](repo:docs/decisions/0084-sauvegarder-l-edition-avant-de-la-vider.md)) : une par vidage (réinitialisation, import, restauration), hors état identique au précédent, conservée jusqu'à la suppression de l'édition, qui les emporte en cascade. La purge annuelle les couvre donc. Chacune pèse de quelques centaines de kilooctets à un mégaoctet ; supprimer celles qui ne servent plus est un geste d'administrateur, par la même route.

Automatiser la purge ne réglerait pas ce cas : elle raisonne par édition échue.

### L'archive du covoiturage 3.x

La migration de la 4.0 range les demandes de covoiturage que la 3.x avait écartées ou annulées, avec le motif libre écrit pour l'animateur, dans la table `declaration_coequipier_avant_4_0` ([ce qu'elle contient](rgpd-limites-a-consigner.md#les-demandes-de-covoiturage)). L'application ne la lit plus et ne l'exporte pas. Elle est **gardée jusqu'à la 5.0**, qui la supprime ; d'ici là, elle part avec son édition (la purge annuelle la couvre) ou avec la fiche du demandeur.

Une fois la 4.0 en place et le retour à la 3.x exclu, rien ne vous oblige à attendre : `DROP TABLE declaration_coequipier_avant_4_0;` la supprime, sans effet sur l'application. Notez-le au registre comme une purge.

## La mesure du réalisé

Aucune tâche planifiée ne la calcule : l'écran « Mesures d'édition » la calcule à la demande ([ADR 0092](repo:docs/decisions/0092-le-realise-se-calcule-a-la-demande.md)). La première lecture faite une fois le dernier créneau de l'édition terminé, quand quelque chose a été publié, écrit la mesure dans `mesure_edition` (nature `REALISE`) : absences, remplacements, sièges vides et heures perdues, par typologie, sans aucune personne.

C'est ce que l'édition suivante lit sur son écran Versions et sur l'onglet Besoin : cette lecture calcule d'abord la mesure de chaque édition terminée et publiée qui n'en a pas, et la suppression d'une édition écrit la sienne avant de l'effacer. Il n'y a donc rien à ouvrir à la main. Une horloge simulée n'écrit rien.

Variables retirées : `REALISE_CRON` et `REALISE_TIMEZONE` ne sont plus lues (les anciennes tâches de nuit sont supprimées). Les laisser dans votre environnement est sans effet, mais vous pouvez les enlever.

La mesure **n'est écrite qu'une fois** par édition : une correction faite après coup (une absence saisie le lendemain) ne la modifie pas. Elle n'est pas écrite, et le journal applicatif le dit, tant que le passé figé est désactivé (`PASSE_FIGE=false`) : le réalisé n'y est plus garanti. La mesure **survit à la suppression de l'édition**, c'est son objet.

Pour **refiger** une édition après une correction qui doit compter, il n'y a pas de bouton : supprimez ses lignes, la prochaine lecture les réécrit à partir du plan du moment.

```sql
DELETE FROM mesure_edition WHERE nature = 'REALISE' AND edition_id = '<id de l''édition>';
```

L'identifiant se lit sur l'écran Éditions. La mesure supprimée disparaît aussi de l'écran Versions de l'édition suivante jusqu'à la lecture qui la réécrit ; une édition supprimée entre-temps ne sera plus jamais refigée.
