package dev.sylvain.planning.service;

/**
 * (édition, animateur) behind an espace-animateur access token.
 *
 * @param editionId   edition the token designates by itself, with no
 *                    {@code X-Edition-Id} to trust
 * @param animateurId owner of the token inside that edition
 * @param email       address on the animateur's fiche, {@code null} when none
 *                    was collected — carried here so the espace guard can
 *                    match a proxy-asserted identity without a second query
 */
public record TokenOwner(String editionId, String animateurId, String email) {
}
