---
ordre: 50
---

### Format du scénario YAML

- **Avant** : l'application écrit `schemaVersion: 1`.
- **Après** : elle écrit `schemaVersion: 5`. Elle lit toujours les fichiers v1 (clé absente ou égale à 1) : ils traversent une chaîne de conversions, maillon par maillon, au fil des changements de modèle de la 4.x. Ce que la chaîne ne peut pas convertir est ignoré, et chaque élément ignoré apparaît dans les avertissements du rapport d'import, jamais en silence. Le maillon 1 → 2 renomme déjà la clé `ninja` des typologies en `polyvalent`, sans rien perdre ; le maillon 4 → 5 convertit les horaires des stands en plages (voir [Horaires des stands](#horaires-des-stands)).
- **Ce que vous faites** : rien pour relire vos anciens fichiers, archives de fin d'événement ou copies avant vidage. Un scénario v2 à v5 ne s'importe pas dans une application 3.x (« format plus récent ») : gardez un export v1 avant de monter, c'est votre retour arrière. Le lecteur v1 est retiré en 5.0.
