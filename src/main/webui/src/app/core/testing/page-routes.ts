// The pages the application serves, read from `app.routes.ts`: what the
// palette, the menu and the `g`+letter table are checked against.

import { Route } from '@angular/router';
import { routes } from '../../app.routes';

/**
 * Every page as an address: the top-level routes and the children of the
 * admin shell, without the login page, the stand manager's own screens
 * (`/responsable`, opened by a right of their own, not an admin destination),
 * a parameter or the wildcard.
 */
export function pageRoutes(): string[] {
  const shell = routes.find((route) => route.path === '' && route.children);
  return [...routes, ...(shell?.children ?? [])]
    .filter(
      (route: Route) =>
        route.loadComponent !== undefined &&
        route.children === undefined &&
        route.path !== undefined &&
        route.path !== 'login' &&
        !route.path.startsWith('responsable') &&
        !route.path.includes(':') &&
        !route.path.includes('*'),
    )
    .map((route) => (route.path === '' ? '/' : `/${route.path}`));
}
