# Tests de charge d'une démo publique

Quatre scénarios [k6](https://grafana.com/docs/k6/) qui vérifient qu'une instance en `DEMO_INSTANCE=true` tient avant son ouverture. k6 tourne par son image Docker officielle, dont la version est épinglée dans `compose.yml`. Rien à installer d'autre que Docker. La page [Tester la charge d'une démo publique](../../../site/src/docs/comprendre/tests-de-charge.md) dit quoi en lire.

```bash
# Une démo locale sur un dump de scripts/demo/generer-dump.sh
ADMIN_PASSWORD=… src/test/charge/demo-locale.sh reference.dump

# Un scénario contre elle, ou contre la démo publique
BASE_URL=http://127.0.0.1:18080 ADMIN_PASSWORD=… N=10 src/test/charge/lancer.sh visiteur

src/test/charge/demo-locale.sh --arreter
```

| Scénario | Ce qu'il joue |
| --- | --- |
| `visiteur` | N visiteurs à la fois pendant `DUREE` : connexion par le formulaire, puis Accueil, Planning, Diagnostic et Règles |
| `calculs` | N visiteurs lancent un calcul au même instant : au plus un qui tourne et `FILE_MAX` en file, le reste refusé en 409 ou 429 avec une phrase |
| `espace` | l'espace animateur de la démo : code affiché, session, planning, ICS de l'espace et flux d'abonnement |
| `pic` | montée de 0 à N visiteurs en `MONTEE`, puis un plateau de `DUREE` |

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `BASE_URL` | exigée | l'instance visée |
| `ADMIN_PASSWORD` | exigée (sauf `espace`) | mot de passe du compte `admin` de la démo ; jamais écrit dans le dépôt |
| `ADMIN_USER` | `admin` | le compte qui se connecte |
| `N` | `5` | nombre de visiteurs simultanés |
| `DUREE` | `1m` | durée du plateau |
| `MONTEE` | `1m` (`pic`), `30s` (`espace`) | durée de la montée |
| `PAUSE` | `2` | secondes passées sur un écran |
| `P95_MAX_MS` | `1500` | seuil du p95 des requêtes |
| `TAUX_ERREUR_MAX` | `0.01` | seuil du taux de réponses en erreur |
| `FILE_MAX` | `3` | `SOLVER_MAX_QUEUED_JOBS` de l'instance visée |
| `SECONDES` | durée de l'édition | durée demandée pour chaque calcul |
| `ESPACE_TOKEN` | le jeton du dump de démo | l'espace visé |
| `TAUX_SESSIONS_MIN` | `0.9` | part des visiteurs de l'espace qui doivent ouvrir leur session |
| `RESULTATS` | `./resultats-charge` | dossier des résumés JSON |

Une 5xx fait toujours échouer le lancement. Le scénario `calculs` consomme le quota horaire de l'instance visée : ne le jouez pas sur la démo publique pendant ses heures d'ouverture.
