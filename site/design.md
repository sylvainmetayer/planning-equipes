# Design: Planning Équipes, la vitrine

The locked system. Every Hallmark run reads this first and pages share it. Change it on purpose; the file is the rule.

## System
- Genre: editorial
- Theme: custom (by request). Idea: the site is the rulebook of a modern board game whose game is the event's staff rota.
- Shape of the first page: Rulebook. The cover (head, the real planning capture, a rule card laid on its corner), then the chapters in sentence case: what's in the box (the screens), the golden rule (the Labour Code), setting up (the solver), what each team member gets, how a day plays out, questions, starting a game (the quote).
- Axes: light/grotesk-sans/chromatic-green · posture: full-palette, four player colours used as markers, never as fills
- Answer pages: a banner (the question, its answer, the quote button), chapters as rows, the quote band, related questions
- Dark mode: derived in `tokens.css` under `prefers-color-scheme: dark`, never inverted

## Tokens
`src/assets/tokens.css` is the source of truth. The roles:
```css
:root {
  --color-paper: oklch(96.6% 0.018 85);  --color-paper-2: oklch(93.6% 0.024 82);  --color-paper-3: oklch(89.5% 0.028 80);
  --color-ink: oklch(25% 0.022 60);  --color-ink-2: oklch(36% 0.022 60);  --color-muted: oklch(46% 0.022 65);  --color-rule: oklch(84% 0.026 76);
  --color-p1: oklch(48% 0.085 238);  --color-p2: oklch(47% 0.08 158);  --color-p3: oklch(52% 0.1 68);  --color-p4: oklch(45% 0.085 350);
  --color-accent: var(--color-p2);  --color-focus: oklch(46% 0.12 158);  --color-card: oklch(98.6% 0.01 85);
  --font-display: "Rethink Sans", ui-sans-serif, system-ui, sans-serif;  --font-body: "Figtree", ui-sans-serif, system-ui, sans-serif;
  --radius-card: 14px;  --radius-inner: 8px;  --radius-pill: 10px;  --rule-hair: 1px;  --rule-bar: 4px;
  --shadow-card: 0 1px 2px oklch(25% 0.022 60 / 0.07);
}
```
Each player colour has a `-wash` companion for a light tint. Both faces are self-hosted from `@fontsource-variable` (the CSP allows no other font source). The spacing and type scales are the scaffold's.

## Rules
- Heads in Rethink Sans 700, sentence case, never tracked capitals; body and labels in Figtree. Three weights in all: 400, 600, 700.
- A chapter carries one player colour (`data-player="1"` to `"4"`): a short bar above its head, the stripe on top of its cards, its numbers. A colour marks, it never fills a surface.
- Cards have rounded corners, a hairline border, a 4px stripe in their player colour on top and the faintest shadow. No thick borders, no hard drop shadows.
- Captures are the application's own, never retouched. Every capture opens full screen in the native `<dialog>` of `/assets/popin.js` (Escape, a click outside or the close button closes it; arrows move within a group; focus returns to the thumbnail). Without script the link opens the image. Films are not enlarged.

## Controls
- Primary: ink fill, paper text · 10px radius · 48px
- Secondary: transparent, hairline ink border · same radius and height

## Motion
- Character: calm. Hover changes a colour; a press moves a button 1px; the dialog fades in. Under reduced motion everything ends at once.

## Exports
`src/assets/tokens.css` in this project.
