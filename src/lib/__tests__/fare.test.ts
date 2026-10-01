import {
  ASSUMED_AVERAGE_SPEED_KMH,
  calculateFare,
  calculateFareBreakdown,
  estimateFare,
  estimateFareBreakdown,
  haversineDistanceKm,
  STRAIGHT_LINE_DISTANCE_FUDGE_FACTOR,
} from '../fare';
import {ALLRIDI_RATE_CARD, TTRS_RATE_CARD} from '../rateCards';

describe('calculateFare with TTRS (tiered, minimum $28, no surge)', () => {
  it('floors at the minimum fare for zero distance and zero time', () => {
    expect(calculateFare(TTRS_RATE_CARD, 0, 0)).toBe(28);
  });

  it('charges the standard per-km rate below the 20km threshold, floored at the minimum', () => {
    // raw = 16 + 10*1.75 + 15*1.1 = 50, above the $28 minimum
    expect(calculateFare(TTRS_RATE_CARD, 10, 15)).toBeCloseTo(50, 5);
  });

  it('charges the standard rate for the full trip exactly at the threshold', () => {
    // raw = 16 + 20*1.75 = 51
    expect(calculateFare(TTRS_RATE_CARD, 20, 0)).toBeCloseTo(51, 5);
  });

  it('charges the higher rate only for distance beyond the threshold', () => {
    // raw = 16 + 20*1.75 + 5*3 = 66
    expect(calculateFare(TTRS_RATE_CARD, 25, 0)).toBeCloseTo(66, 5);
  });

  it('combines the tiered distance charge with the time charge', () => {
    // raw = 16 + 20*1.75 + 10*3 + 10*1.1 = 92
    expect(calculateFare(TTRS_RATE_CARD, 30, 10)).toBeCloseTo(92, 5);
  });

  it('ignores a surge multiplier since TTRS does not support surge', () => {
    expect(calculateFare(TTRS_RATE_CARD, 30, 10, 1.5)).toBeCloseTo(92, 5);
  });
});

describe('calculateFare with Allridi (tiered beyond 20km, minimum $22, supports surge)', () => {
  it('floors at the minimum fare for zero distance and zero time', () => {
    expect(calculateFare(ALLRIDI_RATE_CARD, 0, 0)).toBe(22);
  });

  it('charges the standard per-km rate below the 20km threshold, floored at the minimum', () => {
    // raw = 15 + 10*1.55 + 15*1.1 = 47, above the $22 minimum
    expect(calculateFare(ALLRIDI_RATE_CARD, 10, 15)).toBeCloseTo(47, 5);
  });

  it('charges the standard rate for the full trip exactly at the threshold', () => {
    // raw = 15 + 20*1.55 = 46
    expect(calculateFare(ALLRIDI_RATE_CARD, 20, 0)).toBeCloseTo(46, 5);
  });

  it('charges the higher rate only for distance beyond the threshold', () => {
    // raw = 15 + 20*1.55 + 10*3 + 10*1.1 = 87
    expect(calculateFare(ALLRIDI_RATE_CARD, 30, 10)).toBeCloseTo(87, 5);
  });

  it('applies the surge multiplier to the whole calculated fare, including the beyond-threshold tier', () => {
    // raw = (15 + 20*1.55 + 10*3 + 10*1.1) * 1.2 = 87 * 1.2 = 104.4
    expect(calculateFare(ALLRIDI_RATE_CARD, 30, 10, 1.2)).toBeCloseTo(104.4, 5);
  });

  it('does not floor a surged fare that already exceeds the minimum', () => {
    // raw = (15 + 1*1.55) * 1.5 = 24.825, above the $22 minimum
    expect(calculateFare(ALLRIDI_RATE_CARD, 1, 0, 1.5)).toBeCloseTo(24.825, 5);
  });

  it('still applies the minimum fare floor when the surged fare is below it', () => {
    // raw = 15 * 1.05 = 15.75, below the $22 minimum
    expect(calculateFare(ALLRIDI_RATE_CARD, 0, 0, 1.05)).toBe(22);
  });
});

describe('calculateFareBreakdown', () => {
  it('breaks TTRS down into base/distance/time with no surge applied and minimum not hit', () => {
    // base=16, distance=10*1.75=17.5, time=15*1.1=16.5, subtotal=50
    const breakdown = calculateFareBreakdown(TTRS_RATE_CARD, 10, 15);
    expect(breakdown).toEqual({
      base: 16,
      distanceCharge: 17.5,
      timeCharge: 16.5,
      subtotal: 50,
      surgeMultiplier: 1,
      total: 50,
      minimumApplied: false,
    });
  });

  it('flags minimumApplied when the raw total is below the floor', () => {
    const breakdown = calculateFareBreakdown(TTRS_RATE_CARD, 0, 0);
    expect(breakdown.subtotal).toBe(16);
    expect(breakdown.total).toBe(28);
    expect(breakdown.minimumApplied).toBe(true);
  });

  it('reports the effective surge multiplier and applies it to the total for Allridi', () => {
    // subtotal = 15 + 20*1.55 + 10*3 + 10*1.1 = 87, total = 87*1.2 = 104.4
    const breakdown = calculateFareBreakdown(ALLRIDI_RATE_CARD, 30, 10, 1.2);
    expect(breakdown.subtotal).toBeCloseTo(87, 5);
    expect(breakdown.surgeMultiplier).toBe(1.2);
    expect(breakdown.total).toBeCloseTo(104.4, 5);
    expect(breakdown.minimumApplied).toBe(false);
  });

  it('reports a surge multiplier of 1 for TTRS regardless of what was passed in', () => {
    const breakdown = calculateFareBreakdown(TTRS_RATE_CARD, 30, 10, 1.5);
    expect(breakdown.surgeMultiplier).toBe(1);
    expect(breakdown.total).toBeCloseTo(92, 5);
  });
});

describe('haversineDistanceKm', () => {
  it('returns zero for identical points', () => {
    const point = {latitude: 15.3, longitude: -61.38};
    expect(haversineDistanceKm(point, point)).toBeCloseTo(0, 5);
  });

  it('returns approximately 111km for one degree of longitude at the equator', () => {
    const a = {latitude: 0, longitude: 0};
    const b = {latitude: 0, longitude: 1};
    expect(haversineDistanceKm(a, b)).toBeCloseTo(111.19, 1);
  });
});

describe('estimateFare', () => {
  it('returns the minimum fare when origin equals destination', () => {
    const point = {latitude: 15.3, longitude: -61.38};
    expect(estimateFare(TTRS_RATE_CARD, point, point)).toBeCloseTo(28, 5);
  });

  it('applies the road-distance fudge factor and an assumed average speed for the time charge', () => {
    const a = {latitude: 0, longitude: 0};
    const b = {latitude: 0, longitude: 1};
    const straightLineKm = haversineDistanceKm(a, b);
    const roadDistanceKm = straightLineKm * STRAIGHT_LINE_DISTANCE_FUDGE_FACTOR;
    const estimatedMinutes = (roadDistanceKm / ASSUMED_AVERAGE_SPEED_KMH) * 60;
    expect(estimateFare(TTRS_RATE_CARD, a, b)).toBeCloseTo(
      calculateFare(TTRS_RATE_CARD, roadDistanceKm, estimatedMinutes),
      5,
    );
  });

  it('gives a non-zero time charge for a non-zero-distance estimate', () => {
    const a = {latitude: 0, longitude: 0};
    const b = {latitude: 0, longitude: 1};
    const breakdown = estimateFareBreakdown(TTRS_RATE_CARD, a, b);
    expect(breakdown.timeCharge).toBeGreaterThan(0);
  });
});
