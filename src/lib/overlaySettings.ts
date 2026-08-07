import AsyncStorage from '@react-native-async-storage/async-storage';

// User-adjustable multiplier for the floating overlay's text size and
// padding (see android's FareOverlayView). Separate from rateCards.ts --
// this is a UI preference, not a fare-calculation input.
const OVERLAY_SCALE_STORAGE_KEY = '@rideshare_fare_calc/overlay_scale';

export async function getOverlayScale(): Promise<number> {
  const raw = await AsyncStorage.getItem(OVERLAY_SCALE_STORAGE_KEY);
  const parsed = raw != null ? Number(raw) : NaN;
  return Number.isFinite(parsed) ? parsed : 1;
}

export async function setOverlayScale(value: number): Promise<void> {
  await AsyncStorage.setItem(OVERLAY_SCALE_STORAGE_KEY, String(value));
}
