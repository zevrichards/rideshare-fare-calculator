import {useCallback, useEffect, useRef, useState} from 'react';
import {GeoPoint} from '../lib/fare';
import {
  getActiveTrip,
  startOverlayTrip,
  stopOverlayTrip,
  subscribeToTripCompleted,
  subscribeToTripTick,
  TripSnapshot,
} from '../native/FareOverlay';
import {requestLocationPermission, requestNotificationPermission, TripTracking} from './useTripTracking';

// Android's counterpart to useTripTracking: instead of its own GPS watch,
// this mirrors FareTrackingService -- the same engine that already drives
// the floating overlay -- so there's a single source of truth for
// distance/time on this platform. That also means a trip started outside
// this screen entirely (nav-intercept) can be adopted here instead of never
// appearing in the app at all.
export function useNativeTripTracking(): TripTracking {
  const [isTracking, setIsTracking] = useState(false);
  const [distanceKm, setDistanceKm] = useState(0);
  const [elapsedMinutes, setElapsedMinutes] = useState(0);
  const [adoptedRateCardId, setAdoptedRateCardId] = useState<string | null>(null);
  const [adoptedSurgeMultiplier, setAdoptedSurgeMultiplier] = useState<number | null>(null);
  const [adoptedEstimatedDistanceKm, setAdoptedEstimatedDistanceKm] = useState<number | null>(null);
  const [adoptedEstimatedMinutes, setAdoptedEstimatedMinutes] = useState<number | null>(null);
  // True for a trip this screen adopted rather than started itself -- only those take
  // estimate updates from native (e.g. Waze refreshing it mid-trip).
  const isAdoptedTripRef = useRef(false);

  // Every tick (whether the trip was adopted or started fresh from this
  // screen) updates the live numbers. The "adopted" estimate fields are
  // only refreshed for an adopted trip (see isAdoptedTripRef) -- a trip
  // started via start() already has a real, JS-computed FareBreakdown from
  // handleStart, which native's plain-number version would silently
  // overwrite on the very next tick.
  const applyLiveNumbers = useCallback((snapshot: TripSnapshot) => {
    setIsTracking(true);
    setDistanceKm(snapshot.distanceKm);
    setElapsedMinutes(snapshot.elapsedMinutes);
    if (isAdoptedTripRef.current) {
      setAdoptedEstimatedDistanceKm(snapshot.estimatedDistanceKm);
      setAdoptedEstimatedMinutes(snapshot.estimatedMinutes);
    }
  }, []);

  const adoptSnapshot = useCallback(
    (snapshot: TripSnapshot) => {
      isAdoptedTripRef.current = true;
      applyLiveNumbers(snapshot);
      setAdoptedRateCardId(snapshot.rateCardId);
      setAdoptedSurgeMultiplier(snapshot.surgeMultiplier);
      setAdoptedEstimatedDistanceKm(snapshot.estimatedDistanceKm);
      setAdoptedEstimatedMinutes(snapshot.estimatedMinutes);
    },
    [applyLiveNumbers],
  );

  const markStopped = useCallback(() => {
    isAdoptedTripRef.current = false;
    setIsTracking(false);
    setAdoptedRateCardId(null);
    setAdoptedSurgeMultiplier(null);
    setAdoptedEstimatedDistanceKm(null);
    setAdoptedEstimatedMinutes(null);
  }, []);

  useEffect(() => {
    // One-time read in case a trip is already running when this screen
    // mounts/resumes (e.g. the overlay was tapped for a trip that started
    // via nav-intercept). onTripCompleted covers the trip ending via some
    // other path (the overlay's own stop control, the notification) that
    // this screen didn't initiate.
    getActiveTrip().then(snapshot => {
      if (snapshot) adoptSnapshot(snapshot);
    });

    const tickSubscription = subscribeToTripTick(applyLiveNumbers);
    const completedSubscription = subscribeToTripCompleted(markStopped);

    return () => {
      tickSubscription();
      completedSubscription();
    };
  }, [adoptSnapshot, applyLiveNumbers, markStopped]);

  const start = useCallback(async (destination: GeoPoint | null): Promise<void> => {
    const hasPermission = await requestLocationPermission();
    if (!hasPermission) {
      throw new Error('Location permission denied');
    }
    await requestNotificationPermission();
    await startOverlayTrip(destination?.latitude ?? null, destination?.longitude ?? null);
    isAdoptedTripRef.current = false;
    setIsTracking(true);
    setDistanceKm(0);
    setElapsedMinutes(0);
  }, []);

  const stop = useCallback(() => {
    stopOverlayTrip();
    markStopped();
  }, [markStopped]);

  return {
    isTracking,
    distanceKm,
    elapsedMinutes,
    currentPosition: null,
    error: null,
    start,
    stop,
    adoptedRateCardId,
    adoptedSurgeMultiplier,
    adoptedEstimatedDistanceKm,
    adoptedEstimatedMinutes,
  };
}
