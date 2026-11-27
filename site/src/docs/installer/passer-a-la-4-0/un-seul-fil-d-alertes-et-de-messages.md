---
ordre: 250
---

### Un seul fil d'alertes et de messages sur l'accueil

- **Avant (3.x)** : l'accueil repliait sous « À traiter aujourd'hui » une section « Messages récents » (les alertes des envois de nuit, puis l'historique des messages de l'application), et la ligne « Cohérence du référentiel » de la liste des étapes dépliait ses anomalies en place. La page Notifications avait déjà été absorbée par l'accueil.
- **Après (4.0)** : un seul fil, sous « À traiter aujourd'hui », réunit les alertes du plan (envois de nuit, alerte météo, personnes à prévenir après une exclusion retirée, échanges qui dorment), les règles dures que le dernier calcul n'a pas pu tenir (une ligne par règle, avec son nombre de correspondances et un lien vers Diagnostic › Problèmes), les anomalies de la cohérence du référentiel et les messages de l'application. Il est trié par gravité (bloquants, puis à vérifier, puis messages), puis du plus récent au plus ancien, et se filtre par type : `/?type=alertes`, `/?type=blocages`, `/?type=coherence` ou `/?type=messages`. La cloche de la barre d'outils y mène toujours ; la ligne « Cohérence du référentiel » ne déplie plus rien et mène au fil, filtré sur son type ; l'ancienne adresse `/notifications` ouvre le fil sur les messages. Les personnes y sont désignées comme avant : par leur nom là où l'écran le retrouve à l'affichage, par leur identifiant dans les anomalies et les alertes dont la fiche a disparu. L'onglet Problèmes du Diagnostic ne change pas.
- **Ce que vous faites** : rien côté serveur, ni variable ni migration. Un signet ou un lien vers `/#alertes-nuit` ou `/notifications` arrive en haut de l'accueil, sans défiler ; remplacez-le par `/?type=alertes#a-traiter` pour retrouver seulement les alertes.
