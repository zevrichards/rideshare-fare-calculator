import {useColorScheme} from 'react-native';

export interface ThemeColors {
  background: string;
  surface: string;
  panelBg: string;
  surfaceAlt: string;
  border: string;
  borderSubtle: string;
  textPrimary: string;
  textSecondary: string;
  textMuted: string;
  placeholder: string;
  primary: string;
  onPrimary: string;
  danger: string;
  onDanger: string;
  disclaimerText: string;
  bannerBg: string;
  bannerText: string;
  bannerButtonBg: string;
  bannerButtonText: string;
}

const lightColors: ThemeColors = {
  background: '#ffffff',
  surface: '#ffffff',
  panelBg: '#f5f5f5',
  surfaceAlt: '#e8f0fe',
  border: '#cccccc',
  borderSubtle: '#eeeeee',
  textPrimary: '#1a1a1a',
  textSecondary: '#555555',
  textMuted: '#777777',
  placeholder: '#999999',
  primary: '#1a73e8',
  onPrimary: '#ffffff',
  danger: '#d93025',
  onDanger: '#ffffff',
  disclaimerText: '#d93025',
  bannerBg: '#fef7e0',
  bannerText: '#5f5024',
  bannerButtonBg: '#e8a712',
  bannerButtonText: '#ffffff',
};

// Not just an inverted light palette -- picked for contrast against a near-
// black background specifically (e.g. disclaimerText is a brighter red than
// the light theme's, since that same red on #121212 reads poorly).
const darkColors: ThemeColors = {
  background: '#121212',
  surface: '#1e1e1e',
  panelBg: '#242424',
  surfaceAlt: '#1a2a3f',
  border: '#4d4d4d',
  borderSubtle: '#333333',
  textPrimary: '#f5f5f5',
  textSecondary: '#c2c2c2',
  textMuted: '#9a9a9a',
  placeholder: '#8a8a8a',
  primary: '#4c9aff',
  onPrimary: '#ffffff',
  danger: '#ff5252',
  onDanger: '#ffffff',
  disclaimerText: '#ff6b6b',
  bannerBg: '#3a2f10',
  bannerText: '#f5d67d',
  bannerButtonBg: '#e8a712',
  bannerButtonText: '#1a1a1a',
};

export function useThemeColors(): ThemeColors {
  const scheme = useColorScheme();
  return scheme === 'dark' ? darkColors : lightColors;
}
