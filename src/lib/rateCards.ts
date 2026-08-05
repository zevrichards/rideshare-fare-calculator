import AsyncStorage from '@react-native-async-storage/async-storage';

export interface RateCard {
  id: string;
  name: string;
  baseFare: number;
  perKmRate: number;
  // null means no tiered long-distance rate (flat perKmRate for the whole trip).
  longDistanceKmThreshold: number | null;
  perKmRateBeyondThreshold: number | null;
  perMinuteRate: number;
  minimumFare: number;
  supportsSurge: boolean;
}

export const TTRS_RATE_CARD: RateCard = {
  id: 'ttrs',
  name: 'TTRS',
  baseFare: 16,
  perKmRate: 1.75,
  longDistanceKmThreshold: 20,
  perKmRateBeyondThreshold: 3,
  perMinuteRate: 1.1,
  minimumFare: 28,
  supportsSurge: false,
};

export const ALLRIDI_RATE_CARD: RateCard = {
  id: 'allridi',
  name: 'Allridi',
  baseFare: 15,
  perKmRate: 1.55,
  longDistanceKmThreshold: null,
  perKmRateBeyondThreshold: null,
  perMinuteRate: 1.1,
  minimumFare: 22,
  supportsSurge: true,
};

export const DEFAULT_RATE_CARDS: RateCard[] = [TTRS_RATE_CARD, ALLRIDI_RATE_CARD];

const RATE_CARDS_STORAGE_KEY = '@rideshare_fare_calc/rate_cards';
const SELECTED_RATE_CARD_STORAGE_KEY = '@rideshare_fare_calc/selected_rate_card_id';
const SURGE_MULTIPLIER_STORAGE_KEY = '@rideshare_fare_calc/surge_multiplier';

export async function getRateCards(): Promise<RateCard[]> {
  const raw = await AsyncStorage.getItem(RATE_CARDS_STORAGE_KEY);
  if (!raw) {
    return DEFAULT_RATE_CARDS;
  }
  try {
    const saved = JSON.parse(raw) as RateCard[];
    // Overlay saved edits onto the known default cards by id -- keeps
    // ordering/shape stable even if a default card was never edited, and
    // ignores anything in storage that no longer matches a known card.
    return DEFAULT_RATE_CARDS.map(
      defaultCard => saved.find(card => card.id === defaultCard.id) ?? defaultCard,
    );
  } catch {
    return DEFAULT_RATE_CARDS;
  }
}

export async function saveRateCard(card: RateCard): Promise<RateCard[]> {
  const cards = await getRateCards();
  const updated = cards.map(existing => (existing.id === card.id ? card : existing));
  await AsyncStorage.setItem(RATE_CARDS_STORAGE_KEY, JSON.stringify(updated));
  return updated;
}

export async function getSelectedRateCardId(): Promise<string> {
  const raw = await AsyncStorage.getItem(SELECTED_RATE_CARD_STORAGE_KEY);
  return raw ?? TTRS_RATE_CARD.id;
}

export async function setSelectedRateCardId(id: string): Promise<void> {
  await AsyncStorage.setItem(SELECTED_RATE_CARD_STORAGE_KEY, id);
}

export async function getSurgeMultiplier(): Promise<number> {
  const raw = await AsyncStorage.getItem(SURGE_MULTIPLIER_STORAGE_KEY);
  const parsed = raw != null ? Number(raw) : NaN;
  return Number.isFinite(parsed) ? parsed : 1;
}

export async function setSurgeMultiplier(value: number): Promise<void> {
  await AsyncStorage.setItem(SURGE_MULTIPLIER_STORAGE_KEY, String(value));
}
