package dev.sylvain.planning.service.espace;

import java.time.LocalDate;
import java.util.List;

/**
 * Whether the foire au planning works, over one period: how many swap requests,
 * how many went through, who held them up — the colleague or the organisation —
 * and how long each took. Aggregates only: no name, no animateur id; the
 * nominative list stays the list of swap requests.
 *
 * <p>A rate travels as its numerator and its denominator rather than a
 * percentage, so the screen can say « 2 sur 3 » when the population is small
 * enough for a percentage to lie.</p>
 *
 * @param du                    first creation day counted, in {@code fuseau}; {@code null} = unbounded
 * @param au                    last creation day counted, in {@code fuseau}; {@code null} = unbounded
 * @param fenetreFoire          true when the period is the configured foire window, applied by default
 * @param fuseau                the zone the creation days are cut in — the nightly jobs' zone
 * @param creees                swap requests created in the period, whatever became of them
 * @param dirigees              of which directed: the demandeur named the seat wanted in return
 * @param enAttenteCible        still waiting for the targeted colleague
 * @param enAttenteOrganisation agreed by the colleague, waiting for the organisation
 * @param accordCollegues       agreed by the colleague, over the requests the colleague answered —
 *                              read off {@code cibleDecideLe}, so a refusal by the organisation
 *                              before any answer is in neither, and a request agreed then
 *                              withdrawn is in both
 * @param acceptationOrganisation accepted, over the requests the organisation decided — a
 *                              withdrawal is never a decision of the organisation
 * @param aboutissement         accepted, over every finished request, withdrawals included
 * @param prevalidees           prevalidated at submission, over the requests created
 * @param acceptationNonPrevalidees accepted, over the decided requests whose prevalidation failed
 * @param delaiReponseCollegue  creation to the colleague's answer
 * @param delaiArbitrage        colleague's agreement to the organisation's decision; a refusal
 *                              before any answer has no start and is left out
 * @param delaiCommunication    organisation's decision to the publication announcing it
 * @param delaiAnnulation       creation to the demandeur's withdrawal
 * @param parJourCreation       one entry per day from the first creation day to the last, empty days included
 * @param parJourEvenement      per date of the seat given up, days without a request left out
 * @param creneauRetire         requests whose timeslot no longer exists, outside {@code parJourEvenement}
 * @param parStand              per stand given up, most requested first
 * @param contraintesViolees    the hard constraints the non-prevalidated requests broke, most frequent first
 */
public record EchangeStatistics(
        LocalDate du,
        LocalDate au,
        boolean fenetreFoire,
        String fuseau,
        int creees,
        int dirigees,
        int enAttenteCible,
        int enAttenteOrganisation,
        int acceptees,
        int refusees,
        int refuseesCible,
        int annulees,
        EchangeRate accordCollegues,
        EchangeRate acceptationOrganisation,
        EchangeRate aboutissement,
        EchangeRate prevalidees,
        EchangeRate acceptationNonPrevalidees,
        EchangeDelay delaiReponseCollegue,
        EchangeDelay delaiArbitrage,
        EchangeDelay delaiCommunication,
        EchangeDelay delaiAnnulation,
        List<EchangeDayCount> parJourCreation,
        List<EchangeDayCount> parJourEvenement,
        int creneauRetire,
        List<EchangeStandCount> parStand,
        List<EchangeConstraintCount> contraintesViolees) {

    /** The same statistics, with the constraint lines replaced — what MCP sends after anonymising them. */
    public EchangeStatistics withContraintesViolees(List<EchangeConstraintCount> lignes) {
        return new EchangeStatistics(
                du,
                au,
                fenetreFoire,
                fuseau,
                creees,
                dirigees,
                enAttenteCible,
                enAttenteOrganisation,
                acceptees,
                refusees,
                refuseesCible,
                annulees,
                accordCollegues,
                acceptationOrganisation,
                aboutissement,
                prevalidees,
                acceptationNonPrevalidees,
                delaiReponseCollegue,
                delaiArbitrage,
                delaiCommunication,
                delaiAnnulation,
                parJourCreation,
                parJourEvenement,
                creneauRetire,
                parStand,
                lignes);
    }

    /** A rate, as the two counts it is made of; the denominator may be zero. */
    public record EchangeRate(int numerateur, int denominateur) {}

    /**
     * One delay over the requests carrying both of its timestamps, in seconds.
     *
     * @param mesurees       the requests the figures are computed over
     * @param sansHorodatage requests the delay concerns but that lack one of its two timestamps
     *                       (older than the column) or carry them in the wrong order: counted in
     *                       the volumes, left out of the figures
     * @param medianeSecondes the typical delay; {@code null} when nothing was measured
     * @param centile90Secondes the tail: nine requests out of ten were faster; {@code null} likewise
     * @param moyenneSecondes the mean, which a few forgotten requests drag up; {@code null} likewise
     */
    public record EchangeDelay(
            int mesurees, int sansHorodatage, Long medianeSecondes, Long centile90Secondes, Long moyenneSecondes) {}

    /** How many requests fall on one day. */
    public record EchangeDayCount(LocalDate jour, int nombre) {}

    /** How many requests give up a seat of one stand; {@code standNom} is {@code null} for a stand since deleted. */
    public record EchangeStandCount(String standId, String standNom, int nombre) {}

    /** How many non-prevalidated requests broke one hard constraint, by its business description. */
    public record EchangeConstraintCount(String contrainte, int nombre) {}
}
