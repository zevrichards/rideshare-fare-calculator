import React, {useEffect, useMemo, useState} from 'react';
import {Pressable, StyleSheet, Text, TextInput, View} from 'react-native';
import Slider from '@react-native-community/slider';
import {
  RateCard,
  getRateCards,
  getSelectedRateCardId,
  getSurgeMultiplier,
  saveRateCard,
  setSelectedRateCardId,
  setSurgeMultiplier,
} from '../lib/rateCards';
import {
  mirrorRateCard,
  mirrorSelectedRateCard,
  mirrorSurgeMultiplier,
} from '../native/FareOverlay';
import {ThemeColors, useThemeColors} from '../theme/colors';

interface RateCardSectionProps {
  onActiveRateCardChange: (card: RateCard) => void;
  onSurgeMultiplierChange: (value: number) => void;
}

interface EditableFields {
  baseFare: string;
  perKmRate: string;
  longDistanceKmThreshold: string;
  perKmRateBeyondThreshold: string;
  perMinuteRate: string;
  minimumFare: string;
}

function toEditableFields(card: RateCard): EditableFields {
  return {
    baseFare: String(card.baseFare),
    perKmRate: String(card.perKmRate),
    longDistanceKmThreshold:
      card.longDistanceKmThreshold != null ? String(card.longDistanceKmThreshold) : '',
    perKmRateBeyondThreshold:
      card.perKmRateBeyondThreshold != null ? String(card.perKmRateBeyondThreshold) : '',
    perMinuteRate: String(card.perMinuteRate),
    minimumFare: String(card.minimumFare),
  };
}

export default function RateCardSection({
  onActiveRateCardChange,
  onSurgeMultiplierChange,
}: RateCardSectionProps) {
  const [cards, setCards] = useState<RateCard[]>([]);
  const [selectedId, setSelectedIdState] = useState('ttrs');
  const [surge, setSurge] = useState(1);
  const [editing, setEditing] = useState(false);
  const [fields, setFields] = useState<EditableFields | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const colors = useThemeColors();
  const styles = useMemo(() => createStyles(colors), [colors]);

  useEffect(() => {
    Promise.all([getRateCards(), getSelectedRateCardId(), getSurgeMultiplier()]).then(
      ([loadedCards, id, surgeValue]) => {
        setCards(loadedCards);
        setSelectedIdState(id);
        setSurge(surgeValue);
        onSurgeMultiplierChange(surgeValue);
        const active = loadedCards.find(card => card.id === id) ?? loadedCards[0];
        if (active) {
          onActiveRateCardChange(active);
        }
      },
    );
    // Runs once on mount, same pattern as TripScreen's setup effect.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const selectedCard = cards.find(card => card.id === selectedId) ?? cards[0];

  const handleSelect = async (id: string) => {
    setSelectedIdState(id);
    setEditing(false);
    await setSelectedRateCardId(id);
    mirrorSelectedRateCard(id);
    const card = cards.find(c => c.id === id);
    if (card) {
      onActiveRateCardChange(card);
    }
  };

  const handleStartEditing = () => {
    if (!selectedCard) return;
    setFields(toEditableFields(selectedCard));
    setSaveError(null);
    setEditing(true);
  };

  const handleSave = async () => {
    if (!selectedCard || !fields) return;

    const hasTier = selectedCard.longDistanceKmThreshold != null;
    const parsed = {
      baseFare: Number(fields.baseFare),
      perKmRate: Number(fields.perKmRate),
      perMinuteRate: Number(fields.perMinuteRate),
      minimumFare: Number(fields.minimumFare),
      longDistanceKmThreshold: hasTier ? Number(fields.longDistanceKmThreshold) : null,
      perKmRateBeyondThreshold: hasTier ? Number(fields.perKmRateBeyondThreshold) : null,
    };

    const numbersToValidate = [
      parsed.baseFare,
      parsed.perKmRate,
      parsed.perMinuteRate,
      parsed.minimumFare,
      ...(hasTier ? [parsed.longDistanceKmThreshold!, parsed.perKmRateBeyondThreshold!] : []),
    ];
    if (numbersToValidate.some(value => !Number.isFinite(value) || value < 0)) {
      setSaveError('Enter valid non-negative numbers for every field.');
      return;
    }

    const updatedCard: RateCard = {...selectedCard, ...parsed};
    const updatedCards = await saveRateCard(updatedCard);
    mirrorRateCard(updatedCard);
    setCards(updatedCards);
    setEditing(false);
    setSaveError(null);
    if (updatedCard.id === selectedId) {
      onActiveRateCardChange(updatedCard);
    }
  };

  const handleSurgeChangeLive = (value: number) => {
    setSurge(value);
    onSurgeMultiplierChange(value);
  };

  const handleSurgeCommit = async (value: number) => {
    await setSurgeMultiplier(value);
    mirrorSurgeMultiplier(value);
  };

  if (!selectedCard) {
    return null;
  }

  return (
    <View style={styles.section}>
      <Text style={styles.label}>Rate Card</Text>
      <View style={styles.row}>
        {cards.map(card => (
          <Pressable
            key={card.id}
            style={[styles.option, card.id === selectedId && styles.optionSelected]}
            onPress={() => handleSelect(card.id)}>
            <Text style={styles.optionText}>{card.name}</Text>
          </Pressable>
        ))}
      </View>

      {!editing && (
        <Pressable style={styles.linkButton} onPress={handleStartEditing}>
          <Text style={styles.linkButtonText}>Edit {selectedCard.name} rates</Text>
        </Pressable>
      )}

      {editing && fields && (
        <View style={styles.editForm}>
          <NumericField
            label="Base Fare ($)"
            value={fields.baseFare}
            onChangeText={value => setFields({...fields, baseFare: value})}
          />
          <NumericField
            label="Per Km Rate ($)"
            value={fields.perKmRate}
            onChangeText={value => setFields({...fields, perKmRate: value})}
          />
          {selectedCard.longDistanceKmThreshold != null && (
            <>
              <NumericField
                label="Long-Distance Threshold (km)"
                value={fields.longDistanceKmThreshold}
                onChangeText={value =>
                  setFields({...fields, longDistanceKmThreshold: value})
                }
              />
              <NumericField
                label="Per Km Rate Beyond Threshold ($)"
                value={fields.perKmRateBeyondThreshold}
                onChangeText={value =>
                  setFields({...fields, perKmRateBeyondThreshold: value})
                }
              />
            </>
          )}
          <NumericField
            label="Per Minute Rate ($)"
            value={fields.perMinuteRate}
            onChangeText={value => setFields({...fields, perMinuteRate: value})}
          />
          <NumericField
            label="Minimum Fare ($)"
            value={fields.minimumFare}
            onChangeText={value => setFields({...fields, minimumFare: value})}
          />
          {saveError && <Text style={styles.error}>{saveError}</Text>}
          <View style={styles.editButtonRow}>
            <Pressable style={styles.saveButton} onPress={handleSave}>
              <Text style={styles.saveButtonText}>Save</Text>
            </Pressable>
            <Pressable style={styles.cancelButton} onPress={() => setEditing(false)}>
              <Text style={styles.cancelButtonText}>Cancel</Text>
            </Pressable>
          </View>
        </View>
      )}

      {selectedCard.supportsSurge && (
        <View style={styles.surgeSection}>
          <Text style={styles.label}>Surge: x{surge.toFixed(1)}</Text>
          <Slider
            style={styles.slider}
            minimumValue={1}
            maximumValue={1.5}
            step={0.1}
            value={surge}
            onValueChange={handleSurgeChangeLive}
            onSlidingComplete={handleSurgeCommit}
          />
        </View>
      )}
    </View>
  );
}

function NumericField({
  label,
  value,
  onChangeText,
}: {
  label: string;
  value: string;
  onChangeText: (text: string) => void;
}) {
  // Separate component, so it can't reach RateCardSection's local `styles`
  // (that's created inside the parent, not at module scope) -- recomputing
  // it here is cheap and keeps this component self-contained.
  const colors = useThemeColors();
  const styles = useMemo(() => createStyles(colors), [colors]);
  return (
    <View style={styles.fieldRow}>
      <Text style={styles.fieldLabel}>{label}</Text>
      <TextInput
        style={styles.fieldInput}
        keyboardType="numeric"
        value={value}
        onChangeText={onChangeText}
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
    row: {
      flexDirection: 'row',
      gap: 8,
      marginBottom: 8,
    },
    option: {
      flex: 1,
      borderWidth: 1,
      borderColor: colors.border,
      borderRadius: 8,
      paddingVertical: 10,
      alignItems: 'center',
    },
    optionSelected: {
      borderColor: colors.primary,
      backgroundColor: colors.surfaceAlt,
    },
    optionText: {
      fontSize: 14,
      fontWeight: '600',
      color: colors.textPrimary,
    },
    linkButton: {
      alignSelf: 'flex-start',
      marginBottom: 8,
    },
    linkButtonText: {
      fontSize: 13,
      color: colors.primary,
      fontWeight: '600',
    },
    editForm: {
      backgroundColor: colors.panelBg,
      borderRadius: 8,
      padding: 12,
      marginBottom: 8,
      gap: 8,
    },
    fieldRow: {
      gap: 4,
    },
    fieldLabel: {
      fontSize: 12,
      color: colors.textSecondary,
    },
    fieldInput: {
      borderWidth: 1,
      borderColor: colors.border,
      borderRadius: 6,
      paddingVertical: 8,
      paddingHorizontal: 10,
      fontSize: 14,
      color: colors.textPrimary,
      backgroundColor: colors.surface,
    },
    editButtonRow: {
      flexDirection: 'row',
      gap: 8,
      marginTop: 4,
    },
    saveButton: {
      flex: 1,
      backgroundColor: colors.primary,
      borderRadius: 6,
      paddingVertical: 10,
      alignItems: 'center',
    },
    saveButtonText: {
      color: colors.onPrimary,
      fontSize: 14,
      fontWeight: '600',
    },
    cancelButton: {
      flex: 1,
      borderWidth: 1,
      borderColor: colors.border,
      borderRadius: 6,
      paddingVertical: 10,
      alignItems: 'center',
    },
    cancelButtonText: {
      fontSize: 14,
      fontWeight: '600',
      color: colors.textPrimary,
    },
    error: {
      color: colors.danger,
      fontSize: 12,
    },
    surgeSection: {
      marginTop: 4,
    },
    slider: {
      width: '100%',
      height: 40,
    },
  });
