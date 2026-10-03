const zIndex: { [key: number]: string } = {};
for (let i = -1; i <= 100; i++) {
  zIndex[i] = `${i}`;
}

module.exports = {
  content: [
    './app/app.vue',
    './app/pages/index.vue',
    './app/pages/**/*.vue',
    './app/components/*.vue',
    './app/components/**/*.vue',
  ],
  darkMode: 'class',
  theme: {
    extend: {
      fontFamily: {
        serif: ['Songti SC', 'STSong', 'serif'],
        sans: ['PingFang SC', 'Microsoft YaHei', '-apple-system', 'BlinkMacSystemFont', 'Segoe UI', 'Roboto', 'Helvetica Neue', 'Arial', 'sans-serif'],
      },
      colors: {
        red: {
          DEFAULT: '#205d63', // color-fireball
          50: '#FBE2DF',
          100: '#F9D1CD',
          200: '#F4B0A9',
          300: '#F08F85',
          400: '#EB6D60',
          500: '#205d63',
          600: '#D12B1A',
          700: '#9F2114',
          800: '#6D160D',
          900: '#3B0C07',
          950: '#220704',
        },

        fireball: '#205d63',
        blood: '#15434a',
        mana: '#166c9c',
        fog: '#edf5f2',
        smoke: '#c8d9d6',
        granite: '#637b7d',
        basalt: '#333',
        charcoal: '#263f46',
        darkness: '#152f36',
      },

      // adds z-index from 1 to 100
      zIndex: zIndex,

      animation: {
        'pulse-orange': 'pulse-orange 2s ease-in-out infinite',
        'pulse-orange-dark': 'pulse-orange-dark 2s ease-in-out infinite',
      },
      keyframes: (theme: (path: string) => string) => ({
        'pulse-orange': {
          '0%, 100%': { backgroundColor: theme('colors.orange.200') },
          '50%': { backgroundColor: theme('colors.orange.300') },
        },
        'pulse-orange-dark': {
          '0%, 100%': { backgroundColor: theme('colors.orange.700') },
          '50%': { backgroundColor: theme('colors.orange.600') },
        },
      }),
    },
  },
  plugins: [],
};
