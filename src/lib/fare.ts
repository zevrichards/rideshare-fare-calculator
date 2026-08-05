export const BASE_FARE = 16;
export const RATE_PER_KM = 1.75;
export const LONG_DISTANCE_KM_THRESHOLD = 20;
export const RATE_PER_KM_BEYOND_THRESHOLD = 3;
export const RATE_PER_MINUTE = 1.1;

export interface GeoPoint {
  latitude: number;
  longitude: number;
}

const EARTH_RADIUS_KM = 6371;

function toRadians(degrees: number): number {
  return (degrees * Math.PI) / 180;
}

export function haversineDistanceKm(a: GeoPoint, b: GeoPoint): number {
  const dLat = toRadians(b.latitude - a.latitude);
  const dLon = toRadians(b.longitude - a.longitude);
  const lat1 = toRadians(a.latitude);
  const lat2 = toRadians(b.latitude);

  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;

  return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(h));
}

export function calculateFare(distanceKm: number, minutes: number): number {
  const standardKm = Math.min(distanceKm, LONG_DISTANCE_KM_THRESHOLD);
  const excessKm = Math.max(distanceKm - LONG_DISTANCE_KM_THRESHOLD, 0);

  const distanceCharge =
    standardKm * RATE_PER_KM + excessKm * RATE_PER_KM_BEYOND_THRESHOLD;
  const timeCharge = minutes * RATE_PER_MINUTE;

  return BASE_FARE + distanceCharge + timeCharge;
}

// Straight-line distance systematically undershoots actual road distance;
// this rough multiplier approximates road distance until we have routing data.
export const STRAIGHT_LINE_DISTANCE_FUDGE_FACTOR = 1.3;

export function estimateFare(origin: GeoPoint, destination: GeoPoint): number {
  const roadDistanceEstimateKm =
    haversineDistanceKm(origin, destination) *
    STRAIGHT_LINE_DISTANCE_FUDGE_FACTOR;
  return calculateFare(roadDistanceEstimateKm, 0);
}
