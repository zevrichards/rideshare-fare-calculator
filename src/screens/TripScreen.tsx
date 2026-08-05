import React, {useEffect, useState} from 'react';
import {AppState, Pressable, StyleSheet, Text, View} from 'react-native';
import Geolocation from '@react-native-community/geolocation';
import {calculateFareBreakdown, FareBreakdown, GeoPoint} from '../lib/fare';
import {estimateFareWithRouting} from '../lib/routing';
import {RateCard, TTRS_RATE_CARD} from '../lib/rateCards';
import {useTripTracking} from '../hooks/useTripTracking';
import RateCardSection from './RateCardSection';
import DestinationSearch from './DestinationSearch';
import MapPickerScreen from './MapPickerScreen';
import {
  getPreferredNavApp,
  hasOverlayPermission,
  isOverlaySupported,
  NAV_APPS,
  NavAppPackage,
  openDefaultAppSettings,
  requestOverlayPermission,
  setPreferredNavApp,
  startOverlayTrip,
  stopOverlayTrip,
  subscribeToTripCompleted,
} from '../native/FareOverlay';

const FARE_DISCLAIMER =
  'Final cost may vary. Do not use this figure to charge passengers. Only rely on your rideshare app final figure.';

function BreakdownLines({breakdown}: {breakdown: FareBreakdown}) {
  return (
    <View style={styles.breakdown}>
      <Text style={styles.breakdownRow}>Base ${breakdown.base.toFixed(2)}</Text>
      <Text style={styles.breakdownRow}>
        Distance ${breakdown.distanceCharge.toFixed(2)}
      </Text>
      <Text style={styles.breakdownRow}>Time ${breakdown.timeCharge.toFixed(2)}</Text>
      {breakdown.surgeMultiplier !== 1 && (
        <Text style={styles.breakdownRow}>
          Surge x{breakdown.surgeMultiplier.toFixed(1)}
        </Text>
      )}
      {breakdown.minimumApplied && (
        <Text style={styles.breakdownRow}>Minimum fare applied</Text>
      )}
    </View>
  );
}

function getCurrentPosition(): Promise<GeoPoint> {
  return new Promise((resolve, reject) => {
    Geolocation.getCurrentPosition(
      position =>
        resolve({
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
        }),
      error => reject(error),
      {enableHighAccuracy: true, timeout: 15000},
    );
  });
}

export default function TripScreen() {
  const [destLat, setDestLat] = useState('');
  const [destLng, setDestLng] = useState('');
  const [estimatedTotal, setEstimatedTotal] = useState<number | null>(null);
  const [estimatedBreakdown, setEstimatedBreakdown] = useState<FareBreakdown | null>(
    null,
  );
  const [estimatedSource, setEstimatedSource] = useState<
    'routing' | 'straight-line' | null
  >(null);
  const [startError, setStartError] = useState<string | null>(null);
  const [overlayPermitted, setOverlayPermitted] = useState(false);
  const [preferredNavApp, setPreferredNavAppState] = useState<NavAppPackage>(
    NAV_APPS.WAZE,
  );
  const [activeRateCard, setActiveRateCard] = useState<RateCard>(TTRS_RATE_CARD);
  const [surgeMultiplier, setSurgeMultiplierState] = useState(1);
  const [showMapPicker, setShowMapPicker] = useState(false);
  const tracking = useTripTracking();

  useEffect(() => {
    if (!isOverlaySupported) {
      return;
    }

    const checkPermission = () => {
      hasOverlayPermission().then(setOverlayPermitted);
    };
    checkPermission();
    getPreferredNavApp().then(setPreferredNavAppState);

    const appStateSubscription = AppState.addEventListener('change', state => {
      if (state === 'active') {
        checkPermission();
      }
    });

    // Stopping via the overlay's ✕ only stops the native side (service,
    // notification, overlay view) -- it doesn't touch this screen's own
    // useTripTracking hook, which has its own independent GPS watch. Without
    // this, tapping the overlay body to bring the app to the foreground
    // (see FareOverlayView's onTap) would show a "phantom" still-running
    // trip. This only syncs isTracking/stops the watch -- it does not save
    // history (removed; not needed, see git log).
    const tripCompletedSubscription = subscribeToTripCompleted(() => {
      tracking.stop();
    });

    return () => {
      appStateSubscription.remove();
      tripCompletedSubscription();
    };
  }, []);

  const handleStart = async () => {
    setStartError(null);
    const lat = Number(destLat);
    const lng = Number(destLng);

    if (destLat.trim() === '' || destLng.trim() === '' || Number.isNaN(lat) || Number.isNaN(lng)) {
      setStartError('Enter a valid destination latitude and longitude.');
      return;
    }

    try {
      const origin = await getCurrentPosition();
      const estimate = await estimateFareWithRouting(
        activeRateCard,
        origin,
        {latitude: lat, longitude: lng},
        surgeMultiplier,
      );
      setEstimatedTotal(estimate.total);
      setEstimatedBreakdown(estimate.breakdown);
      setEstimatedSource(estimate.source);
      await tracking.start();
      if (isOverlaySupported) {
        await startOverlayTrip(lat, lng);
      }
    } catch (err) {
      setStartError(
        err instanceof Error ? err.message : 'Failed to get current location.',
      );
    }
  };

  const handleSelectNavApp = (packageName: NavAppPackage) => {
    setPreferredNavAppState(packageName);
    setPreferredNavApp(packageName);
  };

  const handleStop = () => {
    if (isOverlaySupported) {
      stopOverlayTrip();
    }
    tracking.stop();
  };

  const runningBreakdown = calculateFareBreakdown(
    activeRateCard,
    tracking.distanceKm,
    tracking.elapsedMinutes,
    surgeMultiplier,
  );
  const runningTotal = runningBreakdown.total;

  if (showMapPicker) {
    return (
      <MapPickerScreen
        onCancel={() => setShowMapPicker(false)}
        onConfirm={destination => {
          setDestLat(String(destination.latitude));
          setDestLng(String(destination.longitude));
          setShowMapPicker(false);
        }}
      />
    );
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Fare Calculator</Text>

      {!tracking.isTracking && !isOverlaySupported && (
        <View style={styles.banner}>
          <Text style={styles.bannerText}>
            iOS doesn't allow a floating overlay over other apps. Keep this
            screen open during your trip to see the running total.
          </Text>
        </View>
      )}

      {!tracking.isTracking && isOverlaySupported && !overlayPermitted && (
        <View style={styles.banner}>
          <Text style={styles.bannerText}>
            Grant overlay permission to see the running total floating over
            other apps during a trip.
          </Text>
          <Pressable style={styles.bannerButton} onPress={requestOverlayPermission}>
            <Text style={styles.bannerButtonText}>Enable Floating Overlay</Text>
          </Pressable>
        </View>
      )}

      {!tracking.isTracking && (
        <RateCardSection
          onActiveRateCardChange={setActiveRateCard}
          onSurgeMultiplierChange={setSurgeMultiplierState}
        />
      )}

      {!tracking.isTracking && isOverlaySupported && (
        <View style={styles.settingsSection}>
          <Text style={styles.settingsLabel}>Relaunch navigation app</Text>
          <View style={styles.navAppRow}>
            <Pressable
              style={[
                styles.navAppOption,
                preferredNavApp === NAV_APPS.WAZE && styles.navAppOptionSelected,
              ]}
              onPress={() => handleSelectNavApp(NAV_APPS.WAZE)}>
              <Text style={styles.navAppOptionText}>Waze</Text>
            </Pressable>
            <Pressable
              style={[
                styles.navAppOption,
                preferredNavApp === NAV_APPS.GOOGLE_MAPS &&
                  styles.navAppOptionSelected,
              ]}
              onPress={() => handleSelectNavApp(NAV_APPS.GOOGLE_MAPS)}>
              <Text style={styles.navAppOptionText}>Google Maps</Text>
            </Pressable>
          </View>
          <Pressable style={styles.linkButton} onPress={openDefaultAppSettings}>
            <Text style={styles.linkButtonText}>
              Set as default navigation handler
            </Text>
          </Pressable>
        </View>
      )}

      {!tracking.isTracking && (
        <DestinationSearch
          onSelect={result => {
            setDestLat(String(result.location.latitude));
            setDestLng(String(result.location.longitude));
          }}
        />
      )}

      {!tracking.isTracking && (
        <Pressable
          style={styles.mapPickerButton}
          onPress={() => setShowMapPicker(true)}>
          <Text style={styles.mapPickerButtonText}>Pick on Map</Text>
        </Pressable>
      )}

      {!tracking.isTracking && (
        <View style={styles.form}>
          <Pressable style={styles.button} onPress={handleStart}>
            <Text style={styles.buttonText}>Start Trip</Text>
          </Pressable>
          {startError && <Text style={styles.error}>{startError}</Text>}
          <Text style={styles.disclaimer}>{FARE_DISCLAIMER}</Text>
        </View>
      )}

      {tracking.isTracking && (
        <View style={styles.totals}>
          <Text style={styles.disclaimer}>{FARE_DISCLAIMER}</Text>

          {estimatedTotal !== null && estimatedBreakdown !== null && (
            <View style={styles.totalRow}>
              <Text style={styles.totalLabel}>
                Estimated Total
                {estimatedSource === 'straight-line' ? ' (approx.)' : ''}
              </Text>
              <Text style={styles.totalValue}>${estimatedTotal.toFixed(2)}</Text>
              <BreakdownLines breakdown={estimatedBreakdown} />
            </View>
          )}
          <View style={styles.totalRow}>
            <Text style={styles.totalLabel}>Running Total</Text>
            <Text style={styles.totalValueLarge}>${runningTotal.toFixed(2)}</Text>
            <BreakdownLines breakdown={runningBreakdown} />
          </View>
          <Text style={styles.meta}>
            {tracking.distanceKm.toFixed(2)} km · {tracking.elapsedMinutes.toFixed(1)} min
          </Text>
          {tracking.error && <Text style={styles.error}>{tracking.error}</Text>}
          <Pressable style={[styles.button, styles.stopButton]} onPress={handleStop}>
            <Text style={styles.buttonText}>Stop Trip</Text>
          </Pressable>
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 24,
    justifyContent: 'center',
  },
  title: {
    fontSize: 28,
    fontWeight: '700',
    marginBottom: 24,
    textAlign: 'center',
  },
  form: {
    gap: 8,
  },
  banner: {
    backgroundColor: '#fef7e0',
    borderRadius: 8,
    padding: 12,
    marginBottom: 16,
  },
  bannerText: {
    fontSize: 13,
    color: '#5f5024',
    marginBottom: 8,
  },
  bannerButton: {
    alignSelf: 'flex-start',
    backgroundColor: '#e8a712',
    borderRadius: 6,
    paddingVertical: 8,
    paddingHorizontal: 12,
  },
  bannerButtonText: {
    color: '#fff',
    fontSize: 13,
    fontWeight: '600',
  },
  settingsSection: {
    marginBottom: 16,
  },
  settingsLabel: {
    fontSize: 13,
    color: '#555',
    marginBottom: 6,
  },
  navAppRow: {
    flexDirection: 'row',
    gap: 8,
    marginBottom: 8,
  },
  navAppOption: {
    flex: 1,
    borderWidth: 1,
    borderColor: '#ccc',
    borderRadius: 8,
    paddingVertical: 10,
    alignItems: 'center',
  },
  navAppOptionSelected: {
    borderColor: '#1a73e8',
    backgroundColor: '#e8f0fe',
  },
  navAppOptionText: {
    fontSize: 14,
    fontWeight: '600',
    color: '#333',
  },
  linkButton: {
    alignSelf: 'flex-start',
  },
  linkButtonText: {
    fontSize: 13,
    color: '#1a73e8',
    fontWeight: '600',
  },
  mapPickerButton: {
    borderWidth: 1,
    borderColor: '#1a73e8',
    borderRadius: 8,
    paddingVertical: 12,
    alignItems: 'center',
    marginBottom: 16,
  },
  mapPickerButtonText: {
    color: '#1a73e8',
    fontSize: 14,
    fontWeight: '600',
  },
  button: {
    backgroundColor: '#1a73e8',
    borderRadius: 8,
    paddingVertical: 14,
    alignItems: 'center',
    marginTop: 8,
  },
  stopButton: {
    backgroundColor: '#d93025',
  },
  buttonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
  error: {
    color: '#d93025',
    marginTop: 8,
  },
  disclaimer: {
    fontSize: 11,
    color: '#d93025',
    fontWeight: '700',
    textAlign: 'center',
    marginTop: 8,
  },
  breakdown: {
    marginTop: 4,
    alignItems: 'center',
  },
  breakdownRow: {
    fontSize: 12,
    color: '#777',
  },
  totals: {
    alignItems: 'center',
    gap: 8,
  },
  totalRow: {
    alignItems: 'center',
    marginBottom: 8,
  },
  totalLabel: {
    fontSize: 14,
    color: '#555',
  },
  totalValue: {
    fontSize: 24,
    fontWeight: '600',
  },
  totalValueLarge: {
    fontSize: 48,
    fontWeight: '700',
  },
  meta: {
    fontSize: 14,
    color: '#777',
    marginBottom: 16,
  },
});
