// The short help of every screen of the menu and of every tab that is a screen
// of its own: what the screen answers, the main gesture, and the way on — the
// screen itself, the detailed section of this guide, the guide of the site.
// Routes name these sections in `data.aide` / `data.aideOnglets`
// (`app.routes.ts`), and `aide-content.spec.ts` keeps both in step: a screen
// without its help, or a help whose screen is gone, fails there.
//
// Built from a function, never a constant: `$localize` only resolves once
// `main.ts` has loaded the translation catalog.

import { HelpLink, HelpSection } from '../help-section';

/** The id of the short help of a screen (or of a tab): what the routes' `data` names. */
export const SCREEN_PREFIX = 'ecran-';

/** The guide of the site a screen is written up in, one message per page since its address differs per language. */
function guide(page: string): HelpLink {
  return { href: page, label: $localize`:@@aide.ecran.guide:Guide du site` };
}

const guides = {
  fil: () =>
    guide(
      $localize`:@@aide.ecran.guide.fil:https://planning.sylvain.dev/docs/regles/fil-d-une-edition/`,
    ),
  preparer: () =>
    guide(
      $localize`:@@aide.ecran.guide.preparer:https://planning.sylvain.dev/docs/regles/preparer-edition/`,
    ),
  calendrier: () =>
    guide(
      $localize`:@@aide.ecran.guide.calendrier:https://planning.sylvain.dev/docs/regles/calendrier-et-horaires/`,
    ),
  animateurs: () =>
    guide(
      $localize`:@@aide.ecran.guide.animateurs:https://planning.sylvain.dev/docs/regles/animateurs-et-competences/`,
    ),
  collecter: () =>
    guide(
      $localize`:@@aide.ecran.guide.collecter:https://planning.sylvain.dev/docs/regles/collecter-disponibilites/`,
    ),
  importer: () =>
    guide(
      $localize`:@@aide.ecran.guide.importer:https://planning.sylvain.dev/docs/regles/importer-exporter/`,
    ),
  consulter: () =>
    guide(
      $localize`:@@aide.ecran.guide.consulter:https://planning.sylvain.dev/docs/regles/consulter-planning/`,
    ),
  calculer: () =>
    guide(
      $localize`:@@aide.ecran.guide.calculer:https://planning.sylvain.dev/docs/regles/calculer-planning/`,
    ),
  comprendre: () =>
    guide(
      $localize`:@@aide.ecran.guide.comprendre:https://planning.sylvain.dev/docs/regles/comprendre-un-planning/`,
    ),
  dimensionner: () =>
    guide(
      $localize`:@@aide.ecran.guide.dimensionner:https://planning.sylvain.dev/docs/regles/dimensionner-l-equipe/`,
    ),
  regles: () =>
    guide(
      $localize`:@@aide.ecran.guide.regles:https://planning.sylvain.dev/docs/regles/regles-du-planning/`,
    ),
  ajustements: () =>
    guide(
      $localize`:@@aide.ecran.guide.ajustements:https://planning.sylvain.dev/docs/regles/ajustements-et-verrous/`,
    ),
  consigne: () =>
    guide(
      $localize`:@@aide.ecran.guide.consigne:https://planning.sylvain.dev/docs/regles/appliquer-une-consigne/`,
    ),
  publier: () =>
    guide(
      $localize`:@@aide.ecran.guide.publier:https://planning.sylvain.dev/docs/regles/publier-planning/`,
    ),
  echanger: () =>
    guide(
      $localize`:@@aide.ecran.guide.echanger:https://planning.sylvain.dev/docs/regles/echanger-creneaux/`,
    ),
  jourj: () =>
    guide(
      $localize`:@@aide.ecran.guide.jourj:https://planning.sylvain.dev/docs/regles/suivre-le-jour-j/`,
    ),
  comptes: () =>
    guide(
      $localize`:@@aide.ecran.guide.comptes:https://planning.sylvain.dev/docs/regles/comptes-et-droits/`,
    ),
  editions: () =>
    guide(
      $localize`:@@aide.ecran.guide.editions:https://planning.sylvain.dev/docs/regles/enchainer-les-editions/`,
    ),
  outils: () =>
    guide(
      $localize`:@@aide.ecran.guide.outils:https://planning.sylvain.dev/docs/regles/outils-de-l-organisateur/`,
    ),
};

type GuideKey = keyof typeof guides;

type Row = [
  icon: string,
  route: string,
  queryParams: Record<string, string> | undefined,
  detail: string[],
  guide: GuideKey,
  title: string,
  answers: string,
  gesture: string,
];

/** One short help per row. The key is the screen's id, so that no two rows share a run of tokens. */
function screenHelp(key: string, row: Row): HelpSection {
  const [icon, route, queryParams, detail, guideKey, title, answers, gesture] = row;
  const links: HelpLink[] = [{ route, queryParams, label: title }];
  for (const fragment of detail) {
    links.push({
      route: '/aide',
      fragment,
      label: $localize`:@@aide.ecran.detail:Guide détaillé`,
    });
  }
  links.push(guides[guideKey]());
  return {
    id: SCREEN_PREFIX + key.replace(/[A-Z]/g, (letter) => '-' + letter.toLowerCase()),
    icon,
    title,
    summary: answers,
    blocks: [{ kind: 'paragraph', text: gesture }],
    links,
  };
}

export function buildScreenSections(): HelpSection[] {
  const rows: Record<string, Row> = {
    accueil: [
      'checklist',
      '/',
      undefined,
      ['prise-en-main'],
      'fil',
      $localize`:@@aide.ecran.accueil.title:État de l'édition`,
      $localize`:@@aide.ecran.accueil.repond:Où en est l'édition dans son cycle, et ce qui demande votre attention aujourd'hui.`,
      $localize`:@@aide.ecran.accueil.geste:Lisez la phase de l'édition, puis suivez la liste « À traiter aujourd'hui » : chaque ligne mène à l'écran qui règle le point.`,
    ],
    stands: [
      'storefront',
      '/stands',
      undefined,
      ['donnees'],
      'preparer',
      $localize`:@@aide.ecran.stands.title:Stands`,
      $localize`:@@aide.ecran.stands.repond:Quels stands l'édition compte, combien de places chacun demande et quelles typologies il propose.`,
      $localize`:@@aide.ecran.stands.geste:Ajoutez un stand avec son effectif minimal et maximal et ses typologies ; cliquez sur un stand pour ouvrir sa fiche et régler ses horaires.`,
    ],
    lieux: [
      'place',
      '/stands',
      { onglet: 'lieux' },
      [],
      'preparer',
      $localize`:@@aide.ecran.lieux.title:Lieux`,
      $localize`:@@aide.ecran.lieux.repond:Où se trouvent les emplacements des stands sur le site.`,
      $localize`:@@aide.ecran.lieux.geste:Créez un lieu, nommez-le et placez-le sur la carte ; la vue Carte du Planning les montre.`,
    ],
    typologies: [
      'category',
      '/stands',
      { onglet: 'typologies' },
      [],
      'preparer',
      $localize`:@@aide.ecran.typologies.title:Typologies`,
      $localize`:@@aide.ecran.typologies.repond:Les catégories de jeu que les stands proposent et que les animateurs maîtrisent.`,
      $localize`:@@aide.ecran.typologies.geste:Ajoutez une typologie, puis rattachez-la aux stands qui la proposent et aux animateurs compétents.`,
    ],
    calendrier: [
      'schedule',
      '/calendrier',
      undefined,
      ['calendrier'],
      'calendrier',
      $localize`:@@aide.ecran.calendrier.title:Calendrier`,
      $localize`:@@aide.ecran.calendrier.repond:Les créneaux de l'édition : les vacations que le calcul remplira, jour par jour.`,
      $localize`:@@aide.ecran.calendrier.geste:Générez les créneaux depuis un gabarit ou les horaires des stands, puis corrigez à la main ceux qui diffèrent.`,
    ],
    animateurs: [
      'groups',
      '/animateurs',
      undefined,
      ['donnees'],
      'animateurs',
      $localize`:@@aide.ecran.animateurs.title:Animateurs`,
      $localize`:@@aide.ecran.animateurs.repond:Qui compose l'équipe, avec son âge, ses compétences et ses jours d'indisponibilité.`,
      $localize`:@@aide.ecran.animateurs.geste:Ajoutez un animateur ou importez-les depuis un tableur ; cliquez sur un nom pour ouvrir sa fiche et y saisir ses indisponibilités.`,
    ],
    competences: [
      'verified',
      '/animateurs',
      { onglet: 'competences' },
      ['competences'],
      'animateurs',
      $localize`:@@aide.ecran.competences.title:Compétences`,
      $localize`:@@aide.ecran.competences.repond:Quel animateur sait tenir quelle typologie.`,
      $localize`:@@aide.ecran.competences.geste:Cochez les cases de la grille, puis enregistrez : le calcul s'appuie sur ces compétences pour affecter chacun.`,
    ],
    disponibilites: [
      'event_busy',
      '/animateurs',
      { onglet: 'disponibilites' },
      ['disponibilites'],
      'collecter',
      $localize`:@@aide.ecran.disponibilites.title:Disponibilités`,
      $localize`:@@aide.ecran.disponibilites.repond:Quels jours les animateurs ont déclarés indisponibles, et les déclarations qui attendent votre décision.`,
      $localize`:@@aide.ecran.disponibilites.geste:Ouvrez la collecte, laissez chacun déclarer depuis son espace, puis appliquez les déclarations avant le calcul.`,
    ],
    covoiturage: [
      'directions_car',
      '/animateurs',
      { onglet: 'covoiturage' },
      ['disponibilites'],
      'collecter',
      $localize`:@@aide.ecran.covoiturage.title:Covoiturage`,
      $localize`:@@aide.ecran.covoiturage.repond:Les demandes de covoiturage déposées par les animateurs.`,
      $localize`:@@aide.ecran.covoiturage.geste:Marquez chaque demande comme validée ou non, selon que vous pouvez organiser le trajet.`,
    ],
    importer: [
      'upload_file',
      '/fichiers',
      undefined,
      ['import-csv-animateurs'],
      'importer',
      $localize`:@@aide.ecran.importer.title:Importer`,
      $localize`:@@aide.ecran.importer.repond:Comment remplir l'édition depuis un fichier : un scénario complet, une liste d'animateurs, un exemple.`,
      $localize`:@@aide.ecran.importer.geste:Choisissez la carte qui correspond à votre fichier, déposez-le, lisez l'aperçu, puis confirmez l'import.`,
    ],
    exporter: [
      'download',
      '/fichiers',
      { onglet: 'exporter' },
      [],
      'importer',
      $localize`:@@aide.ecran.exporter.title:Exporter`,
      $localize`:@@aide.ecran.exporter.repond:Ce que l'édition peut livrer : ses données en CSV ou en scénario YAML.`,
      $localize`:@@aide.ecran.exporter.geste:Choisissez l'export voulu et téléchargez-le, pour le corriger dans un tableur ou le garder.`,
    ],
    archive: [
      'inventory_2',
      '/fichiers',
      { onglet: 'archive' },
      [],
      'importer',
      $localize`:@@aide.ecran.archive.title:Archive`,
      $localize`:@@aide.ecran.archive.repond:L'archive de fin d'événement : toute l'édition dans un seul fichier ZIP.`,
      $localize`:@@aide.ecran.archive.geste:Une fois l'événement terminé, téléchargez l'archive et conservez-la avec vos documents d'édition.`,
    ],
    planning: [
      'calendar_month',
      '/journee',
      undefined,
      ['consulter'],
      'consulter',
      $localize`:@@aide.ecran.planning.title:Planning`,
      $localize`:@@aide.ecran.planning.repond:Qui est où : le plan calculé, jour par jour, par stand, par personne ou sur la carte.`,
      $localize`:@@aide.ecran.planning.geste:Choisissez le jour et la vue ; cliquez sur un siège pour le relire ou le retoucher sans casser une règle.`,
    ],
    calculer: [
      'play_arrow',
      '/journee',
      { onglet: 'calculer' },
      ['calculer'],
      'calculer',
      $localize`:@@aide.ecran.calculer.title:Calculer`,
      $localize`:@@aide.ecran.calculer.repond:Le plan est-il calculable, et que prendra en compte le prochain calcul.`,
      $localize`:@@aide.ecran.calculer.geste:Lisez la faisabilité, puis lancez « Calculer le planning » pour le calcul complet, ou « Corriger après un changement » pour bouger le moins possible.`,
    ],
    versions: [
      'history',
      '/journee',
      { onglet: 'versions' },
      ['calculer'],
      'calculer',
      $localize`:@@aide.ecran.versions.title:Versions du plan`,
      $localize`:@@aide.ecran.versions.repond:Les plans calculés et enregistrés de l'édition, et ce qui les distingue.`,
      $localize`:@@aide.ecran.versions.geste:Capturez une version avant un essai, comparez-en deux, restaurez celle que vous préférez.`,
    ],
    problemes: [
      'report_problem',
      '/diagnostic',
      { onglet: 'problemes' },
      ['lire-les-resultats'],
      'comprendre',
      $localize`:@@aide.ecran.problemes.title:Problèmes`,
      $localize`:@@aide.ecran.problemes.repond:Pourquoi le planning coince : les places vides et les règles qui cèdent, avec les personnes concernées.`,
      $localize`:@@aide.ecran.problemes.geste:Ouvrez un problème et suivez « Que faire ? » : le geste proposé règle d'abord la cause la plus utile.`,
    ],
    besoin: [
      'calculate',
      '/diagnostic',
      { onglet: 'besoin' },
      [],
      'dimensionner',
      $localize`:@@aide.ecran.besoin.title:Besoin en animateurs`,
      $localize`:@@aide.ecran.besoin.repond:Combien de personnes vos stands exigent au minimum, et quelles compétences former.`,
      $localize`:@@aide.ecran.besoin.geste:Lisez la fiche de recrutement, puis vérifiez le minimum par un vrai calcul depuis l'onglet.`,
    ],
    risques: [
      'warning',
      '/diagnostic',
      { onglet: 'risques' },
      [],
      'comprendre',
      $localize`:@@aide.ecran.risques.title:Risques`,
      $localize`:@@aide.ecran.risques.repond:Où le plan est fragile : les points sans remplaçant possible, et la tension heure par heure.`,
      $localize`:@@aide.ecran.risques.geste:Parcourez la fragilité pour repérer les points de défaillance, puis la tension ; formez ou ajoutez quelqu'un là où cela casse.`,
    ],
    charge: [
      'scale',
      '/diagnostic',
      { onglet: 'charge' },
      [],
      'comprendre',
      $localize`:@@aide.ecran.charge.title:Charge`,
      $localize`:@@aide.ecran.charge.repond:Ce que chacun travaille : heures, soirées, week-ends et jours fériés, et l'équilibre entre les personnes.`,
      $localize`:@@aide.ecran.charge.geste:Triez par heures ou par équité pour repérer les écarts, et exportez en CSV pour la paie.`,
    ],
    repasPauses: [
      'restaurant',
      '/diagnostic',
      { onglet: 'repas-pauses' },
      [],
      'comprendre',
      $localize`:@@aide.ecran.repas-pauses.title:Repas et pauses`,
      $localize`:@@aide.ecran.repas-pauses.repond:Si chacun a ses pauses et ses repas : coupures, relais et enchaînements.`,
      $localize`:@@aide.ecran.repas-pauses.geste:Repérez les personnes sans pause ou sans repas, puis retouchez leur journée depuis le Planning.`,
    ],
    conformite: [
      'gavel',
      '/diagnostic',
      { onglet: 'conformite' },
      [],
      'comprendre',
      $localize`:@@aide.ecran.conformite.title:Conformité`,
      $localize`:@@aide.ecran.conformite.repond:Le rapport de conformité du plan aux plafonds légaux et à la protection des mineurs.`,
      $localize`:@@aide.ecran.conformite.geste:Lisez le rapport avant de publier, et remettez-le à l'inspection du travail ou aux ressources humaines si on vous le demande.`,
    ],
    ajustements: [
      'link',
      '/regles',
      { onglet: 'ajustements' },
      ['ajustements-manuels'],
      'ajustements',
      $localize`:@@aide.ecran.ajustements.title:Ajustements`,
      $localize`:@@aide.ecran.ajustements.repond:Les exceptions que vous imposez au calcul : affinités, incompatibilités, arrivées groupées.`,
      $localize`:@@aide.ecran.ajustements.geste:Ajoutez une exception entre deux personnes, ou une arrivée groupée, puis relancez le calcul.`,
    ],
    verrouillages: [
      'lock',
      '/regles',
      { onglet: 'verrouillages' },
      ['sans-affectation-forcee'],
      'ajustements',
      $localize`:@@aide.ecran.verrouillages.title:Verrouillages`,
      $localize`:@@aide.ecran.verrouillages.repond:Ce que le prochain calcul n'a pas le droit de bouger : un animateur sur un créneau, un stand ou une journée.`,
      $localize`:@@aide.ecran.verrouillages.geste:Choisissez le type de verrou, désignez la personne, le stand ou la journée, puis « Verrouiller » ; « Déverrouiller » le lève.`,
    ],
    consignes: [
      'policy',
      '/regles',
      { onglet: 'consignes' },
      ['consignes'],
      'consigne',
      $localize`:@@aide.ecran.consignes.title:Consignes`,
      $localize`:@@aide.ecran.consignes.repond:Ce qu'une autorité impose à tout l'événement un jour donné, comme la fermeture de stands à certaines heures.`,
      $localize`:@@aide.ecran.consignes.geste:Proposez la consigne, prévisualisez ses effets, posez-la ; levez-la de la même façon.`,
    ],
    regles: [
      'rule',
      '/regles',
      { onglet: 'regles' },
      ['parametrer-pour-un-planning-complet', 'configuration-solveur'],
      'regles',
      $localize`:@@aide.ecran.regles.title:Liste des règles`,
      $localize`:@@aide.ecran.regles.repond:Ce qui décide du plan : les seuils légaux et l'importance de chaque règle de qualité.`,
      $localize`:@@aide.ecran.regles.geste:Changez un seuil ou l'importance d'une règle, puis enregistrez ; une règle bloquée par une donnée absente le dit.`,
    ],
    envoyer: [
      'send',
      '/publication',
      { onglet: 'envoyer' },
      ['relecture'],
      'publier',
      $localize`:@@aide.ecran.envoyer.title:Envoyer`,
      $localize`:@@aide.ecran.envoyer.repond:Qui a reçu quelle version du planning, et qui l'a confirmée.`,
      $localize`:@@aide.ecran.envoyer.geste:Publiez, envoyez à tous ou à quelques personnes seulement, puis relancez ceux qui n'ont pas confirmé.`,
    ],
    documents: [
      'picture_as_pdf',
      '/publication',
      { onglet: 'documents' },
      [],
      'publier',
      $localize`:@@aide.ecran.documents.title:Documents`,
      $localize`:@@aide.ecran.documents.repond:Les documents à remettre : le planning de chaque animateur et le planning global, en PDF.`,
      $localize`:@@aide.ecran.documents.geste:Choisissez le document voulu, puis téléchargez-le ou envoyez-le.`,
    ],
    echanges: [
      'swap_horiz',
      '/echanges',
      undefined,
      ['foire-au-planning'],
      'echanger',
      $localize`:@@aide.ecran.echanges.title:Échanges`,
      $localize`:@@aide.ecran.echanges.repond:Les échanges de créneaux que les animateurs se proposent entre eux.`,
      $localize`:@@aide.ecran.echanges.geste:Ouvrez la foire au planning : les animateurs proposent leurs échanges, et vous arbitrez ceux qui demandent votre accord.`,
    ],
    echangesStats: [
      'query_stats',
      '/echanges',
      { onglet: 'stats' },
      ['foire-au-planning'],
      'echanger',
      $localize`:@@aide.ecran.echanges-stats.title:Statistiques des échanges`,
      $localize`:@@aide.ecran.echanges-stats.repond:Comment la foire fonctionne : taux d'acceptation, délais et volumes.`,
      $localize`:@@aide.ecran.echanges-stats.geste:Lisez les chiffres pour décider d'ouvrir, de fermer ou de borner la foire par des dates.`,
    ],
    aujourdhui: [
      'today',
      '/aujourdhui',
      undefined,
      ['jour-j'],
      'jourj',
      $localize`:@@aide.ecran.aujourdhui.title:Aujourd'hui`,
      $localize`:@@aide.ecran.aujourdhui.repond:Ce qui se passe le jour J : les absents, les places vides, la journée vue sur un téléphone.`,
      $localize`:@@aide.ecran.aujourdhui.geste:Déclarez un absent, puis proposez-lui un remplaçant sur le créneau resté vide.`,
    ],
    edition: [
      'tune',
      '/parametres',
      { onglet: 'edition' },
      ['alerte-meteo'],
      'outils',
      $localize`:@@aide.ecran.edition.title:Paramètres de l'édition`,
      $localize`:@@aide.ecran.edition.repond:Les réglages propres à l'édition : guichets, collecte des disponibilités, foire, covoiturage, météo, contact.`,
      $localize`:@@aide.ecran.edition.geste:Réglez chaque bloc puis enregistrez : ces réglages ne valent que pour l'édition choisie.`,
    ],
    notifications: [
      'notifications',
      '/parametres',
      { onglet: 'notifications' },
      ['rappels'],
      'outils',
      $localize`:@@aide.ecran.notifications.title:Notifications`,
      $localize`:@@aide.ecran.notifications.repond:Les e-mails automatiques : rappel de la veille, relance des non-confirmés, alerte sur les échanges en attente.`,
      $localize`:@@aide.ecran.notifications.geste:Activez ou coupez chaque envoi, puis enregistrez.`,
    ],
    legal: [
      'balance',
      '/parametres',
      { onglet: 'legal' },
      [],
      'regles',
      $localize`:@@aide.ecran.legal.title:Paramètres légaux`,
      $localize`:@@aide.ecran.legal.repond:Les plafonds légaux que le calcul respecte : durée hebdomadaire, pauses, repas.`,
      $localize`:@@aide.ecran.legal.geste:Ajustez un seuil si votre convention collective l'exige, puis enregistrez.`,
    ],
    organisation: [
      'tune',
      '/parametres',
      { onglet: 'organisation' },
      [],
      'regles',
      $localize`:@@aide.ecran.organisation.title:Organisation`,
      $localize`:@@aide.ecran.organisation.repond:Les seuils de confort et d'organisation : jours consécutifs, trajets, pauses.`,
      $localize`:@@aide.ecran.organisation.geste:Réglez un seuil et le niveau de chaque règle d'organisation, puis enregistrez.`,
    ],
    calcul: [
      'timer',
      '/parametres',
      { onglet: 'calcul' },
      ['configuration-solveur', 'tuner'],
      'regles',
      $localize`:@@aide.ecran.calcul.title:Budget de calcul`,
      $localize`:@@aide.ecran.calcul.repond:Le temps que le solveur a le droit de chercher, et sa polyvalence.`,
      $localize`:@@aide.ecran.calcul.geste:Fixez la durée du calcul puis enregistrez : un budget plus long cherche plus loin.`,
    ],
    mural: [
      'tv',
      '/parametres',
      { onglet: 'mural' },
      ['affichage-mural'],
      'publier',
      $localize`:@@aide.ecran.mural.title:Affichage mural`,
      $localize`:@@aide.ecran.mural.repond:La journée en cours sur le téléviseur de la salle de contrôle.`,
      $localize`:@@aide.ecran.mural.geste:Créez un lien, copiez-le sur le téléviseur (il ne s'affiche qu'une fois) et révoquez-le d'un clic s'il a fuité.`,
    ],
    comptes: [
      'manage_accounts',
      '/parametres',
      { onglet: 'comptes' },
      [],
      'comptes',
      $localize`:@@aide.ecran.comptes.title:Comptes`,
      $localize`:@@aide.ecran.comptes.repond:Qui a accès à l'application et avec quels droits.`,
      $localize`:@@aide.ecran.comptes.geste:Créez un compte, donnez-lui un droit, puis précisez les éditions ou les stands qu'il peut voir.`,
    ],
    instance: [
      'dns',
      '/parametres',
      { onglet: 'instance' },
      ['webhooks'],
      'outils',
      $localize`:@@aide.ecran.instance.title:Instance`,
      $localize`:@@aide.ecran.instance.repond:Les réglages communs à toutes les éditions : webhooks sortants, sauvegarde, date simulée.`,
      $localize`:@@aide.ecran.instance.geste:Ajoutez un webhook, suspendez la sauvegarde nocturne ou posez une date simulée pour une répétition.`,
    ],
    historique: [
      'manage_search',
      '/parametres',
      { onglet: 'historique' },
      ['historique-des-actions'],
      'comptes',
      $localize`:@@aide.ecran.historique.title:Historique des actions`,
      $localize`:@@aide.ecran.historique.repond:Qui a fait quoi dans l'application, action par action.`,
      $localize`:@@aide.ecran.historique.geste:Filtrez le journal pour retrouver un changement : il dit ce qui a changé, jamais les valeurs saisies.`,
    ],
    connexions: [
      'login',
      '/parametres',
      { onglet: 'connexions' },
      ['historique-des-actions'],
      'comptes',
      $localize`:@@aide.ecran.connexions.title:Connexions`,
      $localize`:@@aide.ecran.connexions.repond:Les connexions des comptes : réussites, échecs et verrouillages.`,
      $localize`:@@aide.ecran.connexions.geste:Cherchez une adresse ou une date pour repérer des échecs répétés ; rien de ce qui a été saisi n'est conservé.`,
    ],
    editions: [
      'event',
      '/editions',
      undefined,
      ['editions', 'phase-de-l-edition'],
      'editions',
      $localize`:@@aide.ecran.editions.title:Éditions`,
      $localize`:@@aide.ecran.editions.repond:Les éditions de l'instance et la phase de chacune.`,
      $localize`:@@aide.ecran.editions.geste:Créez ou dupliquez une édition, puis activez celle qui commence : seule l'édition active publie et envoie des e-mails.`,
    ],
    mesures: [
      'insights',
      '/mesures',
      undefined,
      ['realise'],
      'jourj',
      $localize`:@@aide.ecran.mesures.title:Mesures d'édition`,
      $localize`:@@aide.ecran.mesures.repond:Ce que l'édition a donné : l'écart entre le planifié et le réalisé, et les indicateurs de chaque calcul.`,
      $localize`:@@aide.ecran.mesures.geste:Lisez le réalisé jour par jour, puis passez à la vue Indicateurs pour comparer les calculs.`,
    ],
    debug: [
      'bug_report',
      '/debug',
      undefined,
      ['echanges'],
      'outils',
      $localize`:@@aide.ecran.debug.title:Débogage`,
      $localize`:@@aide.ecran.debug.repond:L'état technique de l'instance : version, résolution, vérifications. Réservé aux administrateurs.`,
      $localize`:@@aide.ecran.debug.geste:Ouvrez Résolution pour la version et l'analyse brute, Vérifications pour les contrôles techniques.`,
    ],
    comparer: [
      'compare',
      '/comparer',
      undefined,
      [],
      'calculer',
      $localize`:@@aide.ecran.comparer.title:Comparer deux versions`,
      $localize`:@@aide.ecran.comparer.repond:Les différences entre deux versions du plan, ou entre les données de deux éditions.`,
      $localize`:@@aide.ecran.comparer.geste:Choisissez les deux versions (ou les deux éditions) : l'écart de score et de couverture s'affiche.`,
    ],
  };
  return Object.entries(rows).map(([key, row]) => screenHelp(key, row));
}
