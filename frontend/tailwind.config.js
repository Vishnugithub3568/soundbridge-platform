/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx,ts,tsx}'],
  theme: {
    extend: {
      colors: {
        sand: '#f5efe6',
        clay: '#dfcab0',
        ink: '#1f1b16',
        mint: '#1f7a59',
        ember: '#d15a3d'
      },
      fontFamily: {
        display: ['Space Grotesk', 'sans-serif'],
        mono: ['IBM Plex Mono', 'monospace']
      },
      boxShadow: {
        panel: '0 14px 30px rgba(74, 49, 8, 0.12)'
      },
      keyframes: {
        shimmer: {
          '0%': { transform: 'translateX(-100%)' },
          '100%': { transform: 'translateX(200%)' }
        }
      },
      animation: {
        shimmer: 'shimmer 1.6s infinite linear'
      }
    }
  },
  plugins: []
};
