// Imports, exports and tools — the action history and the MCP endpoint
// included — and who to write to.
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
            $localize`:@@aide.exchange.item.scenario:Import d'un scénario YAML depuis votre poste (page Imports, onglet Scénario) : la façon la plus rapide de remplir une édition vide. Un fichier invalide donne une notification détaillée plutôt qu'un import partiel. L'écran Exports fait le chemin inverse, et la page Débogage charge un des scénarios livrés avec l'application.`,
            $localize`:@@aide.exchange.item.csvAnimateurs:Import CSV des animateurs : reprendre le tableur des bénévoles sans le ressaisir, avec un aperçu ligne à ligne avant que quoi que ce soit ne soit écrit. Le format attendu a sa propre section dans ce guide.`,
            $localize`:@@aide.exchange.item.sql:Export et import d'un dump SQL complet, pour dupliquer ou restaurer un jeu de données entier.`,
            $localize`:@@aide.exchange.item.pdfIcs:Export du planning individuel d'un animateur en PDF ou en ICS (importable dans Google Calendar, Apple Calendar ou Outlook), à l'unité ou en archive ZIP pour tout le monde. Un fichier ICS est une photo : il ne bougera plus. Pour un agenda qui suit les republications, c'est l'abonnement de l'espace animateur qu'il faut — voir « Foire au planning ».`,
            $localize`:@@aide.exchange.item.yamlValidator:Validateur YAML (sur la page Débogage) : vérifie un fichier scénario sans rien importer, pour corriger avant de toucher aux données.`,
            $localize`:@@aide.exchange.item.notifications:Notifications : l'historique des événements — fin de résolution, import, erreur. Une alerte non lue est signalée dans la navigation.`,
            $localize`:@@aide.exchange.item.debug:Débogage : l'état brut renvoyé par le serveur, utile pour rapporter un problème précisément.`,
            $localize`:@@aide.exchange.item.sauvegarde:Sauvegarde de nuit (page Paramètres) : la base entière est copiée sur le disque du serveur chaque nuit, et l'écran dit où vont les copies, lesquelles existent et si la dernière s'est bien passée. Elle ne se télécharge pas — elle porte les noms, les dates de naissance et les adresses de tout le monde — et la restauration est une opération de l'exploitant sur le serveur, pas un bouton.`,
          ],
        },
      ],
      links: [
        { route: '/parametres', label: $localize`:@@nav.link.parametres:Paramètres` },
        {
          route: '/imports',
          label: $localize`:@@aide.lien.importAnimateurs:Imports — onglet Animateurs`,
        },
        { route: '/exports', label: $localize`:@@nav.link.exports:Exports` },
        { route: '/debug', label: $localize`:@@nav.link.debug:Débogage` },
        { route: '/notifications', label: $localize`:@@nav.link.notifications:Notifications` },
      ],
    },
    {
      id: 'historique-des-actions',
      icon: 'manage_search',
      title: $localize`:@@aide.historique.title:Historique des actions`,
      summary: $localize`:@@aide.historique.summary:Qui a fait quoi dans cette édition, et ce qui a été refusé — la page à ouvrir quand une donnée a changé sans que personne ne se souvienne de l'avoir touchée.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.historique.intro:La page « Historique des actions » déroule, journée par journée et du plus récent au plus ancien, ce qui a été fait dans l'édition courante : l'heure, la phrase de l'action, l'objet visé, les champs qu'une modification a réellement changés, et l'auteur. Une action refusée y figure aussi, marquée « refusée » : elle n'a rien changé, et c'est souvent la ligne qu'on cherche — un import qui n'a rien écrit, une publication tentée pendant une résolution, un lien d'espace périmé.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.historique.term.acteurs:Les cinq auteurs`,
              text: $localize`:@@aide.historique.def.acteurs:« Administration » : quelqu'un connecté à l'administration. « Animateurs » : une action faite depuis un espace animateur — une demande d'échange, un accusé de réception, une déclaration de disponibilités. « Non identifié » : un appel qui n'a présenté aucun justificatif valable, un lien d'espace faux ou périmé par exemple — ces lignes sont des refus. « Assistant » : un appel venu du MCP. « Application » : les tâches de nuit, quand un envoi est réellement parti. Chaque famille a son bouton de filtre, et « Abouti / Refusé » se filtre à part.`,
            },
            {
              term: $localize`:@@aide.historique.term.contenu:Ce qu'une ligne ne dit pas`,
              text: $localize`:@@aide.historique.def.contenu:Aucune valeur : « nom, e-mail » dit ce qui a bougé, jamais ce que c'est devenu — l'historique n'est pas une sauvegarde et ne permet pas de revenir en arrière, c'est le rôle des instantanés. Aucune identité non plus : la ligne garde un identifiant, le nom est retrouvé à l'affichage depuis le référentiel, si bien qu'une fiche supprimée depuis laisse une ligne qui ne nomme plus personne. Sont tracées les écritures et les sorties de données — exports PDF, ICS, CSV, dump de base, envois de courriel — jamais les simples consultations, ni les calculs qui n'écrivent rien comme un aperçu d'import ou une simulation.`,
            },
            {
              term: $localize`:@@aide.historique.term.portee:Ce que la page charge`,
              text: $localize`:@@aide.historique.def.portee:Les deux cents dernières actions de l'édition consultée, et les filtres travaillent sur elles : une recherche qui ne rend rien ne prouve donc pas que l'action n'a pas eu lieu, seulement qu'elle est sortie de cette fenêtre. Une autre édition a son propre historique. Au-delà, c'est une rétention qui borne la table — quatre-vingt-dix jours par défaut, réglée par l'exploitant sur le serveur, appliquée par la tâche de nuit : aucun bouton de cet écran n'efface quoi que ce soit, et c'est délibéré — un journal qu'on peut modifier n'est pas un journal.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.historique.filtres:Quatre filtres se combinent : la recherche libre (elle porte sur ce qui est affiché — la phrase, l'auteur, l'objet et son identifiant, les champs), l'objet, l'auteur et le résultat. Ils restent dans l'adresse de la page, donc dans un lien que vous envoyez à quelqu'un ; « Tout afficher » les remet à zéro, « Actualiser » relit. Un cas courant : une fiche animateur qui n'est plus celle qu'on croyait — filtrez sur l'objet « ANIMATEUR » et tapez son identifiant, la ligne dit quand, par qui, et quels champs ont changé.`,
        },
      ],
      links: [
        { route: '/historique', label: $localize`:@@nav.link.historique:Historique` },
        { route: '/notifications', label: $localize`:@@nav.link.notifications:Notifications` },
        { route: '/instantanes', label: $localize`:@@nav.link.snapshots:Instantanés` },
      ],
    },
    {
      id: 'assistant-mcp',
      icon: 'smart_toy',
      title: $localize`:@@aide.mcp.title:Piloter l'application par un assistant (MCP)`,
      summary: $localize`:@@aide.mcp.summary:Brancher un assistant IA sur l'édition pour la consulter et la modifier en langage naturel — avec une clé qui ouvre tout.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.mcp.intro:MCP est le protocole par lequel un assistant (Claude, un environnement de développement, un agent) parle à une application. Branché sur celle-ci, il répond en langage naturel — « combien de sièges vides samedi après-midi ? », « ajoute une indisponibilité à cet animateur », « relance une résolution » — et il agit vraiment : il passe par les mêmes services que les écrans, avec les mêmes contrôles et les mêmes refus. La page MCP donne le point d'entrée et la configuration à coller dans le client, d'un bouton.`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@aide.mcp.term.cle:La clé d'API`,
              text: $localize`:@@aide.mcp.def.cle:Elle est posée sur le serveur par l'exploitant, pas depuis cet écran. Tant qu'elle n'existe pas, le MCP refuse tout le monde et la page le dit — mieux vaut un refus de servir qu'une porte ouverte. Quand elle existe, « Révéler la clé d'API » la montre après confirmation du mot de passe administrateur, puis la fait disparaître au bout de deux minutes ou dès que vous quittez la page ; la copier relance le délai.`,
            },
            {
              term: $localize`:@@aide.mcp.term.pouvoir:Ce que la clé permet`,
              text: $localize`:@@aide.mcp.def.pouvoir:Tout ce que fait l'administration, écriture comprise : créer et supprimer des données, lancer une résolution, publier, vider une édition. Elle n'est propre à personne et n'expire pas : traitez-la comme un mot de passe, ne la collez pas dans un fil de discussion, et faites-la remplacer par l'exploitant au moindre doute. Un assistant branché dessus agit sans confirmation à l'écran : avant de lui faire toucher une édition réelle, prenez un instantané.`,
            },
            {
              term: $localize`:@@aide.mcp.term.donnees:Ce qui ne sort jamais par là`,
              text: $localize`:@@aide.mcp.def.donnees:Ni nom, ni prénom, ni date de naissance, ni adresse e-mail. Un assistant désigne une personne par son identifiant d'animateur, et ne connaît d'elle que ses attributs de planification et son régime — mineur, moins de seize ans, majeur — déduit de la date sans que la date circule. Une réponse par identifiants se relit sur la page Animateurs, dont le filtre accepte l'identifiant.`,
            },
            {
              term: $localize`:@@aide.mcp.term.proxy:Derrière un proxy d'accès`,
              text: $localize`:@@aide.mcp.def.proxy:Quand l'application est publiée derrière un proxy d'accès, la page le détecte et l'annonce : chaque appel doit alors présenter en plus un jeton du proxy, que la configuration proposée porte déjà si l'exploitant l'a renseigné côté serveur. Un détail qui coûte une demi-heure quand on l'ignore : la clé se passe dans son en-tête dédié et non dans « Authorization », qu'un proxy consomme souvent pour lui-même.`,
            },
            {
              term: $localize`:@@aide.mcp.term.prompts:Prompts prêts à l'emploi`,
              text: $localize`:@@aide.mcp.def.prompts:Le serveur annonce lui-même une dizaine de demandes toutes faites — vérifier qu'une édition est prête à être résolue, diagnostiquer les contraintes dures encore violées, relancer une résolution sans perdre le planning en place, traiter les déclarations de disponibilité, publier. Un client qui gère les prompts MCP les propose directement ; sinon, « Copier le prompt » et collez le texte dans l'assistant.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.mcp.journal:Rien de ce qu'un assistant écrit n'est invisible : chaque écriture laisse une ligne dans l'historique des actions, sous l'auteur « Assistant ». C'est là qu'on relit ce qu'une session a réellement fait, et le premier réflexe quand une donnée a changé sans qu'aucun écran ne l'ait touchée.`,
        },
      ],
      links: [
        { route: '/mcp-client', label: $localize`:@@nav.link.mcp:MCP` },
        { route: '/historique', label: $localize`:@@nav.link.historique:Historique` },
        { route: '/instantanes', label: $localize`:@@nav.link.snapshots:Instantanés` },
      ],
    },
    {
      id: 'contact',
      icon: 'contact_support',
      title: $localize`:@@aide.contact.title:Contact et support`,
      summary: $localize`:@@aide.contact.summary:Une question, un blocage, une erreur métier : à qui s'adresser et comment.`,
      // The address comes from the deployment (BRANDING_SUPPORT_EMAIL); with
      // none, the paragraph and its link are left out rather than pointing at
      // nobody.
      blocks: [
        ...(supportEmail
          ? [
              {
                kind: 'paragraph' as const,
                text: $localize`:@@aide.contact.mail:Pour une question d'utilisation, un doute sur un résultat ou un besoin d'accompagnement, écrivez à ${supportEmail}:email: — joignez si possible une capture d'écran et la version affichée en bas de page.`,
              },
            ]
          : []),
        {
          kind: 'paragraph',
          text: supportEmail
            ? $localize`:@@aide.contact.issue:Pour une erreur métier reproductible (un score faux, une contrainte non respectée, un export incorrect), ouvrez un rapport de problème sur GitHub : le formulaire guide la description. Attention, GitHub est public : n'y mettez aucune information nominative — désignez les personnes par leur identifiant d'animateur (visible sur la page Animateurs), jamais par leur nom. Les données nominatives, elles, passent par l'e-mail ci-dessus.`
            : $localize`:@@aide.contact.issueSansMail:Pour une erreur métier reproductible (un score faux, une contrainte non respectée, un export incorrect), ouvrez un rapport de problème sur GitHub : le formulaire guide la description. Attention, GitHub est public : n'y mettez aucune information nominative — désignez les personnes par leur identifiant d'animateur (visible sur la page Animateurs), jamais par leur nom.`,
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
