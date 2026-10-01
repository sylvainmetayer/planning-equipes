package dev.sylvain.planning.service;

import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.service.edition.EditionRepository;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Answers the one question every reference-data query needs: <b>which edition
 * am I reading and writing?</b> (see {@code docs/decisions/0001-cloisonnement-par-edition.md} §5).
 *
 * <p>The client designates it per request through the {@code X-Edition-Id}
 * header — not a global {@code actif} flag in the database, which would force
 * every open tab to share one edition and turn switching into a write visible
 * to every other user. Two browser tabs can therefore sit on two different
 * editions at the same time.</p>
 *
 * <p>Resolution order:</p>
 * <ol>
 * <li>an explicit override bound to the current thread, for work that outlives
 * its request — see {@link #executeIn};</li>
 * <li>the edition of the espace token the request carries;</li>
 * <li>the {@code X-Edition-Id} of the request being served — refused when it
 * names no edition ({@code EDITION_INCONNUE});</li>
 * <li>nothing else for a client request: one that names no edition is refused
 * ({@code EDITION_REQUISE}, ADR 0072). There is no default edition to fall
 * back on any more — answering "the default one" is how a tab left on a
 * deleted edition, or an assistant that forgot its argument, wrote into an
 * edition nobody chose.</li>
 * </ol>
 *
 * <p>A request context no client opened — the scope the MCP transport
 * activates around a tool that is not edition-targeted, the one the test
 * harness activates around a test method — names no edition and cannot: it
 * resolves to the <b>active</b> edition, and is refused when there is none.
 * An edition-targeted MCP tool never gets there: its interceptor refuses a
 * call without its {@code edition} argument first.</p>
 */
@ApplicationScoped
public class EditionContext {

    public static final String HEADER = "X-Edition-Id";

    /**
     * Edition bound to the current thread, overriding the request header. Used
     * by work that runs outside (or beyond) the request that triggered it — a
     * solver job keeps writing to the edition it was launched for even if the
     * browser has since switched.
     */
    private static final ThreadLocal<String> OVERRIDE = new ThreadLocal<>();

    private final EditionRepository editionRepository;

    private final EditionRequestScope requestScope;

    @Inject
    public EditionContext(EditionRepository editionRepository, EditionRequestScope requestScope) {
        this.editionRepository = editionRepository;
        this.requestScope = requestScope;
    }

    /**
     * Known edition ids and the default one, both cached: they are read on
     * every single reference-data query, change only through the
     * {@code /api/editions} endpoints, and this is a single-instance
     * application — so {@link #invaliderCache()} on those few writes is
     * enough.
     */
    private volatile Set<String> idsConnus;

    /**
     * The active edition, cached like the ids. A holder rather than a bare
     * {@code Optional}: {@code null} means "not read yet", and an empty
     * holder "read, and none is active".
     */
    private volatile ActiveCache activeId;

    /**
     * Bumped by every {@link #invaliderCache()}: a load started before an
     * invalidation does not store what it read, or a value read just before
     * an activation committed would outlive it until the next invalidation.
     */
    private final AtomicLong generation = new AtomicLong();

    private record ActiveCache(Optional<String> id) {}

    /**
     * Edition the current call reads and writes. Never {@code null}, and never a
     * guess: outside a request and outside {@link #executeIn}, it <b>throws</b>.
     *
     * <p>A fallback on the default edition used to apply everywhere, which made
     * every unwrapped thread hop — a {@code Multi.emitOn}, a
     * {@code CompletableFuture}, a parallel stream — write into the default
     * edition without a sound. It is the exact bug the javadoc of
     * {@link JdbcEditionScope} tells the story of. The fallback inside a request
     * went too (ADR 0072): a client request that names no edition, or one that
     * no longer exists, is refused rather than answered from another edition.</p>
     */
    public String editionIdCourant() {
        String override = OVERRIDE.get();
        if (override != null) {
            return override;
        }
        if (!servingRequest()) {
            throw new IllegalStateException(
                    "Aucune édition dans le contexte : ce code tourne hors requête et hors executeIn. "
                            + "Enveloppez-le dans editionContext.executeIn(editionId, …) — sans cela il "
                            + "ne saurait pas dans quelle édition écrire.");
        }
        String imposee = editionForcedByToken();
        if (imposee != null) {
            return imposee;
        }
        String demande = editionIdDemande();
        if (demande != null) {
            if (!idsConnus().contains(demande)) {
                throw new BusinessError.EditionRefused(
                        BusinessError.EditionRefused.Reason.INCONNUE,
                        "L'édition « " + demande + " » n'existe pas (ou plus) : choisissez-en une autre.");
            }
            return demande;
        }
        if (requestScope.isClientRequest()) {
            throw new BusinessError.EditionRefused(
                    BusinessError.EditionRefused.Reason.REQUISE,
                    "Aucune édition désignée : la requête doit porter l'en-tête " + HEADER + ".");
        }
        return activeEditionId()
                .orElseThrow(() -> new BusinessError.EditionRefused(
                        BusinessError.EditionRefused.Reason.REQUISE,
                        "Aucune édition désignée, et aucune édition n'est active."));
    }

    /**
     * The edition allowed to reach outside — publish, send mail, open the
     * espace, the ICS feed and the wall display (ADR 0072). Empty between two
     * events.
     */
    public Optional<String> activeEditionId() {
        ActiveCache cache = activeId;
        if (cache == null) {
            long seen = generation.get();
            cache = new ActiveCache(editionRepository.activeEditionId());
            if (generation.get() == seen) {
                activeId = cache;
            }
        }
        return cache.id();
    }

    /** Whether {@code editionId} is the active edition. */
    public boolean isActive(String editionId) {
        return editionId != null && activeEditionId().filter(editionId::equals).isPresent();
    }

    /**
     * Whether a request is being served. {@code Arc.container()} is null-checked
     * because unit tests instantiate this class outside a CDI container.
     */
    private static boolean servingRequest() {
        var container = Arc.container();
        return container != null && container.requestContext().isActive();
    }

    /**
     * Runs {@code work} as if the request had designated {@code editionId}. The
     * previous binding is restored afterwards, so nesting and thread reuse in a
     * pool are both safe.
     */
    public <T> T executeIn(String editionId, Callable<T> work) {
        // Without this check, executeIn(null, …) is an empty wrapper: the call
        // falls through to editionIdCourant's throw, deep inside a repository,
        // far from the site that believed it had wrapped.
        Objects.requireNonNull(editionId, "editionId");
        String precedent = OVERRIDE.get();
        OVERRIDE.set(editionId);
        try {
            return work.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to run work in edition " + editionId, e);
        } finally {
            if (precedent != null) {
                OVERRIDE.set(precedent);
            } else {
                OVERRIDE.remove();
            }
        }
    }

    /** Same as {@link #executeIn(String, Callable)} for work returning nothing. */
    public void executeIn(String editionId, Runnable work) {
        executeIn(editionId, () -> {
            work.run();
            return null;
        });
    }

    /** Must be called whenever an edition is created, deleted, activated or deactivated. */
    public void invaliderCache() {
        generation.incrementAndGet();
        idsConnus = null;
        activeId = null;
    }

    /** The {@code X-Edition-Id} of the request being served, or {@code null}. */
    private String editionIdDemande() {
        String demande = requestScope.getEditionIdDemande();
        return demande == null || demande.isBlank() ? null : demande;
    }

    /**
     * Edition of the espace token the request carries, resolved by the espace
     * guards — it overrides the header (the espace never trusts it) and needs
     * no validation against the known ids: it comes from the animateur row
     * itself. {@code null} off the espace routes or outside any request.
     */
    private String editionForcedByToken() {
        var owner = requestScope.getTokenOwner();
        return owner == null ? null : owner.editionId();
    }

    private Set<String> idsConnus() {
        Set<String> cache = idsConnus;
        if (cache == null) {
            long seen = generation.get();
            cache = editionRepository.listEditions().stream()
                    .map(Edition::getId)
                    .collect(Collectors.toUnmodifiableSet());
            if (generation.get() == seen) {
                idsConnus = cache;
            }
        }
        return cache;
    }
}
