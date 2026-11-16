import AsyncStorage from '@react-native-async-storage/async-storage';

// When on, a trip's time/distance estimate comes from Waze's screen (refreshed
// about once a minute while Waze is showing) instead of the Places/Routes API
// lookup. A preference like overlaySettings.ts, mirrored to native so the
// accessibility service can read it while the app isn't running.
const USE_WAZE_ESTIMATE_STORAGE_KEY = '@rideshare_fare_calc/use_waze_estimate';

export async function getUseWazeEstimate(): Promise<boolean> {
  return (await AsyncStorage.getItem(USE_WAZE_ESTIMATE_STORAGE_KEY)) === 'true';
}

export async function setUseWazeEstimate(value: boolean): Promise<void> {
  await AsyncStorage.setItem(USE_WAZE_ESTIMATE_STORAGE_KEY, String(value));
}
