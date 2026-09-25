package dev.sylvain.planning.service.journal;

/**
 * The id of what a creation returned, read reflectively from the payload —
 * either the object itself, or the single business component of a wrapper
 * like {@code WrittenAnimateur}.
 *
 * <p>Since ids are generated (ADR 0050), a creation has no argument naming
 * what it creates: the answer is the only place the id exists. Shared by
 * {@code JournalActionFilter} (REST) and {@code JournalOutilInterceptor}
 * (MCP), so both doors write the same line.</p>
 *
 * <p>Reflection rather than a per-route rule because there is no shape all
 * these payloads share, and swallowing everything because a trace must never
 * cost an action: an unreadable payload leaves the id blank, it does not fail
 * the request that already succeeded.</p>
 */
public final class PayloadIds {

    private PayloadIds() {}

    public static String of(Object payload) {
        if (payload == null) {
            return null;
        }
        String direct = readId(payload);
        if (direct != null) {
            return direct;
        }
        if (payload instanceof Record) {
            for (var composant : payload.getClass().getRecordComponents()) {
                try {
                    String imbrique = readId(composant.getAccessor().invoke(payload));
                    if (imbrique != null) {
                        return imbrique;
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // One unreadable component is not the end of the search:
                    // a later one may well carry the id.
                    continue;
                }
            }
        }
        return null;
    }

    /** {@code getId()} on a bean, {@code id()} on a record. */
    private static String readId(Object objet) {
        if (objet == null || objet instanceof String || objet instanceof Number || objet instanceof Boolean) {
            return null;
        }
        String accesseur = objet instanceof Record ? "id" : "getId";
        try {
            Object id = objet.getClass().getMethod(accesseur).invoke(objet);
            return id == null ? null : String.valueOf(id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
