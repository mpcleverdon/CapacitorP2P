export default {
  input: 'dist/esm/index.js',
  output: [
    {
      file: 'dist/plugin.js',
      format: 'iife',
      name: 'capacitorP2PCounter',
      globals: {
        '@capacitor/core': 'capacitorExports'
      },
      sourcemap: true
    },
    {
      file: 'dist/plugin.cjs.js',
      format: 'cjs',
      sourcemap: true
    }
  ],
  external: ['@capacitor/core']
}; 