# 0027 — Pas de compilation native

- **Statut** : accepté, implémenté
- **Date** : septembre 2026
- **Portée** : `pom.xml` (profil `native`), `src/main/docker/`, image publiée
- **Issue** : #392, question 24

## Contexte

Le squelette du starter Quarkus livrait quatre `src/main/docker/Dockerfile.{jvm,legacy-jar,native,native-micro}`, un profil Maven `native` et un goal `native-image-agent` qui tournait dans **chaque** build. Rien ne les construisait : aucun job de CI, aucun compose, aucune documentation. Le `Dockerfile` racine, seul utilisé, est autonome.

La question posée par l'audit était de savoir s'il fallait garder cette porte ouverte pour les douze mois à venir.

## Décision

Non. La compilation native est retirée, et cette décision est destinée à durer.

## Pourquoi, et c'est propre à cette application

Le cœur de ce logiciel est une boucle d'optimisation CPU de 300 secondes. Or **Timefold mesure son propre solveur ~42 % plus lent en image native** : l'AOT ne peut pas profiler-et-spéculer comme le JIT, ce qui pénalise exactement les tâches liées au calcul. Un solve de 306 s en passerait à ~435.

Les avantages du natif ne compensent rien ici :

| | Avantage | Ce que ça vaut pour nous |
|---|---|---|
| Démarrage | ~20× plus rapide (≈16 s → <1 s) | Un conteneur `restart: unless-stopped`, démarré quelques fois par mois |
| Mémoire | Empreinte bien sous la `mem_limit: 1g` actuelle | Réel, mais l'hôte n'est pas sous contrainte |
| Construction | — | 5 à 15 min et plusieurs Go de RAM |
| Fragilité | — | Timefold génère du bytecode et réfléchit sur `@PlanningEntity` : la zone où le natif casse |

Timefold recommande, pour qui veut le démarrage rapide **et** le débit, de sortir le solveur dans une application séparée. Rien ne justifie cette architecture pour un festival annuel servi par une instance.

## Ce que ça ferme, et à quelles conditions rouvrir

Ça ferme le déploiement en fonction sans serveur ou en montée à zéro, qui vivrait du démarrage à froid. Rouvrir supposerait que l'un de ces trois faits change :

1. l'écart de débit du solveur en natif se réduit nettement (à remesurer, pas à supposer) ;
2. le solveur sort de l'application servie, et seule la façade est compilée ;
3. le déploiement passe à un modèle où le démarrage domine le coût.

En attendant, une PR qui rétablit un profil `native` doit d'abord invalider le premier point, chiffres en main.

## Sources

- [Timefold — How to speed up Timefold Solver startup time by 20x with native images](https://timefold.ai/blog/how-to-speed-up-timefold-solver-startup-time-by-20x-with-native-images)
- [Timefold — How fast is Java 22?](https://timefold.ai/blog/java-22-performance)
