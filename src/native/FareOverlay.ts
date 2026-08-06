import {NativeEventEmitter, NativeModules, Platform} from 'react-native';
import {RateCard} from '../lib/rateCards';

interface FareOverlayNativeModule {
  hasOverlayPermission(): Promise<boolean>;
  requestOverlayPermission(): void;
  hasLocationPermission(): Promise<boolean>;
  startTrip(destLat: number | null, destLng: number | null): Promise<boolean>;
  stopTrip(): void;
  getActiveTrip(): Promise<TripSnapshot | null>;
  getPreferredNavApp(): Promise<string>;
  setPreferredNavApp(packageName: string): void;
  openDefaultAppSettings(): void;
  setSelectedRateCard(id: string): void;
  setRateCard(cardJson: string): void;
  setSurgeMultiplier(value: number): void;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export interface TripCompletedEvent {
  distanceKm: number;
  minutes: number;
  total: number;
}

// Mirrors FareTrackingService.kt's TripSnapshot. estimatedDistanceKm/
// estimatedMinutes are the inputs native used for the pre-trip estimate
// (both null together if no destination was parsed from the intercept) --
// shipped as raw components, not a pre-computed total, so JS can run the
// same calculateFareBreakdown it already uses elsewhere and get a real
// Base/Distance/Time breakdown instead of just a dollar figure.
export interface TripSnapshot {
  distanceKm: number;
  elapsedMinutes: number;
  rateCardId: string;
  surgeMultiplier: number;
  estimatedDistanceKm: number | null;
  estimatedMinutes: number | null;
}

export const NAV_APPS = {
  WAZE: 'com.waze',
  GOOGLE_MAPS: 'com.google.android.apps.maps',
} as const;

export type NavAppPackage = (typeof NAV_APPS)[keyof typeof NAV_APPS];

export const isOverlaySupported = Platform.OS === 'android';

const nativeModule: FareOverlayNativeModule | null = isOverlaySupported
  ? NativeModules.FareOverlay
  : null;

const emitter = nativeModule ? new NativeEventEmitter(NativeModules.FareOverlay) : null;

export async function hasOverlayPermission(): Promise<boolean> {
  if (!nativeModule) {
    return false;
  }
  return nativeModule.hasOverlayPermission();
}

export function requestOverlayPermission(): void {
  nativeModule?.requestOverlayPermission();
}

export async function startOverlayTrip(
  destLat: number | null,
  destLng: number | null,
): Promise<boolean> {
  if (!nativeModule) {
    return false;
  }
  return nativeModule.startTrip(destLat, destLng);
}

export function stopOverlayTrip(): void {
  nativeModule?.stopTrip();
}

// One-time read of a trip already in progress, e.g. TripScreen mounting
// after the overlay was tapped for a trip started via nav-intercept. Returns
// null if no trip is currently running.
export async function getActiveTrip(): Promise<TripSnapshot | null> {
  if (!nativeModule) {
    return null;
  }
  return nativeModule.getActiveTrip();
}

export async function getPreferredNavApp(): Promise<NavAppPackage> {
  if (!nativeModule) {
    return NAV_APPS.WAZE;
  }
  return (await nativeModule.getPreferredNavApp()) as NavAppPackage;
}

export function setPreferredNavApp(packageName: NavAppPackage): void {
  nativeModule?.setPreferredNavApp(packageName);
}

export function openDefaultAppSettings(): void {
  nativeModule?.openDefaultAppSettings();
}

// These mirror JS's AsyncStorage-backed rate card state (src/lib/rateCards.ts,
// the source of truth for the app's own UI) into native SharedPreferences,
// so an intercepted trip -- which never touches JS -- still uses the
// currently-selected card/surge. One-way write-through, not a live sync.
export function mirrorSelectedRateCard(id: string): void {
  nativeModule?.setSelectedRateCard(id);
}

export function mirrorRateCard(card: RateCard): void {
  nativeModule?.setRateCard(JSON.stringify(card));
}

export function mirrorSurgeMultiplier(value: number): void {
  nativeModule?.setSurgeMultiplier(value);
}

export function subscribeToTripCompleted(
  listener: (event: TripCompletedEvent) => void,
): () => void {
  if (!emitter) {
    return () => {};
  }
  const subscription = emitter.addListener('onTripCompleted', listener);
  return () => subscription.remove();
}

// Fires roughly once a second while a trip is running, whether it was
// started via startOverlayTrip() or by a nav-intercept.
export function subscribeToTripTick(
  listener: (snapshot: TripSnapshot) => void,
): () => void {
  if (!emitter) {
    return () => {};
  }
  const subscription = emitter.addListener('onTripTick', listener);
  return () => subscription.remove();
}
