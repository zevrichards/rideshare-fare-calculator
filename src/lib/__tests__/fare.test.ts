import {calculateFare, estimateFare, haversineDistanceKm} from '../fare';

describe('calculateFare', () => {
  it('charges only the base fare for zero distance and zero time', () => {
    expect(calculateFare(0, 0)).toBe(16);
  });

  it('charges the standard per-km rate below the 20km threshold', () => {
    // 16 + 10*1.75 + 15*1.1
    expect(calculateFare(10, 15)).toBeCloseTo(50, 5);
  });

  it('charges the standard rate for the full trip exactly at the threshold', () => {
    // 16 + 20*1.75
    expect(calculateFare(20, 0)).toBeCloseTo(51, 5);
  });

  it('charges the higher rate only for distance beyond the threshold', () => {
    // 16 + 20*1.75 + 5*3
    expect(calculateFare(25, 0)).toBeCloseTo(66, 5);
  });

  it('combines the tiered distance charge with the time charge', () => {
    // 16 + 20*1.75 + 10*3 + 10*1.1
    expect(calculateFare(30, 10)).toBeCloseTo(92, 5);
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
  it('returns just the base fare when origin equals destination', () => {
    const point = {latitude: 15.3, longitude: -61.38};
    expect(estimateFare(point, point)).toBeCloseTo(16, 5);
  });

  it('applies the road-distance fudge factor to the straight-line distance', () => {
    const a = {latitude: 0, longitude: 0};
    const b = {latitude: 0, longitude: 1};
    const straightLineKm = haversineDistanceKm(a, b);
    expect(estimateFare(a, b)).toBeCloseTo(
      calculateFare(straightLineKm * 1.3, 0),
      5,
    );
  });
});
