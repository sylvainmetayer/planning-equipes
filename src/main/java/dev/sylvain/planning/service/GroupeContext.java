package dev.sylvain.planning.service;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import dev.sylvain.planning.domain.Groupe;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Answers the one question every reference-data query needs: <b>which group am
 * I reading and writing?</b> (see {@code docs/groupes.md} §5).
 *
 * <p>The client designates it per request through the {@code X-Groupe-Id}
 * header — not a global {@code actif} flag in the database, which would force
 * every open tab to share one group and turn switching into a write visible to
 * every other user. Two browser tabs can therefore sit on two different
 * editions at the same time.</p>
 *
 * <p>Resolution order:</p>
 * <ol>
 * <li>an explicit override bound to the current thread, for work that outlives
 * its request — see {@link #executeDans};</li>
 * <li>the {@code X-Groupe-Id} of the request being served, <b>if that group
 * exists</b>;</li>
 * <li>the group flagged {@code defaut}.</li>
 * </ol>
 *
 * <p>An unknown or deleted id never fails the request: a tab left open on a
 * group someone else has since deleted must fall back to the default rather
 * than break every screen with a 400.</p>
 */
@ApplicationScoped
public class GroupeContext {

    public static final String HEADER = "X-Groupe-Id";

    /**
     * Group bound to the current thread, overriding the request header. Used by
     * work that runs outside (or beyond) the request that triggered it — a
     * solver job keeps writing to the group it was launched for even if the
     * browser has since switched.
     */
    private static final ThreadLocal<String> OVERRIDE = new ThreadLocal<>();

    @Inject
    GroupeRepository groupeRepository;

    @Inject
    GroupeRequestScope requestScope;

    /**
     * Known group ids and the default one, both cached: they are read on every
     * single reference-data query, change only through the {@code /api/groupes}
     * endpoints, and this is a single-instance application — so
     * {@link #invaliderCache()} on those few writes is enough.
     */
    private volatile Set<String> idsConnus;
    private volatile String idParDefaut;

    /** Group the current call reads and writes. Never {@code null}. */
    public String groupeIdCourant() {
        String override = OVERRIDE.get();
        if (override != null) {
            return override;
        }
        String demande = groupeIdDemande();
        return demande != null && idsConnus().contains(demande) ? demande : idParDefaut();
    }

    /**
     * Runs {@code work} as if the request had designated {@code groupeId}. The
     * previous binding is restored afterwards, so nesting and thread reuse in a
     * pool are both safe.
     */
    public <T> T executeDans(String groupeId, Callable<T> work) {
        String precedent = OVERRIDE.get();
        OVERRIDE.set(groupeId);
        try {
            return work.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to run work in group " + groupeId, e);
        } finally {
            if (precedent != null) {
                OVERRIDE.set(precedent);
            } else {
                OVERRIDE.remove();
            }
        }
    }

    /** Same as {@link #executeDans(String, Callable)} for work returning nothing. */
    public void executeDans(String groupeId, Runnable work) {
        executeDans(groupeId, () -> {
            work.run();
            return null;
        });
    }

    /** Must be called whenever a group is created, deleted, or made the default. */
    public void invaliderCache() {
        idsConnus = null;
        idParDefaut = null;
    }

    /**
     * The header of the request being served, or {@code null} outside any
     * request. {@code Arc.container()} is null-checked because unit tests
     * instantiate this class outside a CDI container.
     */
    private String groupeIdDemande() {
        var container = Arc.container();
        if (container == null || !container.requestContext().isActive()) {
            return null;
        }
        String demande = requestScope.getGroupeIdDemande();
        return demande == null || demande.isBlank() ? null : demande;
    }

    private Set<String> idsConnus() {
        Set<String> cache = idsConnus;
        if (cache == null) {
            cache = groupeRepository.listGroupes().stream().map(Groupe::getId).collect(Collectors.toUnmodifiableSet());
            idsConnus = cache;
        }
        return cache;
    }

    private String idParDefaut() {
        String cache = idParDefaut;
        if (cache == null) {
            cache = groupeRepository.idGroupeParDefaut();
            idParDefaut = cache;
        }
        return cache;
    }
}
