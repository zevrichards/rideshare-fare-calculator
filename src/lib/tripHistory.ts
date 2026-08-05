import AsyncStorage from '@react-native-async-storage/async-storage';

export interface CompletedTrip {
  id: string;
  timestamp: number;
  distanceKm: number;
  minutes: number;
  total: number;
}

const STORAGE_KEY = '@rideshare_fare_calc/trip_history';
const MAX_HISTORY_ENTRIES = 50;

export async function getTripHistory(): Promise<CompletedTrip[]> {
  const raw = await AsyncStorage.getItem(STORAGE_KEY);
  if (!raw) {
    return [];
  }
  try {
    return JSON.parse(raw) as CompletedTrip[];
  } catch {
    return [];
  }
}

export async function saveCompletedTrip(
  trip: Omit<CompletedTrip, 'id' | 'timestamp'>,
): Promise<CompletedTrip> {
  const entry: CompletedTrip = {
    ...trip,
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    timestamp: Date.now(),
  };

  const history = await getTripHistory();
  const updated = [entry, ...history].slice(0, MAX_HISTORY_ENTRIES);
  await AsyncStorage.setItem(STORAGE_KEY, JSON.stringify(updated));

  return entry;
}
