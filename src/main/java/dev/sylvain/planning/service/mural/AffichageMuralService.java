package dev.sylvain.planning.service.mural;

import com.google.zxing.common.BitMatrix;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.analyse.PauseAnalyzer;
import dev.sylvain.planning.service.analyse.PauseAnalyzer.RapportPauses;
import dev.sylvain.planning.service.consigne.ConsigneService;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.espace.JourJClock;
import dev.sylvain.planning.service.export.QrCodeEspace;
import dev.sylvain.planning.service.mural.AffichageMuralRepository.ResolvedLink;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The wall display of the control room: the links that open it, and the view
 * they open.
 *
 * <p><b>A dedicated token rather than an admin session</b> (ADR 0053). A
 * television stays on for days in a room volunteers walk through; an admin
 * session on it — refreshed by the screen's own polling — would be write
 * access to the whole planning left on a wall. The token opens one read, for
 * one edition, and is revoked from the Paramètres screen.</p>
 *
 * <p>The token is 256 random bits, handed out once and stored as its SHA-256
 * only, like the espace sessions: a copy of the database reopens no screen.
 * It resolves its edition on its own, since a public URL carries no edition
 * header anybody may believe.</p>
 *
 * <p><b>The persisted plan, not the published one</b>: the control room must
 * see the replacements made on the mode jour J screen before anybody
 * republishes. And « now » is the server's ({@link JourJClock}, simulated date
 * included) — the television's clock is never asked.</p>
 */
@ApplicationScoped
public class AffichageMuralService {

    /** A label an operator can read on a list; longer is a paste accident. */
    static final int LIBELLE_MAX = 120;

    private static final int TOKEN_BYTES = 32;

    private final AffichageMuralRepository repository;

    private final EditionContext editionContext;

    private final ReferenceDataService referenceDataService;

    private final PlanningPersistenceService persistenceService;

    private final PauseAnalyzer pauseAnalyzer;

    private final ConsigneService consigneService;

    private final JourJClock clock;

    private final EditionService editionService;

    private final dev.sylvain.planning.service.publication.PlanPublieService planPublieService;

    private final dev.sylvain.planning.service.publication.PublicationDiffService diffService;

    private final SecureRandom random = new SecureRandom();

    @Inject
    public AffichageMuralService(
            AffichageMuralRepository repository,
            EditionContext editionContext,
            ReferenceDataService referenceDataService,
            PlanningPersistenceService persistenceService,
            PauseAnalyzer pauseAnalyzer,
            ConsigneService consigneService,
            JourJClock clock,
            EditionService editionService,
            dev.sylvain.planning.service.publication.PlanPublieService planPublieService,
            dev.sylvain.planning.service.publication.PublicationDiffService diffService) {
        this.repository = repository;
        this.editionContext = editionContext;
        this.referenceDataService = referenceDataService;
        this.persistenceService = persistenceService;
        this.pauseAnalyzer = pauseAnalyzer;
        this.consigneService = consigneService;
        this.clock = clock;
        this.editionService = editionService;
        this.planPublieService = planPublieService;
        this.diffService = diffService;
    }

    /* ------------------------------- Admin ------------------------------- */

    /** The live links of the current edition. */
    public List<AffichageMuralLink> list() {
        return repository.list();
    }

    /**
     * Creates a link for the current edition and hands its token out — the
     * one time it is ever readable.
     *
     * @throws BusinessError.Invalid on a blank or overlong label, or a
     *                               location the edition does not hold
     */
    public CreatedAffichageMuralLink create(AffichageMuralLinkRequest request) {
        String libelle = request == null || request.libelle() == null
                ? ""
                : request.libelle().trim();
        if (libelle.isEmpty()) {
            throw new BusinessError.Invalid("Donnez un libellé au lien, pour le reconnaître dans la liste.");
        }
        if (libelle.length() > LIBELLE_MAX) {
            throw new BusinessError.Invalid("Le libellé dépasse " + LIBELLE_MAX + " caractères.");
        }
        List<String> emplacements = knownEmplacements(request.emplacements());
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        AffichageMuralLink link =
                repository.insert(libelle, Boolean.TRUE.equals(request.fullNames()), emplacements, hash(token));
        return new CreatedAffichageMuralLink(link, token);
    }

    /**
     * Revokes a link: the screen showing it gets the dead-link page at its
     * next read.
     *
     * @throws BusinessError.NotFound when no live link of the edition has that id
     */
    public void revoke(long id) {
        if (!repository.revoke(id)) {
            throw new BusinessError.NotFound("Lien d'affichage mural inconnu : " + id);
        }
    }

    /**
     * The QR code of an address, for the admin to show the screen's
     * television a code rather than a keyboard.
     *
     * @throws BusinessError.Invalid when there is nothing to encode
     */
    public QrCodeView qrCode(QrCodeRequest request) {
        BitMatrix matrix = QrCodeEspace.matrix(request == null ? null : request.link());
        if (matrix == null) {
            throw new BusinessError.Invalid("Aucune adresse à encoder.");
        }
        List<String> rows = new ArrayList<>();
        for (int y = 0; y < matrix.getHeight(); y++) {
            StringBuilder row = new StringBuilder(matrix.getWidth());
            for (int x = 0; x < matrix.getWidth(); x++) {
                row.append(matrix.get(x, y) ? '1' : '0');
            }
            rows.add(row.toString());
        }
        return new QrCodeView(matrix.getWidth(), List.copyOf(rows));
    }

    /**
     * The wall view of one day of the current edition, for the admin's
     * « Imprimer cette journée »: the page a token opens, laid out for print,
     * read through the admin session instead of a token — no link is created,
     * touched or needed, and {@code /api/mural/*} keeps naming its one route.
     *
     * <p>Every stand and every full name: the admin already reads them all on
     * the Planning page, which is where this is asked from. {@code jour} null
     * is the journée under way at the server's « now ».</p>
     */
    public AffichageMuralView preview(LocalDate jour) {
        Edition edition = editionService.editionCourante();
        return build(
                edition == null ? "" : edition.getNom(),
                "",
                jour,
                AffichageMuralViewBuilder.Settings.wholeEdition(true),
                true);
    }

    /* ------------------------------- Public ------------------------------ */

    /**
     * The wall view the token opens, in the token's own edition.
     *
     * @throws BusinessError.NotFound for an unknown and a revoked token alike —
     *                               the answer must not tell them apart
     */
    public AffichageMuralView view(String token) {
        ResolvedLink link = token == null || token.isBlank() ? null : repository.resolve(hash(token));
        if (link == null) {
            throw new BusinessError.NotFound("Lien inconnu ou révoqué");
        }
        return editionContext.executeIn(link.editionId(), () -> {
            repository.touch(link.id());
            return build(link);
        });
    }

    /**
     * The token's view: the day under way, with its band of alerts, laid out
     * for the stands and the names the link opens.
     */
    private AffichageMuralView build(ResolvedLink link) {
        return build(
                link.editionNom(),
                link.libelle(),
                null,
                new AffichageMuralViewBuilder.Settings(
                        link.fullNames(),
                        link.restricted(),
                        link.restricted() ? Set.copyOf(repository.emplacementsOf(link.id())) : Set.of()),
                true);
    }

    /**
     * The day under way of the current edition, as the wall display would
     * show it for the whole edition — what the home screen's « Aujourd'hui »
     * line counts its open stands and empty seats on, so that the television
     * and the home screen cannot tell two stories about the same day. Initials
     * only: the reader counts, and names nobody.
     *
     * <p>Without the band of alerts: the break report and the consigne only
     * feed that band, never the stands or their empty seats, and the home
     * screen reads the counts alone — the analysis of three days of breaks
     * would be paid on every read of it for nothing.</p>
     */
    public AffichageMuralView currentEditionView() {
        return build(null, null, null, AffichageMuralViewBuilder.Settings.wholeEdition(false), false);
    }

    /**
     * The day under way as Aujourd'hui reads it: the wall view — its alerts,
     * the one calculation —, the people the working plan has moved since the
     * publication, and the published seats the « nouveau » holes are told
     * from. One reading of each plan for the three. {@code fullNames} for an
     * admin session, which may name.
     */
    public DayReading currentEditionReading(boolean fullNames) {
        return read(null, null, null, AffichageMuralViewBuilder.Settings.wholeEdition(fullNames), true);
    }

    /**
     * @param view             what the wall display would show
     * @param unpublishedPeople the ids of the people whose schedule differs
     *                          from the published plan
     * @param published        the published plan's seats, {@code null} before
     *                          the first publication
     */
    public record DayReading(
            AffichageMuralView view, List<String> unpublishedPeople, List<PosteAffectation> published) {}

    private AffichageMuralView build(
            String editionNom,
            String libelle,
            LocalDate jourDemande,
            AffichageMuralViewBuilder.Settings settings,
            boolean withBand) {
        return read(editionNom, libelle, jourDemande, settings, withBand).view();
    }

    /**
     * Reads the plan once and derives the rest from it: the timeslots are those
     * its seats stand on (a timeslot nobody could hold opens no stand), the
     * legal parameters and meal windows are the ones the persisted plan carries,
     * and the break report covers the day shown alone — with its neighbours,
     * since a night seat of the eve or of the morning after can relay a break.
     *
     * @param jourDemande the day to show; null for the journée under way
     * @param withBand whether to compute the band of alerts (breaks, consigne,
     *                 the holes the publication did not have, what may differ
     *                 from it) — the home screen's counts need none of it
     */
    private DayReading read(
            String editionNom,
            String libelle,
            LocalDate jourDemande,
            AffichageMuralViewBuilder.Settings settings,
            boolean withBand) {
        LocalDateTime now = clock.dateTime();
        PlanningEvenement plan = persistenceService.loadPersistedPlanning();
        List<PosteAffectation> postes = plan.getPostes() == null ? List.of() : plan.getPostes();
        List<Creneau> creneaux = postes.stream()
                .map(PosteAffectation::getCreneau)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        LocalDate jour = jourDemande == null ? AffichageMuralViewBuilder.currentDay(creneaux, now) : jourDemande;
        PlanningEvenement publie =
                !withBand || planPublieService.jamaisPublie() ? null : planPublieService.planPublie();
        List<PosteAffectation> published = publie == null ? null : publie.getPostes();
        // The publication's own diff, so « peut différer » says the number
        // the Diffuser screen would publish to.
        List<String> unpublishedPeople = withBand ? diffService.changedPeople(plan, publie) : List.of();
        AffichageMuralView view = AffichageMuralViewBuilder.build(
                new AffichageMuralViewBuilder.Inputs(
                        editionNom,
                        libelle,
                        now,
                        jour,
                        creneaux,
                        postes,
                        withBand ? breaksAround(plan, postes, jour) : null,
                        withBand ? consigneService.find(jour).orElse(null) : null,
                        published,
                        unpublishedPeople.size()),
                settings);
        return new DayReading(view, unpublishedPeople, published);
    }

    /** The break report over the seats of {@code jour} and of the days either side of it. */
    private RapportPauses breaksAround(PlanningEvenement plan, List<PosteAffectation> postes, LocalDate jour) {
        PlanningEvenement days = new PlanningEvenement();
        days.setPostes(postes.stream()
                .filter(poste -> poste.getCreneau() != null
                        && poste.getCreneau().getDate() != null
                        && !poste.getCreneau().getDate().isBefore(jour.minusDays(1))
                        && !poste.getCreneau().getDate().isAfter(jour.plusDays(1)))
                .toList());
        List<ParametresLegaux> parametres = plan.getParametresLegaux();
        return pauseAnalyzer.analyze(
                days, parametres == null || parametres.isEmpty() ? null : parametres.get(0), plan.getFenetresRepas());
    }

    /** The requested locations, each checked against the edition's; duplicates dropped, order kept. */
    private List<String> knownEmplacements(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        Set<String> known = referenceDataService.listEmplacements().stream()
                .map(Emplacement::getId)
                .collect(Collectors.toSet());
        Set<String> kept = new LinkedHashSet<>();
        for (String id : requested) {
            if (id == null || id.isBlank()) {
                continue;
            }
            if (!known.contains(id)) {
                throw new BusinessError.Invalid("Emplacement inconnu : " + id);
            }
            kept.add(id);
        }
        return List.copyOf(kept);
    }

    /** The SHA-256 of a token, in hex: what the database stores, and what the rate limiter remembers. */
    public static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
