---
ordre: 10
---

### Environment variables

#### White labelling and legal notices: twenty-six variables become eight

- **Before (3.x)**: fourteen `BRANDING_*` variables (name, organisation, logos, mascots, colours, PDF palette, support address) and twelve `LEGAL_*` (legal notices, accessibility statement, demonstration flag).
- **After (4.0)**: eight variables. `BRANDING_PRODUCT_NAME`, `BRANDING_ORGANISATION`, `BRANDING_SUPPORT_EMAIL` and `LEGAL_DEMO_INSTANCE` do not change. Four **grouped** variables, of the form `key=value;key=value` (a literal semicolon is written `\;`), replace the others: `BRANDING_IMAGES`, `BRANDING_COLORS`, `LEGAL_MENTIONS` and `LEGAL_ACCESSIBILITE`. The old names are **still read** throughout 4.x: startup warns for each one, the grouped key wins when both are set, and they will be removed in 5.0. The refusal to start without the five required notices now only applies to a public instance: see [the solve budget, the queue and the public instance](#le-budget-de-calcul-la-file-et-linstance-publique).
- **What you do**: at your own pace before 5.0, replace each old variable with its key, then remove it from your environment (compose, `.env`, deployment CI).

| Old variable | Grouped variable, key |
| --- | --- |
| `BRANDING_LOGO_URL`, `BRANDING_MASCOT_URL`, `BRANDING_MASCOT_ICON_URL` | `BRANDING_IMAGES`: `logo`, `mascot`, `mascot-icon` |
| `BRANDING_PDF_LOGO`, `BRANDING_PDF_STRIP` | `BRANDING_IMAGES`: `pdf-logo`, `pdf-strip` |
| `BRANDING_ACCENT_COLOR` | `BRANDING_COLORS`: `accent` |
| `BRANDING_PDF_HEADLINE`, `BRANDING_PDF_MUTED`, `BRANDING_PDF_ACCENT`, `BRANDING_PDF_HIGHLIGHT`, `BRANDING_PDF_PILL` | `BRANDING_COLORS`: `pdf-headline`, `pdf-muted`, `pdf-accent`, `pdf-highlight`, `pdf-pill` |
| `LEGAL_EDITEUR`, `LEGAL_HEBERGEUR`, `LEGAL_CONTACT`, `LEGAL_BASE_LEGALE`, `LEGAL_CONSERVATION`, `LEGAL_RESPONSABLE_TRAITEMENT`, `LEGAL_DIRECTEUR_PUBLICATION` | `LEGAL_MENTIONS`: `editeur`, `hebergeur`, `contact`, `base-legale`, `conservation`, `responsable-traitement`, `directeur-publication` |
| `LEGAL_ACCESSIBILITE_ETAT`, `LEGAL_ACCESSIBILITE_DATE_AUDIT`, `LEGAL_ACCESSIBILITE_CONTENUS`, `LEGAL_ACCESSIBILITE_SIGNALEMENT` | `LEGAL_ACCESSIBILITE`: `etat`, `date-audit`, `contenus`, `signalement` |

The details and an example are in [the environment variables](variables-environnement.md#marque-et-documents) and [the legal notice](mentions-legales.md).
