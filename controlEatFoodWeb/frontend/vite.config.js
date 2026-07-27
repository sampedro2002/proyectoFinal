import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['favicon.svg'],
      manifest: {
        name: 'Control de Alimentos Club Castillo Amaguaña',
        short_name: 'Club Castillo',
        description: 'Control de consumo por huella digital (ZK9500)',
        theme_color: '#2166ac',
        background_color: '#0f172a',
        display: 'standalone',
        orientation: 'portrait',
        icons: [
          { src: 'pwa-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'pwa-512.png', sizes: '512x512', type: 'image/png' }
        ]
      },
      workbox: {
        // El registro de consumos NO se cachea: se maneja con cola offline propia (IndexedDB)
        navigateFallbackDenylist: [/^\/api/],
        runtimeCaching: [
          {
            urlPattern: ({ url }) => url.pathname.startsWith('/api/'),
            handler: 'NetworkOnly'
          }
        ]
      }
    })
  ],
  server: {
    host: true,
    port: 4173,
    proxy: {
      '/api': { target: 'http://localhost:3000', changeOrigin: true },
      '/zkfinger-ws': { target: 'ws://localhost:3000', ws: true }
    }
  },
  build: {
    rollupOptions: {
      output: {
        // Separa las librerias pesadas en chunks propios para que el bundle
        // principal no supere el umbral de 500 kB y el navegador cachee por
        // separado lo que cambia poco (vendor) de lo que cambia con la app.
        manualChunks: {
          'react-vendor': ['react', 'react-dom', 'react-router-dom'],
          'charts': ['recharts'],
          'qr': ['qrcode']
        }
      }
    }
  }
});
