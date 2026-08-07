import React, {useEffect, useMemo, useState} from 'react';
import {StyleSheet, Text, View} from 'react-native';
import Slider from '@react-native-community/slider';
import {getOverlayScale, setOverlayScale} from '../lib/overlaySettings';
import {mirrorOverlayScale} from '../native/FareOverlay';
import {ThemeColors, useThemeColors} from '../theme/colors';

const MIN_SCALE = 0.8;
const MAX_SCALE = 2.0;

export default function OverlaySizeSection() {
  const [scale, setScale] = useState(1);
  const colors = useThemeColors();
  const styles = useMemo(() => createStyles(colors), [colors]);

  useEffect(() => {
    getOverlayScale().then(value => {
      setScale(value);
      mirrorOverlayScale(value);
    });
    // Runs once on mount, same pattern as RateCardSection's setup effect.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleCommit = async (value: number) => {
    await setOverlayScale(value);
    mirrorOverlayScale(value);
  };

  return (
    <View style={styles.section}>
      <Text style={styles.label}>Overlay Size: {scale.toFixed(1)}x</Text>
      <Slider
        style={styles.slider}
        minimumValue={MIN_SCALE}
        maximumValue={MAX_SCALE}
        step={0.1}
        value={scale}
        onValueChange={setScale}
        onSlidingComplete={handleCommit}
      />
    </View>
  );
}

const createStyles = (colors: ThemeColors) =>
  StyleSheet.create({
    section: {
      marginBottom: 16,
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
