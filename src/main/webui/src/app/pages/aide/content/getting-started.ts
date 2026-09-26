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
          text: $localize`:@@aide.start.intro:L'application place vos animateurs sur des postes. Un poste, c'est une place à tenir sur un stand pendant un créneau. Le solveur n'en crée aucun : il choisit qui occupe ceux que vos stands et vos créneaux décrivent déjà. Tout se joue donc dans la saisie.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.classeur:Trois choses changent par rapport à un tableur. Le cadre légal est vérifié en continu. Un changement tardif se rejoue sans tout refaire. Et le planning diffusé est daté : l'application sait qui a reçu quelle version.`,
        },
        {
          kind: 'steps',
          items: [
            $localize`:@@aide.start.step1:Choisir ou créer l'édition. Rien ne circule d'une édition à l'autre.`,
            $localize`:@@aide.start.step2.fichiers:Saisir les référentiels dans l'ordre du groupe Préparer : typologies, créneaux (c'est là que l'édition prend ses dates), emplacements, stands, animateurs. Chacun s'importe aussi depuis un fichier CSV ou un collage de tableur, par le bouton « Importer » de son écran ou par la page Fichiers ; un scénario YAML remplace l'étape entière.`,
            $localize`:@@aide.start.step3:Ouvrir la collecte des disponibilités, case « prévenir » cochée : chacun reçoit le lien de son espace et y déclare ses absences et ses souhaits. Les fiches animateurs et les créneaux doivent donc déjà exister.`,
            $localize`:@@aide.start.step4:Appliquer ou refuser les déclarations reçues, puis fermer la collecte.`,
            $localize`:@@aide.start.step5:Vérifier les ouvertures des stands, puis le besoin en animateurs. Ces deux écrans répondent avant tout calcul.`,
            $localize`:@@aide.start.step6:Si vos journées ne sont décrites que par une amplitude d'ouverture, générer le découpage en vacations. Avec des journées types, la grille est déjà prête.`,
            $localize`:@@aide.start.step7:Lancer une résolution courte, lire la page Problèmes, corriger, puis relancer sur une durée longue. Ne changez qu'une chose à la fois entre deux essais.`,
            $localize`:@@aide.start.step8:Publier : seules les personnes dont l'emploi du temps a changé reçoivent un message. C'est aussi le seul moment où les agendas abonnés bougent.`,
            $localize`:@@aide.start.step9:Suivre les accusés de réception sur la page Animateurs, et activer les envois de nuit sur la page Paramètres pour relancer les silencieux.`,
            $localize`:@@aide.start.step10:Vérifier que la foire au planning est ouverte — elle l'est par défaut — puis la fermer quand le planning est figé. Le jour même, le mode jour J prend le relais.`,
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.demo:Pour découvrir l'application sans rien saisir, chargez un exemple : page Fichiers, onglet Importer, carte « Exemples ». « Festival réaliste — canicule » est une édition réelle anonymisée de seize jours ; elle arrive dans une édition à son nom, sans toucher la vôtre.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.guichets:Deux guichets s'ouvrent et se ferment à la main, et ce sont les seuls endroits où un animateur écrit quelque chose : la collecte des disponibilités avant la construction, la foire au planning après la publication. Chacun accepte en plus une période datée, mais l'interrupteur reste maître. La collecte est fermée tant que vous ne l'ouvrez pas ; la foire est ouverte par défaut.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.menu:Le menu suit ce cycle : Préparer pour les référentiels, Construire pour le calcul et ce qu'il doit respecter, Diffuser pour l'envoi, Aujourd'hui pour l'événement lui-même, et Planning pour lire le plan sous tous ses angles. « Voir le planning », sur l'accueil et sur le Solveur, ouvre la journée du jour.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.accueil.phases:La page d'accueil prend la forme du moment. Pendant la préparation, elle reprend ces étapes en checklist : chaque ligne dit où vous en êtes, avec le chiffre qui compte et un lien vers l'écran qui fait avancer ; la dernière résolution s'y lit en phrases, et le gel du référentiel y est rappelé avec le lien vers les Paramètres, le seul endroit où il se règle. Du premier au dernier jour de l'événement, la première ligne est celle du jour — « Aujourd'hui — J5 · stands ouverts · places vides · absents · échanges à arbitrer » — et ouvre le mode jour J ; « Afficher sur la TV » mène à l'affichage mural, et la checklist se déplie à la demande. Une fois l'événement passé, l'archive vient en tête. Sur une édition vide, un bloc « Démarrer » propose d'importer un fichier, de repartir d'une édition ou de charger un exemple.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.avertissements:Une saisie douteuse mais tenable est enregistrée avec un avertissement à lire, jamais refusée en silence. Un refus, lui, s'affiche en rouge et n'écrit rien.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.aTraiter.accueil:En tête de page, l'encadré « À traiter aujourd'hui » liste ce qui attend une décision : déclarations de disponibilité à appliquer, demandes d'échange à arbitrer — en alerte au-delà de l'ancienneté réglée dans les paramètres de notifications —, journées pas encore relues dans la semaine qui vient, personnes silencieuses depuis la publication et jamais relancées, données modifiées depuis la dernière résolution, personnes à prévenir, et ce qui n'a pas pu partir, tant que la cause demeure : un rappel de la veille à une fiche toujours sans adresse, une relance — de la nuit ou à la main — à quelqu'un toujours silencieux, une sauvegarde de nuit en échec. Chaque ligne ouvre son écran avec le filtre déjà appliqué. Dessous, « Messages récents » déplie les alertes des envois de nuit, nommément, et l'historique des messages de l'application, à marquer comme lus ou à effacer ; la cloche de la barre du haut mène à cet encadré. « Aujourd'hui » est celui du serveur, la date simulée quand elle est posée.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.coherence.fusion:Un avertissement fermé n'est pas perdu : la ligne « Cohérence du référentiel » de l'accueil rejoue tous les contrôles sur l'ensemble de ce qui est saisi — animateurs, créneaux, stands et ouvertures, ajustements manuels, besoin en animateurs. Elle compte les anomalies ; « Voir le détail » les déplie, les anomalies identiques réunies en une ligne avec leur compte, « 16 jours : rien entre 12:00 et 13:00 », une ligne par sujet avec sa gravité et le lien vers la fiche qui la corrige. Un créneau devenu hors ouverture parce qu'un stand a changé ensuite s'y voit, alors qu'aucun message ne l'a signalé à l'enregistrement du stand.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@aide.start.theme:En haut à droite, un bouton fait tourner l'affichage entre automatique, clair et sombre. « Automatique » suit le réglage de votre appareil ; un choix explicite fige l'affichage sur ce navigateur.`,
        },
      ],
      links: [
        { route: '/editions', label: $localize`:@@nav.link.editions:Éditions` },
        {
          route: '/fichiers',
          queryParams: { cible: 'exemples' },
          label: $localize`:@@aide.lien.exemples:Fichiers — Exemples`,
        },
        {
          route: '/fichiers',
          queryParams: { cible: 'animateurs' },
          label: $localize`:@@nav.tab.importAnimateurs:Importer des animateurs`,
        },
        { route: '/disponibilites', label: $localize`:@@nav.link.disponibilites:Disponibilités` },
        { route: '/', label: $localize`:@@nav.link.accueil:État de l'édition` },
        { route: '/solveur', label: $localize`:@@nav.link.solver:Solveur` },
        { route: '/echanges', label: $localize`:@@nav.link.echanges:Échanges` },
        { route: '/parametres', label: $localize`:@@nav.link.parametres:Paramètres` },
      ],
    },
  ];
}
