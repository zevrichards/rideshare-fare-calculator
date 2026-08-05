import {GOOGLE_ROUTES_API_KEY} from '../config/apiKeys';
import {calculateFare, estimateFare, GeoPoint} from './fare';

const COMPUTE_ROUTES_URL =
  'https://routes.googleapis.com/directions/v2:computeRoutes';
const REQUEST_TIMEOUT_MS = 8000;

export interface FareEstimate {
  total: number;
  source: 'routing' | 'straight-line';
}

async function fetchRoadDistanceKm(
  origin: GeoPoint,
  destination: GeoPoint,
): Promise<number> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

  try {
    const response = await fetch(COMPUTE_ROUTES_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Goog-Api-Key': GOOGLE_ROUTES_API_KEY,
        'X-Goog-FieldMask': 'routes.distanceMeters',
      },
      body: JSON.stringify({
        origin: {location: {latLng: origin}},
        destination: {location: {latLng: destination}},
        travelMode: 'DRIVE',
      }),
      signal: controller.signal,
    });

    if (!response.ok) {
      throw new Error(`Routes API responded with ${response.status}`);
    }

    const data = await response.json();
    const distanceMeters = data?.routes?.[0]?.distanceMeters;
    if (typeof distanceMeters !== 'number') {
      throw new Error('Routes API response missing distanceMeters');
    }
    return distanceMeters / 1000;
  } finally {
    clearTimeout(timeout);
  }
}

// Tries real road distance via the Routes API; falls back to the
// straight-line*1.3 estimate (see fare.ts) if no key is configured or the
// request fails for any reason -- this must never throw, since it sits in
// the critical path of starting a trip.
export async function estimateFareWithRouting(
  origin: GeoPoint,
  destination: GeoPoint,
): Promise<FareEstimate> {
  if (!GOOGLE_ROUTES_API_KEY) {
    return {total: estimateFare(origin, destination), source: 'straight-line'};
  }

  try {
    const roadDistanceKm = await fetchRoadDistanceKm(origin, destination);
    return {total: calculateFare(roadDistanceKm, 0), source: 'routing'};
  } catch {
    return {total: estimateFare(origin, destination), source: 'straight-line'};
  }
}
