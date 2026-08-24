# Contribuer

Merci de l'intérêt porté au projet. Quelques règles, courtes.

## Licence et certificat d'origine (DCO)

Le projet est distribué sous **AGPL-3.0-only** (voir [`LICENSE`](../LICENSE)).
Toute contribution est acceptée sous cette même licence.

Chaque commit doit porter une ligne `Signed-off-by`, obtenue avec `git commit -s` :

```
Signed-off-by: Prénom Nom <adresse@example.org>
```

Cette signature vaut adhésion au [Developer Certificate of Origin](https://developercertificate.org/)
1.1 : vous certifiez avoir le droit de soumettre ce code sous la licence du
projet. Elle n'exige aucune cession de droits — vous restez titulaire des vôtres.

> **Pourquoi un DCO plutôt qu'un CLA ?** Le DCO est plus léger pour le
> contributeur et suffit à établir la traçabilité des contributions. Notez que
> le titulaire du copyright se réserve la possibilité de proposer le logiciel
> sous une licence commerciale distincte ; les contributions reçues restent,
> elles, disponibles sous AGPL-3.0.

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
  `ConstraintCatalog` et dans [`docs/contraintes.md`](../docs/contraintes.md).
- Le vocabulaire métier reste en français, le reste du code en anglais : la
  règle est vérifiée par un test, et expliquée dans [`AGENTS.md`](../AGENTS.md).

## Signaler une faille

Ne l'ouvrez pas en issue publique : voir [`SECURITY.md`](SECURITY.md).
