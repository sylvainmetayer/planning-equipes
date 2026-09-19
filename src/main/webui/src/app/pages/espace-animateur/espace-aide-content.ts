/**
 * Content of the espace animateur's help page: what an animateur can do here,
 * written in the business vocabulary the screens already use (créneau, stand,
 * coéquipiers, foire au planning, organisation).
 *
 * Kept as data rather than as template markup — the page renders it
 * generically as an accordion, and a unit test can assert on it without
 * rendering anything. Deliberately separate from `pages/aide/aide-content.ts`:
 * that guide addresses the organisers and speaks of solving, scores and
 * découpage, none of which an animateur ever sees. Only the blocks and their
 * renderer are shared (`shared/help-blocks.ts`).
 */

import { HelpBlock } from '../../shared/help-blocks';

/** Tab of the espace a section sends the reader to, when there is one. */
export type EspaceAideCible = 'planning' | 'echanges' | 'disponibilites';

export interface EspaceAideSection {
  /** Anchor id, also used as the `track` key. */
  id: string;
  icon: string;
  /** The question as an animateur would ask it — this is the panel header. */
  question: string;
  /** One-line answer, readable while the panel is still collapsed. */
  resume: string;
  blocks: HelpBlock[];
  /** Where to act on what the section describes. */
  cible?: EspaceAideCible;
}

/**
 * Built lazily (never at module scope): `$localize` only resolves once
 * `main.ts` has loaded the translation catalog, which happens after this
 * module is imported. Same reasoning as `buildHelpSections()` next door.
 */
export function buildEspaceAideSections(): EspaceAideSection[] {
  return [
    {
      id: 'mon-espace',
      icon: 'home',
      question: $localize`:@@espace.aide.espace.question:À quoi sert cet espace ?`,
      resume: $localize`:@@espace.aide.espace.resume:À consulter votre planning, toujours à jour, et à demander un échange de créneau.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.espace.intro:L'organisation répartit les animateurs sur les stands, créneau par créneau. Cet espace est votre vue personnelle : vos créneaux à vous, et rien d'autre.`,
        },
        {
          kind: 'list',
          items: [
            $localize`:@@espace.aide.espace.item1:Voir vos créneaux jour par jour, avec le stand et vos coéquipiers.`,
            $localize`:@@espace.aide.espace.item2:Emporter votre planning en PDF ou dans l'agenda de votre téléphone.`,
            $localize`:@@espace.aide.espace.item3:Demander un échange de créneau, tant que la foire au planning est ouverte.`,
            $localize`:@@espace.aide.espace.item4:Répondre aux demandes d'échange qu'un collègue vous adresse.`,
            $localize`:@@espace.aide.espace.item5:Déclarer vos jours d'indisponibilité et vos souhaits, quand la collecte est ouverte.`,
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.espace.limite:Vous ne modifiez jamais le planning vous-même : tout passe par une demande que l'organisation valide.`,
        },
      ],
    },
    {
      id: 'acces',
      icon: 'key',
      question: $localize`:@@espace.aide.acces.question:Comment j'accède à mon espace ?`,
      resume: $localize`:@@espace.aide.acces.resume:Par votre lien personnel, puis par un code reçu par e-mail.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.acces.intro:Le lien que l'organisation vous a envoyé est personnel : il ouvre votre planning et lui seul. La première fois que vous l'ouvrez sur un appareil, un code à 6 chiffres est envoyé à l'adresse e-mail que l'organisation connaît. Il est valable 10 minutes, et vous avez 5 essais.`,
        },
        {
          kind: 'list',
          items: [
            $localize`:@@espace.aide.acces.item1:Un code par appareil. Une fois saisi, cet appareil reste connecté un mois environ, puis un nouveau code vous est demandé.`,
            $localize`:@@espace.aide.acces.item2:Code non reçu ? Regardez dans les indésirables, puis demandez-en un nouveau.`,
            $localize`:@@espace.aide.acces.item3:Ne transférez pas votre lien : la personne qui l'ouvre voit votre planning.`,
            $localize`:@@espace.aide.acces.item4:« Ce lien n'est plus valide », ou pas d'adresse e-mail sur votre fiche : contactez l'organisation.`,
          ],
        },
      ],
    },
    {
      id: 'lire-planning',
      icon: 'calendar_month',
      question: $localize`:@@espace.aide.planning.question:Comment lire mon planning ?`,
      resume: $localize`:@@espace.aide.planning.resume:Une carte par journée ; sur chaque ligne, l'horaire, le stand et vos coéquipiers.`,
      cible: 'planning',
      blocks: [
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@espace.aide.planning.term.horaire:L'horaire`,
              text: $localize`:@@espace.aide.planning.def.horaire:Le début et la fin de votre créneau. Vous êtes attendu à l'heure de début, pas à l'heure d'ouverture du stand.`,
            },
            {
              term: $localize`:@@espace.aide.planning.term.stand:Le stand`,
              text: $localize`:@@espace.aide.planning.def.stand:Le jeu ou le poste que vous tenez. Il peut changer d'un créneau à l'autre dans la même journée.`,
            },
            {
              term: $localize`:@@espace.aide.planning.term.lieu:Le lieu`,
              text: $localize`:@@espace.aide.planning.def.lieu:L'endroit où le stand est installé, quand il en a un — « Hall B », « Château ». S'il est placé sur une carte, le nom est un lien qui l'ouvre.`,
            },
            {
              term: $localize`:@@espace.aide.planning.term.coequipiers:Les coéquipiers`,
              text: $localize`:@@espace.aide.planning.def.coequipiers:Les animateurs sur le même stand, au même créneau que vous.`,
            },
            {
              term: $localize`:@@espace.aide.planning.term.pause:La pause`,
              text: $localize`:@@espace.aide.planning.def.pause:Dès que votre journée atteint six heures d'affilée, vous avez droit à vingt minutes de pause. Votre planning vous dit de quelle heure à quelle heure la prendre. Les pauses d'un même stand se suivent, une personne à la fois : sortez à l'heure dite, ou demandez le relais à l'organisation si vous êtes seul.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.planning.repos:Les journées où vous n'êtes affecté nulle part apparaissent quand même, marquées « Repos ». C'est voulu : rien n'a été oublié.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.planning.maj:« Planning communiqué le… » indique de quand date la version que vous lisez. Elle ne change que lorsque l'organisation publie à nouveau, et vous êtes alors prévenu par e-mail. Repassez tout de même ici avant de partir : c'est cette page qui fait foi.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.planning.changements:Après une republication qui vous concerne, le bandeau « Ce qui a changé pour vous » reprend, au-dessus de vos journées, ce que disait le message reçu — pratique si vous l'avez manqué. Il est replié, avec le nombre de changements dans son titre ; touchez-le pour lire. Il s'efface une fois votre présence confirmée.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.planning.maintenant:Pendant l'événement, la page s'ouvre sur la journée du jour : en tête, votre poste en cours ou le prochain, et la pause qu'il vous reste à prendre. Les journées passées sont repliées derrière « Voir les journées passées ». Un poste qui passe minuit reste affiché comme en cours jusqu'à son heure de fin.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.planning.vide:Si rien ne s'affiche, le planning n'a pas encore été publié. Rien à faire de votre côté.`,
        },
      ],
    },
    {
      id: 'confirmer',
      icon: 'check_circle',
      question: $localize`:@@espace.aide.confirmer.question:Que veut dire « J'ai lu et je serai là » ?`,
      resume: $localize`:@@espace.aide.confirmer.resume:Un clic qui dit à l'organisation que vous avez vu votre planning.`,
      cible: 'planning',
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.confirmer.pourquoi:Le bouton apparaît dès que votre planning est publié. Un clic suffit : l'organisation voit que vous êtes au courant et n'a pas à vous relancer. Sans réponse au bout de quelques jours, un rappel automatique part — une seule fois.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.confirmer.deuxFois:Cliquer deux fois ne change rien : c'est la première date qui compte, et le bouton laisse ensuite la place à « Présence confirmée le… ».`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.confirmer.republication:Si l'organisation publie une nouvelle version et que vos journées changent, la question vous est reposée. Si rien n'a bougé pour vous, votre confirmation reste acquise.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.confirmer.probleme:Confirmer ne vous engage pas définitivement, et ce n'est pas le bon endroit pour signaler un souci. Si un créneau ne va pas, passez par l'onglet Échanges ou contactez l'organisation.`,
        },
      ],
    },
    {
      id: 'emporter',
      icon: 'download',
      question: $localize`:@@espace.aide.emporter.question:Puis-je emporter mon planning ?`,
      resume: $localize`:@@espace.aide.emporter.resume:Oui — le mieux est de vous y abonner, pour qu'il se tienne à jour tout seul.`,
      cible: 'planning',
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.emporter.abonnement:En haut de « Mon planning », la bande « Emporter mon planning » propose quatre sorties.`,
        },
        {
          kind: 'list',
          items: [
            $localize`:@@espace.aide.emporter.item0:« S'abonner dans mon agenda » — à privilégier : donnée une fois à votre agenda, l'adresse le tient à jour à chaque nouvelle publication. Rien à refaire ensuite.`,
            $localize`:@@espace.aide.emporter.item1:« Livret PDF » : une vue d'ensemble de vos journées, leur détail, puis vos coéquipiers et vos lieux.`,
            $localize`:@@espace.aide.emporter.itemFeuille:« Feuille A4 » : le même planning sur une seule feuille, calendrier au recto, équipes et lieux au verso — celle qui tient dans une poche.`,
            $localize`:@@espace.aide.emporter.item2:« Fichier ICS » : chaque créneau devient un rendez-vous, versé une seule fois dans votre agenda.`,
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.emporter.qr:Les deux PDF portent un QR code : scannez-le depuis le papier pour revenir ici, au lieu de recopier l'adresse.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.emporter.photo:Les PDF et l'ICS sont une photo prise au téléchargement : ils ne se mettent pas à jour tout seuls quand le planning change ou qu'un échange est accepté. Au moindre doute, fiez-vous à cette page.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.emporter.abonnementColler:Certaines applications, Google Agenda en premier, demandent qu'on leur colle l'adresse au lieu de cliquer. Elle est alors sous « Copier l'adresse, ou la remplacer », dans la même bande.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.emporter.abonnementPrudence:Cette adresse est personnelle : qui l'obtient voit votre planning, sans code ni mot de passe. Ne la mettez pas sur un agenda partagé. Si elle vous échappe, « Cette adresse a fuité, la remplacer » en crée une neuve et coupe l'ancienne aussitôt.`,
        },
      ],
    },
    {
      id: 'demander-echange',
      icon: 'swap_horiz',
      question: $localize`:@@espace.aide.echange.question:Comment demander un échange ?`,
      resume: $localize`:@@espace.aide.echange.resume:Depuis « Mes échanges » : montez votre liste de demandes, puis soumettez-la.`,
      cible: 'echanges',
      blocks: [
        {
          kind: 'steps',
          items: [
            $localize`:@@espace.aide.echange.step1:Choisissez le créneau dont vous voulez vous libérer.`,
            $localize`:@@espace.aide.echange.step2:Choisissez le ou la collègue avec qui échanger.`,
            $localize`:@@espace.aide.echange.step3:Pour reprendre un de ses créneaux en contrepartie, désignez-le. Sans ce choix, l'échange ne porte que sur le vôtre.`,
            $localize`:@@espace.aide.echange.step4:Indiquez un motif : c'est ce que lira l'organisation pour trancher.`,
            $localize`:@@espace.aide.echange.step5:« Ajouter à la liste », puis recommencez si plusieurs créneaux sont concernés.`,
            $localize`:@@espace.aide.echange.step6:« Soumettre mes demandes » : rien ne part avant ce bouton.`,
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.echange.brouillon:Tant que vous n'avez pas soumis, la liste n'est qu'un brouillon : la croix retire une demande, et personne n'a encore rien reçu.`,
        },
      ],
    },
    {
      id: 'remplacants',
      icon: 'person_search',
      question: $localize`:@@espace.aide.remplacants.question:Je n'ai personne en tête, que faire ?`,
      resume: $localize`:@@espace.aide.remplacants.resume:« Qui peut me remplacer ? » cherche pour vous les échanges qui tiennent.`,
      cible: 'echanges',
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.remplacants.intro:Une fois votre créneau choisi, ce bouton passe les collègues en revue et ne retient que les échanges compatibles avec le reste du planning et avec les règles de repos. Les propositions arrivent en trois familles :`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@espace.aide.remplacants.term.libere:On vous libère de ce créneau`,
              text: $localize`:@@espace.aide.remplacants.def.libere:Un collègue le reprend, et vous n'êtes plus de service à ce moment-là.`,
            },
            {
              term: $localize`:@@espace.aide.remplacants.term.dirige:Vous échangez contre un autre créneau`,
              text: $localize`:@@espace.aide.remplacants.def.dirige:Vous lui laissez le vôtre et vous reprenez l'un des siens, à un autre moment.`,
            },
            {
              term: $localize`:@@espace.aide.remplacants.term.croise:Vous échangez sur ce même créneau`,
              text: $localize`:@@espace.aide.remplacants.def.croise:Vous restez de service au même horaire, mais sur un autre stand.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.remplacants.limite:La liste montre les pistes les plus prometteuses, pas toutes. Si rien ne ressort, vous pouvez quand même proposer l'échange à quelqu'un : l'organisation tranchera.`,
        },
      ],
    },
    {
      id: 'suivi',
      icon: 'hourglass_top',
      question: $localize`:@@espace.aide.suivi.question:Que devient ma demande ?`,
      resume: $localize`:@@espace.aide.suivi.resume:Elle passe par l'accord du collègue, puis par la décision de l'organisation.`,
      cible: 'echanges',
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.suivi.intro:Chaque demande porte une pastille qui dit où elle en est :`,
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@echanges.statut.attenteCible:En attente du collègue`,
              text: $localize`:@@espace.aide.suivi.def.attenteCible:Votre collègue doit d'abord donner son accord.`,
            },
            {
              term: $localize`:@@echanges.statut.proposee:En attente de l'organisation`,
              text: $localize`:@@espace.aide.suivi.def.proposee:L'accord est donné ; l'organisation doit encore trancher.`,
            },
            {
              term: $localize`:@@echanges.statut.acceptee:Acceptée`,
              text: $localize`:@@espace.aide.suivi.def.acceptee:L'échange est validé. Il apparaît dans votre planning à la publication suivante : tant que c'est l'ancien créneau qui s'affiche, elle n'a pas encore eu lieu — la demande le dit sous son statut.`,
            },
            {
              term: $localize`:@@echanges.statut.refusee:Refusée`,
              text: $localize`:@@espace.aide.suivi.def.refusee:L'organisation n'a pas retenu l'échange ; votre créneau reste le vôtre.`,
            },
            {
              term: $localize`:@@echanges.statut.refuseeCible:Déclinée par le collègue`,
              text: $localize`:@@espace.aide.suivi.def.refuseeCible:Le collègue n'a pas souhaité l'échange ; la demande est close.`,
            },
            {
              term: $localize`:@@echanges.statut.annulee:Annulée`,
              text: $localize`:@@espace.aide.suivi.def.annulee:Vous avez retiré la demande vous-même.`,
            },
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.suivi.alerte:Un encadré « cet échange poserait un problème » signale que l'échange se heurte à une règle : temps de repos trop court, horaire déjà occupé, stand qui vous attend au même moment. C'est un avertissement, pas un refus : la demande est transmise quand même.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.suivi.annulation:Tant qu'une demande n'est pas tranchée, « Annuler cette demande » la retire. Si l'organisation a joint un commentaire à sa décision, il s'affiche juste en dessous.`,
        },
      ],
    },
    {
      id: 'demandes-recues',
      icon: 'thumb_up',
      question: $localize`:@@espace.aide.recues.question:Un collègue me demande un échange`,
      resume: $localize`:@@espace.aide.recues.resume:Il attend votre accord : rien ne bouge tant que vous n'avez pas répondu.`,
      cible: 'echanges',
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.recues.intro:Les demandes qui vous sont adressées s'affichent en haut de « Mes échanges », sous « Demandes reçues ». Lisez le créneau qu'on vous propose de reprendre et, s'il y en a un, celui qu'on vous prend en contrepartie.`,
        },
        {
          kind: 'list',
          items: [
            $localize`:@@espace.aide.recues.item1:« Je suis d'accord » transmet la demande à l'organisation, qui tranche : votre accord seul ne suffit pas.`,
            $localize`:@@espace.aide.recues.item2:« Décliner » clôt la demande, et votre collègue en est informé.`,
          ],
        },
      ],
    },
    {
      id: 'declarer-disponibilites',
      icon: 'event_available',
      question: $localize`:@@espace.aide.dispo.question:Comment je dis quand je ne peux pas venir ?`,
      resume: $localize`:@@espace.aide.dispo.resume:Dans « Mes disponibilités », tant que l'organisation a ouvert la collecte.`,
      cible: 'disponibilites',
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.dispo.intro:Avant de construire le planning, l'organisation ouvre une période pendant laquelle chacun dit les jours où il ne peut pas venir, et les types de jeux qu'il aimerait animer. Les jours de l'événement s'affichent en pastilles : touchez celles qui ne vont pas.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.dispo.avant:Si la collecte n'a pas encore commencé, l'onglet annonce la date à partir de laquelle revenir. Ce n'est pas un refus : il n'y a rien à faire d'ici là.`,
        },
        {
          kind: 'steps',
          items: [
            $localize`:@@espace.aide.dispo.etape1:Touchez chaque jour où vous ne pouvez pas venir. Les jours que vous ne touchez pas veulent dire « je suis disponible ».`,
            $localize`:@@espace.aide.dispo.etape2:Choisissez les types de jeux qui vous plairaient. Un souhait n'est pas une garantie : il est suivi quand le planning le permet.`,
            $localize`:@@espace.aide.dispo.etape3:Ajoutez un mot si votre situation ne tient pas dans des cases (« je pars dimanche après le déjeuner »).`,
            $localize`:@@espace.aide.dispo.etape4:Envoyez. Le formulaire s'ouvre déjà sur ce que l'organisation sait de vous : vous corrigez, vous ne repartez pas de zéro.`,
          ],
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.dispo.attente:Une fois envoyée, votre déclaration s'affiche en haut de l'onglet sous « Votre déclaration en attente », avec sa date. Tant qu'elle porte cette mention, l'organisation ne l'a pas traitée, et un nouvel envoi remplace celui-là.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.dispo.historique:Plus bas, « Mes déclarations précédentes » garde la trace de ce que vous avez envoyé et de ce qu'il en est advenu : « Prise en compte », « Non retenue » ou « En attente de l'organisation », avec le mot joint à la décision s'il y en a un. « Non retenue » ne veut pas dire perdue : corrigez et renvoyez tant que la collecte est ouverte.`,
        },
        {
          kind: 'list',
          items: [
            $localize`:@@espace.aide.dispo.item1:Ce que vous envoyez est une proposition : rien n'est enregistré sur votre fiche avant que l'organisation ne l'applique.`,
            $localize`:@@espace.aide.dispo.item2:Vous pouvez renvoyer une version corrigée tant que la collecte est ouverte : elle remplace la précédente.`,
            $localize`:@@espace.aide.dispo.item3:Vos compétences ne se déclarent pas ici : elles restent décidées avec l'organisation.`,
            $localize`:@@espace.aide.dispo.item4:Collecte fermée, l'onglet reste consultable mais n'accepte plus d'envoi. Prévenez alors directement l'organisation.`,
          ],
        },
      ],
    },
    {
      id: 'foire-fermee',
      icon: 'lock',
      question: $localize`:@@espace.aide.foire.question:Pourquoi je ne peux pas proposer d'échange ?`,
      resume: $localize`:@@espace.aide.foire.resume:La foire au planning n'est pas ouverte — pas encore, ou plus.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.foire.intro:La foire au planning est la période pendant laquelle l'organisation accepte les échanges. Une fois fermée — le planning est figé, ou l'événement a commencé — vous gardez l'accès à votre planning et à vos demandes passées, mais vous ne pouvez plus en proposer, en accorder ni en annuler.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.foire.avant:Deux situations se ressemblent à l'écran. Si la foire n'est pas encore ouverte, l'onglet annonce la date à partir de laquelle revenir : rien à faire d'ici là. Si elle est fermée, c'est par l'organisation que passe toute demande.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.foire.suite:Un empêchement après la fermeture ? Prévenez directement l'organisation : elle peut encore agir, pas cet espace.`,
        },
      ],
    },
    {
      id: 'veille',
      icon: 'notifications_active',
      question: $localize`:@@espace.aide.veille.question:Vais-je recevoir un rappel avant l'événement ?`,
      resume: $localize`:@@espace.aide.veille.resume:La veille au soir, un e-mail liste vos postes du lendemain.`,
      cible: 'planning',
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.veille.contenu:Si l'organisation a activé les rappels, vous recevez la veille au soir la liste de vos créneaux du lendemain : horaire et stand, rien de plus. C'est ce que cette page affiche déjà — un rappel ne vous annonce jamais un changement.`,
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.veille.absence:Pas de rappel ? Trois raisons possibles : votre fiche n'a pas d'adresse e-mail, l'organisation n'a pas activé les rappels, ou vous n'êtes affecté nulle part ce jour-là. Dans tous les cas, cette page reste la référence.`,
        },
      ],
    },
    {
      id: 'contact',
      icon: 'support_agent',
      question: $localize`:@@espace.aide.contact.question:Une erreur, une question ?`,
      resume: $localize`:@@espace.aide.contact.resume:L'organisation reste votre interlocuteur ; cet espace ne remplace pas un appel.`,
      blocks: [
        {
          kind: 'list',
          items: [
            $localize`:@@espace.aide.contact.item1:Une affectation vous semble fausse, ou une indisponibilité annoncée n'a pas été prise en compte : signalez-le. Hors période de collecte, vous ne pouvez pas corriger vos données vous-même.`,
            $localize`:@@espace.aide.contact.item2:Empêchement de dernière minute : prévenez tout de suite, sans attendre qu'une demande d'échange soit validée.`,
            $localize`:@@espace.aide.contact.item3:Ce que l'application sait de vous et ce qu'elle en fait est décrit dans la politique de confidentialité, dans le menu en haut à droite.`,
          ],
        },
      ],
    },
  ];
}
