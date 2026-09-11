/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Pin the workspace root to this project; otherwise Turbopack walks up and
  // trips over an unrelated package-lock.json in the home directory.
  turbopack: {
    root: __dirname,
  },
};

module.exports = nextConfig;
