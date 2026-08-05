import React, {useEffect, useState} from 'react';
import {
  AppState,
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import Geolocation from '@react-native-community/geolocation';
import {calculateFare, GeoPoint} from '../lib/fare';
import {estimateFareWithRouting} from '../lib/routing';
import {CompletedTrip, getTripHistory, saveCompletedTrip} from '../lib/tripHistory';
import {useTripTracking} from '../hooks/useTripTracking';
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

const RECENT_TRIPS_SHOWN = 5;

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
  const [estimatedSource, setEstimatedSource] = useState<
    'routing' | 'straight-line' | null
  >(null);
  const [startError, setStartError] = useState<string | null>(null);
  const [overlayPermitted, setOverlayPermitted] = useState(false);
  const [preferredNavApp, setPreferredNavAppState] = useState<NavAppPackage>(
    NAV_APPS.WAZE,
  );
  const [history, setHistory] = useState<CompletedTrip[]>([]);
  const tracking = useTripTracking();

  const refreshHistory = () => {
    getTripHistory().then(setHistory);
  };

  useEffect(() => {
    refreshHistory();

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
    // On Android this is the single source of truth for a completed trip
    // (fires whether the trip was stopped in-app or via the overlay), so
    // history is saved here rather than in handleStop to avoid double-saving.
    const tripCompletedSubscription = subscribeToTripCompleted(event => {
      saveCompletedTrip(event).then(refreshHistory);
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
      const estimate = await estimateFareWithRouting(origin, {
        latitude: lat,
        longitude: lng,
      });
      setEstimatedTotal(estimate.total);
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
    } else {
      saveCompletedTrip({
        distanceKm: tracking.distanceKm,
        minutes: tracking.elapsedMinutes,
        total: runningTotal,
      }).then(refreshHistory);
    }
    tracking.stop();
  };

  const runningTotal = calculateFare(tracking.distanceKm, tracking.elapsedMinutes);

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

      {!tracking.isTracking && history.length > 0 && (
        <View style={styles.historySection}>
          <Text style={styles.settingsLabel}>Recent Trips</Text>
          <FlatList
            data={history.slice(0, RECENT_TRIPS_SHOWN)}
            keyExtractor={item => item.id}
            renderItem={({item}) => (
              <Text style={styles.historyRow}>
                {new Date(item.timestamp).toLocaleDateString()} ·{' '}
                {item.distanceKm.toFixed(2)} km · {item.minutes.toFixed(1)} min
                · ${item.total.toFixed(2)}
              </Text>
            )}
          />
        </View>
      )}

      {!tracking.isTracking && (
        <View style={styles.form}>
          <Text style={styles.label}>Destination Latitude</Text>
          <TextInput
            style={styles.input}
            keyboardType="numeric"
            value={destLat}
            onChangeText={setDestLat}
            placeholder="15.3010"
          />
          <Text style={styles.label}>Destination Longitude</Text>
          <TextInput
            style={styles.input}
            keyboardType="numeric"
            value={destLng}
            onChangeText={setDestLng}
            placeholder="-61.3880"
          />
          <Pressable style={styles.button} onPress={handleStart}>
            <Text style={styles.buttonText}>Start Trip</Text>
          </Pressable>
          {startError && <Text style={styles.error}>{startError}</Text>}
        </View>
      )}

      {tracking.isTracking && (
        <View style={styles.totals}>
          {estimatedTotal !== null && (
            <View style={styles.totalRow}>
              <Text style={styles.totalLabel}>
                Estimated Total
                {estimatedSource === 'straight-line' ? ' (approx.)' : ''}
              </Text>
              <Text style={styles.totalValue}>${estimatedTotal.toFixed(2)}</Text>
            </View>
          )}
          <View style={styles.totalRow}>
            <Text style={styles.totalLabel}>Running Total</Text>
            <Text style={styles.totalValueLarge}>${runningTotal.toFixed(2)}</Text>
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
  historySection: {
    marginBottom: 16,
  },
  historyRow: {
    fontSize: 12,
    color: '#777',
    paddingVertical: 2,
  },
  label: {
    fontSize: 14,
    color: '#555',
  },
  input: {
    borderWidth: 1,
    borderColor: '#ccc',
    borderRadius: 8,
    padding: 12,
    fontSize: 16,
    marginBottom: 12,
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
