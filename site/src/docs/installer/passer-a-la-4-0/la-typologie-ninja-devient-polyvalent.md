---
ordre: 110
---

### La typologie « ninja » devient « polyvalent »

- **Avant** : la typologie qui rend ses titulaires disponibles sur tous les stands s'appelait « ninja » : drapeau `ninja` des typologies (REST, YAML, CSV, outils MCP) et colonne `typologie.ninja`.
- **Après** : elle s'appelle « polyvalent ». La migration `V140` renomme la colonne et son index, et corrige les noms de champs déjà inscrits au journal des actions. Le nom de la règle (`preserverBufferPolyvalents`), ses poids et son interrupteur ne changent pas. Les réponses de l'API, les exports YAML et CSV et le champ des outils MCP disent `polyvalent` ; les clés dérivées suivent (`polyvalentConfigure` des rapports de fragilité et de tension, `typologiePolyvalenteDefinie` du rapport de besoin). Les écrans disent « Typologie polyvalente ». Le drapeau `premium` des stands garde son nom et s'affiche « Stand premium ».
- **Ce que vous faites** : rien pour vos données ni vos fichiers. Les anciens noms sont encore lus pendant toute la 4.x (clé JSON et YAML `ninja`, en-tête CSV `ninja`, ce dernier signalé ligne par ligne dans le rapport d'import) et retirés en 5.0. Seuls vos scripts qui *lisent* une clé `ninja` d'une réponse de l'API ou d'un export doivent passer à son nouveau nom.
