import AsyncStorage from '@react-native-async-storage/async-storage';

// User-adjustable distance thresholds for RequestAlertOverlay's incoming-
// request flash (see RideTriggerAccessibilityService.kt) -- red beyond the
// far threshold, green under the near threshold, nothing in between.
const FAR_THRESHOLD_STORAGE_KEY = '@rideshare_fare_calc/request_alert_far_km';
const NEAR_THRESHOLD_STORAGE_KEY = '@rideshare_fare_calc/request_alert_near_km';

const DEFAULT_FAR_THRESHOLD_KM = 5;
const DEFAULT_NEAR_THRESHOLD_KM = 1;

export async function getFarRequestThresholdKm(): Promise<number> {
  const raw = await AsyncStorage.getItem(FAR_THRESHOLD_STORAGE_KEY);
  const parsed = raw != null ? Number(raw) : NaN;
  return Number.isFinite(parsed) ? parsed : DEFAULT_FAR_THRESHOLD_KM;
}

export async function setFarRequestThresholdKm(value: number): Promise<void> {
  await AsyncStorage.setItem(FAR_THRESHOLD_STORAGE_KEY, String(value));
}

export async function getNearRequestThresholdKm(): Promise<number> {
  const raw = await AsyncStorage.getItem(NEAR_THRESHOLD_STORAGE_KEY);
  const parsed = raw != null ? Number(raw) : NaN;
  return Number.isFinite(parsed) ? parsed : DEFAULT_NEAR_THRESHOLD_KM;
}

export async function setNearRequestThresholdKm(value: number): Promise<void> {
  await AsyncStorage.setItem(NEAR_THRESHOLD_STORAGE_KEY, String(value));
}
