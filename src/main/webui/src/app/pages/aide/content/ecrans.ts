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

/** One short help: its sentences are passed already translated, so each message keeps its own literal. */
function screenHelp(
  id: string,
  icon: string,
  route: string,
  queryParams: Record<string, string> | undefined,
  detail: string[],
  guideKey: GuideKey,
  text: { title: string; repond: string; geste: string },
): HelpSection {
  const links: HelpLink[] = [{ route, queryParams, label: text.title }];
  for (const fragment of detail) {
    links.push({
      route: '/aide',
      fragment,
      label: $localize`:@@aide.ecran.detail:Guide détaillé`,
    });
  }
  links.push(guides[guideKey]());
  return {
    id: SCREEN_PREFIX + id,
    icon,
    title: text.title,
    summary: text.repond,
    blocks: [{ kind: 'paragraph', text: text.geste }],
    links,
  };
}

export function buildScreenSections(): HelpSection[] {
  return [
    screenHelp('accueil', 'checklist', '/', undefined, ['prise-en-main'], 'fil', {
      title: $localize`:@@aide.ecran.accueil.title:État de l'édition`,
      repond: $localize`:@@aide.ecran.accueil.repond:Où en est l'édition dans son cycle, et ce qui demande votre attention aujourd'hui.`,
      geste: $localize`:@@aide.ecran.accueil.geste:Lisez la phase de l'édition, puis suivez la liste « À traiter aujourd'hui » : chaque ligne mène à l'écran qui règle le point.`,
    }),
    screenHelp('stands', 'storefront', '/stands', undefined, ['donnees'], 'preparer', {
      title: $localize`:@@aide.ecran.stands.title:Stands`,
      repond: $localize`:@@aide.ecran.stands.repond:Quels stands l'édition compte, combien de places chacun demande et quelles typologies il propose.`,
      geste: $localize`:@@aide.ecran.stands.geste:Ajoutez un stand avec son effectif minimal et maximal et ses typologies ; cliquez sur un stand pour ouvrir sa fiche et régler ses horaires.`,
    }),
    screenHelp('lieux', 'place', '/stands', { onglet: 'lieux' }, [], 'preparer', {
      title: $localize`:@@aide.ecran.lieux.title:Lieux`,
      repond: $localize`:@@aide.ecran.lieux.repond:Où se trouvent les emplacements des stands sur le site.`,
      geste: $localize`:@@aide.ecran.lieux.geste:Créez un lieu, nommez-le et placez-le sur la carte ; la vue Carte du Planning les montre.`,
    }),
    screenHelp('typologies', 'category', '/stands', { onglet: 'typologies' }, [], 'preparer', {
      title: $localize`:@@aide.ecran.typologies.title:Typologies`,
      repond: $localize`:@@aide.ecran.typologies.repond:Les catégories de jeu que les stands proposent et que les animateurs maîtrisent.`,
      geste: $localize`:@@aide.ecran.typologies.geste:Ajoutez une typologie, puis rattachez-la aux stands qui la proposent et aux animateurs compétents.`,
    }),
    screenHelp('calendrier', 'schedule', '/creneaux', undefined, ['calendrier'], 'calendrier', {
      title: $localize`:@@aide.ecran.calendrier.title:Calendrier`,
      repond: $localize`:@@aide.ecran.calendrier.repond:Les créneaux de l'édition : les vacations que le calcul remplira, jour par jour.`,
      geste: $localize`:@@aide.ecran.calendrier.geste:Générez les créneaux depuis un gabarit ou les horaires des stands, puis corrigez à la main ceux qui diffèrent.`,
    }),
    screenHelp('animateurs', 'groups', '/animateurs', undefined, ['donnees'], 'animateurs', {
      title: $localize`:@@aide.ecran.animateurs.title:Animateurs`,
      repond: $localize`:@@aide.ecran.animateurs.repond:Qui compose l'équipe, avec son âge, ses compétences et ses jours d'indisponibilité.`,
      geste: $localize`:@@aide.ecran.animateurs.geste:Ajoutez un animateur ou importez-les depuis un tableur ; cliquez sur un nom pour ouvrir sa fiche et y saisir ses indisponibilités.`,
    }),
    screenHelp(
      'competences',
      'verified',
      '/animateurs',
      { onglet: 'competences' },
      ['competences'],
      'animateurs',
      {
        title: $localize`:@@aide.ecran.competences.title:Compétences`,
        repond: $localize`:@@aide.ecran.competences.repond:Quel animateur sait tenir quelle typologie.`,
        geste: $localize`:@@aide.ecran.competences.geste:Cochez les cases de la grille, puis enregistrez : le calcul s'appuie sur ces compétences pour affecter chacun.`,
      },
    ),
    screenHelp(
      'disponibilites',
      'event_busy',
      '/animateurs',
      { onglet: 'disponibilites' },
      ['disponibilites'],
      'collecter',
      {
        title: $localize`:@@aide.ecran.disponibilites.title:Disponibilités`,
        repond: $localize`:@@aide.ecran.disponibilites.repond:Quels jours les animateurs ont déclarés indisponibles, et les déclarations qui attendent votre décision.`,
        geste: $localize`:@@aide.ecran.disponibilites.geste:Ouvrez la collecte, laissez chacun déclarer depuis son espace, puis appliquez les déclarations avant le calcul.`,
      },
    ),
    screenHelp(
      'covoiturage',
      'directions_car',
      '/animateurs',
      { onglet: 'covoiturage' },
      ['disponibilites'],
      'collecter',
      {
        title: $localize`:@@aide.ecran.covoiturage.title:Covoiturage`,
        repond: $localize`:@@aide.ecran.covoiturage.repond:Les demandes de covoiturage déposées par les animateurs.`,
        geste: $localize`:@@aide.ecran.covoiturage.geste:Marquez chaque demande comme validée ou non, selon que vous pouvez organiser le trajet.`,
      },
    ),
    screenHelp(
      'importer',
      'upload_file',
      '/fichiers',
      undefined,
      ['import-csv-animateurs'],
      'importer',
      {
        title: $localize`:@@aide.ecran.importer.title:Importer`,
        repond: $localize`:@@aide.ecran.importer.repond:Comment remplir l'édition depuis un fichier : un scénario complet, une liste d'animateurs, un exemple.`,
        geste: $localize`:@@aide.ecran.importer.geste:Choisissez la carte qui correspond à votre fichier, déposez-le, lisez l'aperçu, puis confirmez l'import.`,
      },
    ),
    screenHelp('exporter', 'download', '/fichiers', { onglet: 'exporter' }, [], 'importer', {
      title: $localize`:@@aide.ecran.exporter.title:Exporter`,
      repond: $localize`:@@aide.ecran.exporter.repond:Ce que l'édition peut livrer : ses données en CSV ou en scénario YAML.`,
      geste: $localize`:@@aide.ecran.exporter.geste:Choisissez l'export voulu et téléchargez-le, pour le corriger dans un tableur ou le garder.`,
    }),
    screenHelp('archive', 'inventory_2', '/fichiers', { onglet: 'archive' }, [], 'importer', {
      title: $localize`:@@aide.ecran.archive.title:Archive`,
      repond: $localize`:@@aide.ecran.archive.repond:L'archive de fin d'événement : toute l'édition dans un seul fichier ZIP.`,
      geste: $localize`:@@aide.ecran.archive.geste:Une fois l'événement terminé, téléchargez l'archive et conservez-la avec vos documents d'édition.`,
    }),
    screenHelp('planning', 'calendar_month', '/journee', undefined, ['consulter'], 'consulter', {
      title: $localize`:@@aide.ecran.planning.title:Planning`,
      repond: $localize`:@@aide.ecran.planning.repond:Qui est où : le plan calculé, jour par jour, par stand, par personne ou sur la carte.`,
      geste: $localize`:@@aide.ecran.planning.geste:Choisissez le jour et la vue ; cliquez sur un siège pour le relire ou le retoucher sans casser une règle.`,
    }),
    screenHelp(
      'calculer',
      'play_arrow',
      '/journee',
      { onglet: 'calculer' },
      ['calculer'],
      'calculer',
      {
        title: $localize`:@@aide.ecran.calculer.title:Calculer`,
        repond: $localize`:@@aide.ecran.calculer.repond:Le plan est-il calculable, et que prendra en compte le prochain calcul.`,
        geste: $localize`:@@aide.ecran.calculer.geste:Lisez la faisabilité, puis lancez « Calculer le planning » pour le calcul complet, ou « Corriger après un changement » pour bouger le moins possible.`,
      },
    ),
    screenHelp(
      'versions',
      'history',
      '/journee',
      { onglet: 'versions' },
      ['calculer'],
      'calculer',
      {
        title: $localize`:@@aide.ecran.versions.title:Versions du plan`,
        repond: $localize`:@@aide.ecran.versions.repond:Les plans calculés et enregistrés de l'édition, et ce qui les distingue.`,
        geste: $localize`:@@aide.ecran.versions.geste:Capturez une version avant un essai, comparez-en deux, restaurez celle que vous préférez.`,
      },
    ),
    screenHelp(
      'problemes',
      'report_problem',
      '/diagnostic',
      { onglet: 'problemes' },
      ['lire-les-resultats'],
      'comprendre',
      {
        title: $localize`:@@aide.ecran.problemes.title:Problèmes`,
        repond: $localize`:@@aide.ecran.problemes.repond:Pourquoi le planning coince : les places vides et les règles qui cèdent, avec les personnes concernées.`,
        geste: $localize`:@@aide.ecran.problemes.geste:Ouvrez un problème et suivez « Que faire ? » : le geste proposé règle d'abord la cause la plus utile.`,
      },
    ),
    screenHelp('besoin', 'calculate', '/diagnostic', { onglet: 'besoin' }, [], 'dimensionner', {
      title: $localize`:@@aide.ecran.besoin.title:Besoin en animateurs`,
      repond: $localize`:@@aide.ecran.besoin.repond:Combien de personnes vos stands exigent au minimum, et quelles compétences former.`,
      geste: $localize`:@@aide.ecran.besoin.geste:Lisez la fiche de recrutement, puis vérifiez le minimum par un vrai calcul depuis l'onglet.`,
    }),
    screenHelp('risques', 'warning', '/diagnostic', { onglet: 'risques' }, [], 'comprendre', {
      title: $localize`:@@aide.ecran.risques.title:Risques`,
      repond: $localize`:@@aide.ecran.risques.repond:Où le plan est fragile : les points sans remplaçant possible, et la tension heure par heure.`,
      geste: $localize`:@@aide.ecran.risques.geste:Parcourez la fragilité pour repérer les points de défaillance, puis la tension ; formez ou ajoutez quelqu'un là où cela casse.`,
    }),
    screenHelp('charge', 'scale', '/diagnostic', { onglet: 'charge' }, [], 'comprendre', {
      title: $localize`:@@aide.ecran.charge.title:Charge`,
      repond: $localize`:@@aide.ecran.charge.repond:Ce que chacun travaille : heures, soirées, week-ends et jours fériés, et l'équilibre entre les personnes.`,
      geste: $localize`:@@aide.ecran.charge.geste:Triez par heures ou par équité pour repérer les écarts, et exportez en CSV pour la paie.`,
    }),
    screenHelp(
      'repas-pauses',
      'restaurant',
      '/diagnostic',
      { onglet: 'repas-pauses' },
      [],
      'comprendre',
      {
        title: $localize`:@@aide.ecran.repas-pauses.title:Repas et pauses`,
        repond: $localize`:@@aide.ecran.repas-pauses.repond:Si chacun a ses pauses et ses repas : coupures, relais et enchaînements.`,
        geste: $localize`:@@aide.ecran.repas-pauses.geste:Repérez les personnes sans pause ou sans repas, puis retouchez leur journée depuis le Planning.`,
      },
    ),
    screenHelp('conformite', 'gavel', '/diagnostic', { onglet: 'conformite' }, [], 'comprendre', {
      title: $localize`:@@aide.ecran.conformite.title:Conformité`,
      repond: $localize`:@@aide.ecran.conformite.repond:Le rapport de conformité du plan aux plafonds légaux et à la protection des mineurs.`,
      geste: $localize`:@@aide.ecran.conformite.geste:Lisez le rapport avant de publier, et remettez-le à l'inspection du travail ou aux ressources humaines si on vous le demande.`,
    }),
    screenHelp(
      'ajustements',
      'link',
      '/regles',
      { onglet: 'ajustements' },
      ['ajustements-manuels'],
      'ajustements',
      {
        title: $localize`:@@aide.ecran.ajustements.title:Ajustements`,
        repond: $localize`:@@aide.ecran.ajustements.repond:Les exceptions que vous imposez au calcul : affinités, incompatibilités, arrivées groupées.`,
        geste: $localize`:@@aide.ecran.ajustements.geste:Ajoutez une exception entre deux personnes, ou une arrivée groupée, puis relancez le calcul.`,
      },
    ),
    screenHelp(
      'verrouillages',
      'lock',
      '/regles',
      { onglet: 'verrouillages' },
      ['sans-affectation-forcee'],
      'ajustements',
      {
        title: $localize`:@@aide.ecran.verrouillages.title:Verrouillages`,
        repond: $localize`:@@aide.ecran.verrouillages.repond:Ce que le prochain calcul n'a pas le droit de bouger : un animateur sur un créneau, un stand ou une journée.`,
        geste: $localize`:@@aide.ecran.verrouillages.geste:Choisissez le type de verrou, désignez la personne, le stand ou la journée, puis « Verrouiller » ; « Déverrouiller » le lève.`,
      },
    ),
    screenHelp(
      'consignes',
      'policy',
      '/regles',
      { onglet: 'consignes' },
      ['consignes'],
      'consigne',
      {
        title: $localize`:@@aide.ecran.consignes.title:Consignes`,
        repond: $localize`:@@aide.ecran.consignes.repond:Ce qu'une autorité impose à tout l'événement un jour donné, comme la fermeture de stands à certaines heures.`,
        geste: $localize`:@@aide.ecran.consignes.geste:Proposez la consigne, prévisualisez ses effets, posez-la ; levez-la de la même façon.`,
      },
    ),
    screenHelp(
      'regles',
      'rule',
      '/regles',
      { onglet: 'regles' },
      ['parametrer-pour-un-planning-complet', 'configuration-solveur'],
      'regles',
      {
        title: $localize`:@@aide.ecran.regles.title:Règles`,
        repond: $localize`:@@aide.ecran.regles.repond:Ce qui décide du plan : les seuils légaux et l'importance de chaque règle de qualité.`,
        geste: $localize`:@@aide.ecran.regles.geste:Changez un seuil ou l'importance d'une règle, puis enregistrez ; une règle bloquée par une donnée absente le dit.`,
      },
    ),
    screenHelp('envoyer', 'send', '/publication', { onglet: 'envoyer' }, ['relecture'], 'publier', {
      title: $localize`:@@aide.ecran.envoyer.title:Envoyer`,
      repond: $localize`:@@aide.ecran.envoyer.repond:Qui a reçu quelle version du planning, et qui l'a confirmée.`,
      geste: $localize`:@@aide.ecran.envoyer.geste:Publiez, envoyez à tous ou à quelques personnes seulement, puis relancez ceux qui n'ont pas confirmé.`,
    }),
    screenHelp(
      'documents',
      'picture_as_pdf',
      '/publication',
      { onglet: 'documents' },
      [],
      'publier',
      {
        title: $localize`:@@aide.ecran.documents.title:Documents`,
        repond: $localize`:@@aide.ecran.documents.repond:Les documents à remettre : le planning de chaque animateur et le planning global, en PDF.`,
        geste: $localize`:@@aide.ecran.documents.geste:Choisissez le document voulu, puis téléchargez-le ou envoyez-le.`,
      },
    ),
    screenHelp(
      'echanges',
      'swap_horiz',
      '/echanges',
      undefined,
      ['foire-au-planning'],
      'echanger',
      {
        title: $localize`:@@aide.ecran.echanges.title:Échanges`,
        repond: $localize`:@@aide.ecran.echanges.repond:Les échanges de créneaux que les animateurs se proposent entre eux.`,
        geste: $localize`:@@aide.ecran.echanges.geste:Ouvrez la foire au planning : les animateurs proposent leurs échanges, et vous arbitrez ceux qui demandent votre accord.`,
      },
    ),
    screenHelp(
      'echanges-stats',
      'query_stats',
      '/echanges',
      { onglet: 'stats' },
      ['foire-au-planning'],
      'echanger',
      {
        title: $localize`:@@aide.ecran.echanges-stats.title:Statistiques des échanges`,
        repond: $localize`:@@aide.ecran.echanges-stats.repond:Comment la foire fonctionne : taux d'acceptation, délais et volumes.`,
        geste: $localize`:@@aide.ecran.echanges-stats.geste:Lisez les chiffres pour décider d'ouvrir, de fermer ou de borner la foire par des dates.`,
      },
    ),
    screenHelp('aujourdhui', 'today', '/aujourdhui', undefined, ['jour-j'], 'jourj', {
      title: $localize`:@@aide.ecran.aujourdhui.title:Aujourd'hui`,
      repond: $localize`:@@aide.ecran.aujourdhui.repond:Ce qui se passe le jour J : les absents, les places vides, la journée vue sur un téléphone.`,
      geste: $localize`:@@aide.ecran.aujourdhui.geste:Déclarez un absent, puis proposez-lui un remplaçant sur le créneau resté vide.`,
    }),
    screenHelp(
      'edition',
      'tune',
      '/parametres',
      { onglet: 'edition' },
      ['alerte-meteo'],
      'outils',
      {
        title: $localize`:@@aide.ecran.edition.title:Paramètres de l'édition`,
        repond: $localize`:@@aide.ecran.edition.repond:Les réglages propres à l'édition : guichets, collecte des disponibilités, foire, covoiturage, météo, contact.`,
        geste: $localize`:@@aide.ecran.edition.geste:Réglez chaque bloc puis enregistrez : ces réglages ne valent que pour l'édition choisie.`,
      },
    ),
    screenHelp(
      'notifications',
      'notifications',
      '/parametres',
      { onglet: 'notifications' },
      ['rappels'],
      'outils',
      {
        title: $localize`:@@aide.ecran.notifications.title:Notifications`,
        repond: $localize`:@@aide.ecran.notifications.repond:Les e-mails automatiques : rappel de la veille, relance des non-confirmés, alerte sur les échanges en attente.`,
        geste: $localize`:@@aide.ecran.notifications.geste:Activez ou coupez chaque envoi, puis enregistrez.`,
      },
    ),
    screenHelp('legal', 'balance', '/parametres', { onglet: 'legal' }, [], 'regles', {
      title: $localize`:@@aide.ecran.legal.title:Paramètres légaux`,
      repond: $localize`:@@aide.ecran.legal.repond:Les plafonds légaux que le calcul respecte : durée hebdomadaire, pauses, repas.`,
      geste: $localize`:@@aide.ecran.legal.geste:Ajustez un seuil si votre convention collective l'exige, puis enregistrez.`,
    }),
    screenHelp('organisation', 'tune', '/parametres', { onglet: 'organisation' }, [], 'regles', {
      title: $localize`:@@aide.ecran.organisation.title:Organisation`,
      repond: $localize`:@@aide.ecran.organisation.repond:Les seuils de confort et d'organisation : jours consécutifs, trajets, pauses.`,
      geste: $localize`:@@aide.ecran.organisation.geste:Réglez un seuil et le niveau de chaque règle d'organisation, puis enregistrez.`,
    }),
    screenHelp(
      'calcul',
      'timer',
      '/parametres',
      { onglet: 'calcul' },
      ['configuration-solveur', 'tuner'],
      'regles',
      {
        title: $localize`:@@aide.ecran.calcul.title:Budget de calcul`,
        repond: $localize`:@@aide.ecran.calcul.repond:Le temps que le solveur a le droit de chercher, et sa polyvalence.`,
        geste: $localize`:@@aide.ecran.calcul.geste:Fixez la durée du calcul puis enregistrez : un budget plus long cherche plus loin.`,
      },
    ),
    screenHelp('mural', 'tv', '/parametres', { onglet: 'mural' }, ['affichage-mural'], 'publier', {
      title: $localize`:@@aide.ecran.mural.title:Affichage mural`,
      repond: $localize`:@@aide.ecran.mural.repond:La journée en cours sur le téléviseur de la salle de contrôle.`,
      geste: $localize`:@@aide.ecran.mural.geste:Créez un lien, copiez-le sur le téléviseur (il ne s'affiche qu'une fois) et révoquez-le d'un clic s'il a fuité.`,
    }),
    screenHelp('comptes', 'manage_accounts', '/parametres', { onglet: 'comptes' }, [], 'comptes', {
      title: $localize`:@@aide.ecran.comptes.title:Comptes`,
      repond: $localize`:@@aide.ecran.comptes.repond:Qui a accès à l'application et avec quels droits.`,
      geste: $localize`:@@aide.ecran.comptes.geste:Créez un compte, donnez-lui un droit, puis précisez les éditions ou les stands qu'il peut voir.`,
    }),
    screenHelp('instance', 'dns', '/parametres', { onglet: 'instance' }, ['webhooks'], 'outils', {
      title: $localize`:@@aide.ecran.instance.title:Instance`,
      repond: $localize`:@@aide.ecran.instance.repond:Les réglages communs à toutes les éditions : webhooks sortants, sauvegarde, date simulée.`,
      geste: $localize`:@@aide.ecran.instance.geste:Ajoutez un webhook, suspendez la sauvegarde nocturne ou posez une date simulée pour une répétition.`,
    }),
    screenHelp(
      'historique',
      'manage_search',
      '/parametres',
      { onglet: 'historique' },
      ['historique-des-actions'],
      'comptes',
      {
        title: $localize`:@@aide.ecran.historique.title:Historique des actions`,
        repond: $localize`:@@aide.ecran.historique.repond:Qui a fait quoi dans l'application, action par action.`,
        geste: $localize`:@@aide.ecran.historique.geste:Filtrez le journal pour retrouver un changement : il dit ce qui a changé, jamais les valeurs saisies.`,
      },
    ),
    screenHelp(
      'connexions',
      'login',
      '/parametres',
      { onglet: 'connexions' },
      ['historique-des-actions'],
      'comptes',
      {
        title: $localize`:@@aide.ecran.connexions.title:Connexions`,
        repond: $localize`:@@aide.ecran.connexions.repond:Les connexions des comptes : réussites, échecs et verrouillages.`,
        geste: $localize`:@@aide.ecran.connexions.geste:Cherchez une adresse ou une date pour repérer des échecs répétés ; rien de ce qui a été saisi n'est conservé.`,
      },
    ),
    screenHelp(
      'editions',
      'event',
      '/editions',
      undefined,
      ['editions', 'phase-de-l-edition'],
      'editions',
      {
        title: $localize`:@@aide.ecran.editions.title:Éditions`,
        repond: $localize`:@@aide.ecran.editions.repond:Les éditions de l'instance et la phase de chacune.`,
        geste: $localize`:@@aide.ecran.editions.geste:Créez ou dupliquez une édition, puis activez celle qui commence : seule l'édition active publie et envoie des e-mails.`,
      },
    ),
    screenHelp('mesures', 'insights', '/mesures', undefined, ['realise'], 'jourj', {
      title: $localize`:@@aide.ecran.mesures.title:Mesures d'édition`,
      repond: $localize`:@@aide.ecran.mesures.repond:Ce que l'édition a donné : l'écart entre le planifié et le réalisé, et les indicateurs de chaque calcul.`,
      geste: $localize`:@@aide.ecran.mesures.geste:Lisez le réalisé jour par jour, puis passez à la vue Indicateurs pour comparer les calculs.`,
    }),
    screenHelp('debug', 'bug_report', '/debug', undefined, ['echanges'], 'outils', {
      title: $localize`:@@aide.ecran.debug.title:Débogage`,
      repond: $localize`:@@aide.ecran.debug.repond:L'état technique de l'instance : version, résolution, vérifications. Réservé aux administrateurs.`,
      geste: $localize`:@@aide.ecran.debug.geste:Ouvrez Résolution pour la version et l'analyse brute, Vérifications pour les contrôles techniques.`,
    }),
    screenHelp('comparer', 'compare', '/comparer', undefined, [], 'calculer', {
      title: $localize`:@@aide.ecran.comparer.title:Comparer deux versions`,
      repond: $localize`:@@aide.ecran.comparer.repond:Les différences entre deux versions du plan, ou entre les données de deux éditions.`,
      geste: $localize`:@@aide.ecran.comparer.geste:Choisissez les deux versions (ou les deux éditions) : l'écart de score et de couverture s'affiche.`,
    }),
  ];
}
