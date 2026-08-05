import React, {useState} from 'react';
import {ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View} from 'react-native';
import {PlaceResult, searchDestination} from '../lib/geocoding';

interface DestinationSearchProps {
  onSelect: (result: PlaceResult) => void;
}

export default function DestinationSearch({onSelect}: DestinationSearchProps) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<PlaceResult[]>([]);
  const [searching, setSearching] = useState(false);
  const [searched, setSearched] = useState(false);

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
          onSubmitEditing={handleSearch}
        />
        <Pressable
          style={[styles.searchButton, searching && styles.searchButtonDisabled]}
          onPress={handleSearch}
          disabled={searching}>
          {searching ? (
            <ActivityIndicator color="#fff" />
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

const styles = StyleSheet.create({
  container: {
    marginBottom: 16,
  },
  label: {
    fontSize: 13,
    color: '#555',
    marginBottom: 6,
  },
  searchRow: {
    flexDirection: 'row',
    gap: 8,
  },
  input: {
    flex: 1,
    borderWidth: 1,
    borderColor: '#ccc',
    borderRadius: 8,
    padding: 12,
    fontSize: 16,
  },
  searchButton: {
    backgroundColor: '#1a73e8',
    borderRadius: 8,
    paddingHorizontal: 16,
    alignItems: 'center',
    justifyContent: 'center',
  },
  searchButtonDisabled: {
    opacity: 0.7,
  },
  searchButtonText: {
    color: '#fff',
    fontSize: 14,
    fontWeight: '600',
  },
  resultsList: {
    marginTop: 8,
    borderWidth: 1,
    borderColor: '#eee',
    borderRadius: 8,
    overflow: 'hidden',
  },
  resultRow: {
    paddingVertical: 12,
    paddingHorizontal: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#eee',
  },
  resultText: {
    fontSize: 14,
    color: '#333',
  },
  noResults: {
    marginTop: 8,
    fontSize: 12,
    color: '#777',
  },
});
