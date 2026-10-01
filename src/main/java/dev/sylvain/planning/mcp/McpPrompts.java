package dev.sylvain.planning.mcp;

import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptArg;
import io.quarkiverse.mcp.server.PromptMessage;
import jakarta.enterprise.context.ApplicationScoped;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * The conversations this server is actually for, served as MCP prompts
 * instead of being copy-pasted: one per moment of a real event, from the
 * new edition and its empty grid to the review once the event is over.
 *
 * <p>The MCP page hands the user a ready-to-copy prompt, and that text had
 * already drifted: it named a tool the application has never exposed. A prompt
 * the server itself announces cannot drift that way — it lives next to the
 * tools it names, {@code McpToolNamesTest} reads this file, and
 * {@code McpPromptsWordingTest} reads every rendered text against the tools the
 * server announces.</p>
 *
 * <p>These are deliberately <b>not</b> the localized string of the page. That
 * one is displayed, translated and copied by a human; this one is executed.
 * Keeping the page's copy is what serves a client with no prompt support.</p>
 *
 * <p>No {@code @EditionCiblee} here: a prompt writes nothing and reads
 * nothing. It weaves the edition into the text it hands back, and the tools
 * the assistant then calls carry it themselves. The argument stays optional:
 * without it, the text tells the assistant to read {@code lister_editions}
 * and take the active edition (ADR 0072). In both cases it tells the assistant
 * to pass the edition to every tool, since a tool called without one is
 * refused — there is no edition a call falls back on any more.</p>
 *
 * <p>A prompt that publishes or mails also tells the assistant to check that
 * its edition is the active one, and to stop otherwise: only the active
 * edition speaks outside, and an assistant that "fixed" the refusal by
 * activating another edition would close every link of the one in use.</p>
 *
 * <p>{@link #catalogue()} serves the same texts to the MCP page, for a client
 * that does not support prompts. The page used to carry its own copies, and
 * one of them had drifted to a tool this application never exposed — reading
 * them from here is what makes that impossible rather than merely
 * unlikely.</p>
 */
@ApplicationScoped
public class McpPrompts {

    private static final String EDITION =
            "Id ou nom de l'édition à traiter ; omis, l'édition active, lue par lister_editions";

    /**
     * The sentence every prompt that sends mail carries, word for word, so
     * that {@code McpPromptsWordingTest} can hold it to the tools that do.
     */
    static final String ACTIVE_EDITION_CHECK = "Seule l'édition active publie et envoie des courriels : si "
            + "lister_editions ne marque pas cette édition ACTIVE, arrête-toi et dis-le-moi avant tout envoi — "
            + "n'appelle pas activer_edition de ton propre chef.";

    /**
     * Display order on the MCP page, which is the order of a real event:
     * start the edition, build the grid, collect, check, solve, review,
     * publish, hold the day, then look back.
     *
     * <p>Declared rather than derived from {@code getDeclaredMethods()}, whose
     * order the JVM does not guarantee — a page whose sections reshuffle
     * between two deployments reads as a bug. {@code McpPromptsResourcesTest}
     * fails if this list and the annotated methods ever diverge.</p>
     */
    private static final List<String> ORDRE = List.of(
            "demarrer_une_nouvelle_edition",
            "saisir_les_horaires_des_stands",
            "construire_la_grille_de_creneaux",
            "ouvrir_collecte_et_foire",
            "traiter_les_declarations_de_disponibilite",
            "figer_le_referentiel",
            "savoir_ou_recruter_ou_former",
            "regler_les_regles",
            "verifier_avant_resolution",
            "resoudre_sans_perdre_le_planning",
            "diagnostiquer_contraintes_dures",
            "verifier_les_arrivees_groupees",
            "relire_et_valider_les_journees",
            "verrouiller_ce_qui_tient",
            "preparer_un_plan_de_repli",
            "auditer_avant_diffusion",
            "publier_le_planning",
            "suivre_les_confirmations",
            "traiter_les_demandes_dechange",
            "reprendre_apres_un_changement_tardif",
            "tenir_le_jour_j",
            "tirer_le_bilan_de_l_evenement");

    @Prompt(
            name = "demarrer_une_nouvelle_edition",
            description = "Préparer l'édition suivante à côté de celle en cours : la créer ou la dupliquer, la "
                    + "préparer, puis l'activer le jour venu.")
    PromptMessage startANewEdition(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Prépare une nouvelle édition à partir de %s, sans rien changer à celle qui vit.

                %s

                1. lister_editions : quelles éditions existent, laquelle est ACTIVE, et quelle période \
                chacune couvre.
                2. dupliquer_edition pour repartir de cette édition — stands, typologies, emplacements, \
                créneaux, horaires et paramètres sont recopiés, jamais le planning. Propose-moi de laisser \
                les animateurs derrière : une personne qui ne s'est pas réinscrite n'a rien à faire dans \
                l'édition suivante. creer_edition seulement si je veux repartir d'une page blanche. Note \
                l'id de la nouvelle édition dans ta réponse, et dis-moi à chaque appel sur quelle \
                édition tu écris.
                3. renommer_edition si le nom proposé ne dit pas de quelle année il s'agit.
                4. La nouvelle édition naît inactive : elle n'envoie rien et n'ouvre aucun lien, on la \
                prépare tranquillement pendant que l'autre vit — horaires des stands, grille de \
                créneaux, collecte, résolution, avec les prompts dédiés appelés avec \
                edition=<id de la nouvelle édition> : sans cet argument, ils travaillent sur l'édition \
                active, celle qui vit. etat_edition dit où elle en est.
                5. activer_edition seulement le jour de la bascule et après mon accord explicite. Le \
                geste désactive l'ancienne : ses liens d'espace animateur, ses flux de calendrier et \
                ses affichages muraux cessent de répondre, et c'est la nouvelle qui envoie désormais les \
                courriels de nuit. Il est refusé tant qu'une résolution tourne sur l'une des deux. \
                desactiver_edition existe pour l'entre-deux, quand plus rien ne doit partir.

                Ne supprime aucune édition.""".formatted(designation(edition), newEditionFrame(edition)));
    }

    @Prompt(
            name = "diagnostiquer_contraintes_dures",
            description = "Diagnostiquer les contraintes dures encore violées après une résolution, et dire "
                    + "quoi corriger dans les données de référence.")
    PromptMessage diagnoseHardContraintes(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Le dernier planning résolu%s contient des violations de contraintes dures.

                %s

                1. Appelle etat_planning pour savoir si le planning affiché est encore à jour.
                2. Appelle diagnostiquer_plan pour recalculer le score du plan persisté, puis \
                expliquer_echec_contraintes_dures pour le détail des violations.
                3. Pour chaque contrainte HARD en défaut, identifie les postes touchés avec \
                lister_affectations puis expliquer_affectation, et donne la cause racine probable \
                (compétences manquantes sur la typologie, indisponibilité, effectif insuffisant sur la \
                tranche, plafond légal atteint, aucun animateur polyvalent…).
                4. Recoupe avec analyser_faisabilite et analyser_effectifs : si le problème est \
                structurellement infaisable, aucune résolution ne le corrigera.
                5. Termine par des actions concrètes classées par impact décroissant, en disant pour \
                chacune quel outil l'appliquerait.

                N'expose aucune donnée nominative : les animateurs se désignent par leur id.""".formatted(suffixe(edition), editionFrame(edition)));
    }

    @Prompt(
            name = "verifier_avant_resolution",
            description = "Vérifier qu'une édition est prête à être résolue : référentiel, déclarations en "
                    + "attente, grille de créneaux, ouvertures de stands, marge et effectifs, avant de lancer quoi "
                    + "que ce soit.")
    PromptMessage checkBeforeSolving(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Vérifie que %s est prête à être résolue, sans rien lancer ni rien modifier.

                %s

                1. etat_edition en premier : la checklist du cycle dit déjà ce qui est fait, ce qui \
                reste et ce qui bloque. Le reste de la liste en détaille les lignes.
                2. volumes : y a-t-il des animateurs et des postes à pourvoir ?
                3. lister_anomalies_referentiel : traite d'abord les lignes BLOQUANT, puis dis-moi \
                combien restent A_VERIFIER.
                4. lister_declarations_disponibilite avec statut EN_ATTENTE : une déclaration non \
                décidée n'est pas dans le référentiel, et résoudre avant de la traiter, c'est résoudre \
                le mauvais problème. lister_demandes_covoiturage de même : une arrivée groupée encore \
                EN_ATTENTE ne pèse rien tant qu'elle n'est pas validée depuis l'onglet Covoiturage de \
                l'écran Disponibilités. Dis-moi combien il en reste de chaque.
                5. valider_creneaux : la grille est-elle cohérente ?
                6. analyser_ouvertures_stands : y a-t-il des stands jamais ouverts, des fenêtres sans \
                effet, des segments trop courts ?
                7. lister_consignes : une consigne posée sur une date à venir change les horaires de \
                tous les stands ce jour-là — dis-moi lesquelles, et si leur motif tient toujours.
                8. analyser_effectifs : combien d'animateurs faut-il au minimum, et l'effectif présent \
                suffit-il ? Puis analyser_marge en mode « avant » : une tranche négative manquera de \
                monde quoi que fasse le solveur.
                9. analyser_faisabilite : reste-t-il une cause structurellement bloquante ?
                10. consulter_parametres_legaux : la pause minimale entre vacations et la pause sur le \
                poste sont-elles réglées comme l'organisateur le veut ? À 30 minutes entre vacations, \
                deux blocs qui se touchent exigent deux équipes ; sans la pause sur le poste, aucune \
                séquence ne peut dépasser six heures.

                Conclus par oui/non, puis par la liste de ce qui reste à corriger avant de lancer une \
                résolution.""".formatted(designation(edition), editionFrame(edition)));
    }

    @Prompt(
            name = "resoudre_sans_perdre_le_planning",
            description = "Relancer une résolution sans risquer de perdre le planning en place : capturer, "
                    + "résoudre, comparer, restaurer si c'est pire.")
    PromptMessage solveWithoutLosingThePlanning(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Relance une résolution%s en gardant la possibilité de revenir en arrière.

                %s

                1. capturer_instantane avec un libellé qui dit d'où on part.
                2. Si une partie du planning est déjà bonne, fige-la avec verrouiller avant de \
                relancer — sinon la résolution la défera.
                3. lancer_solveur, puis statut_solveur jusqu'à la fin du job.
                4. comparer_instantanes entre l'instantané capturé et « courant ».
                5. Dis-moi ce qui a changé : score, postes pourvus, violations par contrainte. Si le \
                résultat est moins bon, propose restaurer_instantane — mais ne le fais pas sans mon \
                accord. Si la pause sur le poste est déclarée (consulter_parametres_legaux), appelle \
                analyser_pauses avec sansRelaisSeulement : chaque pause sans relais est un relais à \
                organiser avant de publier.

                6. Rappelle-moi que résoudre n'est pas prévenir : tant que le planning n'est pas \
                publié, les animateurs lisent toujours le précédent. etat_publication dit depuis quand.

                Ne relance pas une deuxième résolution de ta propre initiative.""".formatted(suffixe(edition), editionFrame(edition)));
    }

    @Prompt(
            name = "construire_la_grille_de_creneaux",
            description = "Poser une grille de créneaux sans les saisir un par un — journées types, dérivation "
                    + "des horaires des stands ou règle récurrente — et faire contrôler la grille obtenue avant "
                    + "de la garder.")
    PromptMessage buildTheCreneauGrid(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Construis la grille de créneaux%s.

                %s

                1. etat_edition : la famille CRENEAUX est-elle figée (bloc gel) ? Si oui, toute \
                création ou modification de créneau sera refusée : dis-le-moi et arrête-toi. lever_gel \
                n'est à proposer que si je le demande.
                2. diagnostiquer_grille_creneaux pour voir ce qui existe déjà, et lister_journees_types \
                pour savoir si un calendrier de journées types est déjà en place.
                3. Si les journées de l'évènement se ressemblent, propose-moi les journées types :
                   - une grille existe déjà : previsualiser_reconnaissance_journees_types montre les \
                types qu'elle implique, et reconnaitre_journees_types les enregistre après mon accord, \
                sans toucher aux créneaux ;
                   - sinon, definir_journee_type décrit une journée en une ligne, affecter_journee_type \
                lui donne ses dates, et previsualiser_application_journees_types montre ce que la \
                grille deviendrait. materialiser_journees_types seulement après mon accord explicite : \
                un créneau que sa journée type ne nomme plus est supprimé AVEC les sièges du planning \
                qu'il porte — dis-moi combien avant.
                4. Sans journées types : si les stands ont déjà leurs horaires (lister_stands), \
                previsualiser_derivation_creneaux déduit la grille de leurs fenêtres sans saisie de plus ; \
                sinon previsualiser_creneaux_recurrents montre ce que ta règle produirait — une règle \
                qui se trompe d'une heure crée des dizaines de lignes d'un coup. \
                generer_creneaux_depuis_stands ou creer_creneaux_recurrents seulement après mon accord \
                explicite, et jamais remplacer=true sans me dire qu'il efface le planning résolu.
                5. valider_creneaux pour finir, et explique-moi chaque anomalie — doublon, vacation trop \
                longue, trou dans une journée, date isolée, stand que personne ne pourra tenir, \
                sous-effectif — en disant pour chacune si c'est une vraie erreur ou un choix légitime \
                de ma part.
                6. Quand la grille est arrêtée, propose-moi de la figer (figer_referentiel, famille \
                CRENEAUX) : c'est le prompt figer_le_referentiel.""".formatted(suffixe(edition), editionFrame(edition)));
    }

    @Prompt(
            name = "ouvrir_collecte_et_foire",
            description = "Ouvrir aux animateurs la collecte des disponibilités, puis la foire aux échanges, sur "
                    + "l'édition active et avec leurs dates.")
    PromptMessage openCollectionAndSwapFair(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Ouvre aux animateurs ce qui doit l'être%s : la collecte des disponibilités, puis la \
                foire aux échanges. **Inviter à déclarer envoie des courriels** : ne le fais pas sans \
                mon accord explicite.

                %s

                1. lister_editions : l'espace animateur n'est ouvert que sur l'édition active. Sur une \
                autre, une collecte ouverte ne recevrait rien.
                2. consulter_collecte_disponibilites : ouverte ou non, et sur quelles dates.
                3. configurer_collecte_disponibilites pour l'ouvrir, bornée par des dates si je t'en \
                donne. prevenirAnimateurs envoie à chacun le lien de son espace : c'est une décision à \
                chaque ouverture, à prendre seulement avec mon accord, et jamais pour une collecte qu'on \
                ferme.
                4. consulter_foire_echanges : la foire accepte-t-elle des demandes aujourd'hui ? Elle \
                n'a de sens qu'une fois un planning publié, que les animateurs puissent échanger ce \
                qu'ils ont lu.
                5. configurer_foire_echanges pour l'ouvrir ou la fermer, après mon accord — il n'envoie \
                aucun courriel, mais l'interrupteur est le maître : une fenêtre datée dont \
                l'interrupteur est éteint n'accepte rien.

                Dis-moi pour finir ce que voit un animateur qui ouvre son espace aujourd'hui.""".formatted(suffixe(edition), emittingEditionFrame(edition)));
    }

    @Prompt(
            name = "traiter_les_declarations_de_disponibilite",
            description = "Traiter les déclarations de disponibilité envoyées par les animateurs : les "
                    + "lire, les appliquer ou les refuser, avant de résoudre.")
    PromptMessage handleAvailabilityDeclarations(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Aide-moi à traiter les déclarations de disponibilité en attente%s.

                %s

                1. consulter_collecte_disponibilites : la collecte est-elle encore ouverte ? Une \
                déclaration peut encore arriver après celles que nous allons lire.
                2. lister_declarations_disponibilite avec statut EN_ATTENTE. Pour chacune, compare ce \
                qui est déclaré (joursIndisponibles, souhaits) avec ce que la fiche dit aujourd'hui \
                (joursActuels) : dis-moi ce qui changerait vraiment.
                3. Signale-moi celles qui coûtent cher avant de les appliquer : un jour retiré sur une \
                journée déjà tendue se voit avec analyser_effectifs et analyser_faisabilite.
                4. appliquer_declaration_disponibilite ou refuser_declaration_disponibilite, une par \
                une et seulement après mon accord — appliquer écrit sur la fiche, tout ou rien, et il \
                n'y a pas de retour en arrière.
                5. lister_demandes_covoiturage : les demandes « Je viens avec… » sont distinctes des \
                déclarations, et appliquer une déclaration ne les touche pas. Dis-moi celles qui sont \
                EN_ATTENTE : elles se valident depuis l'onglet Covoiturage de l'écran Disponibilités, \
                pas ici.
                6. Quand il n'en reste plus, rappelle-moi que le planning résolu est maintenant \
                périmé : etat_planning le dit, et il faut relancer une résolution.

                Les animateurs se désignent par leur id : ne me demande pas de noms, tu n'en verras \
                pas.""".formatted(suffixe(edition), editionFrame(edition)));
    }

    @Prompt(
            name = "figer_le_referentiel",
            description = "Figer le référentiel une fois sa préparation terminée — stands, créneaux, "
                    + "typologies et emplacements, compétences — pour qu'aucune écriture ne le fasse plus bouger.")
    PromptMessage freezeTheReferential(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Aide-moi à figer le référentiel%s, famille par famille.

                %s

                1. etat_edition : quelles familles sont déjà figées (bloc gel), et depuis quand ? Le \
                reste de la checklist dit si la préparation est vraiment finie.
                2. lister_anomalies_referentiel : une anomalie BLOQUANT sur une famille se corrige \
                avant de la figer — après, la fiche ne se corrige plus sans lever le gel.
                3. figer_referentiel, une famille à la fois et après mon accord : STANDS, CRENEAUX, \
                TYPOLOGIES_EMPLACEMENTS ou COMPETENCES. Dis-moi chaque fois ce qui ne s'écrira plus, \
                et ce qui reste libre : disponibilités, souhaits, déclarations, ajustements, verrous et \
                consignes.
                4. Ne confonds pas figer et verrouiller : figer_referentiel gèle des fiches du \
                référentiel, verrouiller fige des sièges du planning pour les prochaines résolutions. \
                Les deux se décident séparément.

                lever_gel seulement si je le demande explicitement : après une publication, toute \
                modification fait bouger des plannings déjà envoyés. Fermer une bande horaire à tous \
                les stands reste possible sous gel, par une consigne (prompt \
                preparer_un_plan_de_repli) ; fermer un seul stand, non : ajouter_fermeture_stand est \
                refusé sous gel STANDS.""".formatted(suffixe(edition), editionFrame(edition)));
    }

    @Prompt(
            name = "publier_le_planning",
            description = "Publier le planning aux animateurs : vérifier l'édition, qui est concerné et ce "
                    + "qu'ils liront, publier, puis contrôler qui a bien été prévenu.")
    PromptMessage publishThePlanning(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Prépare la publication du planning%s. **Publier envoie des courriels** : ne le fais \
                pas sans mon accord explicite.

                %s

                1. etat_publication : depuis quand rien n'est parti, combien de personnes sont \
                concernées, et ce que chacune lirait. Zéro concerné veut dire que le planning publié \
                est déjà à jour — il n'y a rien à faire.
                2. Si une résolution tourne, ou si le plan à publier est vide, la publication sera \
                refusée : dis-le-moi plutôt que de réessayer.
                3. diagnostiquer_plan avant d'envoyer : publier un planning qui viole encore des \
                contraintes dures, c'est faire lire à quelqu'un un horaire qu'on va lui reprendre.
                4. lister_validations_journee : quelles journées n'ont été relues par personne ? Ce \
                n'est pas bloquant, mais dis-le-moi. Pour les journées qui bougent le plus, \
                changements_journee avec reference=publication montre, siège par siège, ce que la \
                publication va annoncer.
                5. Signale-moi les personnes dont adresseConnue est faux : elles ne recevront rien et \
                devront être prévenues autrement. Si je veux prévenir quelqu'un de vive voix d'abord, \
                l'argument exclusions diffère son message : il restera à prévenir à la publication \
                suivante.
                6. publier_planning seulement après mon accord.
                7. lister_destinataires_publication pour contrôler le résultat. Le suivi des \
                accusés de réception est le prompt suivre_les_confirmations : synthese_confirmations, \
                puis relancer_animateurs ou envoyer_planning_animateur — chacun un courriel de plus, \
                toujours après mon accord.

                Les destinataires se désignent par leur id : ni nom ni adresse ne sortent d'ici.""".formatted(suffixe(edition), emittingEditionFrame(edition)));
    }

    @Prompt(
            name = "suivre_les_confirmations",
            description = "Après une publication, suivre qui a accusé réception de son planning, relancer les "
                    + "silencieux et rattraper un envoi qui a échoué.")
    PromptMessage followConfirmations(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Fais le point sur les accusés de réception du planning publié%s. **Relancer envoie des \
                courriels** : rien ne part sans mon accord explicite.

                %s

                1. synthese_confirmations : combien ont confirmé, combien ont été relancés, combien \
                restent silencieux, et depuis quelle publication. jamaisPublie vrai veut dire que la \
                question n'a encore été posée à personne : arrête-toi là.
                2. lister_destinataires_publication : les envois en échec et les personnes sans \
                adresse, par id. Ceux-là n'ont rien reçu : les relancer ne servirait à rien.
                3. consulter_parametres_notifications : la relance automatique de nuit part d'elle-même \
                après le délai réglé. Dis-moi si elle suffit avant de proposer une relance à la main.
                4. relancer_animateurs pour les silencieux que je désigne, après mon accord : personne \
                ne reçoit deux fois la relance d'une même publication, et le compte rendu dit, par id, \
                qui a été écarté et pourquoi.
                5. envoyer_planning_animateur pour un échec isolé, une personne à la fois et après mon \
                accord : il renvoie le planning publié, celui que la personne n'a pas reçu.

                Les animateurs se désignent par leur id : ni nom ni adresse ne sortent d'ici.""".formatted(suffixe(edition), emittingEditionFrame(edition)));
    }

    @Prompt(
            name = "traiter_les_demandes_dechange",
            description = "Trancher les demandes d'échange des animateurs : chiffrer l'impact de chacune sur "
                    + "le planning d'aujourd'hui, puis accepter ou refuser.")
    PromptMessage handleDemandesEchange(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Aide-moi à trancher les demandes d'échange en attente%s.

                %s

                1. consulter_foire_echanges : la foire accepte-t-elle encore des demandes aujourd'hui ?
                2. lister_demandes_echange avec statut PROPOSEE — celles dont le collègue visé a déjà \
                donné son accord, et qui n'attendent que nous. Celles en EN_ATTENTE_CIBLE ne sont pas \
                à nous.
                3. Pour chacune, analyser_impact_echange : la prévalidation stockée décrit le planning \
                du jour où la demande a été envoyée, pas celui d'aujourd'hui. Classe-les par delta de \
                score, et mets à part celles qui cassent une contrainte dure.
                4. accepter_demande_echange ou refuser_demande_echange, une par une et après mon \
                accord. Accepter écrit dans le planning résolu et le fige par des verrouillages : une \
                résolution ultérieure ne le défera pas, mais lister_verrouillages s'allonge d'autant.
                5. Rappelle-moi de publier ensuite : un échange accepté n'est annoncé à personne tant \
                que la publication n'est pas partie.

                Demandeur et cible se désignent par leur id.""".formatted(suffixe(edition), editionFrame(edition)));
    }

    @Prompt(
            name = "saisir_les_horaires_des_stands",
            description = "Poser les horaires d'ouverture des stands à partir du classeur de l'organisateur, "
                    + "et vérifier ce que le solveur en lira vraiment.")
    PromptMessage enterStandHoraires(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Aide-moi à poser les horaires d'ouverture des stands%s.

                %s

                1. etat_edition : la famille STANDS est-elle figée (bloc gel) ? Si oui, aucun horaire \
                ne s'écrira : dis-le-moi et arrête-toi — lever_gel n'est à proposer que si je le \
                demande.
                2. lister_stands puis analyser_ouvertures_stands : pars de ce qui existe déjà plutôt \
                que d'une page blanche, et dis-moi ce que le planning retiendrait en l'état.
                3. Pour chaque stand à régler, ajouter_horaire_stand pose une règle récurrente — la \
                forme normale, « tous les jours de 10 h à 12 h puis de 14 h à la fermeture ». Une \
                fenêtre peut nommer son propre effectif ; sans effectif, elle reprend le minimum du \
                stand. Garde ajouter_ouverture_stand et ajouter_fermeture_stand pour les exceptions \
                datées, qui priment sur les règles du jour qu'elles nomment.
                4. Si les horaires ont été saisis jour par jour, compacter_horaires_stands les replie \
                en règles quand le motif se répète. Dis-moi ce qu'il n'a pas su compacter, et pourquoi.
                5. Relance analyser_ouvertures_stands et traite les trois erreurs habituelles : un \
                stand finalement ouvert aucun jour, une fenêtre hors des heures du jour donc sans \
                effet, une plage trop courte pour être une vraie vacation.
                6. Si la grille de créneaux n'existe pas encore, previsualiser_derivation_creneaux la \
                déduit de ces fenêtres — une coupure à chaque heure où un stand ouvre ou ferme. Montre \
                l'aperçu avant d'écrire quoi que ce soit.

                Deux choses ne passent pas par ici et méritent d'être dites : l'import de la matrice \
                du classeur et la grille de saisie case par case vivent dans l'interface, pas dans \
                ces outils. Si l'organisateur a déjà son tableau, l'écran « Import de la grille des \
                stands » ira plus vite que trente appels.""".formatted(suffixe(edition), editionFrame(edition)));
    }

    @Prompt(
            name = "savoir_ou_recruter_ou_former",
            description = "Dire où recruter ou former : sur quelle typologie le vivier est trop mince, "
                    + "quels jours manquent de monde, et qui former en priorité.")
    PromptMessage findWhereToRecruitOrTrain(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Dis-moi où %s manque de monde, et de quel monde exactement.

                %s

                1. analyser_effectifs : l'effectif minimum que l'événement exige, le pic qui le fixe, \
                et les typologies dont le vivier de compétents est trop mince. C'est la réponse à \
                « faut-il recruter ou faut-il calculer plus longtemps ».
                2. analyser_faisabilite : reste-t-il une cause structurellement bloquante ? Un manque \
                de compétence sur une typologie ne se résout pas en allongeant le budget du solveur.
                3. analyser_marge en mode « avant » : quels jours et quelles tranches manquent de \
                monde, compétences mises à part. Sur un planning déjà résolu, le mode « tension » \
                croise la marge avec la fragilité et note chaque tranche de CALME à CRITIQUE, avec les \
                sièges que personne d'autre ne peut tenir.
                4. plan_formation : typologie par typologie, le déficit, les jours en tension et les \
                candidats à former — des animateurs déjà présents dont la compétence peut monter. Une \
                typologie sans candidat relève du recrutement.
                5. Sur un planning déjà résolu, suggerer_reparations sur un poste sensible montre \
                combien de personnes pourraient réellement le reprendre : une liste vide est un point \
                de défaillance unique, quel que soit le score.
                6. Conclus par deux listes courtes et séparées : recruter (personne compétente \
                manquante) et former (compétence à faire monter chez quelqu'un de déjà présent), en \
                nommant la typologie à chaque ligne.

                L'onglet « Fragilité » de l'écran Diagnostic montre la même lecture poste par poste, \
                si le détail est nécessaire. Les animateurs se désignent par leur id.""".formatted(designation(edition), editionFrame(edition)));
    }

    @Prompt(
            name = "regler_les_regles",
            description = "Régler le dosage des règles et le budget de calcul : lire ce qui a déjà été essayé, "
                    + "changer un poids ou activer une règle, en sachant ce que cela coûte.")
    PromptMessage tuneTheRules(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Aide-moi à régler les règles du planning%s avant la prochaine résolution.

                %s

                1. lister_contraintes : niveau, état et poids de chaque règle, et ce qu'elle a coûté à \
                la dernière analyse. Une règle qui porte ratioPlancher pénalise presque tout ce qu'elle \
                évalue : ses points s'expliquent par une donnée absente du référentiel, pas par son \
                poids.
                2. consulter_historique_ponderation : ce qui a déjà été essayé, quand, et sous quel \
                dosage chaque résolution a tourné. C'est une juxtaposition, pas une causalité — le \
                référentiel a pu bouger entre deux.
                3. modifier_poids_contrainte, activer_contrainte ou desactiver_contrainte, une règle \
                à la fois et après mon accord. Le niveau HARD, MEDIUM ou SOFT ne se règle pas ; seul \
                le poids, à niveau égal. Une règle légale désactivée doit être une décision que je \
                prends en connaissance de cause : dis-le-moi en toutes lettres avant.
                4. consulter_parametres_solveur : la durée d'une résolution et l'arrêt sur plateau. \
                modifier_parametres_solveur après mon accord, sous les plafonds de l'instance — un \
                budget plus long ne corrige pas un manque de monde (analyser_effectifs).
                5. Rappelle-moi que rien de tout cela ne change le planning en place : le nouveau \
                dosage vaut pour la prochaine résolution, et diagnostiquer_plan relit dès maintenant \
                le plan persisté sous ce dosage.""".formatted(suffixe(edition), editionFrame(edition)));
    }

    @Prompt(
            name = "verifier_les_arrivees_groupees",
            description = "Vérifier que les animateurs qui viennent ensemble — covoiturage — arrivent et "
                    + "repartent ensemble dans le planning résolu.")
    PromptMessage checkGroupedArrivals(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Vérifie les arrivées groupées%s : ceux qui viennent ensemble doivent pouvoir arriver \
                et repartir ensemble.

                %s

                1. lister_demandes_covoiturage : les demandes EN_ATTENTE ne pèsent rien tant qu'elles \
                ne sont pas validées, depuis l'onglet Covoiturage de l'écran Disponibilités. Signale \
                celles dont les membres ne se sont pas tous nommés, ou dont les jours d'indisponibilité \
                divergent.
                2. analyser_arrivees_groupees sur le planning résolu : jour par jour, qui travaille, \
                qui reste sans poste alors qu'un autre membre travaille, et l'écart entre les arrivées \
                et entre les départs. Classe les jours non alignés du plus grand écart au plus petit.
                3. consulter_parametres_qualite : quelle tolérance d'arrivée groupée l'édition \
                accepte-t-elle ? modifier_parametres_qualite seulement après mon accord, et en me \
                disant que la tolérance ne vaut qu'à la prochaine résolution.
                4. analyser_enchainements : un membre du groupe qui court d'un emplacement à l'autre \
                entre deux postes arrive en retard pour tout le monde.
                5. Conclus par les groupes à revoir, et pour chacun ce qui le corrigerait : une \
                résolution, une tolérance, ou une conversation avec les intéressés.

                Les animateurs se désignent par leur id.""".formatted(suffixe(edition), editionFrame(edition)));
    }

    @Prompt(
            name = "relire_et_valider_les_journees",
            description = "Relire le planning journée par journée et marquer chacune « relue et acceptée », "
                    + "avec ou sans verrou.")
    PromptMessage reviewAndValidateDays(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Aide-moi à relire le planning%s journée par journée.

                %s

                1. lister_validations_journee : où en est la relecture (« 3 journées sur 12 »), et \
                quelles journées restent à relire. etat_edition signale celles des sept jours à venir.
                2. Pour chaque journée à relire, consulter_prerequis_validation : écarts durs, sièges \
                vides, pauses sans relais, postes irremplaçables, comptés sur cette seule journée.
                3. changements_journee pour la même date : ce qui a bougé depuis la dernière \
                publication (reference=publication), ou depuis la dernière résolution \
                (reference=resolution). referenceDisponible faux veut dire « rien à comparer », pas \
                « aucun changement ».
                4. ajouter_validation_journee après mon accord, une journée à la fois. poserVerrou est \
                une décision de plus : il fige la journée pour les prochaines résolutions — ne le pose \
                que si je le demande.
                5. retirer_validation_journee si je reviens sur une relecture : le verrou éventuel \
                reste en place, il se lève à part (deverrouiller).

                Rappelle-moi qu'une validation n'est pas un verrou : une résolution qui recalcule la \
                journée la retire, une consigne posée sur la date aussi.""".formatted(suffixe(edition), editionFrame(edition)));
    }

    @Prompt(
            name = "verrouiller_ce_qui_tient",
            description =
                    "Figer ce qui est déjà bon avant de relancer une résolution, et dire ce que le " + "verrou coûte.")
    PromptMessage lockWhatHolds(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Aide-moi à figer ce qui tient déjà dans le planning%s avant de relancer.

                %s

                1. etat_planning puis diagnostiquer_plan : sur quoi porte le verrou, et ce plan \
                est-il bon au point de mériter d'être figé ? Un verrou posé sur une mauvaise \
                affectation la rend définitive — c'est l'ordre « résoudre, vérifier, puis \
                verrouiller », jamais l'inverse.
                2. lister_verrouillages : qu'est-ce qui est déjà gelé ? Un verrou oublié d'une \
                campagne précédente explique bien des résultats incompréhensibles.
                3. synthese_affectations et lister_affectations pour repérer ce qui est manifestement \
                bon : une journée entière pourvue, un stand tenu par les bonnes personnes, un \
                animateur dont la semaine est équilibrée.
                4. Pour une journée entière relue, ajouter_validation_journee avec poserVerrou la \
                marque « relue et acceptée » et la fige d'un même geste (lister_validations_journee \
                dit lesquelles le sont déjà). Pour le reste, verrouiller, une cible à la fois. Dans \
                les deux cas après mon accord, en me disant chaque fois ce que le verrou soustrait au \
                solveur : ces personnes-là sont figées et leurs heures consommées, ce qui réduit \
                l'espace de recherche mais aussi les échanges possibles.
                5. Ne confonds pas verrouiller et figer_referentiel : le premier fige des sièges du \
                planning, le second gèle des fiches du référentiel (stands, créneaux…) et n'empêche \
                aucune résolution de déplacer qui que ce soit.
                6. Relance ensuite par le prompt resoudre_sans_perdre_le_planning, et rappelle-moi que \
                deverrouiller existe : un verrou est une décision, pas un fait acquis.""".formatted(suffixe(edition), editionFrame(edition)));
    }

    @Prompt(
            name = "preparer_un_plan_de_repli",
            description = "Préparer un plan de repli — canicule, orage, arrêté — par une consigne datée dans "
                    + "l'édition en cours : la chiffrer, la poser, résoudre et publier, ou revenir en arrière.")
    PromptMessage prepareAFallbackPlan(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Prépare un plan de repli%s : une consigne qui ferme une bande horaire à tous les \
                stands, sur une ou plusieurs dates à venir, et rouvre ceux qui compensent.

                %s

                Le plan de repli vit dans cette édition-ci : n'appelle ni dupliquer_edition ni \
                activer_edition, et ne change pas d'édition en cours de route.

                1. lister_consignes : ce qui est déjà posé, et les préréglages (« Plan canicule ») \
                dont on peut partir.
                2. Si le scénario risque de revenir, definir_prereglage_consigne le mémorise — bande, \
                motif, fenêtres de compensation —, après mon accord. Rien n'est posé par là.
                3. consulter_preselection_consigne pour la date et la bande : ce que la bande prend à \
                chaque stand, et ceux qui sont proposés pour rouvrir.
                4. simuler_consigne : par jour, sièges et minutes avant et après, vacations qui \
                perdent leurs sièges, personnes assises dans la bande, validations de relecture \
                retirées, verrous touchés. C'est ce chiffre-là qu'on regarde, pas une intuition.
                5. appliquer_consigne seulement après mon accord explicite. Une consigne ne se pose \
                que pour une date à venir, jamais pour aujourd'hui ; prolonger une alerte, c'est le \
                même appel avec les dates ajoutées.
                6. resoudre_incremental sur les jours touchés : le reste du planning ne bouge pas.
                7. diagnostiquer_plan : le plan de repli tient-il les règles dures ?
                8. consulter_prerequis_validation pour chaque journée touchée : la consigne a retiré \
                leur validation, elles sont à relire.
                9. etat_publication, puis publier_planning seulement après mon accord — sans quoi les \
                animateurs continuent de lire le plan nominal.

                Retour arrière, si l'alerte est levée : simuler_levee_consigne montre ce qui serait \
                retiré et qui y est assis, lever_consigne après mon accord, puis resoudre_incremental \
                — la règle de stabilité rend les créneaux à leurs titulaires. Un jour déjà travaillé \
                garde la consigne qui l'a gouverné.""".formatted(suffixe(edition), emittingEditionFrame(edition)));
    }

    @Prompt(
            name = "auditer_avant_diffusion",
            description = "Contrôler avant de diffuser : l'édition, ce qui part aux animateurs, ce que le "
                    + "planning dit du cadre légal, et ce qui reste à relire.")
    PromptMessage auditBeforeRelease(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Contrôle %s avant qu'elle ne parte aux animateurs. Ne publie rien.

                %s

                1. lister_editions : cette édition est-elle l'ACTIVE ? Seule l'active publie ; sinon, \
                c'est la première chose à me dire, avant tout le reste.
                2. lister_contraintes : quelles règles sont désactivées ou dosées à zéro ? Une règle \
                légale désactivée doit être une décision assumée et datée, pas un reste de séance de \
                diagnostic. Nomme-les toutes, même celles qui semblent inoffensives.
                3. diagnostiquer_plan : le plan persisté est-il faisable, et sur quelles règles \
                reste-t-il en défaut ? Une règle de temps de travail encore en défaut se traite \
                avant diffusion, pas après.
                4. consulter_parametres_legaux, puis analyser_pauses avec sansRelaisSeulement : une \
                pause que personne ne peut relayer est une pause qui n'aura pas lieu.
                5. heures_travaillees, puis equite_planning : y a-t-il des semaines au-dessus du \
                plafond, ou des écarts que personne n'a arbitrés — heures de soirée, de week-end, \
                postes pénibles, souhaits jamais satisfaits ? L'équité se lit ici, pas dans le score.
                6. analyser_enchainements : des postes qui se suivent sur deux emplacements trop \
                éloignés pour le battement. analyser_arrivees_groupees : des covoiturages que le \
                planning sépare.
                7. lister_consignes : une consigne posée change les horaires de toute une journée — \
                le planning doit avoir été résolu depuis.
                8. lister_validations_journee : quelles journées n'ont été relues par personne ?
                9. lister_destinataires_publication : qui a reçu quoi la dernière fois. Signale les \
                personnes sans adresse connue — elles devront être prévenues autrement — et rappelle \
                que le courriel porte le planning individuel, donc des données personnelles.
                10. etat_sauvegardes : une sauvegarde récente existe-t-elle avant une diffusion qui \
                engage l'organisation ?

                Conclus par une liste de ce qui bloque la diffusion et une liste de ce qui mérite \
                seulement d'être su. Les personnes se désignent par leur id : ni nom ni adresse ne \
                sortent d'ici.""".formatted(designation(edition), editionFrame(edition)));
    }

    @Prompt(
            name = "reprendre_apres_un_changement_tardif",
            description =
                    "Encaisser un changement tardif — désistement, stand fermé, arrêté — sans tout " + "recalculer.")
    PromptMessage recoverFromLateChange(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Un changement tardif est arrivé sur le planning%s. Reprends-le sans repartir de zéro.

                %s

                1. capturer_instantane d'abord : ce qui suit modifie un planning que des animateurs \
                ont peut-être déjà lu.
                2. Applique le changement, après mon accord :
                   - un désistement : modifier_animateur, pour le jour indisponible ;
                   - un seul stand qui ferme à une date à venir : ajouter_fermeture_stand sur ce stand. \
                Il est refusé quand la famille STANDS est figée (etat_edition dit lesquelles le sont) : \
                dis-le-moi, lever_gel ne se fait que si je le demande ;
                   - une bande horaire fermée à tous les stands (arrêté, alerte météo) : une consigne — \
                simuler_consigne puis appliquer_consigne. Elle ferme la bande pour tous les stands et ne \
                rouvre que ceux qu'elle nomme : ce n'est pas l'outil pour fermer un seul stand. Elle \
                reste possible quand le référentiel est figé.
                   Ni l'un ni l'autre ne se pose pour aujourd'hui : le jour même, c'est le prompt \
                tenir_le_jour_j.
                3. resoudre_incremental plutôt que lancer_solveur : il repart du planning enregistré, \
                fige ce qui reste valable et ne recalcule que ce que le changement a invalidé. \
                Quelques dizaines de secondes au lieu de plusieurs minutes, et surtout un planning \
                que les animateurs reconnaissent encore.
                4. changements_journee pour chaque journée touchée, avec reference=publication : \
                siège par siège et personne par personne, qui gagne, perd ou change de vacation par \
                rapport à ce qui a été annoncé. Ce sont exactement les gens à prévenir.
                5. etat_publication puis publier_planning après mon accord — sans quoi les animateurs \
                continuent de lire la version d'avant.

                Si le changement touche beaucoup de monde, dis-le-moi plutôt que de l'appliquer : une \
                résolution complète est parfois le bon choix, mais c'est une décision.""".formatted(suffixe(edition), emittingEditionFrame(edition)));
    }

    @Prompt(
            name = "tenir_le_jour_j",
            description = "Le jour même : trouver qui peut reprendre les postes d'un absent, n'appliquer que "
                    + "ce qui est décidé, et prévenir par une publication.")
    PromptMessage runTheDay(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Quelqu'un ne s'est pas présenté aujourd'hui%s. Aide-moi à recouvrir ses postes.

                %s

                1. resultats_animateur pour la personne : ses postes, par id de stand et de créneau ; \
                garde ceux d'aujourd'hui. changements_journee sur la date du jour dit aussi ce qui a \
                déjà bougé depuis la publication. Un créneau passé ne se rattrape pas : le passé ne \
                se modifie plus.
                2. Pour chaque poste restant, suggerer_reparations : qui pourrait le prendre sans \
                casser une règle dure. La liste est bornée ; elle dit combien de candidats ont été \
                évalués, et une liste courte n'est pas une preuve qu'il n'y a personne d'autre.
                3. simuler_deplacement avant d'écrire quoi que ce soit : le poste de l'absent déposé \
                sur le remplaçant, l'effet sur tout le planning chiffré plutôt qu'espéré. \
                casseContrainteDure vrai veut dire que le déplacement sera refusé.
                4. deplacer_affectation seulement après mon accord, poste par poste.
                5. Ne relance pas de résolution : le jour J, un planning que tout le monde a lu vaut \
                mieux qu'un planning meilleur que personne n'attend. Et ne pose pas de consigne : elle \
                ne vaut que pour une date à venir, jamais pour aujourd'hui.
                6. Quand tout est arbitré, etat_publication dit qui verrait un changement. Seul \
                publier_planning envoie le nouveau planning, et seulement après mon accord. \
                N'appelle pas envoyer_planning_animateur pour prévenir un remplaçant : il renvoie le \
                planning déjà publié, donc l'ancien. Un appel téléphonique va souvent plus vite.

                Marquer quelqu'un absent pour la suite de la journée se fait sur l'écran \
                « Aujourd'hui », pas ici : ces outils déplacent des affectations, ils n'enregistrent \
                pas l'absence elle-même. Les animateurs se désignent par leur id.""".formatted(suffixe(edition), emittingEditionFrame(edition)));
    }

    @Prompt(
            name = "tirer_le_bilan_de_l_evenement",
            description = "Une fois l'événement passé : l'écart entre le publié et le tenu, la comparaison avec "
                    + "les éditions précédentes, et ce que l'équité a réellement donné.")
    PromptMessage reviewTheEvent(@PromptArg(description = EDITION, required = false) String edition) {
        return PromptMessage.withUserRole("""
                Tire le bilan de %s, sans rien modifier.

                %s

                1. realise_vs_planifie : par stand et par journée écoulée, les sièges publiés, tenus, \
                vides, les absences et les remplacements, et les minutes perdues. Une journée \
                commencée avant toute publication est montrée mais exclue des totaux : dis-le si c'est \
                le cas.
                2. lister_kpi_historique : score, couverture des postes, heures et violations par \
                contrainte, résolution par résolution et toutes éditions confondues — compare la \
                dernière résolution de cette édition à celle de l'édition précédente.
                3. consulter_historique_ponderation : quels réglages ont changé en cours de route, et \
                sous quel dosage chaque résolution a tourné. Une juxtaposition, pas une causalité.
                4. equite_planning et heures_travaillees : les écarts d'heures, de soirées, de \
                week-ends et de postes pénibles, que l'organisation doit pouvoir expliquer.
                5. synthese_confirmations et consulter_foire_echanges : combien ont accusé réception, \
                et ce que la foire aux échanges a donné.
                6. Conclus par trois listes courtes : ce qui a tenu, ce qui a coûté, et ce qu'il faudra \
                régler autrement pour l'édition suivante — recrutement, formation, horaires, \
                pondérations.

                Les animateurs se désignent par leur id : un bilan se lit en comptes, jamais en \
                noms.""".formatted(reviewedEdition(edition), reviewEditionFrame(edition)));
    }

    /**
     * The prompts as the MCP page shows them: name, what each is for, and the
     * text itself, built without an edition — the text then tells the
     * assistant to take the active one.
     *
     * <p>The description comes from the annotation rather than from a second
     * copy here — one text, one place, whichever way a client reaches it.</p>
     */
    public List<PromptExpose> catalogue() {
        return ORDRE.stream().map(this::expose).toList();
    }

    private PromptExpose expose(String nom) {
        Method methode = Arrays.stream(McpPrompts.class.getDeclaredMethods())
                .filter(candidate -> candidate.isAnnotationPresent(Prompt.class))
                .filter(candidate -> FeatureNames.of(candidate).equals(nom))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Prompt " + nom + " introuvable"));
        try {
            PromptMessage message = (PromptMessage) methode.invoke(this, (String) null);
            return new PromptExpose(
                    nom,
                    methode.getAnnotation(Prompt.class).description(),
                    message.content().asText().text());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Prompt " + nom + " non appelable", e);
        }
    }

    /** One prompt, as the interface displays it. */
    public record PromptExpose(String nom, String description, String texte) {}

    /**
     * How the assistant picks the edition and carries it: the one it was
     * given, or the active one read from {@code lister_editions} — and in
     * both cases on every tool call, since a call without it is refused.
     */
    private static String editionFrame(String edition) {
        if (edition == null || edition.isBlank()) {
            return "Édition : appelle d'abord lister_editions et retiens celle marquée ACTIVE ; s'il n'y en "
                    + "a aucune, pose-moi la question plutôt que d'en choisir une. Passe son id en argument "
                    + "edition à chaque outil qui en prend un : un appel sans édition est refusé.";
        }
        return "Édition : « " + edition.trim() + " ». Passe-la en argument edition à chaque outil qui en "
                + "prend un : un appel sans édition est refusé.";
    }

    /**
     * The frame of {@code demarrer_une_nouvelle_edition}, whose argument is the
     * <b>source</b> of a duplication rather than the edition to write in: once
     * the new edition exists, its id is what every later call carries.
     */
    private static String newEditionFrame(String edition) {
        String source = edition == null || edition.isBlank()
                ? "appelle d'abord lister_editions et retiens celle marquée ACTIVE ; s'il n'y en a aucune, "
                        + "pose-moi la question plutôt que d'en choisir une"
                : "« " + edition.trim() + " »";
        return "Édition source : " + source + ". Elle se passe en argument source de dupliquer_edition, et "
                + "n'est jamais celle que tu renommes ou actives. Une fois la nouvelle édition créée, passe "
                + "son id en argument edition à chaque outil qui en prend un : un appel sans édition est "
                + "refusé.";
    }

    /**
     * The frame of {@code tirer_le_bilan_de_l_evenement}: an event under review
     * is usually over, its edition often deactivated or already replaced by
     * the next one, so without an argument the active edition is only a guess
     * to confirm.
     */
    private static String reviewEditionFrame(String edition) {
        if (edition == null || edition.isBlank()) {
            return "Édition : appelle d'abord lister_editions. Un bilan porte d'ordinaire sur une édition "
                    + "terminée, qui n'est souvent plus l'active : propose-moi celle dont l'événement vient de "
                    + "finir et attends ma réponse avant l'étape 1. Passe son id en argument edition à chaque "
                    + "outil qui en prend un : un appel sans édition est refusé.";
        }
        return editionFrame(edition);
    }

    /** The edition under review, as the subject of a sentence. */
    private static String reviewedEdition(String edition) {
        return edition == null || edition.isBlank()
                ? "l'édition dont l'événement vient de finir"
                : "l'édition « " + edition.trim() + " »";
    }

    /** {@link #editionFrame}, plus the check a prompt that publishes or mails must make first. */
    private static String emittingEditionFrame(String edition) {
        return editionFrame(edition) + " " + ACTIVE_EDITION_CHECK;
    }

    /** Names the edition inside the sentence, or says nothing when the frame tells how to pick it. */
    private static String suffixe(String edition) {
        return edition == null || edition.isBlank() ? "" : " de l'édition « " + edition.trim() + " »";
    }

    /** The edition as the subject of a sentence, where saying nothing would leave a hole. */
    private static String designation(String edition) {
        return edition == null || edition.isBlank() ? "l'édition active" : "l'édition « " + edition.trim() + " »";
    }
}
