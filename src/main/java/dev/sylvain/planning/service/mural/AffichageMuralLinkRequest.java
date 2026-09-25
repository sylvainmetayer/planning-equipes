package dev.sylvain.planning.service.mural;

import java.util.List;

/**
 * What the admin asks for: a label, whether the screen shows full names, and
 * the locations it is restricted to (none for the whole edition).
 */
public record AffichageMuralLinkRequest(String libelle, Boolean fullNames, List<String> emplacements) {}
