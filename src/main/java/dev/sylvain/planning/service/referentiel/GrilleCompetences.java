package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.NiveauCompetence;
import java.text.Normalizer;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The animateur × game-category matrix of appreciations — one level per
 * cell, empty for "no appreciation" — as the "Compétences" screen types it
 * and as a CSV carries it.
 *
 * <p>Pure and static, like {@link GrilleHorairesStands}: the shapes a submit
 * and a report are made of, the CSV a grid leaves the application as, and the
 * reading of one cell. The persistence is {@link CompetencesGrilleService}'s
 * business.</p>
 */
public final class GrilleCompetences {

    private GrilleCompetences() {}

    /** The first header cell of the CSV; the other headers are typologie ids. */
    public static final String COLONNE_ANIMATEUR = "animateur";

    /**
     * One animateur of a submitted grid: their whole map of appreciations,
     * replacing the stored one — a cell left out is an appreciation removed,
     * exactly as the fiche form does.
     *
     * @param modifieLe the fiche's {@code modifie_le} as the grid read it, sent
     *                  back as this row's own precondition (issue #362); {@code null}
     *                  means "no precondition", the way a knowing overwrite says it
     */
    @Schema(requiredProperties = {"animateurId", "competences"})
    public record SaisieCompetences(String animateurId, Instant modifieLe, Map<String, NiveauCompetence> competences) {}

    /** How one submitted row ended. */
    public enum ResultatLigne {
        /** Written; {@code modifieLe} carries the stamp the next save must send back. */
        WRITTEN,
        /** Refused: another session wrote the fiche after the grid read it (issue #362). */
        STALE,
        /** Refused for any other reason, named in {@code message}. */
        REJECTED
    }

    /**
     * What happened to one row of the submit, in the order submitted. The
     * rows are independent: one refusal never undoes the others, which is why
     * the answer is a report rather than a status.
     *
     * @param modifieLe on {@link ResultatLigne#WRITTEN} the stamp written; on
     *                  {@link ResultatLigne#STALE} the row's current stamp, so
     *                  the client can overwrite knowingly by sending it back
     */
    @Schema(requiredProperties = {"animateurId", "resultat"})
    public record LigneCompetences(String animateurId, ResultatLigne resultat, String message, Instant modifieLe) {}

    /** The three levels as the CSV writes them — the enum names, which the animateur CSV import reads too. */
    public static final List<NiveauCompetence> NIVEAUX = List.of(NiveauCompetence.values());

    /**
     * The grid as a CSV: {@code animateur;<typologie>;<typologie>…}, one row per
     * animateur in referential order, a level name or nothing in each cell.
     * Ids only — no prénom, no nom: the id is what an import needs to find the
     * fiche again, and a file that names nobody can travel without the care a
     * roster asks for.
     */
    public static String csv(List<Animateur> animateurs, List<String> typologieIds) {
        return csv(animateurs, typologieIds, Map.of());
    }

    /**
     * The same grid, a column headed by what {@code entetes} gives for its
     * typologie — its code, which a reader recognises, where the id is a
     * number (ADR 0050) — and by its id otherwise. The import reads both.
     */
    public static String csv(List<Animateur> animateurs, List<String> typologieIds, Map<String, String> entetes) {
        StringBuilder csv = new StringBuilder();
        csv.append(COLONNE_ANIMATEUR);
        for (String typologie : typologieIds) {
            csv.append(';').append(field(entetes.getOrDefault(typologie, typologie)));
        }
        csv.append('\n');
        for (Animateur animateur : animateurs) {
            csv.append(field(animateur.getId()));
            Map<String, NiveauCompetence> competences =
                    animateur.getCompetences() == null ? Map.of() : animateur.getCompetences();
            for (String typologie : typologieIds) {
                NiveauCompetence niveau = competences.get(typologie);
                csv.append(';').append(niveau == null ? "" : niveau.name());
            }
            csv.append('\n');
        }
        return csv.toString();
    }

    /** What one CSV cell says: nothing (leave the appreciation as it is), a level, or a word that is neither. */
    public record CelluleCompetence(NiveauCompetence niveau, boolean vide, boolean lisible) {

        static CelluleCompetence empty() {
            return new CelluleCompetence(null, true, true);
        }

        static CelluleCompetence of(NiveauCompetence niveau) {
            return new CelluleCompetence(niveau, false, true);
        }

        static CelluleCompetence unreadable() {
            return new CelluleCompetence(null, false, false);
        }
    }

    /**
     * Reads a cell the way a spreadsheet writes it: the level name in any case
     * and with or without its accents ({@code référent}, {@code Debutant}), or
     * the digit the screen's keyboard uses for it (1, 2, 3). A blank cell is
     * not "no appreciation" but "nothing said" — the import adds and updates,
     * it never removes — and anything else is unreadable, for the caller to
     * refuse the row.
     */
    public static CelluleCompetence cellule(String texte) {
        String propre = texte == null ? "" : texte.trim();
        if (propre.isEmpty()) {
            return CelluleCompetence.empty();
        }
        return level(propre).map(CelluleCompetence::of).orElseGet(CelluleCompetence::unreadable);
    }

    /** {@code référent} → {@code REFERENT}, {@code 2} → {@code AUTONOME}; empty when the text names no level. */
    public static Optional<NiveauCompetence> level(String texte) {
        String normalise = Normalizer.normalize(texte == null ? "" : texte.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT);
        if (normalise.matches("[1-3]")) {
            return Optional.of(NIVEAUX.get(Integer.parseInt(normalise) - 1));
        }
        for (NiveauCompetence niveau : NIVEAUX) {
            if (niveau.name().equals(normalise)) {
                return Optional.of(niveau);
            }
        }
        return Optional.empty();
    }

    /**
     * The fiche as it stands, with its appreciations replaced by
     * {@code competences}: everything else — identity, off days, wishes,
     * e-mail — is carried over untouched, since a write rewrites the whole row.
     * A copy, so a refused write leaves the caller's list as it read it.
     */
    public static Animateur withCompetences(
            Animateur source, Map<String, NiveauCompetence> competences, Instant modifieLe) {
        Animateur copie = new Animateur(
                source.getId(), source.getPrenom(), source.getNom(), source.getDateNaissance(), source.isManager());
        copie.setEmail(source.getEmail());
        copie.setAccessToken(source.getAccessToken());
        copie.setJoursIndisponibles(
                source.getJoursIndisponibles() == null
                        ? new HashSet<>()
                        : new HashSet<>(source.getJoursIndisponibles()));
        copie.setSouhaits(source.getSouhaits() == null ? new HashSet<>() : new HashSet<>(source.getSouhaits()));
        copie.setCompetences(competences == null ? new LinkedHashMap<>() : new LinkedHashMap<>(competences));
        copie.setModifieLe(modifieLe);
        return copie;
    }

    /** A field a spreadsheet reads back as one: quoted as soon as it carries the separator, a quote or a newline. */
    private static String field(String valeur) {
        String texte = valeur == null ? "" : valeur;
        if (texte.indexOf(';') < 0 && texte.indexOf('"') < 0 && texte.indexOf('\n') < 0 && texte.indexOf('\r') < 0) {
            return texte;
        }
        return '"' + texte.replace("\"", "\"\"") + '"';
    }
}
