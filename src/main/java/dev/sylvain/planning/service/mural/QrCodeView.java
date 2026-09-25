package dev.sylvain.planning.service.mural;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A QR code as its grid of modules: {@code size} rows of {@code size}
 * characters, {@code '1'} for a dark module. The screen draws it as SVG — no
 * image to download, no library to add.
 */
@Schema(requiredProperties = {"size", "rows"})
public record QrCodeView(int size, List<String> rows) {}
