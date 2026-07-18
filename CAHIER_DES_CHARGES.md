# Cahier des charges — Application de gestion de planning

**Festival de jeux — 15 jours**
Version 0.2 — Document de travail — Juillet 2026

> Ce document est un point de départ à valider ensemble : les sections signalées
> « à valider » sont des hypothèses de travail à confirmer avant le développement.

## Sommaire

1. [Contexte et objectifs](#1-contexte-et-objectifs)
2. [Acteurs et rôles](#2-acteurs-et-rôles)
3. [Modèle de données](#3-modèle-de-données-entités-principales)
4. [Référentiel de contraintes](#4-référentiel-de-contraintes)
5. [Fonctionnalités attendues](#5-fonctionnalités-attendues)
6. [Architecture technique](#6-architecture-technique)
7. [Points de vigilance](#7-points-de-vigilance)
8. [Prochaines étapes](#8-prochaines-étapes-proposées)

---

## 1. Contexte et objectifs

Le festival se déroule sur 15 jours consécutifs et mobilise une équipe d'environ
**150 animateurs** de stands (jeux) dont certains sont mineurs. La gestion manuelle
du planning devient rapidement complexe du fait du croisement de plusieurs types de
contraintes : réglementaires (temps de travail, repos, âge), organisationnelles
(compétences requises par stand, effectifs) et humaines (équité, préférences,
rotation du repos).

Objectifs du projet :

- Centraliser le profil de chaque animateur : âge, statut (majeur/mineur),
  compétences par jeu, disponibilités sur les 15 jours.
- Définir un référentiel de contraintes classées en trois niveaux — **dures**,
  **medium**, **souples** — applicables à la génération du planning.
- Générer automatiquement un planning respectant les contraintes dures, en
  optimisant au mieux les contraintes medium et souples.
- Produire deux types de livrables : un planning individuel par animateur, et un
  planning global consolidé pour l'organisation.
- Permettre des ajustements manuels après génération, avec revalidation
  automatique des contraintes dures.

### 1.1 Périmètre (à valider)

Le périmètre couvre la saisie des données animateurs et stands, la configuration
des contraintes, la génération et l'ajustement du planning, ainsi que son export.
Sont exclus, sauf indication contraire : la paie, la gestion des contrats de
travail, et la billetterie du festival.

---

## 2. Acteurs et rôles

| Rôle | Description | Actions clés |
|---|---|---|
| **Administrateur / organisateur** | Responsable de la programmation du festival | Configure les contraintes, les stands, valide le planning généré, effectue les ajustements manuels |
| **Référent planning** | Personne en charge du planning au quotidien | Suit les rotations de repos, gère les imprévus (absence, remplacement) |
| **Animateur majeur** | Bénévole ou salarié majeur | Déclare ses disponibilités et compétences, consulte son planning |
| **Animateur mineur** | Bénévole ou salarié de moins de 18 ans | Idem, avec contraintes légales renforcées et, selon le statut, accord du représentant légal |
| **Responsable légal** (si applicable) | Encadrant / référent des mineurs sur site | Validation des affectations de mineurs, présence obligatoire selon les créneaux |

---

## 3. Modèle de données (entités principales)

### 3.1 Animateur

- Identité : nom, prénom, date de naissance (→ calcul automatique majeur/mineur et
  tranche d'âge 14-15 / 16-17)
- Statut : bénévole ou salarié (le cadre légal diffère selon le statut)
- Compétences : liste des jeux/stands maîtrisés, avec niveau (débutant / autonome /
  référent)
- Disponibilités déclarées sur les 15 jours et créneaux
- Historique : jours travaillés, jours de repos déjà attribués (pour piloter la
  rotation)
- Contact représentant légal (si mineur)

### 3.2 Jeu / Stand

- Nom, description, compétences requises pour l'animer
- Effectif minimum et maximum d'animateurs simultanés
- Plages horaires d'ouverture du stand
- Le cas échéant, restriction d'accès aux mineurs (stand nécessitant un encadrement
  renforcé)

### 3.3 Créneau

- Jour (J1 à J15), heure de début, heure de fin
- Rattaché à un stand et à un ou plusieurs animateurs affectés

### 3.4 Affectation / Planning

- Association animateur × créneau × stand, avec statut (proposé, validé, modifié
  manuellement)

### 3.5 Modélisation technique (pattern Timefold)

Voir `CLAUDE.md` pour le détail des classes (`Animateur`, `Stand`, `Creneau`,
`PosteAffectation`, `PlanningFestival`) et leur mapping vers les contraintes
ci-dessous. Point clé : un `PosteAffectation` est créé par place à pourvoir (pas
une seule entité par couple stand × créneau), ce qui permet de gérer l'effectif
min/max nativement.

---

## 4. Référentiel de contraintes

Trois niveaux de contraintes sont proposés, chacun ayant un traitement différent
dans le moteur de génération :

- **Dure (bloquante)** — ne peut jamais être violée ; le planning est invalide si
  une contrainte dure n'est pas respectée.
- **Medium (forte priorité)** — doit être respectée autant que possible ; une
  violation est possible mais pénalisée fortement et doit être signalée à
  l'organisateur.
- **Souple (préférence)** — optimisée « au mieux » ; sert à départager plusieurs
  plannings valides, sans jamais empêcher la génération.

### 4.1 Contraintes liées au temps de travail et au repos

> ⚠️ **Cadre de référence** : Code du travail français relatif aux jeunes
> travailleurs de moins de 18 ans (art. L.3162-1 et s., L.3163-1 et s., L.3164-2,
> D.4153-3 et s.). Ces règles évoluent et comportent des cas particuliers
> (spectacle, dérogations de l'inspection du travail) : elles doivent être
> confirmées avec un juriste en droit social ou l'inspection du travail avant mise
> en production, notamment pour le statut exact des animateurs (bénévolat
> associatif vs contrat de travail, qui ne relèvent pas des mêmes règles).

| Contrainte | Niveau | Justification / commentaire |
|---|:---:|---|
| Durée de travail continue ≤ 4h30 sans pause, puis pause ≥ 30 min pour tout mineur | **DUR** | Art. L.3162-3 — s'applique à tous les mineurs quel que soit l'âge |
| Durée quotidienne de travail ≤ 8h pour un mineur de 16-17 ans (7h en période de vacances scolaires pour un jeune non lié par contrat de travail classique) | **DUR** | Art. D.4153-3 — à confirmer selon le statut exact (bénévole/salarié) |
| Durée hebdomadaire ≤ 35h pour un mineur | **DUR** | Art. L.3162-1 |
| Repos quotidien ≥ 12h consécutives (16-17 ans) ou ≥ 14h (moins de 16 ans) | **DUR** | Art. L.3164-2 |
| Repos hebdomadaire ≥ 2 jours consécutifs pour un mineur | **DUR** | Dérogation possible à 36h consécutives dans certains cas encadrés |
| Interdiction de travail de nuit pour un mineur (22h-6h pour 16-17 ans, 20h-6h pour 14-15 ans) | **DUR** | Dérogations très encadrées possibles pour le spectacle, à valider au cas par cas |
| Durée de travail / repos pour les majeurs (à définir : conventions internes du festival) | **DUR** | Cadre légal général du travail ou charte bénévole à préciser |
| Nombre de jours de repos consécutifs équivalent pour tous les mineurs sur la durée du festival | **MEDIUM** | Équité entre profils, au-delà du minimum légal |

### 4.2 Contraintes liées à l'âge et à l'encadrement

| Contrainte | Niveau | Justification / commentaire |
|---|:---:|---|
| Âge minimum global pour être animateur (seuil à définir, ex. 14 ou 16 ans) | **DUR** | À fixer selon la politique du festival et le statut (bénévole/salarié) |
| Présence d'un encadrant majeur qualifié sur tout créneau incluant un mineur | **DUR** | Exigence de sécurité et, potentiellement, exigence légale selon le statut |
| Accord du représentant légal enregistré avant toute affectation d'un mineur | **DUR** | Prérequis administratif avant intégration au planning |
| Certains stands réservés aux animateurs majeurs (matériel sensible, horaires tardifs) | **DUR** | Dépend du stand — configurable |
| Répartition équilibrée du nombre de mineurs par créneau (éviter une majorité de mineurs sans encadrement suffisant) | **MEDIUM** | Bonne pratique organisationnelle |

### 4.3 Contraintes liées aux compétences

| Contrainte | Niveau | Justification / commentaire |
|---|:---:|---|
| Un animateur ne peut être affecté qu'à un stand pour lequel il déclare la compétence requise | **DUR** | Cœur du système d'affectation |
| Présence d'au moins un animateur « référent » (niveau expert) par créneau sur les stands complexes | **MEDIUM** | Qualité d'animation |
| Mixité des niveaux d'expérience sur un même créneau (référent + débutant en binôme) | **SOFT** | Formation continue des débutants |
| Variation des stands proposés à un même animateur sur la durée du festival | **SOFT** | Éviter la lassitude, favoriser la polyvalence |

### 4.4 Contraintes liées à la disponibilité et à l'équité

| Contrainte | Niveau | Justification / commentaire |
|---|:---:|---|
| Un animateur n'est jamais affecté sur un créneau où il s'est déclaré indisponible | **DUR** | Respect des disponibilités déclarées |
| Effectif minimum requis par stand et créneau | **DUR** | Le stand ne peut ouvrir sans effectif minimal |
| Effectif maximum par stand et créneau | **DUR** | Contrainte d'espace / de matériel |
| Équilibrage du nombre total de créneaux travaillés entre animateurs de disponibilité comparable | **MEDIUM** | Équité de charge |
| Rotation des jours de repos « premium » (week-ends, soirées à forte affluence) | **MEDIUM** | Éviter que ce soit toujours les mêmes qui travaillent les créneaux difficiles |
| Prise en compte des préférences de créneaux ou de stands exprimées par l'animateur | **SOFT** | Satisfaction individuelle |
| Regroupement d'animateurs qui souhaitent travailler ensemble | **SOFT** | Ambiance d'équipe, à activer en option |

---

## 5. Fonctionnalités attendues

### 5.1 Gestion des données

- Création / import des animateurs (saisie manuelle ou import fichier — CSV, Excel)
- Formulaire de déclaration des disponibilités et compétences (auto-service
  animateur, ou saisie par l'administrateur)
- Gestion des stands et de leurs besoins (effectifs, compétences, horaires)
- Configuration du référentiel de contraintes (activation, pondération des
  contraintes medium/soft)

### 5.2 Génération du planning

- Lancement d'une génération automatique respectant les contraintes dures et
  optimisant medium/soft
- Détection et signalement des cas impossibles à satisfaire (ex. pas assez
  d'animateurs qualifiés pour un stand)
- Score de qualité du planning généré (niveau de respect des contraintes
  medium/soft)

### 5.3 Ajustement et validation

- Édition manuelle du planning avec revalidation automatique des contraintes dures
  en temps réel
- Historique des modifications manuelles

### 5.4 Restitution

- Planning individuel par animateur (vue calendrier, export PDF, envoi par email)
- Planning global (vue par stand et par jour, filtrable par animateur, stand ou
  statut majeur/mineur)
- Tableau de bord de suivi : taux d'occupation des stands, répartition des repos,
  alertes de conformité

---

## 6. Architecture technique

Le cœur du problème est un problème d'optimisation sous contraintes (assignation
animateur × créneau × stand), à l'échelle d'environ 150 animateurs sur 15 jours.

### 6.1 Moteur de génération : Timefold Solver

- **Choix retenu** : Timefold Solver (édition Community, open source, licence
  Apache) — continuation activement maintenue du projet OptaPlanner, dont le build
  Red Hat est en fin de vie. Java/Kotlin.
- **Pourquoi ce choix** : Timefold est spécifiquement conçu pour les problèmes de
  type « employee rostering » et propose nativement un modèle de score en trois
  niveaux (`HardMediumSoftScore`), qui correspond directement au référentiel dur /
  medium / soft défini en section 4 — sans avoir à recoder cette logique.
- **Important** : Timefold Solver est uniquement un moteur de calcul (bibliothèque
  appelée par le backend) : il ne fournit pas d'interface de gestion de planning.
  Toute la couche de visualisation et d'édition reste à développer (sections 6.3
  et 5.4).

### 6.2 Backend / API

- **Choix retenu** : **Quarkus** (Java), en service unique qui encapsule
  directement Timefold Solver — pas de micro-service séparé pour le solveur.
  Quarkus dispose de quickstarts officiels Timefold, démarre rapidement et se
  prête bien à un déploiement conteneurisé léger.
- **API** : endpoints REST exposés par ce même service (ex. `/api/solve`,
  `/api/animateurs`, `/api/planning`) pour la gestion des données métier et le
  déclenchement de la génération de planning.

### 6.3 Frontend

- **Choix retenu** : JavaScript **vanilla** (sans framework), servi directement
  par Quarkus en tant que ressources statiques
  (`src/main/resources/META-INF/resources`) — un seul déploiement, aucune
  dépendance Node ni build frontend séparé à orchestrer.
- **Communication** : appels REST natifs (`fetch`) vers les endpoints exposés par
  le même service Quarkus.
- **À prévoir vu le volume (150 animateurs)** : des filtres robustes (par stand,
  par jour, par statut mineur/majeur, par animateur) plutôt qu'un tableau unique —
  la vue globale doit rester lisible à cette échelle.
- **Interface self-service animateur** : formulaire web pour que chaque animateur
  déclare lui-même ses disponibilités et compétences, plutôt qu'une saisie
  centralisée par l'administrateur — indispensable à 150 profils.

### 6.4 Base de données

PostgreSQL : modèle relationnel adapté aux entités définies en section 3
(animateurs, stands, créneaux, affectations), avec contraintes d'intégrité pour
prévenir les doublons d'affectation.

### 6.5 Points d'attention liés à l'échelle (150 animateurs)

- **Temps de résolution** : fixer une limite de calcul (ex. 1 à quelques minutes) :
  Timefold trouve rapidement une bonne solution puis continue à l'améliorer
  jusqu'à la limite fixée — on garde la meilleure trouvée plutôt que d'attendre un
  optimum théorique.
- **Régénération incrémentale** : après un ajustement manuel ou une absence de
  dernière minute, éviter de relancer une résolution « à froid » qui rebattrait
  les affectations de 150 personnes. Timefold permet de repartir d'une solution
  existante et de minimiser le nombre de changements (planification par
  « pinning » des affectations déjà validées).
- **Import en masse** : prévoir dès le MVP un import CSV/Excel des animateurs,
  disponibilités et compétences plutôt qu'une saisie unitaire, pour ne pas créer
  de goulot d'étranglement administratif.

### 6.6 Hébergement & RGPD

Les données concernant des mineurs (identité, contact du représentant légal)
imposent un hébergement en France ou dans l'UE avec chiffrement des données
sensibles. Un hébergeur français (OVH, Scaleway) simplifie la conformité RGPD.

### 6.7 Conteneurisation

L'application est conteneurisée avec **Docker Compose**, orchestrant a minima :

- le service applicatif Quarkus (Dockerfile multi-stage : build Maven/Gradle puis
  image runtime légère type `eclipse-temurin` JRE, ou build natif GraalVM
  envisageable ultérieurement pour réduire le temps de démarrage) ;
- le service PostgreSQL avec volume de persistance ;
- des variables d'environnement pour la configuration (connexion base de données,
  etc.) plutôt que des valeurs en dur.

Ce mode de déploiement facilite la mise en place d'environnements reproductibles
(développement, recette, production) et simplifie l'hébergement chez un
prestataire français.

---

## 7. Points de vigilance

- **Cadre légal réel** : le statut exact des animateurs (bénévoles associatifs,
  salariés en CDD, stagiaires) change les règles applicables. Une vérification
  avec un juriste en droit social ou l'inspection du travail est fortement
  recommandée avant la mise en production, en particulier pour les mineurs.
- **Cas non solvables** : avec des contraintes dures nombreuses, il est possible
  qu'aucun planning ne satisfasse toutes les contraintes (ex. pas assez
  d'animateurs qualifiés). L'application doit pouvoir identifier précisément
  quelle contrainte bloque, plutôt que d'échouer silencieusement.
- **Qualité des données déclarées** : la fiabilité du planning dépend de la
  fiabilité des disponibilités et compétences déclarées par les animateurs —
  prévoir une phase de vérification.
- **Confidentialité des données mineurs** : les données concernant des mineurs
  (identité, contact du représentant légal) nécessitent une attention
  particulière en matière de protection des données (RGPD).

---

## 8. Prochaines étapes proposées

- [ ] Valider le périmètre (section 1.1) et le statut exact des animateurs
      (bénévolat vs salariat)
- [ ] Confirmer avec un juriste ou l'inspection du travail les seuils légaux
      applicables (sections 4.1 et 4.2)
- [ ] Compléter et prioriser le référentiel de contraintes medium et soft
      (sections 4.3 et 4.4)
- [ ] Confirmer les compétences de l'équipe de développement sur Quarkus /
      Timefold Solver (section 6.2)
- [ ] Définir une V1 minimale (MVP) : quelles fonctionnalités de la section 5
      sont indispensables au lancement ?

---

*Voir aussi `CLAUDE.md` à la racine du dépôt pour le contexte destiné à Claude
Code (modèle de données technique, conventions de travail, structure de
dossiers).*
