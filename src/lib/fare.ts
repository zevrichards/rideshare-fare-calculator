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

// surgeMultiplier is ignored for rate cards that don't support surge (e.g.
// TTRS) -- the caller doesn't need to know which cards support it.
export function calculateFare(
  rateCard: RateCard,
  distanceKm: number,
  minutes: number,
  surgeMultiplier: number = 1,
): number {
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

  const effectiveSurge = rateCard.supportsSurge ? surgeMultiplier : 1;
  const rawTotal = (rateCard.baseFare + distanceCharge + timeCharge) * effectiveSurge;

  // The rate card's minimum fare is a floor on the whole trip, applied after
  // surge -- a short/cheap surged trip still can't undercut the minimum.
  return Math.max(rawTotal, rateCard.minimumFare);
}

// Straight-line distance systematically undershoots actual road distance;
// this rough multiplier approximates road distance until we have routing data.
export const STRAIGHT_LINE_DISTANCE_FUDGE_FACTOR = 1.3;

export function estimateFare(
  rateCard: RateCard,
  origin: GeoPoint,
  destination: GeoPoint,
  surgeMultiplier: number = 1,
): number {
  const roadDistanceEstimateKm =
    haversineDistanceKm(origin, destination) *
    STRAIGHT_LINE_DISTANCE_FUDGE_FACTOR;
  return calculateFare(rateCard, roadDistanceEstimateKm, 0, surgeMultiplier);
}
