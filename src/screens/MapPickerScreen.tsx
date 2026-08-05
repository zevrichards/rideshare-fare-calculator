import React, {useState} from 'react';
import {Pressable, StyleSheet, Text, View} from 'react-native';
import MapView, {MapPressEvent, Marker} from 'react-native-maps';
import {GeoPoint} from '../lib/fare';

interface MapPickerScreenProps {
  onConfirm: (destination: GeoPoint) => void;
  onCancel: () => void;
}

// Centered on Trinidad since that's this app's actual coverage area --
// no GPS lookup here to keep this screen fast/simple to open.
const DEFAULT_REGION = {
  latitude: 10.6549,
  longitude: -61.5019,
  latitudeDelta: 0.5,
  longitudeDelta: 0.5,
};

export default function MapPickerScreen({onConfirm, onCancel}: MapPickerScreenProps) {
  const [selected, setSelected] = useState<GeoPoint | null>(null);

  const handlePress = (event: MapPressEvent) => {
    setSelected(event.nativeEvent.coordinate);
  };

  return (
    <View style={styles.container}>
      <MapView style={styles.map} initialRegion={DEFAULT_REGION} onPress={handlePress}>
        {selected && <Marker coordinate={selected} />}
      </MapView>

      <View style={styles.footer}>
        <Text style={styles.hint}>
          {selected
            ? `${selected.latitude.toFixed(5)}, ${selected.longitude.toFixed(5)}`
            : 'Tap the map to place a destination pin.'}
        </Text>
        <View style={styles.buttonRow}>
          <Pressable style={styles.cancelButton} onPress={onCancel}>
            <Text style={styles.cancelButtonText}>Cancel</Text>
          </Pressable>
          <Pressable
            style={[styles.confirmButton, !selected && styles.confirmButtonDisabled]}
            disabled={!selected}
            onPress={() => selected && onConfirm(selected)}>
            <Text style={styles.confirmButtonText}>Confirm Destination</Text>
          </Pressable>
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  map: {
    flex: 1,
  },
  footer: {
    padding: 16,
    backgroundColor: '#fff',
  },
  hint: {
    fontSize: 13,
    color: '#555',
    marginBottom: 12,
    textAlign: 'center',
  },
  buttonRow: {
    flexDirection: 'row',
    gap: 8,
  },
  cancelButton: {
    flex: 1,
    borderWidth: 1,
    borderColor: '#ccc',
    borderRadius: 8,
    paddingVertical: 14,
    alignItems: 'center',
  },
  cancelButtonText: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
  },
  confirmButton: {
    flex: 1,
    backgroundColor: '#1a73e8',
    borderRadius: 8,
    paddingVertical: 14,
    alignItems: 'center',
  },
  confirmButtonDisabled: {
    opacity: 0.5,
  },
  confirmButtonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
});
