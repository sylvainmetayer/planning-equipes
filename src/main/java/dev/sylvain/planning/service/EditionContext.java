package dev.sylvain.planning.service;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import dev.sylvain.planning.domain.Edition;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Answers the one question every reference-data query needs: <b>which edition
 * am I reading and writing?</b> (see {@code docs/editions.md} §5).
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
 * its request — see {@link #executeDans};</li>
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
    private volatile String idParDefaut;

    /** Edition the current call reads and writes. Never {@code null}. */
    public String editionIdCourant() {
        String override = OVERRIDE.get();
        if (override != null) {
            return override;
        }
        String imposee = editionImposeeParJeton();
        if (imposee != null) {
            return imposee;
        }
        String demande = editionIdDemande();
        return demande != null && idsConnus().contains(demande) ? demande : idParDefaut();
    }

    /**
     * Runs {@code work} as if the request had designated {@code editionId}. The
     * previous binding is restored afterwards, so nesting and thread reuse in a
     * pool are both safe.
     */
    public <T> T executeDans(String editionId, Callable<T> work) {
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

    /** Same as {@link #executeDans(String, Callable)} for work returning nothing. */
    public void executeDans(String editionId, Runnable work) {
        executeDans(editionId, () -> {
            work.run();
            return null;
        });
    }

    /** Must be called whenever an edition is created, deleted, or made the default. */
    public void invaliderCache() {
        idsConnus = null;
        idParDefaut = null;
    }

    /**
     * The header of the request being served, or {@code null} outside any
     * request. {@code Arc.container()} is null-checked because unit tests
     * instantiate this class outside a CDI container.
     */
    private String editionIdDemande() {
        var container = Arc.container();
        if (container == null || !container.requestContext().isActive()) {
            return null;
        }
        String demande = requestScope.getEditionIdDemande();
        return demande == null || demande.isBlank() ? null : demande;
    }

    /**
     * Edition of the espace token the request carries, resolved by the espace
     * guards — it overrides the header (the espace never trusts it) and needs
     * no validation against the known ids: it comes from the animateur row
     * itself. {@code null} off the espace routes or outside any request.
     */
    private String editionImposeeParJeton() {
        var container = Arc.container();
        if (container == null || !container.requestContext().isActive()) {
            return null;
        }
        var proprietaire = requestScope.getProprietaireJeton();
        return proprietaire == null ? null : proprietaire.editionId();
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

    private String idParDefaut() {
        String cache = idParDefaut;
        if (cache == null) {
            cache = editionRepository.idEditionParDefaut();
            idParDefaut = cache;
        }
        return cache;
    }
}
