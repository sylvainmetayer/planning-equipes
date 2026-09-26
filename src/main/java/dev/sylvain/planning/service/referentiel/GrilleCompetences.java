package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.NiveauCompetence;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The animateur × game-category matrix of appreciations — one level per
 * cell, empty for "no appreciation" — as the "Compétences" screen types it.
 * The screen is the only way in: the grid is not exchanged as a file.
 *
 * <p>Pure and static, like {@link GrilleHorairesStands}: the shapes a submit
 * and a report are made of. The persistence is {@link CompetencesGrilleService}'s
 * business.</p>
 */
public final class GrilleCompetences {

    private GrilleCompetences() {}

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
        copie.setTelephone(source.getTelephone());
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
}
