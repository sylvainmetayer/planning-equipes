<!--
Ce gabarit est un aide-mémoire, pas un formulaire : supprimez ce qui ne sert
pas. La contribution est acceptée sous AGPL-3.0 — voir CONTRIBUTING.md.
-->

## Ce que ça change, et pourquoi

<!--
Le « pourquoi » compte plus que le « quoi » : le diff dit déjà ce qui change.
Décrivez le problème que ça résout, et ce que vous avez écarté en chemin.
-->

## Comment vous l'avez vérifié

<!--
Ce que vous avez lancé, et ce que ça a donné. Une suite verte prouve qu'on n'a
rien cassé ; elle ne prouve pas que le neuf fonctionne — un test qui échoue
sans le correctif, si.
-->

```bash
./mvnw test
cd src/main/webui && npm test && npm run lint && npm run i18n-check
```

## Avant de demander une relecture

- [ ] **Chaque commit porte `Signed-off-by`** (`git commit -s`) — le workflow
      *Certificat d'origine (DCO)* le vérifie, et se rattrape en une passe :
      `git rebase --signoff origin/main`
- [ ] Toute chaîne visible par l'utilisateur a sa version française **et** son
      entrée dans `public/i18n/messages.en.json` (`npm run i18n-check`)
- [ ] La prose du dépôt est en anglais — commentaires et noms déclarés —
      seul le vocabulaire métier reste français (`LanguagePolicyStructuralTest`)
- [ ] La documentation concernée est à jour : une nouvelle contrainte se
      déclare aussi dans `docs/contraintes.md`, un nouvel endpoint dans
      `docs/api.md`, une décision structurante dans `docs/decisions/`
- [ ] **Aucune donnée nominative** dans le diff, les fixtures ou la description

<!--
Une décision d'architecture ne se raconte pas dans une description de PR : elle
finit par disparaître avec elle. Écrivez un ADR dans docs/decisions/, et ne
réécrivez jamais une décision passée — on en ajoute une qui la révise.
-->
