# Design: Planning Équipes, la vitrine

The locked system. Every Hallmark run reads this first and pages share it. Change it on purpose; the file is the rule.

## System
- Genre: editorial (the theme's genre; the brief asked for playful and warm)
- Shape of the first page: Workbench (the application's own captures and films lead)
- Theme: Carnival, drop cold-snap (tinted paper, deep warm ink, a festival poster)
- Axes: light/display-heavy/chromatic-yellow · posture: full-palette
- Answer pages: a Banner hero (the question, its answer, the quote button), chapters as ruled rows, the oxblood quote band

## Tokens
`src/assets/tokens.css` is the source of truth. The roles:
```css
:root {
  --color-paper: oklch(92% 0.045 50);  --color-paper-2: oklch(88% 0.050 45);  --color-paper-3: oklch(82% 0.060 40);
  --color-ink: oklch(18% 0.080 20);  --color-ink-2: oklch(28% 0.060 25);  --color-muted: oklch(42% 0.050 30);  --color-rule: oklch(40% 0.21 25);
  --color-accent: oklch(86% 0.18 95);  --color-accent-2: oklch(40% 0.21 25);  --color-accent-ink: oklch(18% 0.080 20);  --color-focus: oklch(40% 0.21 25);
  --font-display: "Big Shoulders Display", Impact, sans-serif;  --font-body: "Golos Text", system-ui, sans-serif;
  --radius-card: 2px;  --radius-pill: 2px;  --rule-bar: 2px;  --shadow-card: 4px 4px 0 var(--color-ink);
}
```
The spacing scale, the type scale, the easings and the durations are the scaffold's and live in `tokens.css`. Both faces are self-hosted from `@fontsource-variable` (the CSP allows no other font source).

## Rules
- Heads in Big Shoulders Display 800 capitals, six words or fewer, a full stop or a question mark; body, labels and nav links in Golos Text, sentence case.
- Two accents that never share a surface: mustard fills the primary button, the refused-shifts ledger and one tile; oxblood carries rules, focus, links and the quote band.
- Every framed thing (a capture, a film, a well, a button) takes a 2px ink border and the hard 4px shadow; nothing else casts depth.
- Captures are the application's own, never retouched; the hero print tilts about a degree, with the first refused shift pinned on it.

## Controls
- Primary: mustard fill · 2px ink border · hard shadow · 48px · display capitals
- Secondary: the same block on paper; on the oxblood band, the paper block

## Motion
- Character: still. Hover swaps a colour; a press moves the shadow in by 3px. No reveal, no loop.

## Exports
`src/assets/tokens.css` in this project.
