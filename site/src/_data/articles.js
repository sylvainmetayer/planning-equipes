// The articles of the French Labour Code the application APPLIES as rules, in both
// languages (a cited derogation such as R3164-2 is not applied, so not listed).
// Not edited in Pages CMS: each number must match ConstraintCatalog, and
// test/articles.test.mjs checks that against ConstraintCatalog.java. A rule that
// changes its legal basis changes here. Every count the pages state comes from
// this list (countWords in src/lib.js, filled by copy.js): never type one.
const articles = [
  {
    number: 'L3121-18',
    fr: { subject: 'Durée quotidienne', rule: "10 heures de travail effectif par jour au plus pour un majeur. Les pauses dues sont déduites." },
    en: { subject: 'Daily hours', rule: 'At most 10 hours of actual work a day for an adult. Breaks that are due are deducted.' },
  },
  {
    number: 'L3121-20',
    fr: { subject: 'Durée hebdomadaire', rule: "48 heures par semaine au plus pour un majeur, seuil réglable." },
    en: { subject: 'Weekly hours', rule: 'At most 48 hours a week for an adult, an adjustable threshold.' },
  },
  {
    number: 'L3121-22',
    fr: { subject: 'Durée sur deux semaines', rule: "Pas deux semaines ISO consécutives au plafond hebdomadaire : 48 h puis 48 h est refusé, 47 h puis 48 h reste permis." },
    en: { subject: 'Hours over two weeks', rule: 'Not two consecutive ISO weeks at the weekly ceiling: 48 h then 48 h is refused, 47 h then 48 h is allowed.' },
  },
  {
    number: 'L3121-16',
    fr: { subject: 'Pause', rule: "Au-delà de 6 heures de travail continu, une pause d'au moins 20 minutes : un trou dans la grille, ou un collègue du stand qui relaie." },
    en: { subject: 'Breaks', rule: 'After 6 hours of continuous work, a break of at least 20 minutes: a gap in the grid, or a colleague on the stand covering.' },
  },
  {
    number: 'L3131-1',
    fr: { subject: 'Repos quotidien', rule: "11 heures de repos entre deux journées travaillées pour un majeur." },
    en: { subject: 'Daily rest', rule: '11 hours of rest between two working days for an adult.' },
  },
  {
    number: 'L3132-1',
    fr: { subject: 'Jours travaillés', rule: "Six jours travaillés par semaine au plus, pour tout animateur." },
    en: { subject: 'Working days', rule: 'At most six working days a week, for every staff member.' },
  },
  {
    number: 'L3132-2',
    fr: { subject: 'Repos hebdomadaire', rule: "24 heures consécutives, auxquelles s'ajoutent les 11 heures de repos quotidien : 35 heures par semaine." },
    en: { subject: 'Weekly rest', rule: '24 consecutive hours, plus the 11 hours of daily rest: 35 hours each week.' },
  },
  {
    number: 'L3162-1',
    mineurs: true,
    fr: { subject: 'Mineurs : durée', rule: "8 heures par jour et 35 par semaine au plus, ramenées à 7 heures par jour avant 16 ans." },
    en: { subject: 'Under-18s: hours', rule: 'At most 8 hours a day and 35 a week, down to 7 hours a day before 16.' },
  },
  {
    number: 'L3162-3',
    mineurs: true,
    fr: { subject: 'Mineurs : pause', rule: "Une pause d'au moins 30 minutes après 4 h 30 de travail continu." },
    en: { subject: 'Under-18s: breaks', rule: 'A break of at least 30 minutes after 4 hours 30 of continuous work.' },
  },
  {
    number: 'L3163-1',
    mineurs: true,
    fr: { subject: 'Mineurs : nuit', rule: "Aucun poste entre 20 h et 6 h avant 16 ans, entre 22 h et 6 h de 16 à 18 ans." },
    en: { subject: 'Under-18s: nights', rule: 'No shift between 20:00 and 06:00 before 16, between 22:00 and 06:00 from 16 to 18.' },
  },
  {
    number: 'L3164-1',
    mineurs: true,
    fr: { subject: 'Mineurs : repos quotidien', rule: "12 heures de repos entre deux journées travaillées de 16 à 18 ans, 14 heures avant 16 ans." },
    en: { subject: 'Under-18s: daily rest', rule: '12 hours of rest between two working days from 16 to 18, 14 hours before 16.' },
  },
  {
    number: 'L3164-2',
    mineurs: true,
    fr: { subject: 'Mineurs : repos', rule: "Deux jours de repos consécutifs dans chaque semaine, du lundi au dimanche." },
    en: { subject: 'Under-18s: rest', rule: 'Two consecutive rest days in every week, Monday to Sunday.' },
  },
  {
    number: 'L3164-6',
    mineurs: true,
    fr: { subject: 'Mineurs : jours fériés', rule: "Aucun poste un jour férié légal." },
    en: { subject: 'Under-18s: public holidays', rule: 'No shift on a legal public holiday.' },
  },
];

export default articles;
