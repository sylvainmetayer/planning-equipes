---
ordre: 10
---

### Variables d'environnement

#### Marque blanche et mentions légales : vingt-six variables deviennent huit

- **Avant (3.x)** : quatorze variables `BRANDING_*` (nom, organisation, logos, mascottes, couleurs, palette des PDF, adresse de support) et douze `LEGAL_*` (mentions légales, déclaration d'accessibilité, drapeau de démonstration).
- **Après (4.0)** : huit variables. `BRANDING_PRODUCT_NAME`, `BRANDING_ORGANISATION`, `BRANDING_SUPPORT_EMAIL` et `LEGAL_DEMO_INSTANCE` ne changent pas. Quatre variables **groupées**, de la forme `clé=valeur;clé=valeur` (un point-virgule littéral s'écrit `\;`), remplacent les autres : `BRANDING_IMAGES`, `BRANDING_COLORS`, `LEGAL_MENTIONS` et `LEGAL_ACCESSIBILITE`. Les anciens noms sont **encore lus** pendant toute la 4.x : le démarrage avertit pour chacun, la clé groupée l'emporte quand les deux sont posées, et ils seront retirés en 5.0. Le refus de démarrer sans les cinq mentions exigées ne vaut plus que sur une instance publique : voir [le budget de calcul, la file et l'instance publique](#le-budget-de-calcul-la-file-et-linstance-publique).
- **Ce que vous faites** : à votre rythme avant la 5.0, remplacez chaque ancienne variable par sa clé, puis retirez-la de votre environnement (compose, `.env`, CI de déploiement).

| Ancienne variable | Variable groupée, clé |
| --- | --- |
| `BRANDING_LOGO_URL`, `BRANDING_MASCOT_URL`, `BRANDING_MASCOT_ICON_URL` | `BRANDING_IMAGES` : `logo`, `mascot`, `mascot-icon` |
| `BRANDING_PDF_LOGO`, `BRANDING_PDF_STRIP` | `BRANDING_IMAGES` : `pdf-logo`, `pdf-strip` |
| `BRANDING_ACCENT_COLOR` | `BRANDING_COLORS` : `accent` |
| `BRANDING_PDF_HEADLINE`, `BRANDING_PDF_MUTED`, `BRANDING_PDF_ACCENT`, `BRANDING_PDF_HIGHLIGHT`, `BRANDING_PDF_PILL` | `BRANDING_COLORS` : `pdf-headline`, `pdf-muted`, `pdf-accent`, `pdf-highlight`, `pdf-pill` |
| `LEGAL_EDITEUR`, `LEGAL_HEBERGEUR`, `LEGAL_CONTACT`, `LEGAL_BASE_LEGALE`, `LEGAL_CONSERVATION`, `LEGAL_RESPONSABLE_TRAITEMENT`, `LEGAL_DIRECTEUR_PUBLICATION` | `LEGAL_MENTIONS` : `editeur`, `hebergeur`, `contact`, `base-legale`, `conservation`, `responsable-traitement`, `directeur-publication` |
| `LEGAL_ACCESSIBILITE_ETAT`, `LEGAL_ACCESSIBILITE_DATE_AUDIT`, `LEGAL_ACCESSIBILITE_CONTENUS`, `LEGAL_ACCESSIBILITE_SIGNALEMENT` | `LEGAL_ACCESSIBILITE` : `etat`, `date-audit`, `contenus`, `signalement` |

Le détail et un exemple sont dans [les variables d'environnement](variables-environnement.md#marque-et-documents) et [les mentions légales](mentions-legales.md).
