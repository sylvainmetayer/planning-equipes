# Rules for this app

- The UI is French. Buttons are "Enregistrer", "Annuler", "Supprimer",
  "Ajouter", "Valider", "Fermer".
- **Never click "Vider la base de données"** (Paramètres / Debug page) — it wipes
  every stand, créneau, animateur and assignment and leaves nothing to test.
- **Never start a solve** ("Résoudre avec Timefold" on the Solveur page): it runs
  for minutes in the background and locks the reference-data screens while it
  runs. If a screen says it is locked by a running job, move to another screen.
- Do not delete the current *édition* on `/editions`, and do not switch the
  current edition unless the test is about editions.
- Prefer creating your own records with a recognizable prefix (e.g.
  `EXPLORBOT-…`) over editing existing ones, and clean them up afterwards.
- After any save, check for a snackbar toast at the bottom of the screen: the
  toast text is the real result, the dialog closing alone does not prove success.
