---
url: /login
title: Login
---

Form login (Quarkus form auth, cookie `planning-session`).

Credentials for the local dev instance:
- utilisateur / user: `admin`
- mot de passe / password: `admin`

The submit button ("Se connecter") stays disabled until both fields are filled.
On success the app lands on the Solveur page (`/`). A failed login shows an
inline error, not a page reload.

Unauthenticated access to any admin route redirects to `/login`.
