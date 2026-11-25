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

## Contact sheet
The contact form sits on a paper card (`--color-sheet`, `--color-sheet-rule`) that stays light in dark mode: the two tokens are defined once and never overridden. The Tally form is styled to match it.

## Masthead navigation
Five text links left of the wordmark (the four answer pages, then « Doc », which leads to `/docs/`): 44px targets, underlined on hover and on the current page (`aria-current`, `page` on the docs home, `true` on one of its pages). Under 40rem they take their own row under the wordmark and wrap rather than overflow. On an English page « Doc » carries `hreflang="fr"`: the docs are French only.

## Theme switch
One 44px icon button in the masthead cycles auto, light, dark (`data-theme` on `<html>`, none for auto); hidden until `theme.js` runs.

## Controls
- Primary: ink fill, paper text · 10px radius · 48px
- Secondary: transparent, hairline ink border · same radius and height

## Motion
- Character: calm. Hover changes a colour; a press moves a button 1px; the dialog fades in. Under reduced motion everything ends at once.

## Exports
`src/assets/tokens.css` in this project.

## Documentation
The `/docs` pages are written in `src/docs/` and set in the same system, no new face or colour. Three parts, each carrying one player colour as a marker (rules of the game green, install blue, understanding ochre), never as a fill: the colour marks the part's cards, the bar above each chapter, the list numbers, the stripe of a capture, a code block and a note.
- **Docs home**: the answer pages' header (breadcrumb, head), then the search of the site (it submits to the site map, which searches the docs index), a short intro and three cards, one per part, each listing its first five guides and a link to all of them. No side column.
- **Part home**: the head, a line saying whom the part is for, the part's own intro, then the guides as cards (title, first sentence), in reading order. No side column.
- **Guide**: breadcrumb with the part, the head, then on a wide screen two columns: a sticky side column of two navs ("Sur cette page", then the part's guides with the other parts' links under them), and the text. The first paragraph is the lead, larger; each h2 carries a short player-colour bar on its hairline; the previous and next guide close the text as two cards, and a link proposes a fix on GitHub. Each nav is a native `<details>`, folded by default so that on a phone the text comes first, unfolded by `theme.js` on a wide screen; the side column scrolls on its own when it is taller than the screen.
- **Closing band** on every docs page: the quote band of the answer pages (a question no guide answers, the contact button, the hosting page).
- **Text**: a table or a code block wider than the page scrolls in its own frame (`.scroll`, keyboard-reachable), never the page; a table head is a rule under the labels, no fill; a blockquote is a note on the part's wash; a capture is a figure that opens in the site's dialog like the home's (`![alt](/images/fr/x.webp "caption")` in the Markdown); a diagram is shown as it is.
- **Sources**: `repo:path` in a link names a file of the repository and leads to GitHub; a link to a missing page fails the build (`src/docs.js`).
