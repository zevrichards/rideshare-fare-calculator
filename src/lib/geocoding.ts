import {GOOGLE_ROUTES_API_KEY} from '../config/apiKeys';
import {GeoPoint} from './fare';

const SEARCH_TEXT_URL = 'https://places.googleapis.com/v1/places:searchText';
const REQUEST_TIMEOUT_MS = 8000;
const MAX_RESULTS = 5;

export interface PlaceResult {
  name: string;
  location: GeoPoint;
}

// Uses the "Places API (New)" Text Search endpoint -- same Google Cloud
// project/API key as the Routes integration, but Places API (New) must be
// separately enabled on that project (see README). Returns [] on any
// failure (no key, API not enabled, network error, no matches) rather than
// throwing, since this sits in an interactive search box.
export async function searchDestination(query: string): Promise<PlaceResult[]> {
  if (!GOOGLE_ROUTES_API_KEY || query.trim() === '') {
    return [];
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

  try {
    const response = await fetch(SEARCH_TEXT_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Goog-Api-Key': GOOGLE_ROUTES_API_KEY,
        'X-Goog-FieldMask': 'places.displayName,places.formattedAddress,places.location',
      },
      body: JSON.stringify({textQuery: query}),
      signal: controller.signal,
    });

    if (!response.ok) {
      return [];
    }

    const data = await response.json();
    const places = data?.places;
    if (!Array.isArray(places)) {
      return [];
    }

    return places
      .filter(
        (place: any) =>
          typeof place?.location?.latitude === 'number' &&
          typeof place?.location?.longitude === 'number',
      )
      .slice(0, MAX_RESULTS)
      .map((place: any) => ({
        name: place.displayName?.text ?? place.formattedAddress ?? query,
        location: {
          latitude: place.location.latitude,
          longitude: place.location.longitude,
        },
      }));
  } catch {
    return [];
  } finally {
    clearTimeout(timeout);
  }
}
