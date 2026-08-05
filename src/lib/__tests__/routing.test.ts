import {TTRS_RATE_CARD} from '../rateCards';

describe('estimateFareWithRouting', () => {
  const origin = {latitude: 0, longitude: 0};
  const destination = {latitude: 0, longitude: 1};

  beforeEach(() => {
    jest.resetModules();
  });

  afterEach(() => {
    jest.restoreAllMocks();
    delete (globalThis as any).fetch;
  });

  it('falls back to the straight-line estimate when no API key is configured', async () => {
    jest.doMock('../../config/apiKeys', () => ({GOOGLE_ROUTES_API_KEY: ''}));
    const fetchSpy = jest.fn();
    (globalThis as any).fetch = fetchSpy;

    const {estimateFareWithRouting} = require('../routing');
    const {estimateFareBreakdown} = require('../fare');

    const result = await estimateFareWithRouting(TTRS_RATE_CARD, origin, destination);
    const breakdown = estimateFareBreakdown(TTRS_RATE_CARD, origin, destination);

    expect(fetchSpy).not.toHaveBeenCalled();
    expect(result).toEqual({total: breakdown.total, source: 'straight-line', breakdown});
  });

  it('uses road distance from the Routes API when the request succeeds', async () => {
    jest.doMock('../../config/apiKeys', () => ({
      GOOGLE_ROUTES_API_KEY: 'test-key',
    }));
    (globalThis as any).fetch = jest.fn().mockResolvedValue({
      ok: true,
      json: async () => ({routes: [{distanceMeters: 12000}]}),
    });

    const {estimateFareWithRouting} = require('../routing');
    const {calculateFareBreakdown} = require('../fare');

    const result = await estimateFareWithRouting(TTRS_RATE_CARD, origin, destination);
    const breakdown = calculateFareBreakdown(TTRS_RATE_CARD, 12, 0);

    expect(result).toEqual({total: breakdown.total, source: 'routing', breakdown});
  });

  it('applies a surge multiplier when the rate card supports it', async () => {
    jest.doMock('../../config/apiKeys', () => ({
      GOOGLE_ROUTES_API_KEY: 'test-key',
    }));
    (globalThis as any).fetch = jest.fn().mockResolvedValue({
      ok: true,
      json: async () => ({routes: [{distanceMeters: 12000}]}),
    });

    const {estimateFareWithRouting} = require('../routing');
    const {calculateFareBreakdown} = require('../fare');
    const {ALLRIDI_RATE_CARD} = require('../rateCards');

    const result = await estimateFareWithRouting(
      ALLRIDI_RATE_CARD,
      origin,
      destination,
      1.2,
    );
    const breakdown = calculateFareBreakdown(ALLRIDI_RATE_CARD, 12, 0, 1.2);

    expect(result).toEqual({total: breakdown.total, source: 'routing', breakdown});
  });

  it('falls back to the straight-line estimate on a non-OK response', async () => {
    jest.doMock('../../config/apiKeys', () => ({
      GOOGLE_ROUTES_API_KEY: 'test-key',
    }));
    (globalThis as any).fetch = jest.fn().mockResolvedValue({ok: false, status: 403});

    const {estimateFareWithRouting} = require('../routing');
    const {estimateFareBreakdown} = require('../fare');

    const result = await estimateFareWithRouting(TTRS_RATE_CARD, origin, destination);
    const breakdown = estimateFareBreakdown(TTRS_RATE_CARD, origin, destination);

    expect(result).toEqual({total: breakdown.total, source: 'straight-line', breakdown});
  });

  it('falls back to the straight-line estimate when distance data is missing', async () => {
    jest.doMock('../../config/apiKeys', () => ({
      GOOGLE_ROUTES_API_KEY: 'test-key',
    }));
    (globalThis as any).fetch = jest.fn().mockResolvedValue({
      ok: true,
      json: async () => ({routes: []}),
    });

    const {estimateFareWithRouting} = require('../routing');
    const {estimateFareBreakdown} = require('../fare');

    const result = await estimateFareWithRouting(TTRS_RATE_CARD, origin, destination);
    const breakdown = estimateFareBreakdown(TTRS_RATE_CARD, origin, destination);

    expect(result).toEqual({total: breakdown.total, source: 'straight-line', breakdown});
  });

  it('falls back to the straight-line estimate when the network request throws', async () => {
    jest.doMock('../../config/apiKeys', () => ({
      GOOGLE_ROUTES_API_KEY: 'test-key',
    }));
    (globalThis as any).fetch = jest.fn().mockRejectedValue(new Error('network down'));

    const {estimateFareWithRouting} = require('../routing');
    const {estimateFareBreakdown} = require('../fare');

    const result = await estimateFareWithRouting(TTRS_RATE_CARD, origin, destination);
    const breakdown = estimateFareBreakdown(TTRS_RATE_CARD, origin, destination);

    expect(result).toEqual({total: breakdown.total, source: 'straight-line', breakdown});
  });
});
