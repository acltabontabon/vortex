import { defineConfig } from 'vite';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));

// Unlike web/vite.config.ts (which disables hashing because that build is baked into a versioned
// jar with nothing else to cache-bust against), this site redeploys to GitHub Pages independently
// of any jar release — so default (hashed) output filenames are kept, giving returning visitors
// real caching benefit. `base` stays relative since there's no custom domain (no www/CNAME) and
// GitHub Pages serves a project site from a repo subpath.
export default defineConfig({
  base: './',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      input: {
        main: resolve(__dirname, 'index.html'),
        docs: resolve(__dirname, 'docs.html'),
      },
    },
  },
  server: {
    port: 5174,
  },
});
