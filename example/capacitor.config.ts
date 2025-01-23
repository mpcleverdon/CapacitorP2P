import { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.example.p2pcounter',
  appName: 'P2P Counter',
  webDir: 'dist',
  plugins: {
    P2PCounter: {
      // any plugin config if needed
    }
  }
};

export default config; 