const output = document.getElementById('output');
const button = document.getElementById('solve-btn');

button.addEventListener('click', async () => {
  button.disabled = true;
  output.textContent = 'Chargement...';

  try {
    const sampleResponse = await fetch('/api/planning/sample');
    const sample = await sampleResponse.json();

    const solveResponse = await fetch('/api/solve', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(sample)
    });

    const solved = await solveResponse.json();
    output.textContent = JSON.stringify(solved, null, 2);
  } catch (error) {
    output.textContent = `Erreur: ${error.message}`;
  } finally {
    button.disabled = false;
  }
});
