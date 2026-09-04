// Explorbot — configuration pour « Planning Équipes ».
// Lancement : cd <ce dossier> && npx explorbot explore /stands --show
//
// Le fournisseur d'IA est choisi par EXPLORBOT_AI_PROVIDER (défaut : openrouter).
// La clé correspondante doit être dans .env (OPENROUTER_API_KEY, ANTHROPIC_API_KEY, …).
// Les identifiants « provider/model » utilisent les paquets fournis par explorbot,
// rien de plus à installer.

const provider = process.env.EXPLORBOT_AI_PROVIDER || 'openrouter';

const MODELS = {
  openrouter: {
    model: 'openrouter/openai/gpt-oss-20b:nitro',
    visionModel: 'openrouter/openai/gpt-5.6-luna',
    agenticModel: 'openrouter/openai/gpt-5.6-luna',
  },
  anthropic: {
    model: 'anthropic/claude-haiku-4-5-20251001',
    visionModel: 'anthropic/claude-haiku-4-5-20251001',
    agenticModel: 'anthropic/claude-haiku-4-5-20251001',
  },
  openai: {
    model: 'openai/gpt-5-nano',
    visionModel: 'openai/gpt-5.6-luna',
    agenticModel: 'openai/gpt-5.6-luna',
  },
  google: {
    model: 'google/gemini-3.1-flash-lite',
    visionModel: 'google/gemini-3.1-flash-lite',
    agenticModel: 'google/gemini-3.5-flash',
  },
  groq: {
    model: 'groq/openai/gpt-oss-20b',
    visionModel: 'groq/qwen/qwen3.6-27b',
    agenticModel: 'groq/qwen/qwen3.6-27b',
  },
};

export default {
  web: {
    // Hôte seulement, sans chemin.
    url: process.env.EXPLORBOT_URL || 'http://localhost:8080',
  },

  playwright: {
    browser: 'chromium',
    show: false,
    windowSize: '1400x1000',
    // Quarkus dev + Angular : premiers chargements lents, et le solveur
    // travaille en tâche de fond.
    timeout: 60000,
    waitForNavigation: 'networkidle',
    waitForTimeout: 1000,
  },

  ai: {
    ...MODELS[provider],
    agents: {
      tester: { rules: ['planning-equipes'] },
      planner: { rules: ['planning-equipes'] },
    },
  },

  dirs: {
    output: './output',
    knowledge: './knowledge',
  },
};
