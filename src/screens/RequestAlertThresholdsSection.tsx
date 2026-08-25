import React, {useEffect, useMemo, useState} from 'react';
import {StyleSheet, Text, View} from 'react-native';
import Slider from '@react-native-community/slider';
import {
  getFarRequestThresholdKm,
  getNearRequestThresholdKm,
  setFarRequestThresholdKm,
  setNearRequestThresholdKm,
} from '../lib/requestAlertSettings';
import {mirrorFarRequestThresholdKm, mirrorNearRequestThresholdKm} from '../native/FareOverlay';
import {ThemeColors, useThemeColors} from '../theme/colors';

const MIN_FAR_KM = 1;
const MAX_FAR_KM = 15;
const MIN_NEAR_KM = 0.1;
const MAX_NEAR_KM = 3;

export default function RequestAlertThresholdsSection() {
  const [farKm, setFarKm] = useState(5);
  const [nearKm, setNearKm] = useState(1);
  const colors = useThemeColors();
  const styles = useMemo(() => createStyles(colors), [colors]);

  useEffect(() => {
    getFarRequestThresholdKm().then(value => {
      setFarKm(value);
      mirrorFarRequestThresholdKm(value);
    });
    getNearRequestThresholdKm().then(value => {
      setNearKm(value);
      mirrorNearRequestThresholdKm(value);
    });
    // Runs once on mount, same pattern as OverlaySizeSection's setup effect.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleFarCommit = async (value: number) => {
    await setFarRequestThresholdKm(value);
    mirrorFarRequestThresholdKm(value);
  };

  const handleNearCommit = async (value: number) => {
    await setNearRequestThresholdKm(value);
    mirrorNearRequestThresholdKm(value);
  };

  return (
    <View style={styles.section}>
      <Text style={styles.heading}>Request Alert Distances</Text>

      <Text style={styles.label}>Red (skip it) beyond: {farKm.toFixed(1)} km</Text>
      <Slider
        style={styles.slider}
        minimumValue={MIN_FAR_KM}
        maximumValue={MAX_FAR_KM}
        step={0.5}
        value={farKm}
        onValueChange={setFarKm}
        onSlidingComplete={handleFarCommit}
        minimumTrackTintColor={colors.danger}
      />

      <Text style={styles.label}>Green (grab it) under: {nearKm.toFixed(1)} km</Text>
      <Slider
        style={styles.slider}
        minimumValue={MIN_NEAR_KM}
        maximumValue={MAX_NEAR_KM}
        step={0.1}
        value={nearKm}
        onValueChange={setNearKm}
        onSlidingComplete={handleNearCommit}
        minimumTrackTintColor={colors.success}
      />
    </View>
  );
}

const createStyles = (colors: ThemeColors) =>
  StyleSheet.create({
    section: {
      marginBottom: 16,
    },
    heading: {
      fontSize: 13,
      fontWeight: '600',
      color: colors.textSecondary,
      marginBottom: 6,
    },
    label: {
      fontSize: 13,
      color: colors.textSecondary,
      marginBottom: 6,
    },
    slider: {
      width: '100%',
      height: 40,
    },
  });
