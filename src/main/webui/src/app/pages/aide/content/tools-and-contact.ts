// Imports, exports and tools — the action history, the MCP endpoint and the
// accounts included — and who to write to.
//
// One theme of the organisers' guide; `aide-content.ts` assembles the
// themes in reading order. Built lazily, never at module scope: `$localize`
// only resolves once `main.ts` has loaded the translation catalog.

import { HelpSection } from '../help-section';

export function buildToolsAndContactSections(supportEmail: string): HelpSection[] {
  return [
    {
      id: 'echanges',
      icon: 'swap_horiz',
      title: $localize`:@@aide.exchange.title:Imports, exports et outils`,
      summary: $localize`:@@aide.exchange.summary:Faire entrer des données, en sortir des plannings, et diagnostiquer le reste.`,
      blocks: [
        {
          kind: 'list',
          items: [
            $localize`:@@aide.exchange.item.fichiers:La page Fichiers a trois onglets : Importer, Exporter, Archive. On y exporte, on corrige dans le tableur, on réimporte, et on y trouve l'archive qui clôt une édition. Chaque écran de référentiel a aussi son bouton « Importer », qui ouvre la même carte sans quitter l'écran.`,
            $localize`:@@aide.exchange.item.scenario.fichiers:Import d'un scénario YAML (Fichiers, onglet Importer, carte Scénario) : la façon la plus rapide de remplir une édition vide. Un fichier invalide n'importe rien, même en partie, et donne une notification détaillée.`,
            $localize`:@@aide.exchange.item.exemples:Exemples (Fichiers, onglet Importer) : les plannings livrés avec l'application, rangés « pour découvrir », « pour tester un cas » et « extrêmes », chacun avec sa taille et ce qu'il montre.`,
            $localize`:@@aide.exchange.item.collage:Coller depuis un tableur : chaque carte d'import de référentiel accepte aussi les cellules copiées, en-têtes compris, et les lit comme un fichier ; l'aperçu passe avant toute écriture. Après un import, « Voir les … lignes importées » ouvre l'écran du référentiel sur ces seules lignes ; « Tout afficher » y rend la liste entière.`,
            $localize`:@@aide.exchange.item.csvAnimateurs:Import CSV des animateurs : reprendre votre tableur sans le ressaisir. Le format attendu a sa propre section dans ce guide.`,
            $localize`:@@aide.exchange.item.exports.fichiers:Exports (Fichiers, onglet Exporter) : les référentiels de l'édition en une archive ZIP, ou l'édition entière en un scénario, sous la forme exacte que relit l'onglet Importer.`,
            $localize`:@@aide.exchange.item.archive.fichiers:Archive de fin d'événement (Fichiers, onglet Archive) : les exports d'une édition terminée en un seul ZIP (planning global, équité, heures, référentiels, scénario, relecture, plannings individuels au choix), avec un manifeste LISEZMOI.txt qui dit de quel plan ils viennent. Aucun jeton d'accès n'y figure, mais les noms, dates de naissance et adresses, si : une fois téléchargée, sa conservation vous revient. L'État de l'édition la propose une fois le dernier jour passé.`,
            $localize`:@@aide.exchange.item.sql:Export et import d'un dump SQL complet, pour dupliquer ou restaurer un jeu de données entier.`,
            $localize`:@@aide.exchange.item.pdfIcs:Export du planning d'un animateur en PDF ou en ICS, à l'unité ou en archive ZIP pour tout le monde. Un fichier ICS est une photo : pour un agenda qui suit les republications, c'est l'abonnement de l'espace animateur qu'il faut.`,
            $localize`:@@aide.exchange.item.yamlValidator.fichiers:Vérifier un fichier sans l'importer (Fichiers, onglet Importer) : contrôle la structure d'un fichier scénario sans rien écrire.`,
            $localize`:@@aide.exchange.item.notifications.accueil:Messages récents (accueil, sous « À traiter aujourd'hui ») : l'historique des messages de l'application (fin de résolution, import, erreur) et les alertes des envois de nuit. La cloche de la barre du haut y mène et compte les messages non lus.`,
            $localize`:@@aide.exchange.item.debug.brut:Débogage : l'état brut renvoyé par le serveur, la version, la documentation de l'API et les vérifications techniques (notification, erreur, e-mail de test, Mailpit). Utile pour rapporter un problème précisément. Il n'est pas dans le menu : Ctrl+K puis « débogage », ou son adresse /debug.`,
            $localize`:@@aide.exchange.item.nouveautes:Nouveautés : ce que la version installée a apporté, regroupé par version. « À venir » rassemble ce qui est fait mais pas encore publié. La liste est construite depuis l'historique du dépôt, donc personne ne la tient à jour et elle ne peut pas mentir sur ce qui tourne. « À surveiller » en tête signale les changements qui demandent une vérification avant de relancer un calcul.`,
            $localize`:@@aide.exchange.item.sauvegarde.instance:Sauvegarde de nuit (page Paramètres, onglet Instance) : la base entière est copiée sur le disque du serveur chaque nuit, et l'écran dit où, lesquelles existent et si la dernière s'est bien passée. Elle ne se télécharge pas, car elle porte les noms, les dates de naissance et les adresses de tout le monde. La restauration est une opération de l'exploitant.`,
          ],
        },
      ],
      links: [
        { route: '/fichiers', label: $localize`:@@nav.link.fichiers:Fichiers` },
        {
          route: '/fichiers',
          queryParams: { cible: 'exemples' },
          label: $localize`:@@aide.lien.exemples:Fichiers — Exemples`,
        },
        {
          route: '/parametres',
          queryParams: { onglet: 'instance' },
          label: $localize`:@@aide.lien.parametresInstance:Paramètres — onglet Instance`,
        },
        { route: '/debug', label: $localize`:@@nav.link.debug:Débogage` },
        { route: '/nouveautes', label: $localize`:@@nav.link.nouveautes:Nouveautés` },
        {
          route: '/',
          fragment: 'a-traiter',
          label: $localize`:@@aide.lien.aTraiter:Accueil — À traiter aujourd'hui`,
        },
      ],
    },
    {
      id: 'historique-des-actions',
      icon: 'manage_search',
      title: $localize`:@@aide.historique.title:Historique des actions`,
      summary: $localize`:@@aide.historique.summary:Qui a fait quoi dans cette édition, et ce qui a été refusé.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.historique.intro:La page déroule, du plus récent au plus ancien, ce qui a été fait dans l'édition courante : l'heure, l'action, l'objet visé, les champs qu'une modification a réellement changés, et l'auteur. C'est la page à ouvrir quand une donnée a changé sans que personne ne se souvienne de l'avoir touchée. Une action refusée y figure aussi, marquée « refusée » : c'est souvent la ligne qu'on cherche, par exemple un import qui n'a rien écrit, une publication tentée pendant une résolution ou un lien d'espace périmé.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.historique.term.acteurs:Les cinq auteurs`,
              text: $localize`:@@aide.historique.def.acteurs:« Administration » : quelqu'un connecté à l'administration. « Animateurs » : une action faite depuis un espace animateur. « Non identifié » : un appel sans justificatif valable, un lien d'espace faux ou périmé ; ces lignes sont des refus. « Assistant » : un appel venu du MCP. « Application » : les tâches de nuit. Chaque famille a son bouton de filtre, et « Abouti / Refusé » se filtre à part.`,
            },
            {
              term: $localize`:@@aide.historique.term.contenu:Ce qu'une ligne ne dit pas`,
              text: $localize`:@@aide.historique.def.contenu:Aucune valeur : « nom, e-mail » dit ce qui a bougé, jamais ce que c'est devenu. L'historique n'est pas une sauvegarde et ne permet pas de revenir en arrière ; pour cela, il y a les instantanés. Aucune identité non plus : la ligne garde un identifiant, si bien qu'une fiche supprimée depuis laisse une ligne qui ne nomme plus personne. Sont tracées les écritures et les sorties de données (exports, dumps, envois de courriel), mais ni les consultations ni les calculs qui n'écrivent rien.`,
            },
            {
              term: $localize`:@@aide.historique.term.portee:Ce que la page charge`,
              text: $localize`:@@aide.historique.def.portee:Les deux cents dernières actions de l'édition consultée, et les filtres travaillent sur elles : une recherche qui ne rend rien ne prouve donc pas que l'action n'a pas eu lieu, seulement qu'elle est sortie de cette fenêtre. « Exports » fait exception : le serveur cherche alors les deux cents derniers fichiers sortis sur toute la durée conservée, quel que soit le nombre de modifications survenues depuis. Au-delà, la table est limitée par une durée de rétention, quatre-vingt-dix jours par défaut, que règle l'exploitant. L'historique est un journal en lecture seule : aucun bouton de cet écran n'efface quoi que ce soit.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.historique.filtres:Cinq filtres se combinent : la recherche libre, l'objet, l'auteur, le résultat et « Exports ». Ils restent dans l'adresse de la page, donc dans un lien que vous envoyez. Un cas courant : une fiche animateur n'est plus celle qu'on croyait. Filtrez sur l'objet « ANIMATEUR » et tapez son identifiant ; la ligne dit quand, par qui et quels champs ont changé. « Exports » ne garde que les fichiers sortis de l'application : exports de l'administration, dump de la base, plannings téléchargés depuis un espace. Ce filtre répond à « qui a sorti la liste des animateurs, et quand ? » et à « a-t-il bien récupéré son planning ? ».`,
        },
      ],
      links: [
        { route: '/historique', label: $localize`:@@nav.link.historique:Historique` },
        {
          route: '/',
          fragment: 'a-traiter',
          label: $localize`:@@aide.lien.aTraiter:Accueil — À traiter aujourd'hui`,
        },
        { route: '/versions', label: $localize`:@@nav.link.versions:Versions du plan` },
      ],
    },
    {
      id: 'webhooks',
      icon: 'webhook',
      title: $localize`:@@aide.webhooks.title:Webhooks : prévenir l'équipe là où elle travaille`,
      summary: $localize`:@@aide.webhooks.summary:Annoncer une publication, un échange, une résolution ou une sauvegarde ratée dans Slack, Discord, Matrix, Telegram ou un outil d'automatisation : des comptes, jamais un nom.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.webhooks.intro:Un webhook envoie un court message à un outil extérieur chaque fois qu'un événement choisi se produit : un salon Slack, Discord ou Matrix, une conversation Telegram, ou un outil d'automatisation comme n8n. Il se règle sur la page Paramètres, onglet Instance, carte « Webhooks » : la configuration vaut pour toute l'instance, mais seule l'édition active produit ses événements, et l'échec de la sauvegarde de nuit, qui concerne l'instance, part toujours. Chaque message dit de quelle édition il parle et mène à l'écran qui s'en occupe. Sans webhook, rien ne sort de l'application en dehors des courriels.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.webhooks.term.formats:Les formats`,
              text: $localize`:@@aide.webhooks.def.formats:« Générique (JSON signé) », pour un outil d'automatisation : un secret de signature est généré à la création et montré une seule fois ; copiez-le dans le récepteur, qui vérifie avec lui la signature et l'horodatage de chaque message. « Régénérer le secret » en donne un nouveau. Slack, Discord et Matrix (pont hookshot) prennent l'adresse de webhook du salon, qui est elle-même un secret : l'écran n'en montre ensuite que l'hôte. Telegram prend le jeton du bot donné par BotFather et la conversation : un nombre, négatif pour un groupe, ou le nom d'un canal public.`,
            },
            {
              term: $localize`:@@aide.webhooks.term.evenements:Les événements`,
              text: $localize`:@@aide.webhooks.def.evenements:Planning publié, demande d'échange à trancher, échanges en attente depuis trop longtemps, disponibilités déclarées, résolution terminée, échec de la sauvegarde nocturne, alerte météo : chaque webhook coche les siens. Les messages ne portent que des comptes et des identifiants, jamais le nom d'un animateur, puisqu'ils partent vers des services hébergés ailleurs. Ce qui s'adresse à une seule personne, un rappel ou une décision de covoiturage, reste un courriel.`,
            },
            {
              term: $localize`:@@aide.webhooks.term.gestes:Tester, modifier, mettre en pause`,
              text: $localize`:@@aide.webhooks.def.gestes:« Envoyer un test » envoie un message « Test » et dit aussitôt ce que le récepteur a répondu, sans réessayer. Le menu de la ligne ouvre le journal des livraisons, modifie le webhook (une adresse ou un jeton laissé vide garde l'actuel) ou le supprime. Décocher « Actif » dans le formulaire le met en pause sans le perdre : la table le marque « inactif ».`,
            },
            {
              term: $localize`:@@aide.webhooks.term.journal:Le journal des livraisons`,
              text: $localize`:@@aide.webhooks.def.journal:Chaque message est une livraison : son statut (en attente, livrée, échec, abandonnée), sa tentative, le code de réponse et la durée, gardés trente jours ; le contenu des réponses n'est jamais conservé. Un récepteur injoignable ou en panne passagère est réessayé après 1 min, 5 min, 30 min, 2 h et 12 h, six tentatives au plus, puis la livraison passe en échec ; toute autre réponse, une redirection comprise, l'abandonne tout de suite, comme une livraison d'une édition qui n'est plus active. « Renvoyer » la remet au début de son calendrier. Un récepteur lent ou en panne ne retarde jamais l'opération qu'il annonce.`,
            },
            {
              term: $localize`:@@aide.webhooks.term.securite:Ce que l'application refuse`,
              text: $localize`:@@aide.webhooks.def.securite:Une adresse doit être en https et viser l'extérieur : une adresse interne (la machine elle-même, un réseau privé, les métadonnées d'un hébergeur) est refusée, à l'enregistrement comme à chaque envoi, sauf dans les réseaux que l'exploitant a autorisés (WEBHOOKS_RESEAUX_AUTORISES), pour un n8n voisin par exemple. Les secrets sont chiffrés en base avec une clé que pose l'exploitant (WEBHOOKS_SECRET_KEY) : sans elle, aucun webhook ne se crée, et l'écran ne réaffiche jamais un secret. Aucun assistant MCP ne peut configurer un webhook.`,
            },
          ],
        },
      ],
      links: [
        {
          route: '/parametres',
          queryParams: { onglet: 'instance' },
          fragment: 'webhooks',
          label: $localize`:@@aide.lien.parametresWebhooks:Paramètres › Webhooks`,
        },
        { route: '/editions', label: $localize`:@@nav.link.editions:Éditions` },
      ],
    },
    {
      id: 'assistant-mcp',
      icon: 'smart_toy',
      title: $localize`:@@aide.mcp.title:Piloter l'application par un assistant (MCP)`,
      summary: $localize`:@@aide.mcp.summary:Brancher un assistant IA sur l'édition pour la consulter et la modifier, avec une clé qui ouvre tout.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.mcp.intro:MCP est le protocole par lequel un assistant parle à une application. Branché sur celle-ci, il répond aux demandes en langage naturel (« combien de sièges vides samedi après-midi ? », « relance une résolution ») et il agit réellement, en passant par les mêmes services que les écrans, avec les mêmes contrôles et les mêmes refus. La page MCP donne le point d'entrée et la configuration à coller dans le client.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.mcp.term.cle:La clé d'API`,
              text: $localize`:@@aide.mcp.def.cle:Elle est posée sur le serveur par l'exploitant, pas depuis cet écran. Tant qu'elle n'existe pas, le MCP refuse tout le monde et la page le dit. Quand elle existe, « Révéler la clé d'API » la montre après confirmation du mot de passe administrateur, puis la fait disparaître au bout de deux minutes ou dès que vous quittez la page.`,
            },
            {
              term: $localize`:@@aide.mcp.term.pouvoir:Ce que la clé permet`,
              text: $localize`:@@aide.mcp.def.pouvoir:Tout ce que fait l'administration, écriture comprise : créer et supprimer des données, lancer une résolution, publier, vider une édition. Elle n'est propre à personne et n'expire pas : traitez-la comme un mot de passe et faites-la remplacer au moindre doute. Un assistant branché dessus agit sans confirmation à l'écran. Avant de lui faire toucher une édition réelle, prenez un instantané.`,
            },
            {
              term: $localize`:@@aide.mcp.term.edition:L'édition visée`,
              text: $localize`:@@aide.mcp.def.edition:Un assistant nomme l'édition à chaque appel ; un appel qui ne la nomme pas est refusé, plutôt que de travailler dans une édition que personne n'a désignée. Ce qui envoie du courriel, publier compris, n'aboutit que dans l'édition active, et l'assistant active ou désactive une édition comme la page Éditions.`,
            },
            {
              term: $localize`:@@aide.mcp.term.donnees:Ce qui ne sort jamais par là`,
              text: $localize`:@@aide.mcp.def.donnees:Ni nom, ni prénom, ni date de naissance, ni adresse e-mail. Un assistant désigne une personne par son identifiant d'animateur, et ne connaît d'elle que ses attributs de planification et son régime (mineur, moins de seize ans, majeur), déduit de la date sans que la date circule. Une réponse par identifiants se relit sur la page Animateurs, dont le filtre les accepte.`,
            },
            {
              term: $localize`:@@aide.mcp.term.proxy:Derrière un proxy d'accès`,
              text: $localize`:@@aide.mcp.def.proxy:Quand l'application est publiée derrière un proxy d'accès, la page le détecte et l'annonce : chaque appel doit alors présenter en plus un jeton du proxy, que la configuration proposée porte déjà. La clé se passe dans son en-tête dédié, jamais dans « Authorization », qu'un proxy consomme souvent pour lui-même. Qui ne le sait pas y perd une demi-heure.`,
            },
            {
              term: $localize`:@@aide.mcp.term.prompts:Prompts prêts à l'emploi`,
              text: $localize`:@@aide.mcp.def.prompts:Le serveur annonce lui-même une vingtaine de demandes toutes faites, une par moment de l'édition : démarrer une nouvelle édition, vérifier qu'elle est prête, diagnostiquer les contraintes dures encore violées, relancer une résolution sans perdre le plan en place, préparer un plan de repli par une consigne, publier, suivre les confirmations, tirer le bilan de l'événement. Chacune fait travailler l'assistant dans l'édition que vous nommez, ou à défaut dans l'édition active, et demande votre accord avant tout envoi de courriel. Un client qui gère les prompts MCP les propose directement ; sinon, « Copier le prompt ».`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.mcp.journal:Chaque écriture d'un assistant laisse une ligne dans l'historique des actions, sous l'auteur « Assistant ».`,
        },
      ],
      links: [
        { route: '/mcp-client', label: $localize`:@@nav.link.mcp:MCP` },
        { route: '/historique', label: $localize`:@@nav.link.historique:Historique` },
        { route: '/versions', label: $localize`:@@nav.link.versions:Versions du plan` },
      ],
    },
    {
      id: 'comptes-et-droits',
      icon: 'manage_accounts',
      title: $localize`:@@aide.comptes.title:Comptes et droits`,
      summary: $localize`:@@aide.comptes.summary:Qui peut se connecter, et quels droits délégués lui sont accordés — sans jamais rien supprimer.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.comptes.intro:Chaque personne qui se connecte par Keycloak a un compte nominatif, créé à sa première connexion. La page Comptes et droits les liste, avec leur dernière connexion et leur état. « Ajouter un compte » en crée un d'avance, à partir de l'adresse e-mail de la personne : c'est ce qui permet de lui accorder un droit avant qu'elle n'arrive — sa première connexion avec cette adresse retrouve le compte.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.comptes.term.roles:Les deux rôles délégués`,
              text: $localize`:@@aide.comptes.def.roles:RH, en lecture seule, pour une édition ou pour toutes. Responsable de stand, toujours dans une édition et pour au moins un de ses stands. Un droit peut porter une date de fin : passé ce jour, il n'ouvre plus rien. Ces rôles n'ouvrent encore aucun écran — tout leur est refusé par défaut, en attendant les travaux qui construiront leurs vues.`,
            },
            {
              term: $localize`:@@aide.comptes.term.desactivation:Désactiver plutôt que supprimer`,
              text: $localize`:@@aide.comptes.def.desactivation:Un compte désactivé perd tous ses rôles dans l'application, ceux du realm Keycloak compris — le rôle admin aussi. Désactiver son propre compte ferme donc l'administration à soi-même : l'écran prévient avant. Un compte se réactive, il ne se supprime jamais ; un droit se retire par « Retirer » et reste listé, daté : l'historique doit pouvoir dire qui détenait quoi, et quand.`,
            },
            {
              term: $localize`:@@aide.comptes.term.keycloak:Ce qui reste à Keycloak`,
              text: $localize`:@@aide.comptes.def.keycloak:Les mots de passe, la double authentification et les rôles globaux (admin, mcp, animateur) se gèrent dans la console Keycloak, jamais ici ; la marche à suivre est dans la documentation d'exploitation (docs/keycloak.md).`,
            },
          ],
        },
      ],
      links: [
        { route: '/comptes', label: $localize`:@@nav.link.comptes:Comptes et droits` },
        { route: '/historique', label: $localize`:@@nav.link.historique:Historique` },
      ],
    },
    {
      id: 'contact',
      icon: 'contact_support',
      title: $localize`:@@aide.contact.title:Contact et support`,
      summary: $localize`:@@aide.contact.summary:Une question, un blocage, une erreur métier : à qui s'adresser.`,
      // The address comes from the deployment (BRANDING_SUPPORT_EMAIL); with
      // none, the paragraph and its link are left out rather than pointing at
      // nobody.
      blocks: [
        ...(supportEmail
          ? [
              {
                kind: 'paragraph' as const,
                text: $localize`:@@aide.contact.mail:Pour une question d'utilisation ou un doute sur un résultat, écrivez à ${supportEmail}:email:. Joignez si possible une capture d'écran et la version affichée en bas de page.`,
              },
            ]
          : []),
        {
          kind: 'paragraph',
          text: supportEmail
            ? $localize`:@@aide.contact.issue:Pour une erreur métier reproductible (un score faux, une contrainte non respectée, un export incorrect), ouvrez un rapport de problème sur GitHub : le formulaire guide la description. GitHub est public : désignez les personnes par leur identifiant d'animateur, jamais par leur nom. Les données nominatives passent par l'e-mail ci-dessus.`
            : $localize`:@@aide.contact.issueSansMail:Pour une erreur métier reproductible (un score faux, une contrainte non respectée, un export incorrect), ouvrez un rapport de problème sur GitHub : le formulaire guide la description. GitHub est public : désignez les personnes par leur identifiant d'animateur, jamais par leur nom.`,
        },
      ],
      links: [
        ...(supportEmail
          ? [
              {
                href: `mailto:${supportEmail}`,
                label: $localize`:@@aide.contact.lienMail:Contacter le support`,
              },
            ]
          : []),
        {
          href: 'https://github.com/sylvainmetayer/planning-equipes/issues/new?template=erreur-metier.yml',
          label: $localize`:@@aide.contact.lienIssue:Rapporter un problème (GitHub)`,
        },
      ],
    },
  ];
}
