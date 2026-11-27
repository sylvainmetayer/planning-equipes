# 0130 — Un drapeau pour l'instance de démonstration publique

- **Statut** : accepté, implémenté
- **Date** : novembre 2026
- **Portée** : `config/DemoInstance`, `config/DefaultSecrets`, `service/demo/RefusedOnDemoInstance`, `CompteService`, `CompteAccesService`, `McpKeyService`, `/api/config`, bandeau du frontend, `scripts/demo/`
- **Prolonge** : [0110](0110-budget-de-calcul-d-instance-file-en-memoire-quota-public.md) (instance publique, quota), [0094](0094-comptes-locaux-par-mot-de-passe.md) (comptes locaux)

## Contexte

Une démonstration ouverte à tous doit montrer l'application telle qu'elle est, avec des identifiants affichés sur la vitrine. Trois risques en découlent : un visiteur qui change un mot de passe ou un rôle ferme la démo aux suivants ; une saisie part vers l'extérieur (un e-mail à une adresse réelle, un webhook vers une machine tierce, une clé MCP qui ouvre l'instance à un programme) ; et le calcul, qui occupe un cœur, est relancé sans fin.

La configuration savait déjà l'essentiel, mais par huit variables séparées (`PUBLIC_INSTANCE`, `LEGAL_DEMO_INSTANCE`, `MAIL_MOCK`, `WEBHOOKS_ENABLED`, `METEO_ENABLED`, `AUTH_LOCAL_SECONDE_ETAPE`, `PLANNING_MCP_API_KEY`, `OIDC_ENABLED`) : une seule oubliée, et la démo envoie des e-mails ou ouvre `/mcp`. Rien ne savait refuser un changement de compte, et `DefaultSecrets` refusait de démarrer sur le mot de passe d'exemple, celui qu'une démo veut afficher.

## Décision

1. **Un drapeau, `DEMO_INSTANCE=true`, qui impose ce qu'il implique.** Un intercepteur de configuration (`config/DemoInstance`) répond une valeur fixe pour chacun de ces réglages, quelle que soit la variable posée à côté : instance publique et dispensée de mentions légales, e-mails simulés et connexion sans code par e-mail, webhooks et météo coupés, OAuth MCP fermé. La liste est dans la classe, et son test la lit. La clé MCP de la configuration ne peut pas s'y forcer à vide (le build de Quarkus refuse une valeur absente imposée) : `McpKeyService` l'ignore sur une démo, et n'y accepte aucune clé, générée ou non.
2. **Les gestes qui changent un compte sont refusés au service**, par l'annotation `@RefusedOnDemoInstance`, sur le modèle de `@RequiresActiveEdition` : créer, désactiver, réactiver un compte, accorder ou retirer un droit, envoyer un lien, choisir un mot de passe, générer une clé MCP, configurer, tester ou renvoyer un webhook. Le refus est un `409` qui commence par « Instance de démonstration : ». `DemoInstanceStructuralTest` refuse une méthode publique de ces quatre services qui ne porte ni l'annotation ni une exemption argumentée.
3. **Les mots de passe d'exemple sont acceptés sous ce drapeau seulement** (`DefaultSecrets`) ; un mot de passe vide reste refusé.
4. **Le calcul est borné par la configuration existante** : un seul calcul tourne à la fois sur une instance, c'est la file ; la durée, la file et le quota sont `SOLVER_SECONDS_LIMIT`, `SOLVER_SECONDS_LIMIT_MAX`, `SOLVER_MAX_QUEUED_JOBS` et `SOLVER_MAX_SOLVES_PER_HOUR`, appliqués puisque l'instance est publique. Le drapeau ne les impose pas : leurs valeurs sont celles de l'exploitant, documentées.
5. **Les données viennent d'un dump de référence**, fabriqué par `scripts/demo/generer-dump.sh` sur une pile jetable en mode ordinaire, et restauré par l'hôte toutes les trente minutes. Le dump n'est ni dans le dépôt ni dans l'image : l'application ne charge jamais de données de démonstration d'elle-même.
6. **L'espace animateur affiche son code d'accès** au lieu de l'envoyer : le jeton de l'URL reste la seule clé, comme partout ailleurs, et le dump donne à un animateur un jeton fixe, que la vitrine peut afficher. Ouvrir l'espace par le jeton seul a été écarté : ce serait une seconde règle d'accès, à garder hors de toute autre instance.
7. **Chaque page le dit** : `/api/config` publie `demoInstance`, et l'application affiche un bandeau au-dessus de toutes ses pages, connexion comprise.

## Conséquences

- Une démo se déploie avec une variable de plus, et ne peut pas être à moitié configurée.
- Supprimer une édition reste permis sur la démo et retire les droits qui la nommaient : la remise à zéro suivante les rend.
- Le mailer simulé garde les e-mails en mémoire jusqu'au redémarrage de la remise à zéro.
- Les comptes locaux viennent du dump ; seul `admin` vient de `ADMIN_PASSWORD`.

## Options écartées

- **Refuser de démarrer si une des huit variables manque** : plus explicite, mais l'exploitant recopie une liste que l'application connaît déjà, et l'oubli d'une valeur se paie par un démarrage refusé plutôt que par rien.
- **Vérifier le drapeau à la main dans chaque méthode** : rien n'empêcherait un geste neuf de l'oublier ; l'annotation et son test structurel le rendent visible.
- **Livrer le dump dans l'image** : il suivrait la version, mais l'image porterait des données qu'une instance en service ne doit jamais charger, et sa fabrication (un calcul complet) alourdirait chaque construction d'image. Un dump plus ancien que l'application est migré par Flyway au démarrage.
- **Mailpit à la place du mailer simulé** : il montrerait les e-mails, mais à qui ouvre son interface, et c'est un service de plus à exposer ou à fermer.
