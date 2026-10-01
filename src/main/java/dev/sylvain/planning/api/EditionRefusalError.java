package dev.sylvain.planning.api;

/**
 * Body of a request refused because of its edition ({@code BusinessError.EditionRefused}, ADR 0072).
 *
 * @param message what happened, in the user's words
 * @param code    {@code EDITION_REQUISE}, {@code EDITION_INCONNUE} or {@code EDITION_INACTIVE}: the
 *                discriminator the frontend switches on
 */
public record EditionRefusalError(String message, String code) {}
