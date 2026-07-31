/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        primary: {
          DEFAULT: '#DEDBC8',
          50: '#F5F4EE',
          100: '#EBE9DD',
          200: '#DEDBC8',
          300: '#C4C0A3',
          400: '#A9A47E',
          500: '#8E8860',
          600: '#716C4C',
          700: '#555139',
          800: '#393627',
          900: '#1C1B14',
        },
        surface: {
          ink: '#000000',
          base: '#0a0a0a',
          card: '#0f0f0f',
          raised: '#141414',
          overlay: '#1a1a1a',
        },
        accent: {
          green: '#4ADE80',
          yellow: '#FBBF24',
          red: '#F87171',
          blue: '#60A5FA',
          purple: '#A78BFA',
        },
      },
      fontFamily: {
        sans: ['Inter', '-apple-system', 'BlinkMacSystemFont', 'Segoe UI', 'sans-serif'],
        mono: ['JetBrains Mono', 'Fira Code', 'monospace'],
      },
      fontSize: {
        '2xs': ['0.625rem', { lineHeight: '0.875rem' }],
      },
      animation: {
        'fade-in': 'fadeIn 200ms ease-out',
        'slide-up': 'slideUp 200ms ease-out',
        'slide-right': 'slideRight 200ms ease-out',
        'pulse-dot': 'pulseDot 1.4s infinite ease-in-out',
        'spin-slow': 'spin 2s linear infinite',
      },
      keyframes: {
        fadeIn: {
          '0%': { opacity: '0' },
          '100%': { opacity: '1' },
        },
        slideUp: {
          '0%': { opacity: '0', transform: 'translateY(8px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        slideRight: {
          '0%': { opacity: '0', transform: 'translateX(-8px)' },
          '100%': { opacity: '1', transform: 'translateX(0)' },
        },
        pulseDot: {
          '0%, 80%, 100%': { opacity: '0.2', transform: 'scale(0.8)' },
          '40%': { opacity: '1', transform: 'scale(1)' },
        },
      },
    },
  },
  plugins: [],
}
