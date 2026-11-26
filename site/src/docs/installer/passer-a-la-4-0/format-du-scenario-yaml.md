---
ordre: 50
---

### Format du scénario YAML

- **Avant** : l'application écrit `schemaVersion: 1`.
- **Après** : elle écrit `schemaVersion: 7`. Elle lit toujours les fichiers v1 (clé absente ou égale à 1) : ils traversent une chaîne de conversions, maillon par maillon, au fil des changements de modèle de la 4.x. Ce que la chaîne ne peut pas convertir est ignoré, et chaque élément ignoré apparaît dans les avertissements du rapport d'import, jamais en silence. Le maillon 1 → 2 renomme déjà la clé `ninja` des typologies en `polyvalent`, sans rien perdre ; le maillon 4 → 5 convertit les horaires des stands en plages (voir [Horaires des stands](#horaires-des-stands)) ; le maillon 5 → 6 renomme la section `journeesTypes` en `gabarits` (voir [Le générateur de créneaux](#un-seul-générateur-de-créneaux)) ; le maillon 6 → 7 ignore la clé `couverturePause` (voir [Le relais repas à effectif réduit](#le-relais-repas-à-effectif-réduit-est-retiré)).
- **Ce que vous faites** : rien pour relire vos anciens fichiers, archives de fin d'événement ou copies avant vidage. Un scénario v2 à v7 ne s'importe pas dans une application 3.x (« format plus récent ») : gardez un export v1 avant de monter, c'est votre retour arrière. Le lecteur v1 est retiré en 5.0.
