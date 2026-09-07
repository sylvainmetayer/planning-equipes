package dev.sylvain.planning.service;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import dev.sylvain.planning.domain.Edition;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
 * <li>the {@code X-Edition-Id} of the request being served, <b>if that edition
 * exists</b>;</li>
 * <li>the edition flagged {@code defaut}.</li>
 * </ol>
 *
 * <p>An unknown or deleted id never fails the request: a tab left open on an
 * edition someone else has since deleted must fall back to the default rather
 * than break every screen with a 400.</p>
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

    @Inject
    EditionRepository editionRepository;

    @Inject
    EditionRequestScope requestScope;

    /**
     * Known edition ids and the default one, both cached: they are read on
     * every single reference-data query, change only through the
     * {@code /api/editions} endpoints, and this is a single-instance
     * application — so {@link #invaliderCache()} on those few writes is
     * enough.
     */
    private volatile Set<String> idsConnus;
    private volatile String defaultId;

    /**
     * Edition the current call reads and writes. Never {@code null}, and never a
     * guess: outside a request and outside {@link #executeIn}, it <b>throws</b>
     * rather than fall back on the default edition.
     *
     * <p>That fallback used to apply everywhere, which made every unwrapped
     * thread hop — a {@code Multi.emitOn}, a {@code CompletableFuture}, a
     * parallel stream — write into the default edition without a sound. It is
     * the exact bug the javadoc of {@link JdbcEditionScope} tells the story of,
     * and the one thing a silent default cannot be trusted with: writing.</p>
     *
     * <p>The <em>other</em> fallback stays, and is deliberate: inside a request,
     * an absent or unknown {@code X-Edition-Id} still resolves to the default.
     * A tab left open on an edition someone else deleted must fall back rather
     * than break every screen, and a client that names no edition at all is the
     * ordinary case.</p>
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
                            + "écrirait dans l'édition par défaut, quelle que soit celle visée.");
        }
        String imposee = editionForcedByToken();
        if (imposee != null) {
            return imposee;
        }
        String demande = editionIdDemande();
        return demande != null && idsConnus().contains(demande) ? demande : defaultId();
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

    /** Must be called whenever an edition is created, deleted, or made the default. */
    public void invaliderCache() {
        idsConnus = null;
        defaultId = null;
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
            cache = editionRepository.listEditions().stream().map(Edition::getId)
                    .collect(Collectors.toUnmodifiableSet());
            idsConnus = cache;
        }
        return cache;
    }

    private String defaultId() {
        String cache = defaultId;
        if (cache == null) {
            cache = editionRepository.defaultEditionId();
            defaultId = cache;
        }
        return cache;
    }
}
