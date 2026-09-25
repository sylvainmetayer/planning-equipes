package dev.sylvain.planning.service.mural;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A link just created, and the only moment its token is ever handed out: the
 * database keeps its hash alone, so the admin copies the address now or makes
 * another link later.
 */
@Schema(requiredProperties = {"link", "token"})
public record CreatedAffichageMuralLink(AffichageMuralLink link, String token) {

    /** What the history names as the action's target — the link, never its token. */
    @JsonIgnore
    public String getId() {
        return String.valueOf(link.id());
    }
}
