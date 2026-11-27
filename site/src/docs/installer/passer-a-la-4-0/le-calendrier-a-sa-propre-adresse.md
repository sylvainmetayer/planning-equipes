---
ordre: 245.5
---

### Le Calendrier a sa propre adresse

- **Avant (3.x)** : l'écran *Calendrier* s'ouvrait à l'adresse `/creneaux`, du nom de ce qu'il liste.
- **Après (4.0)** : il s'ouvre à **`/calendrier`**, du nom que le menu et la palette lui donnent. L'ancienne adresse `/creneaux` redirige vers la nouvelle en gardant tous ses paramètres (`?edit=`, `?onglet=`…) : un signet ou un lien déjà envoyé continue de fonctionner. La redirection disparaît en 5.0. Les routes de l'API (`/api/creneaux`) ne changent pas.
- **Ce que vous faites** : rien. Si vous gardez des signets ou des liens de documentation interne vers `/creneaux`, remplacez-les par `/calendrier` avant la 5.0.
