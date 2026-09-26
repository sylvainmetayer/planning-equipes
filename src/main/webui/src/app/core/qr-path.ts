import { QrCodeView } from './models';

/**
 * A QR code's dark modules as one SVG path, one unit square per module — drawn
 * in a `viewBox` of `size` units, so the browser scales it without blurring.
 */
export function qrPath(qr: QrCodeView): string {
  const segments: string[] = [];
  qr.rows.forEach((row, y) => {
    for (let x = 0; x < row.length; x++) {
      if (row[x] === '1') {
        segments.push(`M${x} ${y}h1v1h-1z`);
      }
    }
  });
  return segments.join('');
}
