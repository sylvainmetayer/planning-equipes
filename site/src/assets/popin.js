// Screenshots open full screen in a native <dialog>. Without script, or without
// <dialog>, each link simply opens the image itself. Links sharing a data-popin
// value form a group the arrow keys and the two buttons walk through.
const dialog = document.querySelector('[data-popin-dialog]');

if (dialog && typeof dialog.showModal === 'function') {
  const caption = dialog.querySelector('figcaption');
  // The <img> is made here: an empty one in the markup would carry no src
  const img = new Image();
  caption.before(img);
  const prev = dialog.querySelector('[data-popin-prev]');
  const next = dialog.querySelector('[data-popin-next]');
  let group = [];
  let index = 0;
  let opener = null;

  const show = (i) => {
    index = (i + group.length) % group.length;
    const link = group[index];
    img.src = link.href;
    img.alt = link.querySelector('img')?.alt ?? '';
    caption.textContent = link.dataset.caption ?? '';
    prev.hidden = next.hidden = group.length < 2;
  };

  document.addEventListener('click', (event) => {
    const link = event.target.closest('a[data-popin]');
    if (!link || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    group = [...document.querySelectorAll(`a[data-popin="${link.dataset.popin}"]`)];
    opener = link;
    show(group.indexOf(link));
    dialog.showModal();
  });

  prev.addEventListener('click', () => show(index - 1));
  next.addEventListener('click', () => show(index + 1));
  dialog.querySelector('[data-popin-close]').addEventListener('click', () => dialog.close());
  // A click on the dialog itself, not on the picture or a button, is a click outside
  dialog.addEventListener('click', (event) => {
    if (event.target === dialog) dialog.close();
  });
  dialog.addEventListener('keydown', (event) => {
    if (group.length < 2) return;
    if (event.key === 'ArrowLeft') show(index - 1);
    else if (event.key === 'ArrowRight') show(index + 1);
    else return;
    event.preventDefault();
  });
  // Escape closes natively; focus goes back to the thumbnail that opened it
  dialog.addEventListener('close', () => {
    img.removeAttribute('src');
    opener?.focus();
  });
}
