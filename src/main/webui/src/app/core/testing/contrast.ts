// A contrast meter for the specs, and for nothing else.
//
// The RGAA audit (issue #36) measured every colour this repository writes by
// hand, off to the side; issue #40 found two of them under the threshold. A
// figure nobody can replay is a figure that drifts, so the arithmetic lives
// here and a spec can assert on it.
//
// jsdom has no CSS engine: it cannot resolve `oklch(from … min(l, .53) c h)`,
// which is exactly the value `core/branding.ts` hands the browser. So the
// conversion is done here — sRGB → OKLab (Björn Ottosson's matrices) and back,
// then the WCAG relative luminance — and a spec measures the *policy* the code
// emits rather than trusting the constant it was given.

/** `--mat-sys-surface` on each scheme, as `mat.theme()` compiles it. */
export const LIGHT_SURFACE = '#faf9fd';
export const DARK_SURFACE = '#121316';

/** A colour in OKLCH: lightness 0..1, chroma, hue in degrees. */
export interface Oklch {
  l: number;
  c: number;
  h: number;
}

type LinearRgb = [number, number, number];

/** The sRGB transfer function, inverted: a stored channel to linear light. */
const toLinearChannel = (channel: number): number =>
  channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;

/**
 * The three digits of `#fff` are expanded, and anything else throws: a meter
 * that answers a wrong figure is worse than no meter. `Number.parseInt` read
 * `#fff` as `0x000fff`, so white measured 1,84:1 on the brand bar instead of
 * 4,54:1 — and `--app-toolbar-foreground` is written `#fff`, which is exactly
 * the pair `styles/branding.css` asks a deployment to measure again.
 */
function hexToLinear(hex: string): LinearRgb {
  const digits = hex.replace('#', '');
  const six = digits.length === 3 ? digits.replace(/./g, (digit) => digit + digit) : digits;
  if (!/^[0-9a-fA-F]{6}$/.test(six)) throw new Error(`not a hex colour: ${hex}`);
  const packed = Number.parseInt(six, 16);
  return [(packed >> 16) & 255, (packed >> 8) & 255, packed & 255].map((channel) =>
    toLinearChannel(channel / 255),
  ) as LinearRgb;
}

function oklchToLinear({ l, c, h }: Oklch): LinearRgb {
  const radians = (h * Math.PI) / 180;
  const a = c * Math.cos(radians);
  const b = c * Math.sin(radians);
  const long = (l + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const medium = (l - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const short = (l - 0.0894841775 * a - 1.291485548 * b) ** 3;
  return [
    4.0767416621 * long - 3.3077115913 * medium + 0.2309699292 * short,
    -1.2684380046 * long + 2.6097574011 * medium - 0.3413193965 * short,
    -0.0041960863 * long - 0.7034186147 * medium + 1.707614701 * short,
  ];
}

const isInGamut = (rgb: number[]): boolean =>
  rgb.every((channel) => channel >= -1e-4 && channel <= 1.0001);

/**
 * CSS Color 4 gamut mapping: an OKLCH colour the screen cannot show has its
 * chroma reduced at constant lightness until it can. Without this, a saturated
 * brand would be measured on a colour no browser ever paints.
 */
function mapIntoGamut(colour: Oklch): LinearRgb {
  let reachable = colour.c;
  if (!isInGamut(oklchToLinear(colour))) {
    reachable = 0;
    let tooFar = colour.c;
    for (let i = 0; i < 40; i++) {
      const middle = (reachable + tooFar) / 2;
      if (isInGamut(oklchToLinear({ ...colour, c: middle }))) reachable = middle;
      else tooFar = middle;
    }
  }
  return oklchToLinear({ ...colour, c: reachable }).map((channel) =>
    Math.min(1, Math.max(0, channel)),
  ) as LinearRgb;
}

/** WCAG relative luminance, from linear-light sRGB. */
const luminance = ([r, g, b]: LinearRgb): number => 0.2126 * r + 0.7152 * g + 0.0722 * b;

function ratio(one: number, other: number): number {
  const [lighter, darker] = one >= other ? [one, other] : [other, one];
  return (lighter + 0.05) / (darker + 0.05);
}

/** The WCAG contrast ratio of two hex colours, e.g. white on the brand bar. */
export function hexContrast(one: string, other: string): number {
  return ratio(luminance(hexToLinear(one)), luminance(hexToLinear(other)));
}

/** The WCAG contrast ratio of an OKLCH colour on a hex background. */
export function oklchContrast(colour: Oklch, background: string): number {
  return ratio(luminance(mapIntoGamut(colour)), luminance(hexToLinear(background)));
}

/** A hex colour read as OKLCH, so a spec can apply the clamps the CSS declares. */
export function hexToOklch(hex: string): Oklch {
  const [r, g, b] = hexToLinear(hex);
  const long = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const medium = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const short = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  const l = 0.2104542553 * long + 0.793617785 * medium - 0.0040720468 * short;
  const a = 1.9779984951 * long - 2.428592205 * medium + 0.4505937099 * short;
  const b2 = 0.0259040371 * long + 0.7827717662 * medium - 0.808675766 * short;
  return { l, c: Math.hypot(a, b2), h: ((Math.atan2(b2, a) * 180) / Math.PI + 360) % 360 };
}
