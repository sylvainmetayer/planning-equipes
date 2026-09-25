package dev.sylvain.planning.service.referentiel;

import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * The four families of the referential an organiser can freeze once their
 * preparation is over (see {@code docs/decisions/0052-gel-du-referentiel-distinct-du-verrou.md}).
 *
 * <p>A family, not a field nor a fiche: freezing closes a phase of the
 * preparation (« les stands sont prêts »), it is not a fine-grained setting —
 * a per-fiche freeze would be the planning lock over again. What each family
 * covers is the list of fields whose change moves a computed plan:</p>
 *
 * <ul>
 *   <li>{@link #STANDS} — creation and deletion, the headcounts, the
 *       adults-only reserve, the game categories offered, the opening hours
 *       (rules and dated exceptions);</li>
 *   <li>{@link #CRENEAUX} — creation and deletion, date, hours, break cover,
 *       and the three generators of the grid (day templates applied, grid
 *       derived from the stands, recurring series);</li>
 *   <li>{@link #TYPOLOGIES_EMPLACEMENTS} — creation and deletion of both, the
 *       cap per game category and which one is the polyvalent one;</li>
 *   <li>{@link #COMPETENCES} — the competence levels of the animateurs already
 *       in the roster.</li>
 * </ul>
 *
 * <p>The constant names are on the wire: the {@code famille} column, the
 * path of {@code /api/editions/courant/gel/{famille}} and the MCP argument.</p>
 */
public enum ReferentialFamily {
    STANDS("Stands"),
    CRENEAUX("Créneaux"),
    TYPOLOGIES_EMPLACEMENTS("Typologies & emplacements"),
    COMPETENCES("Compétences des animateurs");

    private final String libelle;

    ReferentialFamily(String libelle) {
        this.libelle = libelle;
    }

    /** How the screens and the refusals name the family, in French. */
    public String libelle() {
        return libelle;
    }

    /** « « Stands », « Créneaux » », in declaration order — what a refusal quotes. */
    public static String quote(Collection<ReferentialFamily> families) {
        return Arrays.stream(values())
                .filter(families::contains)
                .map(family -> "« " + family.libelle + " »")
                .collect(Collectors.joining(", "));
    }
}
