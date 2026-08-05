import {RateCard} from './rateCards';

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

export interface FareBreakdown {
  base: number;
  distanceCharge: number;
  timeCharge: number;
  // base + distanceCharge + timeCharge, before surge/minimum.
  subtotal: number;
  // 1 for rate cards that don't support surge, regardless of what was passed in.
  surgeMultiplier: number;
  total: number;
  minimumApplied: boolean;
}

// surgeMultiplier is ignored for rate cards that don't support surge (e.g.
// TTRS) -- the caller doesn't need to know which cards support it.
export function calculateFareBreakdown(
  rateCard: RateCard,
  distanceKm: number,
  minutes: number,
  surgeMultiplier: number = 1,
): FareBreakdown {
  const hasTier = rateCard.longDistanceKmThreshold != null;
  const standardKm = hasTier
    ? Math.min(distanceKm, rateCard.longDistanceKmThreshold!)
    : distanceKm;
  const excessKm = hasTier
    ? Math.max(distanceKm - rateCard.longDistanceKmThreshold!, 0)
    : 0;

  const distanceCharge =
    standardKm * rateCard.perKmRate +
    excessKm * (rateCard.perKmRateBeyondThreshold ?? rateCard.perKmRate);
  const timeCharge = minutes * rateCard.perMinuteRate;
  const subtotal = rateCard.baseFare + distanceCharge + timeCharge;

  const effectiveSurge = rateCard.supportsSurge ? surgeMultiplier : 1;
  const rawTotal = subtotal * effectiveSurge;

  // The rate card's minimum fare is a floor on the whole trip, applied after
  // surge -- a short/cheap surged trip still can't undercut the minimum.
  const total = Math.max(rawTotal, rateCard.minimumFare);

  return {
    base: rateCard.baseFare,
    distanceCharge,
    timeCharge,
    subtotal,
    surgeMultiplier: effectiveSurge,
    total,
    minimumApplied: total > rawTotal,
  };
}

export function calculateFare(
  rateCard: RateCard,
  distanceKm: number,
  minutes: number,
  surgeMultiplier: number = 1,
): number {
  return calculateFareBreakdown(rateCard, distanceKm, minutes, surgeMultiplier).total;
}

// Straight-line distance systematically undershoots actual road distance;
// this rough multiplier approximates road distance until we have routing data.
export const STRAIGHT_LINE_DISTANCE_FUDGE_FACTOR = 1.3;

// Only used when there's no real routing data at all (no API key, or the
// Routes API request failed) -- a rough guess so the pre-trip estimate has
// *some* time charge instead of always $0, not meant to reflect real traffic.
export const ASSUMED_AVERAGE_SPEED_KMH = 30;

export function estimateFareBreakdown(
  rateCard: RateCard,
  origin: GeoPoint,
  destination: GeoPoint,
  surgeMultiplier: number = 1,
): FareBreakdown {
  const roadDistanceEstimateKm =
    haversineDistanceKm(origin, destination) *
    STRAIGHT_LINE_DISTANCE_FUDGE_FACTOR;
  const estimatedMinutes = (roadDistanceEstimateKm / ASSUMED_AVERAGE_SPEED_KMH) * 60;
  return calculateFareBreakdown(rateCard, roadDistanceEstimateKm, estimatedMinutes, surgeMultiplier);
}

export function estimateFare(
  rateCard: RateCard,
  origin: GeoPoint,
  destination: GeoPoint,
  surgeMultiplier: number = 1,
): number {
  return estimateFareBreakdown(rateCard, origin, destination, surgeMultiplier).total;
}
