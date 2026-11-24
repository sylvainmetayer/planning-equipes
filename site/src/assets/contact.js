// The Tally form speaks the iframe-resizer protocol: once asked, it reports its height.
// Without this script the iframe keeps the fixed height of the stylesheet.
const ORIGIN = 'https://tally.so';
const frame = document.querySelector('.form__frame');

if (frame) {
  let answered = false;

  addEventListener('message', (event) => {
    if (event.origin !== ORIGIN || event.source !== frame.contentWindow || typeof event.data !== 'string') return;
    // "[iFrameSizer]tally:<height>:<width>:<type>"; the title and mouse messages are 0:0, the height is sometimes fractional
    const [head, height, width] = event.data.split(':');
    const px = Math.ceil(Number.parseFloat(height));
    if (head !== '[iFrameSizer]tally' || !(px > 0) || !(Number.parseFloat(width) > 0)) return;
    answered = true;
    const before = frame.offsetHeight;
    frame.style.height = `${Math.min(Math.max(px, 400), 4000)}px`;
    // A shorter page (next step, thank-you): bring the top of the sheet back if it scrolled above the viewport
    const sheet = frame.parentElement;
    if (frame.offsetHeight < before && sheet.getBoundingClientRect().top < 0) {
      sheet.scrollIntoView({ block: 'start', behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
    }
  });

  const ask = () => {
    for (const delay of [0, 300, 1000]) {
      setTimeout(() => {
        if (!answered) frame.contentWindow?.postMessage('[iFrameSizer]tally:8:false:false:32:true:true:null:bodyOffset:null:null:0:false:parent:scroll:true', ORIGIN);
      }, delay);
    }
  };
  frame.addEventListener('load', ask);
}
