/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Vite builds the whole application now — Spring Boot forwards every route it doesn't own itself
// straight to this build's index.html (see SpaController), and React owns the app shell (top bar,
// service switcher, runtime status, command palette) that used to be Thymeleaf's layout.html.
// Output filenames stay fixed (no content hash) for simplicity in a self-contained jar — a new jar
// is already a new deploy, so there's nothing else to cache-bust against.
export default defineConfig({
  plugins: [react()],
  base: '/app/',
  build: {
    outDir: '../modules/app/src/main/resources/static/app',
    emptyOutDir: true,
    rollupOptions: {
      output: {
        entryFileNames: 'main.js',
        chunkFileNames: 'chunk-[name].js',
        assetFileNames: (assetInfo) =>
          assetInfo.names?.some((name) => name.endsWith('.css')) ? 'main.css' : 'assets/[name][extname]',
      },
    },
  },
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:7717' },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: true,
  },
});
