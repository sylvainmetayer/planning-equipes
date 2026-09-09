# Contribuer

Merci de l'intérêt porté au projet. Quelques règles, courtes.

## Licence

Le projet est distribué sous **AGPL-3.0-only** (voir [`LICENSE`](../LICENSE)).
Toute contribution est acceptée sous cette même licence.

> Le titulaire du copyright se réserve la possibilité de proposer le logiciel
> sous une licence commerciale distincte ; les contributions reçues restent,
> elles, disponibles sous AGPL-3.0. Un certificat d'origine (DCO) sera demandé
> le jour où des contributions extérieures arriveront — pas avant.

## Avant d'ouvrir une pull request

Le détail du workflow est dans [`docs/developpement.md`](../docs/developpement.md) ;
l'essentiel :

```bash
./mvnw test                          # suite backend (hors scénarios lents)
cd src/main/webui && npm test        # suite frontend (Vitest)
npm run lint && npm run i18n-check   # lint et cohérence des traductions
```

- Toute chaîne visible par l'utilisateur a besoin de sa version française **et**
  de son entrée dans `public/i18n/messages.en.json` — `npm run i18n-check`
  échoue sinon.
- Une nouvelle contrainte de planification se déclare aussi dans
  `ConstraintCatalog`, que `GET /api/constraints` sert ; [`docs/contraintes.md`](../docs/contraintes.md)
  ne porte que le *pourquoi* de la règle, pas sa fiche.
- Le vocabulaire métier reste en français, le reste du code en anglais : la
  règle est vérifiée par un test, et expliquée dans [`AGENTS.md`](../AGENTS.md).

## Signaler une faille

Ne l'ouvrez pas en issue publique : voir [`SECURITY.md`](SECURITY.md).

## Code de conduite

Court, et lisible en deux minutes :
[`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md). Le point qui vous concerne le plus
vite : **aucune donnée nominative dans une issue, une capture ou un scénario** —
ce dépôt est public, et ces données concernent souvent des mineurs.
