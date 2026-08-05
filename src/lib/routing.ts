import {GOOGLE_ROUTES_API_KEY} from '../config/apiKeys';
import {
  calculateFareBreakdown,
  estimateFareBreakdown,
  FareBreakdown,
  GeoPoint,
} from './fare';
import {RateCard} from './rateCards';

const COMPUTE_ROUTES_URL =
  'https://routes.googleapis.com/directions/v2:computeRoutes';
const REQUEST_TIMEOUT_MS = 8000;

export interface FareEstimate {
  total: number;
  source: 'routing' | 'straight-line';
  breakdown: FareBreakdown;
}

interface RouteEstimate {
  distanceKm: number;
  durationMinutes: number;
}

// Routes API returns duration as a protobuf Duration string, e.g. "929s".
function parseDurationSeconds(duration: unknown): number | null {
  if (typeof duration !== 'string') return null;
  const match = /^(\d+(?:\.\d+)?)s$/.exec(duration);
  return match ? Number(match[1]) : null;
}

async function fetchRouteEstimate(
  origin: GeoPoint,
  destination: GeoPoint,
): Promise<RouteEstimate> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

  try {
    const response = await fetch(COMPUTE_ROUTES_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Goog-Api-Key': GOOGLE_ROUTES_API_KEY,
        'X-Goog-FieldMask': 'routes.distanceMeters,routes.duration',
      },
      body: JSON.stringify({
        origin: {location: {latLng: origin}},
        destination: {location: {latLng: destination}},
        travelMode: 'DRIVE',
        // Duration reflects live/predictive traffic conditions. This bills
        // at the Routes API's Pro SKU ($10/1,000, 5,000 free/month) rather
        // than Basic ($5/1,000, 10,000 free/month) -- see README.
        routingPreference: 'TRAFFIC_AWARE',
      }),
      signal: controller.signal,
    });

    if (!response.ok) {
      throw new Error(`Routes API responded with ${response.status}`);
    }

    const data = await response.json();
    const route = data?.routes?.[0];
    const distanceMeters = route?.distanceMeters;
    const durationSeconds = parseDurationSeconds(route?.duration);
    if (typeof distanceMeters !== 'number' || durationSeconds == null) {
      throw new Error('Routes API response missing distanceMeters/duration');
    }
    return {distanceKm: distanceMeters / 1000, durationMinutes: durationSeconds / 60};
  } finally {
    clearTimeout(timeout);
  }
}

// Tries real road distance via the Routes API; falls back to the
// straight-line*1.3 estimate (see fare.ts) if no key is configured or the
// request fails for any reason -- this must never throw, since it sits in
// the critical path of starting a trip.
export async function estimateFareWithRouting(
  rateCard: RateCard,
  origin: GeoPoint,
  destination: GeoPoint,
  surgeMultiplier: number = 1,
): Promise<FareEstimate> {
  if (!GOOGLE_ROUTES_API_KEY) {
    const breakdown = estimateFareBreakdown(rateCard, origin, destination, surgeMultiplier);
    return {total: breakdown.total, source: 'straight-line', breakdown};
  }

  try {
    const {distanceKm, durationMinutes} = await fetchRouteEstimate(origin, destination);
    const breakdown = calculateFareBreakdown(rateCard, distanceKm, durationMinutes, surgeMultiplier);
    return {total: breakdown.total, source: 'routing', breakdown};
  } catch {
    const breakdown = estimateFareBreakdown(rateCard, origin, destination, surgeMultiplier);
    return {total: breakdown.total, source: 'straight-line', breakdown};
  }
}
