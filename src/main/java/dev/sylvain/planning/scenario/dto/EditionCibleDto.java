package dev.sylvain.planning.scenario.dto;

/**
 * Optional top-level {@code edition:} scenario section: designates the edition
 * the import writes into, instead of the caller's current one — by its
 * {@code id} when that edition exists, else by its {@code nom}. An edition
 * neither designates is created empty first, under that name and an id the
 * application draws (ADR 0050); an existing one is reused as-is. The import
 * response reports which of the two happened, so the UI can show the
 * mandatory recap.
 */
public record EditionCibleDto(String id, String nom) {}
