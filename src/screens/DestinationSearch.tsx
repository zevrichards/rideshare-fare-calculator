import React, {useMemo, useState} from 'react';
import {ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View} from 'react-native';
import {PlaceResult, searchDestination} from '../lib/geocoding';
import {ThemeColors, useThemeColors} from '../theme/colors';

interface DestinationSearchProps {
  onSelect: (result: PlaceResult) => void;
}

export default function DestinationSearch({onSelect}: DestinationSearchProps) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<PlaceResult[]>([]);
  const [searching, setSearching] = useState(false);
  const [searched, setSearched] = useState(false);
  const colors = useThemeColors();
  const styles = useMemo(() => createStyles(colors), [colors]);

  const handleSearch = async () => {
    if (query.trim() === '') return;
    setSearching(true);
    setSearched(false);
    const found = await searchDestination(query);
    setResults(found);
    setSearching(false);
    setSearched(true);
  };

  const handleSelect = (result: PlaceResult) => {
    setResults([]);
    setSearched(false);
    setQuery(result.name);
    onSelect(result);
  };

  return (
    <View style={styles.container}>
      <Text style={styles.label}>Search Destination</Text>
      <View style={styles.searchRow}>
        <TextInput
          style={styles.input}
          value={query}
          onChangeText={setQuery}
          placeholder="e.g. Arima Hospital"
          placeholderTextColor={colors.placeholder}
          onSubmitEditing={handleSearch}
        />
        <Pressable
          style={[styles.searchButton, searching && styles.searchButtonDisabled]}
          onPress={handleSearch}
          disabled={searching}>
          {searching ? (
            <ActivityIndicator color={colors.onPrimary} />
          ) : (
            <Text style={styles.searchButtonText}>Search</Text>
          )}
        </Pressable>
      </View>

      {results.length > 0 && (
        <View style={styles.resultsList}>
          {results.map((result, index) => (
            <Pressable
              key={`${result.location.latitude}-${result.location.longitude}-${index}`}
              style={styles.resultRow}
              onPress={() => handleSelect(result)}>
              <Text style={styles.resultText}>{result.name}</Text>
            </Pressable>
          ))}
        </View>
      )}

      {searched && !searching && results.length === 0 && (
        <Text style={styles.noResults}>
          No matches found. Try a more specific search, or enter coordinates below.
        </Text>
      )}
    </View>
  );
}

const createStyles = (colors: ThemeColors) =>
  StyleSheet.create({
    container: {
      marginBottom: 16,
    },
    label: {
      fontSize: 13,
      color: colors.textSecondary,
      marginBottom: 6,
    },
    searchRow: {
      flexDirection: 'row',
      gap: 8,
    },
    input: {
      flex: 1,
      borderWidth: 1,
      borderColor: colors.border,
      borderRadius: 8,
      padding: 12,
      fontSize: 16,
      color: colors.textPrimary,
      backgroundColor: colors.surface,
    },
    searchButton: {
      backgroundColor: colors.primary,
      borderRadius: 8,
      paddingHorizontal: 16,
      alignItems: 'center',
      justifyContent: 'center',
    },
    searchButtonDisabled: {
      opacity: 0.7,
    },
    searchButtonText: {
      color: colors.onPrimary,
      fontSize: 14,
      fontWeight: '600',
    },
    resultsList: {
      marginTop: 8,
      borderWidth: 1,
      borderColor: colors.borderSubtle,
      borderRadius: 8,
      overflow: 'hidden',
      backgroundColor: colors.surface,
    },
    resultRow: {
      paddingVertical: 12,
      paddingHorizontal: 12,
      borderBottomWidth: 1,
      borderBottomColor: colors.borderSubtle,
    },
    resultText: {
      fontSize: 14,
      color: colors.textPrimary,
    },
    noResults: {
      marginTop: 8,
      fontSize: 12,
      color: colors.textMuted,
    },
  });
