import React, {useEffect, useMemo, useRef, useState} from 'react';
import {Pressable, ScrollView, StyleSheet, Switch, Text, View} from 'react-native';
import {RateCard} from '../lib/rateCards';
import {getUseWazeEstimate, setUseWazeEstimate} from '../lib/wazeEstimateSetting';
import {
  clearDiagnosticLog,
  copyToClipboard,
  getDiagnosticLog,
  isOverlaySupported,
  mirrorUseWazeEstimate,
  NAV_APPS,
  NavAppPackage,
  openDefaultAppSettings,
} from '../native/FareOverlay';
import {ThemeColors, useThemeColors} from '../theme/colors';
import OverlaySizeSection from './OverlaySizeSection';
import RateCardSection from './RateCardSection';

interface SettingsScreenProps {
  onBack: () => void;
  isTracking: boolean;
  accessibilityServiceEnabled: boolean;
  preferredNavApp: NavAppPackage;
  onSelectNavApp: (packageName: NavAppPackage) => void;
  onActiveRateCardChange: (card: RateCard) => void;
  onSurgeMultiplierChange: (value: number) => void;
}

// The options that don't need touching every trip, kept off the main screen.
export default function SettingsScreen({
  onBack,
  isTracking,
  accessibilityServiceEnabled,
  preferredNavApp,
  onSelectNavApp,
  onActiveRateCardChange,
  onSurgeMultiplierChange,
}: SettingsScreenProps) {
  const colors = useThemeColors();
  const styles = useMemo(() => createStyles(colors), [colors]);

  const [useWazeEstimate, setUseWazeEstimateState] = useState(false);
  const [showDiagnosticLog, setShowDiagnosticLog] = useState(false);
  const [diagnosticLogText, setDiagnosticLogText] = useState('');
  const [logCopied, setLogCopied] = useState(false);
  const logScrollRef = useRef<ScrollView>(null);

  useEffect(() => {
    getUseWazeEstimate().then(setUseWazeEstimateState);
  }, []);

  const handleWazeToggle = (value: boolean) => {
    setUseWazeEstimateState(value);
    setUseWazeEstimate(value);
    mirrorUseWazeEstimate(value);
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

  return (
    <ScrollView style={styles.scrollContainer} contentContainerStyle={styles.container}>
      <View style={styles.header}>
        <Pressable onPress={onBack} hitSlop={12}>
          <Text style={styles.linkButtonText}>‹ Back</Text>
        </Pressable>
        <Text style={styles.title}>Settings</Text>
        <View style={styles.headerSpacer} />
      </View>

      {isOverlaySupported && (
        <View style={styles.section}>
          <View style={styles.switchRow}>
            <Text style={styles.switchLabel}>Use Waze for the fare estimate</Text>
            <Switch
              value={useWazeEstimate}
              onValueChange={handleWazeToggle}
              trackColor={{true: colors.primary, false: colors.border}}
            />
          </View>
          <Text style={styles.hint}>
            Reads the time and distance remaining from Waze's screen instead of the
            Google routes lookup, and refreshes it about once a minute while Waze is
            showing. Needs Waze open during the trip.
          </Text>
        </View>
      )}

      {!isTracking && (
        <RateCardSection
          onActiveRateCardChange={onActiveRateCardChange}
          onSurgeMultiplierChange={onSurgeMultiplierChange}
        />
      )}

      {!isTracking && isOverlaySupported && <OverlaySizeSection />}

      {!isTracking && isOverlaySupported && (
        <View style={styles.section}>
          <Text style={styles.label}>Relaunch navigation app</Text>
          <View style={styles.navAppRow}>
            <Pressable
              style={[
                styles.navAppOption,
                preferredNavApp === NAV_APPS.WAZE && styles.navAppOptionSelected,
              ]}
              onPress={() => onSelectNavApp(NAV_APPS.WAZE)}>
              <Text style={styles.navAppOptionText}>Waze</Text>
            </Pressable>
            <Pressable
              style={[
                styles.navAppOption,
                preferredNavApp === NAV_APPS.GOOGLE_MAPS && styles.navAppOptionSelected,
              ]}
              onPress={() => onSelectNavApp(NAV_APPS.GOOGLE_MAPS)}>
              <Text style={styles.navAppOptionText}>Google Maps</Text>
            </Pressable>
          </View>
          <Pressable style={styles.linkButton} onPress={openDefaultAppSettings}>
            <Text style={styles.linkButtonText}>Set as default navigation handler</Text>
          </Pressable>
        </View>
      )}

      {isOverlaySupported && accessibilityServiceEnabled && (
        <View style={styles.section}>
          <Pressable onPress={handleToggleDiagnosticLog}>
            <Text style={styles.linkButtonText}>
              {showDiagnosticLog ? 'Hide' : 'View'} Auto-Start Log
            </Text>
          </Pressable>
          {showDiagnosticLog && (
            <View style={styles.diagnosticLogBox}>
              <ScrollView
                ref={logScrollRef}
                style={styles.diagnosticLogScroll}
                nestedScrollEnabled
                onContentSizeChange={() => logScrollRef.current?.scrollToEnd({animated: false})}>
                <Text style={styles.diagnosticLogText}>
                  {diagnosticLogText || 'No auto-start activity recorded yet.'}
                </Text>
              </ScrollView>
              <View style={styles.diagnosticLogActions}>
                <Pressable onPress={handleRefreshDiagnosticLog}>
                  <Text style={styles.linkButtonText}>Refresh</Text>
                </Pressable>
                <Pressable onPress={handleCopyDiagnosticLog} disabled={!diagnosticLogText}>
                  <Text style={styles.linkButtonText}>{logCopied ? 'Copied!' : 'Copy'}</Text>
                </Pressable>
                <Pressable onPress={handleClearDiagnosticLog}>
                  <Text style={styles.linkButtonText}>Clear</Text>
                </Pressable>
              </View>
            </View>
          )}
        </View>
      )}

      {isTracking && (
        <Text style={styles.hint}>
          Rate cards, overlay size and the navigation app can be changed once the trip ends.
        </Text>
      )}
    </ScrollView>
  );
}

const createStyles = (colors: ThemeColors) =>
  StyleSheet.create({
    scrollContainer: {
      flex: 1,
      backgroundColor: colors.background,
    },
    container: {
      flexGrow: 1,
      padding: 24,
    },
    header: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      marginBottom: 24,
    },
    headerSpacer: {
      width: 48,
    },
    title: {
      fontSize: 22,
      fontWeight: '700',
      color: colors.textPrimary,
    },
    section: {
      marginBottom: 20,
    },
    label: {
      fontSize: 13,
      color: colors.textSecondary,
      marginBottom: 6,
    },
    switchRow: {
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      gap: 12,
    },
    switchLabel: {
      flex: 1,
      fontSize: 15,
      fontWeight: '600',
      color: colors.textPrimary,
    },
    hint: {
      fontSize: 12,
      color: colors.textMuted,
      marginTop: 6,
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
    diagnosticLogBox: {
      marginTop: 8,
      borderWidth: 1,
      borderColor: colors.borderSubtle,
      borderRadius: 8,
      padding: 8,
      backgroundColor: colors.surface,
    },
    diagnosticLogScroll: {
      maxHeight: 320,
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
  });
