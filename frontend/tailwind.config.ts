import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./src/**/*.{js,ts,jsx,tsx,mdx}"],
  theme: {
    extend: {
      colors: {
        brand: { DEFAULT: "#6366f1", light: "#818cf8", dark: "#4f46e5" },
      },
    },
  },
  plugins: [],
};
export default config;
