/**
 * Content of the espace animateur's help page: what an animateur can do here,
 * written in the business vocabulary the screens already use (créneau, stand,
 * coéquipiers, foire au planning, organisation).
 *
 * Kept as data rather than as template markup — the page renders it
 * generically as an accordion, and a unit test can assert on it without
 * rendering anything. Deliberately separate from `pages/aide/aide-content.ts`:
 * that guide addresses the organisers and speaks of solving, scores and
 * découpage, none of which an animateur ever sees.
 */

/** Tab of the espace a section sends the reader to, when there is one. */
export type EspaceAideCible = 'planning' | 'echanges' | 'disponibilites';

export type EspaceAideBlock =
  | { kind: 'paragraph'; text: string }
  | { kind: 'list'; items: string[] }
  /** Numbered sequence: the reader is meant to follow it in order. */
  | { kind: 'steps'; items: string[] }
  | { kind: 'definitions'; items: { term: string; text: string }[] };

export interface EspaceAideSection {
  /** Anchor id, also used as the `track` key. */
  id: string;
  icon: string;
  /** The question as an animateur would ask it — this is the panel header. */
  question: string;
  /** One-line answer, readable while the panel is still collapsed. */
  resume: string;
  blocks: EspaceAideBlock[];
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
      resume: $localize`:@@espace.aide.espace.resume:À consulter votre planning personnel, toujours à jour, et à demander un échange de créneau.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.espace.intro:L'organisation construit le planning de l'événement en répartissant les animateurs sur les stands, créneau par créneau. Cet espace est votre view personnelle de ce planning : vos créneaux à vous, et rien d'autre.`
        },
        {
          kind: 'list',
          items: [
            $localize`:@@espace.aide.espace.item1:Consulter vos créneaux jour par jour, avec le stand et vos coéquipiers.`,
            $localize`:@@espace.aide.espace.item2:Emporter votre planning en PDF ou dans l'agenda de votre téléphone.`,
            $localize`:@@espace.aide.espace.item3:Demander un échange de créneau avec un collègue, tant que la foire au planning est ouverte.`,
            $localize`:@@espace.aide.espace.item4:Répondre aux demandes d'échange que des collègues vous adressent.`,
            $localize`:@@espace.aide.espace.item5:Déclarer vos jours d'indisponibilité et vos souhaits, quand l'organisation ouvre la collecte.`
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.espace.limite:Vous ne modifiez jamais le planning directement : tout passe par une demande que l'organisation valide.`
        }
      ]
    },
    {
      id: 'acces',
      icon: 'key',
      question: $localize`:@@espace.aide.acces.question:Comment j'accède à mon espace ?`,
      resume: $localize`:@@espace.aide.acces.resume:Par votre lien personnel, puis par un code reçu par e-mail, demandé une fois par appareil.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.acces.intro:Le lien que l'organisation vous a envoyé est personnel : il désigne votre planning et lui seul. La première fois que vous l'ouvrez sur un téléphone ou un ordinateur, un code vous est envoyé à l'adresse e-mail que l'organisation connaît. Il est valable 10 minutes.`
        },
        {
          kind: 'list',
          items: [
            $localize`:@@espace.aide.acces.item1:Un code par appareil : une fois saisi, cet appareil garde l'accès, vous n'avez plus à recommencer.`,
            $localize`:@@espace.aide.acces.item2:Code non reçu ? Regardez dans les indésirables, puis demandez-en un nouveau.`,
            $localize`:@@espace.aide.acces.item3:Ne transférez pas votre lien : la personne qui l'ouvre accède à votre planning.`,
            $localize`:@@espace.aide.acces.item4:« Ce lien n'est plus valide » : rapprochez-vous de l'organisation, elle vous en enverra un nouveau.`
          ]
        }
      ]
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
              text: $localize`:@@espace.aide.planning.def.horaire:Le début et la fin de votre créneau. C'est à l'heure de début que vous êtes attendu au stand, pas à l'heure d'ouverture du stand.`
            },
            {
              term: $localize`:@@espace.aide.planning.term.stand:Le stand`,
              text: $localize`:@@espace.aide.planning.def.stand:Le jeu ou le poste que vous tenez pendant ce créneau. Il peut changer d'un créneau à l'autre dans la même journée.`
            },
            {
              term: $localize`:@@espace.aide.planning.term.coequipiers:Les coéquipiers`,
              text: $localize`:@@espace.aide.planning.def.coequipiers:Les animateurs affectés au même stand, sur le même créneau que vous.`
            },
            {
              term: $localize`:@@espace.aide.planning.term.pause:La pause`,
              text: $localize`:@@espace.aide.planning.def.pause:Dès que votre journée atteint six heures d'affilée, vous avez droit à vingt minutes de pause. Votre planning vous dit de quelle heure à quelle heure la prendre et sur quel stand vous serez ; les pauses d'un même stand se suivent, une personne à la fois. Sortez à l'heure dite, ou demandez le relais à l'organisation si vous êtes seul.`
            }
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.planning.repos:Les journées où vous n'êtes affecté nulle part apparaissent quand même, marquées « Repos » : une journée absente de la liste serait un oubli, une journée « Repos » est une décision.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.planning.maj:La mention « Planning communiqué le… » indique de quand date la version que vous lisez. C'est celle que l'organisation vous a envoyée : elle ne change que lorsqu'elle publie à nouveau, et vous êtes alors prévenu par e-mail de ce qui bouge pour vous. Repassez tout de même sur cette page avant de partir, c'est elle qui fait foi.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.planning.vide:Si rien ne s'affiche, le planning n'a pas encore été publié. Il n'y a rien à faire de votre côté.`
        }
      ]
    },
    {
      id: 'confirmer',
      icon: 'check_circle',
      question: $localize`:@@espace.aide.confirmer.question:Que veut dire « J'ai lu et je serai là » ?`,
      resume: $localize`:@@espace.aide.confirmer.resume:Un clic qui dit à l'organisation que vous avez vu votre planning. Un seul suffit.`,
      cible: 'planning',
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.confirmer.pourquoi:Le bouton apparaît dès que votre planning a été publié. Il ne demande rien d'autre qu'un clic : l'organisation voit alors que vous êtes au courant, et n'a pas à vous relancer. Sans réponse de votre part au bout de quelques jours, un rappel automatique vous est envoyé — une seule fois.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.confirmer.deuxFois:Cliquer deux fois ne change rien : c'est la première date qui est retenue, et le bouton laisse ensuite la place à la mention « Présence confirmée le… ».`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.confirmer.republication:Si l'organisation publie une nouvelle version et que vos journées changent, la question vous est reposée — et à vous seul. Quand rien n'a bougé pour vous, votre confirmation reste acquise : on ne vous fera pas reconfirmer un planning identique.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.confirmer.probleme:Confirmer n'est pas un engagement irrévocable, et ce n'est pas non plus le bon endroit pour signaler un souci. Si un créneau ne va pas, passez par l'onglet Échanges ou contactez l'organisation.`
        }
      ]
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
          text: $localize`:@@espace.aide.emporter.abonnement:En haut de la page « Mon planning », la bande « Emporter mon planning » propose trois sorties. La première, « S'abonner dans mon agenda », est celle à privilégier : donnée une fois à votre agenda, l'adresse le tient à jour tout seul à chaque nouvelle publication.`
        },
        {
          kind: 'list',
          items: [
            $localize`:@@espace.aide.emporter.item0:« S'abonner dans mon agenda » : vos créneaux apparaissent dans votre agenda et suivent les republications. Rien à refaire ensuite.`,
            $localize`:@@espace.aide.emporter.item1:« Télécharger en PDF » : votre planning sur une page, à imprimer ou à garder hors connexion.`,
            $localize`:@@espace.aide.emporter.item2:« Télécharger le fichier ICS » : chaque créneau devient un rendez-vous, versé une seule fois dans l'agenda de votre téléphone.`
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.emporter.photo:Ces deux fichiers sont une photo prise au moment du téléchargement : ils ne se mettent pas à jour tout seuls quand le planning change ou qu'un échange est accepté. Au moindre doute, fiez-vous à cette page plutôt qu'au fichier.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.emporter.abonnementColler:Certaines applications, Google Agenda en premier, demandent qu'on leur colle l'adresse au lieu de cliquer. Elle est alors sous « Copier l'adresse, ou la remplacer », dans la même bande.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.emporter.abonnementPrudence:Cette adresse est personnelle : qui l'obtient voit votre planning, sans code ni mot de passe. Ne la publiez pas sur un agenda partagé, et si elle vous échappe, le bouton « Cette adresse a fuité, la remplacer » — au même endroit — en crée une neuve, l'ancienne cessant aussitôt de fonctionner.`
        }
      ]
    },
    {
      id: 'demander-echange',
      icon: 'swap_horiz',
      question: $localize`:@@espace.aide.echange.question:Comment demander un échange ?`,
      resume: $localize`:@@espace.aide.echange.resume:Depuis « Mes échanges », en montant une liste de demandes puis en la soumettant.`,
      cible: 'echanges',
      blocks: [
        {
          kind: 'steps',
          items: [
            $localize`:@@espace.aide.echange.step1:Choisissez le créneau dont vous voulez vous libérer.`,
            $localize`:@@espace.aide.echange.step2:Choisissez le ou la collègue avec qui échanger.`,
            $localize`:@@espace.aide.echange.step3:Si vous voulez reprendre un de ses créneaux en contrepartie, désignez-le. Sans ce choix, l'échange ne porte que sur le vôtre.`,
            $localize`:@@espace.aide.echange.step4:Indiquez un motif : c'est ce que lira l'organisation pour trancher.`,
            $localize`:@@espace.aide.echange.step5:« Ajouter à la liste », puis recommencez si plusieurs créneaux sont concernés.`,
            $localize`:@@espace.aide.echange.step6:« Soumettre mes demandes » : rien ne part avant ce bouton.`
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.echange.brouillon:Tant que vous n'avez pas soumis, la liste n'est qu'un brouillon : la croix retire une demande, et personne n'a encore rien reçu.`
        }
      ]
    },
    {
      id: 'remplacants',
      icon: 'person_search',
      question: $localize`:@@espace.aide.remplacants.question:Je n'ai personne en tête, que faire ?`,
      resume: $localize`:@@espace.aide.remplacants.resume:Le bouton « Qui peut me remplacer ? » cherche pour vous les échanges qui tiennent.`,
      cible: 'echanges',
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.remplacants.intro:Une fois votre créneau choisi, ce bouton passe les collègues en revue et ne retient que les échanges compatibles avec le reste du planning et avec les règles de repos. Les propositions arrivent en trois familles :`
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@espace.aide.remplacants.term.libere:On vous libère de ce créneau`,
              text: $localize`:@@espace.aide.remplacants.def.libere:Un collègue le reprend et vous n'êtes plus de service à ce moment-là, sans contrepartie.`
            },
            {
              term: $localize`:@@espace.aide.remplacants.term.dirige:Vous échangez contre un autre créneau`,
              text: $localize`:@@espace.aide.remplacants.def.dirige:Vous lui laissez le vôtre et vous reprenez l'un des siens, à un autre moment.`
            },
            {
              term: $localize`:@@espace.aide.remplacants.term.croise:Vous échangez sur ce même créneau`,
              text: $localize`:@@espace.aide.remplacants.def.croise:Vous restez de service au même horaire, mais sur un autre stand.`
            }
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.remplacants.limite:La liste montre les pistes les plus prometteuses, pas toutes. Et si rien ne ressort, vous pouvez tout de même proposer l'échange à quelqu'un : l'organisation tranchera.`
        }
      ]
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
          text: $localize`:@@espace.aide.suivi.intro:Chaque demande porte une pastille qui dit où elle en est :`
        },
        {
          kind: 'definitions',
          items: [
            {
              term: $localize`:@@echanges.statut.attenteCible:En attente du collègue`,
              text: $localize`:@@espace.aide.suivi.def.attenteCible:Votre collègue doit d'abord donner son accord.`
            },
            {
              term: $localize`:@@echanges.statut.proposee:En attente de l'organisation`,
              text: $localize`:@@espace.aide.suivi.def.proposee:L'accord est donné ; l'organisation doit encore trancher.`
            },
            {
              term: $localize`:@@echanges.statut.acceptee:Acceptée`,
              text: $localize`:@@espace.aide.suivi.def.acceptee:L'échange est fait : votre planning a changé, vérifiez-le.`
            },
            {
              term: $localize`:@@echanges.statut.refusee:Refusée`,
              text: $localize`:@@espace.aide.suivi.def.refusee:L'organisation n'a pas retenu l'échange ; votre créneau reste le vôtre.`
            },
            {
              term: $localize`:@@echanges.statut.refuseeCible:Déclinée par le collègue`,
              text: $localize`:@@espace.aide.suivi.def.refuseeCible:Le collègue n'a pas souhaité l'échange ; la demande est close.`
            },
            {
              term: $localize`:@@echanges.statut.annulee:Annulée`,
              text: $localize`:@@espace.aide.suivi.def.annulee:Vous avez retiré la demande vous-même.`
            }
          ]
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.suivi.alerte:Un encadré « cet échange poserait un problème » signale que l'échange se heurte à une règle : temps de repos trop court, horaire déjà occupé, stand qui vous attend au même moment… C'est un avertissement, pas un refus, et la demande est transmise quand même.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.suivi.annulation:Tant qu'une demande n'est pas tranchée, « Annuler cette demande » la retire. Et si l'organisation a joint un commentaire à sa décision, il s'affiche juste en dessous.`
        }
      ]
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
          text: $localize`:@@espace.aide.recues.intro:Les demandes qui vous sont adressées s'affichent en haut de « Mes échanges », sous « Demandes reçues ». Lisez le créneau qu'on vous propose de reprendre et, s'il y en a un, celui qu'on vous prend en contrepartie.`
        },
        {
          kind: 'list',
          items: [
            $localize`:@@espace.aide.recues.item1:« Je suis d'accord » transmet la demande à l'organisation, qui tranche : votre accord seul ne suffit pas à échanger.`,
            $localize`:@@espace.aide.recues.item2:« Décliner » clôt la demande, et votre collègue en est informé.`
          ]
        }
      ]
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
          text: $localize`:@@espace.aide.dispo.intro:Avant de construire le planning, l'organisation ouvre une période pendant laquelle chacun dit les jours où il ne peut pas venir, et les types de jeux qu'il aimerait animer. Vous le faites depuis l'onglet « Mes disponibilités », sur votre téléphone : les jours de l'événement s'affichent en pastilles, touchez celles qui ne vont pas.`
        },
        {
          kind: 'steps',
          items: [
            $localize`:@@espace.aide.dispo.etape1:Touchez chaque jour où vous ne pouvez pas venir. Les jours que vous ne touchez pas veulent dire « je suis disponible ».`,
            $localize`:@@espace.aide.dispo.etape2:Choisissez les types de jeux qui vous plairaient. Un souhait n'est pas une garantie : il est suivi quand le planning le permet.`,
            $localize`:@@espace.aide.dispo.etape3:Ajoutez un mot si votre situation ne tient pas dans des cases (« je pars dimanche après le déjeuner »).`,
            $localize`:@@espace.aide.dispo.etape4:Envoyez. Le formulaire s'ouvre déjà sur ce que l'organisation sait de vous : vous corrigez, vous ne repartez pas de zéro.`
          ]
        },
        {
          kind: 'list',
          items: [
            $localize`:@@espace.aide.dispo.item1:Ce que vous envoyez est une proposition : l'organisation la relit et l'applique, ou vous répond. Rien n'est enregistré sur votre fiche avant.`,
            $localize`:@@espace.aide.dispo.item2:Vous pouvez renvoyer une version corrigée tant que la collecte est ouverte : elle remplace la précédente, il n'y en a jamais deux en attente.`,
            $localize`:@@espace.aide.dispo.item3:Vos compétences ne se déclarent pas ici : elles restent décidées avec l'organisation.`,
            $localize`:@@espace.aide.dispo.item4:Collecte fermée, l'onglet reste consultable mais n'accepte plus d'envoi — prévenez alors directement l'organisation.`
          ]
        }
      ]
    },
    {
      id: 'foire-fermee',
      icon: 'lock',
      question: $localize`:@@espace.aide.foire.question:Pourquoi je ne peux plus rien demander ?`,
      resume: $localize`:@@espace.aide.foire.resume:La foire au planning est fermée : l'espace passe en consultation seule.`,
      blocks: [
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.foire.intro:La foire au planning est la période pendant laquelle l'organisation accepte les échanges. Une fois qu'elle est fermée — parce que le planning est figé, ou parce que l'événement a commencé — vous gardez l'accès à votre planning et à l'historique de vos demandes, mais vous ne pouvez plus en proposer, en accorder ni en annuler.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.foire.suite:Si un empêchement survient après la fermeture, prévenez directement l'organisation : elle peut encore agir, pas cet espace.`
        }
      ]
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
          text: $localize`:@@espace.aide.veille.contenu:Si l'organisation a activé les rappels, vous recevez la veille au soir la liste de vos créneaux du lendemain : horaire et stand, rien de plus. C'est exactement ce que cette page affiche déjà — un rappel ne vous annonce jamais un changement, il répète ce qui vous a été communiqué.`
        },
        {
          kind: 'paragraph',
          text: $localize`:@@espace.aide.veille.absence:Pas de rappel reçu ? Trois raisons possibles : votre fiche ne porte pas d'adresse e-mail, l'organisation n'a pas activé les rappels, ou vous n'êtes affecté nulle part ce jour-là. Dans tous les cas, cette page reste la référence.`
        }
      ]
    },
    {
      id: 'contact',
      icon: 'support_agent',
      question: $localize`:@@espace.aide.contact.question:Une erreur, une question ?`,
      resume: $localize`:@@espace.aide.contact.resume:L'organisation reste votre interlocuteur ; cet espace ne remplace pas un mot ou un appel.`,
      blocks: [
        {
          kind: 'list',
          items: [
            $localize`:@@espace.aide.contact.item1:Une affectation vous semble fausse, ou une indisponibilité annoncée n'a pas été prise en compte : signalez-le. Hors période de collecte, vous ne pouvez pas corriger vos données vous-même.`,
            $localize`:@@espace.aide.contact.item2:Empêchement de dernière minute : prévenez tout de suite, sans attendre qu'une demande d'échange soit validée.`,
            $localize`:@@espace.aide.contact.item3:Ce que l'application sait de vous et ce qu'elle en fait est décrit dans la politique de confidentialité, dans le menu en haut à droite de l'écran.`
          ]
        }
      ]
    }
  ];
}
