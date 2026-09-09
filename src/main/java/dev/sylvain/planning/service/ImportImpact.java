package dev.sylvain.planning.service;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What a scenario import would touch, counted for the confirmation dialog
 * shown before it runs: nothing here decides anything, it only lets the
 * operator see the blast radius — replaced referentials, erased resolved
 * planning, demandes and locks that will go with it.
 */
@Schema(requiredProperties = {"animateurs", "demandesEchange", "demandesEnAttente", "planningResolu", "postes", "stands", "verrous"})
public record ImportImpact(int animateurs, int stands, int postes, boolean planningResolu,
        int demandesEchange, int demandesEnAttente, int verrous) {
}
