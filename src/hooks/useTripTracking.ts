import {useCallback, useEffect, useRef, useState} from 'react';
import {PermissionsAndroid, Platform} from 'react-native';
import Geolocation, {
  GeolocationResponse,
} from '@react-native-community/geolocation';
import {GeoPoint, haversineDistanceKm} from '../lib/fare';

const MIN_USABLE_ACCURACY_METERS = 50;

export async function requestLocationPermission(): Promise<boolean> {
  if (Platform.OS !== 'android') {
    return true;
  }
  const granted = await PermissionsAndroid.request(
    PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
    {
      title: 'Location Permission',
      message:
        'This app needs your location to track trip distance in real time.',
      buttonPositive: 'Allow',
    },
  );
  return granted === PermissionsAndroid.RESULTS.GRANTED;
}

export async function requestNotificationPermission(): Promise<void> {
  if (Platform.OS !== 'android' || Platform.Version < 33) {
    return;
  }
  // Best-effort: the foreground service notification just won't show if
  // this is denied, it doesn't block tracking.
  await PermissionsAndroid.request(
    PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
  );
}

export interface TripTracking {
  isTracking: boolean;
  distanceKm: number;
  elapsedMinutes: number;
  currentPosition: GeoPoint | null;
  error: string | null;
  start: (destination: GeoPoint | null) => Promise<void>;
  stop: () => void;
  // Set only by useNativeTripTracking, when TripScreen mounts/resumes while
  // a trip started elsewhere (nav-intercept) is already running. Always
  // null here -- this hook has no such adoption path.
  adoptedRateCardId: string | null;
  adoptedSurgeMultiplier: number | null;
  adoptedEstimatedDistanceKm: number | null;
  adoptedEstimatedMinutes: number | null;
}

export function useTripTracking(): TripTracking {
  const [isTracking, setIsTracking] = useState(false);
  const [distanceKm, setDistanceKm] = useState(0);
  const [elapsedMinutes, setElapsedMinutes] = useState(0);
  const [currentPosition, setCurrentPosition] = useState<GeoPoint | null>(
    null,
  );
  const [error, setError] = useState<string | null>(null);

  const watchIdRef = useRef<number | null>(null);
  const tickIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const lastPositionRef = useRef<GeoPoint | null>(null);
  const distanceKmRef = useRef(0);
  const startTimeRef = useRef<number | null>(null);

  const clearWatchers = useCallback(() => {
    if (watchIdRef.current !== null) {
      Geolocation.clearWatch(watchIdRef.current);
      watchIdRef.current = null;
    }
    if (tickIntervalRef.current !== null) {
      clearInterval(tickIntervalRef.current);
      tickIntervalRef.current = null;
    }
  }, []);

  const stop = useCallback(() => {
    clearWatchers();
    setIsTracking(false);
  }, [clearWatchers]);

  // destination is unused here -- this hook tracks via GPS deltas, it
  // doesn't need to know where the trip is headed. Accepted only so
  // TripScreen can call tracking.start(destination) without branching on
  // which hook (this one or useNativeTripTracking) it got.
  const start = useCallback(async (_destination: GeoPoint | null): Promise<void> => {
    setError(null);
    const hasPermission = await requestLocationPermission();
    if (!hasPermission) {
      setError('Location permission denied');
      throw new Error('Location permission denied');
    }
    await requestNotificationPermission();

    lastPositionRef.current = null;
    distanceKmRef.current = 0;
    startTimeRef.current = Date.now();
    setDistanceKm(0);
    setElapsedMinutes(0);
    setCurrentPosition(null);
    setIsTracking(true);

    watchIdRef.current = Geolocation.watchPosition(
      (position: GeolocationResponse) => {
        const point: GeoPoint = {
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
        };

        const isNoisyFix =
          lastPositionRef.current !== null &&
          position.coords.accuracy > MIN_USABLE_ACCURACY_METERS;

        if (!isNoisyFix) {
          if (lastPositionRef.current !== null) {
            distanceKmRef.current += haversineDistanceKm(
              lastPositionRef.current,
              point,
            );
            setDistanceKm(distanceKmRef.current);
          }
          lastPositionRef.current = point;
        }

        setCurrentPosition(point);
      },
      err => setError(err.message),
      {
        enableHighAccuracy: true,
        distanceFilter: 5,
        interval: 3000,
        fastestInterval: 1000,
      },
    );

    tickIntervalRef.current = setInterval(() => {
      if (startTimeRef.current !== null) {
        setElapsedMinutes((Date.now() - startTimeRef.current) / 60000);
      }
    }, 1000);
  }, []);

  useEffect(() => clearWatchers, [clearWatchers]);

  return {
    isTracking,
    distanceKm,
    elapsedMinutes,
    currentPosition,
    error,
    start,
    stop,
    adoptedRateCardId: null,
    adoptedSurgeMultiplier: null,
    adoptedEstimatedDistanceKm: null,
    adoptedEstimatedMinutes: null,
  };
}
