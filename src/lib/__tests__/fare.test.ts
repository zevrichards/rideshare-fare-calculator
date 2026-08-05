import {calculateFare, estimateFare, haversineDistanceKm} from '../fare';
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

describe('calculateFare with Allridi (flat rate, minimum $22, supports surge)', () => {
  it('floors at the minimum fare for zero distance and zero time', () => {
    expect(calculateFare(ALLRIDI_RATE_CARD, 0, 0)).toBe(22);
  });

  it('charges a flat per-km rate with no long-distance tier', () => {
    // raw = 15 + 30*1.55 + 10*1.1 = 72.5
    expect(calculateFare(ALLRIDI_RATE_CARD, 30, 10)).toBeCloseTo(72.5, 5);
  });

  it('applies the surge multiplier to the whole calculated fare', () => {
    // raw = (15 + 30*1.55 + 10*1.1) * 1.2 = 72.5 * 1.2 = 87
    expect(calculateFare(ALLRIDI_RATE_CARD, 30, 10, 1.2)).toBeCloseTo(87, 5);
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

  it('applies the road-distance fudge factor to the straight-line distance', () => {
    const a = {latitude: 0, longitude: 0};
    const b = {latitude: 0, longitude: 1};
    const straightLineKm = haversineDistanceKm(a, b);
    expect(estimateFare(TTRS_RATE_CARD, a, b)).toBeCloseTo(
      calculateFare(TTRS_RATE_CARD, straightLineKm * 1.3, 0),
      5,
    );
  });
});
