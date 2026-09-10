// Getting started: the whole cycle of an edition, from the first record to
// the acknowledged schedule.
//
// One theme of the organisers' guide; `aide-content.ts` assembles the
// themes in reading order. Built lazily, never at module scope: `$localize`
// only resolves once `main.ts` has loaded the translation catalog.

import { HelpSection } from '../help-section';

export function buildGettingStartedSections(): HelpSection[] {
  return [
    {
      id: 'prise-en-main',
      icon: 'rocket_launch',
      title: $localize`:@@aide.start.title:Prise en main`,
      summary: $localize`:@@aide.start.summary:Le cycle complet d'une édition : collecter, construire, résoudre, publier, suivre.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.intro:L'application affecte des animateurs à des postes. Un poste, c'est une place à pourvoir sur un stand pendant un créneau : le solveur n'en crée aucune, il choisit qui occupe celles que vos stands et vos créneaux décrivent déjà. Ce qui se réglait dans la mise en page du classeur se règle donc en amont, dans la saisie.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.classeur:Trois choses changent la méthode par rapport à un tableur. Le cadre légal est vérifié en continu, pas à la relecture. Un changement tardif se rejoue par une replanification incrémentale, qui laisse intact tout ce qui restait valable. Et le planning diffusé est daté : l'application sait qui a reçu quelle version, et qui ne l'a pas encore lue.`,
        },
        {
          kind: 'list',
          items: [
            $localize`:@@aide.start.step1:1. Choisir ou créer l'édition : tout le reste lui appartient, et rien ne circule d'une édition à l'autre.`,
            $localize`:@@aide.start.step2:2. Saisir les référentiels : typologies de jeux, emplacements, stands, animateurs, créneaux. Les animateurs entrent aussi d'un seul geste par l'import CSV, si vos bénévoles arrivent déjà dans un tableur ; un scénario YAML ou un dump SQL remplace l'étape entière. Une saisie douteuse mais tenable est enregistrée avec un avertissement à lire, jamais refusée en silence.`,
            $localize`:@@aide.start.step3:3. Ouvrir la collecte des disponibilités, case « prévenir » cochée : elle envoie à chacun le lien de son espace, où il déclare depuis son téléphone ses jours d'absence et les jeux qu'il aimerait animer. Les fiches animateurs doivent donc déjà exister ; et si vous ouvrez la collecte avant d'avoir saisi les créneaux, l'espace n'offre aucun jour à cocher — seuls les souhaits se déclarent.`,
            $localize`:@@aide.start.step4:4. Appliquer ou refuser les déclarations reçues, puis fermer la collecte : appliquer écrit la proposition entière sur la fiche de l'animateur.`,
            $localize`:@@aide.start.step5:5. Vérifier les ouvertures des stands, puis le besoin en animateurs : ces deux écrans répondent sans qu'aucun calcul ait tourné.`,
            $localize`:@@aide.start.step6:6. Si les journées sont décrites par une simple amplitude d'ouverture, générer le découpage en vacations.`,
            $localize`:@@aide.start.step7:7. Lancer une résolution courte, lire la page Problèmes, corriger — puis relancer sur une durée longue.`,
            $localize`:@@aide.start.step8:8. Publier : seules les personnes dont l'emploi du temps a changé reçoivent un message, avec le lien de leur espace. C'est aussi le seul moment où les agendas abonnés bougent — tant que vous n'avez pas publié, ils montrent la version précédente.`,
            $localize`:@@aide.start.step9:9. Suivre les accusés de réception sur la page Animateurs, et activer les envois de nuit sur la page Paramètres pour que les silencieux soient relancés — une fois.`,
            $localize`:@@aide.start.step10:10. Vérifier que la foire au planning est ouverte — elle l'est par défaut — pour que les échanges se négocient entre animateurs, la fermer quand le planning est figé. Le jour même, le mode jour J prend le relais.`,
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.guichets:Deux guichets s'ouvrent et se ferment à la main, et ce sont les seuls endroits où un animateur écrit quelque chose : la collecte des disponibilités (page Disponibilités) avant la construction, la foire au planning (page Échanges) après la publication. Chacun accepte en plus une période datée, mais l'interrupteur reste maître — une période renseignée n'ouvre jamais un guichet fermé. La collecte est fermée tant que vous ne l'ouvrez pas ; la foire, elle, est ouverte par défaut.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.iterate:Les étapes de résolution se répètent : une première résolution courte sert à révéler ce qui bloque, pas à produire le planning final. Ne changez qu'une chose à la fois entre deux essais, sinon plus rien n'est comparable.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.theme:En haut à droite, un bouton fait tourner l'affichage entre trois réglages : automatique, clair et sombre. « Automatique » suit le réglage de votre ordinateur ou de votre téléphone — c'est pourquoi l'application peut passer au sombre toute seule le soir. Choisir explicitement « clair » ou « sombre » fige l'affichage : ce navigateur s'en souvient et ne change plus d'avis.`,
        },
      ],
      links: [
        { route: '/editions', label: $localize`:@@nav.link.editions:Éditions` },
        {
          route: '/import-animateurs',
          label: $localize`:@@nav.link.importAnimateurs:Import CSV des animateurs`,
        },
        { route: '/disponibilites', label: $localize`:@@nav.link.disponibilites:Disponibilités` },
        { route: '/', label: $localize`:@@nav.link.solver:Solveur` },
        { route: '/echanges', label: $localize`:@@nav.link.echanges:Échanges` },
        { route: '/parametres', label: $localize`:@@nav.link.parametres:Paramètres` },
      ],
    },
  ];
}
