package dev.sylvain.planning.service.analyse;

/**
 * One reading of the Marge grid: the margin itself, before or after the solve,
 * or the tension map that crosses the « après » margin with the fragility.
 * What the MCP tool {@code analyser_marge} returns, whichever {@code mode} it
 * was asked — sealed, so the tool's return type still names every shape it can
 * send and the privacy check can walk each of them.
 */
public sealed interface MarginReading permits MargeAnalyzer.RapportMarge, TensionAnalyzer.RapportTension {}
