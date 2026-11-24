// Auto / light / dark switch. Loaded blocking in <head> so the stored choice is on <html>
// before first paint; auto means no data-theme, the stylesheet then follows the system.
// Without script the button stays hidden and the site follows the system.
const STATES = ['auto', 'clair', 'sombre'];
const ATTR = { clair: 'light', sombre: 'dark' };
const COLORS = ['#f8f2e6', '#1f1b16']; // the two theme-color metas of base.njk: light, dark
const root = document.documentElement;

let state = 'auto';
try {
  const saved = localStorage.getItem('theme');
  if (STATES.includes(saved)) state = saved;
} catch {
  // storage blocked: the choice lasts for this page only
}

const metas = [...document.querySelectorAll('meta[name="theme-color"]')];
const apply = () => {
  if (ATTR[state]) root.dataset.theme = ATTR[state];
  else delete root.dataset.theme;
  // a forced theme must also drive the browser chrome, whatever the system says
  metas.forEach((m, i) => { m.content = state === 'sombre' ? COLORS[1] : state === 'clair' ? COLORS[0] : COLORS[i]; });
};
apply();

document.addEventListener('DOMContentLoaded', () => {
  const button = document.querySelector('[data-theme-toggle]');
  if (!button) return;
  const sync = () => {
    button.dataset.state = state;
    button.setAttribute('aria-label', button.dataset[`t${state[0].toUpperCase()}${state.slice(1)}`]);
  };
  sync();
  button.hidden = false;
  button.addEventListener('click', () => {
    state = STATES[(STATES.indexOf(state) + 1) % STATES.length];
    try {
      if (state === 'auto') localStorage.removeItem('theme');
      else localStorage.setItem('theme', state);
    } catch {
      // see above
    }
    apply();
    sync();
  });
});
