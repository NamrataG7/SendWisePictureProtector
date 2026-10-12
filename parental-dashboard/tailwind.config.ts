import type { Config } from 'tailwindcss'

const config: Config = {
  content: [
    './pages/**/*.{js,ts,jsx,tsx,mdx}',
    './components/**/*.{js,ts,jsx,tsx,mdx}',
    './app/**/*.{js,ts,jsx,tsx,mdx}',
  ],
  theme: {
    extend: {
      colors: {
        urgent: '#dc2626',
        critical: '#ea580c',
        high: '#f59e0b',
        medium: '#eab308',
        low: '#84cc16',
      },
    },
  },
  plugins: [],
}
export default config
