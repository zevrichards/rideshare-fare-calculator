import {NativeEventEmitter, NativeModules, Platform} from 'react-native';

interface FareOverlayNativeModule {
  hasOverlayPermission(): Promise<boolean>;
  requestOverlayPermission(): void;
  hasLocationPermission(): Promise<boolean>;
  startTrip(destLat: number, destLng: number): Promise<boolean>;
  stopTrip(): void;
  getPreferredNavApp(): Promise<string>;
  setPreferredNavApp(packageName: string): void;
  openDefaultAppSettings(): void;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export interface TripCompletedEvent {
  distanceKm: number;
  minutes: number;
  total: number;
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
  destLat: number,
  destLng: number,
): Promise<boolean> {
  if (!nativeModule) {
    return false;
  }
  return nativeModule.startTrip(destLat, destLng);
}

export function stopOverlayTrip(): void {
  nativeModule?.stopTrip();
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

export function subscribeToTripCompleted(
  listener: (event: TripCompletedEvent) => void,
): () => void {
  if (!nativeModule) {
    return () => {};
  }
  const emitter = new NativeEventEmitter(NativeModules.FareOverlay);
  const subscription = emitter.addListener('onTripCompleted', listener);
  return () => subscription.remove();
}
