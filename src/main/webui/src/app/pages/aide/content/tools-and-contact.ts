// Imports, exports and tools, and who to write to.
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
            $localize`:@@aide.exchange.item.scenario:Import d'un scénario YAML depuis votre poste : la façon la plus rapide de remplir une édition vide. Un fichier invalide donne une notification détaillée plutôt qu'un import partiel.`,
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
        { route: '/debug', label: $localize`:@@nav.link.debug:Débogage` },
        { route: '/notifications', label: $localize`:@@nav.link.notifications:Notifications` },
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
                text: $localize`:@@aide.contact.mail:Pour une question d'utilisation, un doute sur un résultat ou un besoin d'accompagnement, écrivez à ${supportEmail}:email: — joignez si possible une capture d'écran et la version affichée en bas de la page Débogage.`,
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
