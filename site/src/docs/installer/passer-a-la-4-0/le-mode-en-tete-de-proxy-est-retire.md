---
ordre: 30
---

### Le mode en-tête de proxy est retiré

- **Avant (3.x)** : `REMOTE_USER_ENABLED=true`, avec `REMOTE_USER_SECRET`, laissait un proxy d'accès authentifier l'administrateur (`REMOTE_USER_ADMIN_EMAIL`) et ouvrir l'espace animateur sans code, par un en-tête. Une 3.x l'avertissait déjà « déprécié ».
- **Après (4.0)** : le mécanisme n'existe plus. Les cinq variables `REMOTE_USER_ENABLED`, `REMOTE_USER_HEADER`, `REMOTE_USER_SECRET_HEADER`, `REMOTE_USER_SECRET` et `REMOTE_USER_ADMIN_EMAIL` n'ont plus aucun effet. Si l'une est renseignée, le démarrage le dit dans un avertissement et continue ; l'avertissement disparaît en 5.0. L'en-tête d'un proxy n'ouvre plus rien, ni l'administration ni l'espace d'un animateur.
- **Ce que vous faites** : **avant** de monter, configurez [la connexion par un fournisseur OIDC](oidc.md) ou créez des [comptes locaux](comptes-locaux.md), et vérifiez qu'un compte administrateur nominatif s'ouvre. Les animateurs reprennent le code à six chiffres envoyé par courriel. Retirez ensuite les `REMOTE_USER_*` de votre environnement. Votre proxy peut rester devant l'instance comme filtre réseau, il ne remplace plus la connexion.
