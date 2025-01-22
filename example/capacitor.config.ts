import { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'tools.bink.p2pcounter',
  appName: 'P2P Counter',
  webDir: 'dist',
  server: {
    androidScheme: 'https'
  },
  plugins: {
    P2PCounter: {
      // any plugin config options
    }
  }
};

export default config; 