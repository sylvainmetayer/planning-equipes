# 0075 — Le nombre de calculs se borne dans l'application, pour toute l'instance, et pas au reverse proxy

- **Statut** : accepté, implémenté
- **Date** : octobre 2026
- **Portée** : solveur (file des jobs, solve synchrone, vérification du besoin), API, MCP, IHM (écrans qui lancent un calcul)
- **S'appuie sur** : [0051](0051-budget-de-calcul-par-edition-sous-plafond-d-exploitant.md), les plafonds d'exploitant sur la durée d'un calcul

## Contexte

Une instance de démonstration publique — mot de passe admin partagé, données
fictives — peut partager sa machine avec une instance de production. Les
plafonds de 0051 bornent la durée d'**un** calcul ; rien ne bornait combien
un visiteur pouvait en enchaîner. Le solveur ne fait qu'un calcul à la fois,
mais une file qu'on remplit et des lancements répétés suffisent à tenir les
cœurs occupés en continu, au détriment de la voisine.

## Décision

1. **Deux gardes d'exploitant, en variables d'environnement, coupées par
   défaut** : `SOLVER_MAX_SOLVES_PER_HOUR`, un quota de calculs acceptés sur
   les 60 dernières minutes glissantes, et `SOLVER_MAX_QUEUED_JOBS`, le nombre
   de tâches qui peuvent attendre derrière celle qui tourne. Une instance
   d'organisateur n'a aucune raison de se refuser des calculs : `0`, la valeur
   par défaut, n'en refuse aucun.
2. **Pour toute l'instance**, toutes éditions et tous appelants confondus :
   c'est la machine qu'on protège, et une démonstration n'a qu'un compte.
3. **Compté au lancement, sur tout ce qui prendra les cœurs** : la résolution
   complète, l'incrémentale, le solve synchrone, depuis l'écran, l'API ou
   MCP, et la vérification du besoin, qui est un vrai calcul hors file. Un
   lancement refusé pour une autre raison ne compte pas ; une tâche planifiée
   puis retirée de la file, si — sinon un clic sur « retirer » rendrait le
   quota. La file persistée **rejouée au démarrage** ne passe par aucune des
   deux gardes : elle a été comptée quand on l'a soumise.
4. **Refus en `409`** avec une phrase qui dit quand revenir (« Prochaine
   résolution possible à 14:32 », dans le fuseau de l'événement), portée par
   l'erreur métier ordinaire : l'écran l'affiche telle quelle, l'outil MCP la
   rend en résultat d'erreur. Le corps est un message, jamais un job — c'est
   ce qui le distingue, côté écran, du `409` « solveur occupé ».
5. **Compteur en mémoire** : un redémarrage le remet à zéro, ce qui est
   acceptable pour une garde contre l'abus — redémarrer l'instance n'est pas à
   la portée du visiteur qu'elle vise.

## Alternatives écartées

- **Une limite de débit au reverse proxy.** Elle compte des requêtes par
  adresse, pas des calculs : elle ne sait pas qu'un `POST` lance quinze minutes
  de calcul et qu'un autre lit une liste, ne voit pas MCP derrière sa clé, et
  un visiteur qui change d'adresse repart à zéro. Elle reste utile contre le
  reste (voir [`securite.md`](../securite.md)), pas contre ce coût-là.
- **Un `429`.** Il dit « vous, ralentissez » : c'est la réponse des limiteurs
  par adresse ou par clé de l'application. Ici l'appelant n'a rien fait de
  trop à titre personnel, c'est l'**état de l'instance** qui refuse — la
  famille du « solveur occupé », déjà en `409`. Un `429` aurait demandé une
  variante d'erreur métier, une traduction MCP et une branche d'écran de plus
  pour dire la même phrase.
- **Un code d'erreur dédié.** Rien, côté écran, n'a à réagir autrement qu'en
  affichant la phrase ; un code ne s'ajouterait que le jour où un écran devra
  griser son bouton jusqu'à l'heure dite.
- **Compter les lignes de `solver_job`.** Pas plus juste : le solve synchrone
  et la vérification du besoin n'en écrivent pas, et supprimer un job de
  l'historique supprime sa ligne.
- **Une fenêtre fixe** comme celle des limiteurs de débit (ouverte au premier
  appel, refermée une heure après). Elle laisse passer deux fois le quota de
  part et d'autre d'une bascule — la rafale même que la garde doit empêcher —
  et ne sait pas dire à quelle minute un calcul redevient possible.

## Conséquences

- L'exploitant d'une démonstration règle les deux variables ; celui d'une
  instance d'organisateur n'a rien à faire. Voir
  [`exploitation.md`](../exploitation.md#combien-de-calculs-linstance-accepte).
- Le « solveur occupé » reste le seul `409` dont le corps est un job : un
  futur refus au lancement doit garder la forme `{ message }`, ou l'écran le
  prendrait pour un job.
