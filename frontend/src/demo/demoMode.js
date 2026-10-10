import { createDemoAdapter } from './mockAdapter';

export const DEMO_MODE = String(import.meta.env.VITE_DEMO_MODE || '').toLowerCase() === 'true';

export function installDemoAdapter(api) {
  if (!DEMO_MODE) return;
  api.defaults.adapter = createDemoAdapter();
}
