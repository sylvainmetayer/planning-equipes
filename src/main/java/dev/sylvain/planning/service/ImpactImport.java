package dev.sylvain.planning.service;

/**
 * What a scenario import would touch, counted for the confirmation dialog
 * shown before it runs: nothing here decides anything, it only lets the
 * operator see the blast radius — replaced referentials, erased resolved
 * planning, demandes and locks that will go with it.
 */
public record ImpactImport(int animateurs, int stands, int postes, boolean planningResolu,
        int demandesEchange, int demandesEnAttente, int verrous) {
}
