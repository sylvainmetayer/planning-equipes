package dev.sylvain.planning.service.export;

import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.TypologieLibelles;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The colour a stand is drawn in on a timeline, and the legend that explains
 * it. The colour carries the stand's <b>typologie</b>, never the stand itself:
 * a dozen stands over six categories give a legend somebody can read, where a
 * colour per stand gives a legend nobody can.
 *
 * <p>The referential holds no colour of its own ({@code TypologieItem}), so one
 * is attributed here: eight hues, handed out in the order of the typologie ids.
 * Storing the colour on the typologie is a possible extension — this class is
 * then the fallback, not the rule.</p>
 *
 * <p>Ids are <b>sorted</b> rather than read in the map's own order:
 * {@link TypologieLibelles} is an interface a caller satisfies with a
 * {@code Map.of(...)}, whose iteration order changes from one JVM to the next.
 * Two runs of the same planning must produce the same document, so the order
 * that decides the colours has to be the ids' own.</p>
 */
final class TypologiePalette {

    /**
     * Eight hues, all dark enough to carry white text: a bar of the timeline
     * is a filled rectangle with its stand's name written inside it.
     */
    private static final List<Color> TEINTES = List.of(
            new Color(0xC04A2B),
            new Color(0x2C68A3),
            new Color(0x1D7457),
            new Color(0x7646A6),
            new Color(0x836400),
            new Color(0x4A5361),
            new Color(0xA03050),
            new Color(0x0F6C7A));

    /** A stand whose typologie the referential does not know, or which declares none. */
    private static final Color SANS_TYPOLOGIE = new Color(0x4A5361);

    private final Map<String, String> libelles;
    private final Map<String, Color> couleurs;

    private TypologiePalette(Map<String, String> libelles) {
        this.libelles = libelles;
        Map<String, Color> parId = new LinkedHashMap<>();
        Set<String> ordonnes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        ordonnes.addAll(libelles.keySet());
        int rang = 0;
        for (String id : ordonnes) {
            parId.put(id, TEINTES.get(rang % TEINTES.size()));
            rang++;
        }
        this.couleurs = parId;
    }

    static TypologiePalette of(TypologieLibelles typologies) {
        return new TypologiePalette(typologies == null ? Map.of() : Map.copyOf(typologies.labelsById()));
    }

    /**
     * The typologie a stand is coloured and labelled by: the first of the ones
     * it proposes, in the same order the palette attributes its hues — a stand
     * offering several games has to pick one colour, and it must pick the same
     * one on every page of the document.
     */
    String typologiePrincipale(Stand stand) {
        if (stand == null || stand.getTypologiesProposees() == null) {
            return null;
        }
        return stand.getTypologiesProposees().stream()
                .min(String.CASE_INSENSITIVE_ORDER)
                .orElse(null);
    }

    Color couleur(String typologieId) {
        if (typologieId == null) {
            return SANS_TYPOLOGIE;
        }
        Color connue = couleurs.get(typologieId);
        if (connue != null) {
            return connue;
        }
        // An id the referential no longer knows still has to be drawn, and
        // drawn the same way twice: its own hash, not the iteration order of
        // whatever collection it was met in.
        return TEINTES.get(Math.floorMod(typologieId.hashCode(), TEINTES.size()));
    }

    Color couleurDuStand(Stand stand) {
        return couleur(typologiePrincipale(stand));
    }

    /** Label of a typologie id, the id itself when the referential dropped it, {@code null} for a stand without one. */
    String libelle(String typologieId) {
        if (typologieId == null) {
            return null;
        }
        return libelles.getOrDefault(typologieId, typologieId);
    }

    String libelleDuStand(Stand stand) {
        return libelle(typologiePrincipale(stand));
    }

    /**
     * The legend of a document: one entry per typologie actually drawn in it,
     * in the palette's own order. A legend listing the whole referential would
     * explain colours the reader never meets.
     */
    List<Entree> legende(Collection<Stand> stands) {
        Set<String> vues = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        boolean sansTypologie = false;
        for (Stand stand : stands) {
            String typologie = typologiePrincipale(stand);
            if (typologie == null) {
                sansTypologie = true;
            } else {
                vues.add(typologie);
            }
        }
        List<Entree> entrees = new ArrayList<>();
        for (String id : vues) {
            entrees.add(new Entree(libelle(id), couleur(id)));
        }
        if (sansTypologie) {
            entrees.add(new Entree("Sans typologie", SANS_TYPOLOGIE));
        }
        return entrees;
    }

    /** One line of the legend: what the colour means, and the colour. */
    record Entree(String libelle, Color couleur) {}
}
