import React, {useEffect, useMemo, useState} from 'react';
import {AppState, Pressable, ScrollView, StyleSheet, Text, View} from 'react-native';
import Geolocation from '@react-native-community/geolocation';
import {calculateFareBreakdown, FareBreakdown, GeoPoint} from '../lib/fare';
import {estimateFareWithRouting} from '../lib/routing';
import {
  getRateCards,
  RateCard,
  setSelectedRateCardId,
  TTRS_RATE_CARD,
} from '../lib/rateCards';
import {useTripTracking} from '../hooks/useTripTracking';
import {useNativeTripTracking} from '../hooks/useNativeTripTracking';
import RateCardSection from './RateCardSection';
import DestinationSearch from './DestinationSearch';
import MapPickerScreen from './MapPickerScreen';
import OverlaySizeSection from './OverlaySizeSection';
import {
  clearDiagnosticLog,
  copyToClipboard,
  getDiagnosticLog,
  getPreferredNavApp,
  hasAccessibilityServiceEnabled,
  hasOverlayPermission,
  isOverlaySupported,
  mirrorSelectedRateCard,
  NAV_APPS,
  NavAppPackage,
  openDefaultAppSettings,
  requestAccessibilityServiceEnable,
  requestOverlayPermission,
  setPreferredNavApp,
} from '../native/FareOverlay';
import {ThemeColors, useThemeColors} from '../theme/colors';

const FARE_DISCLAIMER =
  'Final cost may vary. Do not use this figure to charge passengers. Only rely on your rideshare app final figure.';

function BreakdownLines({breakdown}: {breakdown: FareBreakdown}) {
  // Separate component, so it can't reach TripScreen's local `styles` --
  // recomputing it here is cheap and keeps this component self-contained.
  const colors = useThemeColors();
  const styles = useMemo(() => createStyles(colors), [colors]);
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
  // Display-only label for whatever destLat/destLng currently holds -- a
  // place name from search, or coordinates from the map picker (which has
  // no name to offer). Purely cosmetic; destLat/destLng remain the only
  // values handleStart actually reads.
  const [destinationLabel, setDestinationLabel] = useState<string | null>(null);
  // The raw estimate inputs, not the computed breakdown -- so switching rate
  // cards mid-trip (see handleSwitchRateCard) can recompute the Estimated
  // Total against the same distance/duration without a fresh API call.
  // estimatedBreakdown/estimatedTotal below are derived from this every
  // render, the same way runningBreakdown already derives from
  // tracking.distanceKm/elapsedMinutes.
  const [estimatedInputs, setEstimatedInputs] = useState<{
    distanceKm: number;
    minutes: number;
    source: 'routing' | 'straight-line' | null;
  } | null>(null);
  const [startError, setStartError] = useState<string | null>(null);
  const [overlayPermitted, setOverlayPermitted] = useState(false);
  const [accessibilityServiceEnabled, setAccessibilityServiceEnabled] =
    useState(false);
  const [showDiagnosticLog, setShowDiagnosticLog] = useState(false);
  const [diagnosticLogText, setDiagnosticLogText] = useState('');
  const [logCopied, setLogCopied] = useState(false);
  const [preferredNavApp, setPreferredNavAppState] = useState<NavAppPackage>(
    NAV_APPS.WAZE,
  );
  const [activeRateCard, setActiveRateCard] = useState<RateCard>(TTRS_RATE_CARD);
  const [surgeMultiplier, setSurgeMultiplierState] = useState(1);
  const [showMapPicker, setShowMapPicker] = useState(false);
  const [rateCards, setRateCards] = useState<RateCard[]>([]);
  // isOverlaySupported is a module-level constant (Platform.OS === 'android'),
  // invariant for the process lifetime, so picking a hook based on it never
  // changes the hook call order between renders.
  const tracking = isOverlaySupported ? useNativeTripTracking() : useTripTracking();
  const colors = useThemeColors();
  const styles = useMemo(() => createStyles(colors), [colors]);

  useEffect(() => {
    if (!isOverlaySupported) {
      return;
    }

    const checkPermission = () => {
      hasOverlayPermission().then(setOverlayPermitted);
      hasAccessibilityServiceEnabled().then(setAccessibilityServiceEnabled);
    };
    checkPermission();
    getPreferredNavApp().then(setPreferredNavAppState);

    const appStateSubscription = AppState.addEventListener('change', state => {
      if (state === 'active') {
        checkPermission();
      }
    });

    return () => {
      appStateSubscription.remove();
    };
  }, []);

  // Needed for the mid-trip rate-card switch buttons (see
  // handleSwitchRateCard) -- RateCardSection loads its own copy for the idle
  // screen, but it doesn't render while tracking.isTracking is true.
  useEffect(() => {
    getRateCards().then(setRateCards);
  }, []);

  // Adopts a trip that's already running when this screen mounts/resumes --
  // only useNativeTripTracking (Android) ever sets these; useTripTracking
  // (iOS) always reports them as null. RateCardSection is the only other
  // place activeRateCard/surgeMultiplier get set, and it doesn't render
  // while tracking.isTracking is true, so without this the breakdown would
  // silently use the hardcoded TTRS/no-surge defaults for an adopted trip.
  useEffect(() => {
    if (!tracking.adoptedRateCardId) {
      return;
    }
    if (tracking.adoptedSurgeMultiplier !== null) {
      setSurgeMultiplierState(tracking.adoptedSurgeMultiplier);
    }
    getRateCards().then(cards => {
      const matched = cards.find(card => card.id === tracking.adoptedRateCardId);
      if (!matched) {
        return;
      }
      setActiveRateCard(matched);
      // Native ships the estimate's distance/time inputs (both null together
      // if the intercept never got a destination to estimate from), not a
      // pre-computed total -- store the raw inputs, not a computed
      // breakdown, so a mid-trip rate-card switch can recompute this too.
      if (tracking.adoptedEstimatedDistanceKm !== null && tracking.adoptedEstimatedMinutes !== null) {
        setEstimatedInputs({
          distanceKm: tracking.adoptedEstimatedDistanceKm,
          minutes: tracking.adoptedEstimatedMinutes,
          source: null,
        });
      }
    });
  }, [
    tracking.adoptedRateCardId,
    tracking.adoptedSurgeMultiplier,
    tracking.adoptedEstimatedDistanceKm,
    tracking.adoptedEstimatedMinutes,
  ]);

  const handleStart = async () => {
    setStartError(null);

    // A destination is optional -- both fields blank means "track the
    // running total only, no pre-trip estimate" (same as an intercept where
    // the destination couldn't be parsed). Only reject genuinely invalid
    // input (one field filled, or non-numeric).
    const hasDestinationInput = destLat.trim() !== '' || destLng.trim() !== '';
    let destination: GeoPoint | null = null;
    if (hasDestinationInput) {
      const lat = Number(destLat);
      const lng = Number(destLng);
      if (destLat.trim() === '' || destLng.trim() === '' || Number.isNaN(lat) || Number.isNaN(lng)) {
        setStartError('Enter a valid destination latitude and longitude, or leave both blank to track without an estimate.');
        return;
      }
      destination = {latitude: lat, longitude: lng};
    }

    try {
      if (destination) {
        const origin = await getCurrentPosition();
        const estimate = await estimateFareWithRouting(
          activeRateCard,
          origin,
          destination,
          surgeMultiplier,
        );
        setEstimatedInputs({
          distanceKm: estimate.distanceKm,
          minutes: estimate.minutes,
          source: estimate.source,
        });
      } else {
        setEstimatedInputs(null);
      }
      await tracking.start(destination);
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
    tracking.stop();
  };

  const handleRefreshDiagnosticLog = () => {
    getDiagnosticLog().then(setDiagnosticLogText);
  };

  const handleToggleDiagnosticLog = () => {
    if (!showDiagnosticLog) {
      handleRefreshDiagnosticLog();
    }
    setShowDiagnosticLog(!showDiagnosticLog);
  };

  const handleClearDiagnosticLog = () => {
    clearDiagnosticLog();
    setDiagnosticLogText('');
  };

  const handleCopyDiagnosticLog = () => {
    copyToClipboard(diagnosticLogText);
    setLogCopied(true);
    setTimeout(() => setLogCopied(false), 2000);
  };

  // Switching mid-trip (not just before Start Trip) -- write-through to
  // AsyncStorage + the native mirror same as RateCardSection's handleSelect,
  // so a resumed/reopened app and the floating overlay both see the change.
  // activeRateCard itself is what makes both breakdowns below recompute.
  const handleSwitchRateCard = (card: RateCard) => {
    setActiveRateCard(card);
    setSelectedRateCardId(card.id);
    mirrorSelectedRateCard(card.id);
  };

  const runningBreakdown = calculateFareBreakdown(
    activeRateCard,
    tracking.distanceKm,
    tracking.elapsedMinutes,
    surgeMultiplier,
  );
  const runningTotal = runningBreakdown.total;

  const estimatedBreakdown = estimatedInputs
    ? calculateFareBreakdown(
        activeRateCard,
        estimatedInputs.distanceKm,
        estimatedInputs.minutes,
        surgeMultiplier,
      )
    : null;
  const estimatedTotal = estimatedBreakdown?.total ?? null;

  if (showMapPicker) {
    return (
      <MapPickerScreen
        onCancel={() => setShowMapPicker(false)}
        onConfirm={destination => {
          setDestLat(String(destination.latitude));
          setDestLng(String(destination.longitude));
          setDestinationLabel(
            `${destination.latitude.toFixed(5)}, ${destination.longitude.toFixed(5)}`,
          );
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

      {!tracking.isTracking && isOverlaySupported && !accessibilityServiceEnabled && (
        <View style={styles.banner}>
          <Text style={styles.bannerText}>
            Optionally, auto-start fare tracking when you tap Start Ride in
            TTRS or Allridi -- including the destination if it's shown on
            that screen. Enable the Accessibility Service to turn this on.
          </Text>
          <Pressable
            style={styles.bannerButton}
            onPress={requestAccessibilityServiceEnable}>
            <Text style={styles.bannerButtonText}>Enable Auto-Start</Text>
          </Pressable>
        </View>
      )}

      {isOverlaySupported && accessibilityServiceEnabled && (
        <View style={styles.diagnosticSection}>
          <Pressable onPress={handleToggleDiagnosticLog}>
            <Text style={styles.linkButtonText}>
              {showDiagnosticLog ? 'Hide' : 'View'} Auto-Start Log
            </Text>
          </Pressable>
          {showDiagnosticLog && (
            <View style={styles.diagnosticLogBox}>
              <ScrollView style={styles.diagnosticLogScroll}>
                <Text style={styles.diagnosticLogText}>
                  {diagnosticLogText || 'No auto-start activity recorded yet.'}
                </Text>
              </ScrollView>
              <View style={styles.diagnosticLogActions}>
                <Pressable onPress={handleRefreshDiagnosticLog}>
                  <Text style={styles.linkButtonText}>Refresh</Text>
                </Pressable>
                <Pressable
                  onPress={handleCopyDiagnosticLog}
                  disabled={!diagnosticLogText}>
                  <Text style={styles.linkButtonText}>
                    {logCopied ? 'Copied!' : 'Copy'}
                  </Text>
                </Pressable>
                <Pressable onPress={handleClearDiagnosticLog}>
                  <Text style={styles.linkButtonText}>Clear</Text>
                </Pressable>
              </View>
            </View>
          )}
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

      {!tracking.isTracking && isOverlaySupported && <OverlaySizeSection />}

      {!tracking.isTracking && (
        <DestinationSearch
          onSelect={result => {
            setDestLat(String(result.location.latitude));
            setDestLng(String(result.location.longitude));
            setDestinationLabel(result.name);
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

      {!tracking.isTracking && destinationLabel && (
        <View style={styles.destinationRow}>
          <Text style={styles.destinationLabel}>Destination</Text>
          <Text style={styles.destinationValue}>{destinationLabel}</Text>
        </View>
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

          {rateCards.length > 1 && (
            <View style={styles.navAppRow}>
              {rateCards.map(card => (
                <Pressable
                  key={card.id}
                  style={[
                    styles.navAppOption,
                    activeRateCard.id === card.id && styles.navAppOptionSelected,
                  ]}
                  onPress={() => handleSwitchRateCard(card)}>
                  <Text style={styles.navAppOptionText}>{card.name}</Text>
                </Pressable>
              ))}
            </View>
          )}

          {estimatedTotal !== null && estimatedBreakdown !== null && (
            <View style={styles.totalRow}>
              <Text style={styles.totalLabel}>
                Estimated Total
                {estimatedInputs?.source === 'straight-line' ? ' (approx.)' : ''}
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

const createStyles = (colors: ThemeColors) =>
  StyleSheet.create({
    container: {
      flex: 1,
      padding: 24,
      justifyContent: 'center',
      backgroundColor: colors.background,
    },
    title: {
      fontSize: 28,
      fontWeight: '700',
      marginBottom: 24,
      textAlign: 'center',
      color: colors.textPrimary,
    },
    form: {
      gap: 8,
    },
    banner: {
      backgroundColor: colors.bannerBg,
      borderRadius: 8,
      padding: 12,
      marginBottom: 16,
    },
    bannerText: {
      fontSize: 13,
      color: colors.bannerText,
      marginBottom: 8,
    },
    bannerButton: {
      alignSelf: 'flex-start',
      backgroundColor: colors.bannerButtonBg,
      borderRadius: 6,
      paddingVertical: 8,
      paddingHorizontal: 12,
    },
    bannerButtonText: {
      color: colors.bannerButtonText,
      fontSize: 13,
      fontWeight: '600',
    },
    settingsSection: {
      marginBottom: 16,
    },
    settingsLabel: {
      fontSize: 13,
      color: colors.textSecondary,
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
      borderColor: colors.border,
      borderRadius: 8,
      paddingVertical: 10,
      alignItems: 'center',
    },
    navAppOptionSelected: {
      borderColor: colors.primary,
      backgroundColor: colors.surfaceAlt,
    },
    navAppOptionText: {
      fontSize: 14,
      fontWeight: '600',
      color: colors.textPrimary,
    },
    linkButton: {
      alignSelf: 'flex-start',
    },
    linkButtonText: {
      fontSize: 13,
      color: colors.primary,
      fontWeight: '600',
    },
    mapPickerButton: {
      borderWidth: 1,
      borderColor: colors.primary,
      borderRadius: 8,
      paddingVertical: 12,
      alignItems: 'center',
      marginBottom: 16,
    },
    mapPickerButtonText: {
      color: colors.primary,
      fontSize: 14,
      fontWeight: '600',
    },
    diagnosticSection: {
      marginBottom: 16,
    },
    diagnosticLogBox: {
      marginTop: 8,
      borderWidth: 1,
      borderColor: colors.borderSubtle,
      borderRadius: 8,
      padding: 8,
      backgroundColor: colors.surface,
    },
    diagnosticLogScroll: {
      maxHeight: 200,
    },
    diagnosticLogText: {
      fontSize: 12,
      fontFamily: 'monospace',
      color: colors.textSecondary,
    },
    diagnosticLogActions: {
      flexDirection: 'row',
      gap: 16,
      marginTop: 8,
    },
    destinationRow: {
      marginBottom: 16,
    },
    destinationLabel: {
      fontSize: 13,
      color: colors.textSecondary,
      marginBottom: 2,
    },
    destinationValue: {
      fontSize: 15,
      fontWeight: '600',
      color: colors.textPrimary,
    },
    button: {
      backgroundColor: colors.primary,
      borderRadius: 8,
      paddingVertical: 14,
      alignItems: 'center',
      marginTop: 8,
    },
    stopButton: {
      backgroundColor: colors.danger,
    },
    buttonText: {
      color: colors.onPrimary,
      fontSize: 16,
      fontWeight: '600',
    },
    error: {
      color: colors.danger,
      marginTop: 8,
    },
    disclaimer: {
      fontSize: 11,
      color: colors.disclaimerText,
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
      color: colors.textMuted,
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
      color: colors.textSecondary,
    },
    totalValue: {
      fontSize: 24,
      fontWeight: '600',
      color: colors.textPrimary,
    },
    totalValueLarge: {
      fontSize: 48,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    meta: {
      fontSize: 14,
      color: colors.textMuted,
      marginBottom: 16,
    },
  });
