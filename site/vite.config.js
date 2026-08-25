import { defineConfig } from 'vite';

// Unlike web/vite.config.ts (which disables hashing because that build is baked into a versioned
// jar with nothing else to cache-bust against), this site redeploys to GitHub Pages independently
// of any jar release — so default (hashed) output filenames are kept, giving returning visitors
// real caching benefit. `base` stays relative since there's no custom domain (no site/CNAME) and
// GitHub Pages serves a project site from a repo subpath.
export default defineConfig({
  base: './',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    port: 5174,
  },
});
