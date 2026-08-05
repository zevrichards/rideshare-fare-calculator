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
    const {estimateFare} = require('../fare');

    const result = await estimateFareWithRouting(origin, destination);

    expect(fetchSpy).not.toHaveBeenCalled();
    expect(result).toEqual({
      total: estimateFare(origin, destination),
      source: 'straight-line',
    });
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
    const {calculateFare} = require('../fare');

    const result = await estimateFareWithRouting(origin, destination);

    expect(result).toEqual({total: calculateFare(12, 0), source: 'routing'});
  });

  it('falls back to the straight-line estimate on a non-OK response', async () => {
    jest.doMock('../../config/apiKeys', () => ({
      GOOGLE_ROUTES_API_KEY: 'test-key',
    }));
    (globalThis as any).fetch = jest.fn().mockResolvedValue({ok: false, status: 403});

    const {estimateFareWithRouting} = require('../routing');
    const {estimateFare} = require('../fare');

    const result = await estimateFareWithRouting(origin, destination);

    expect(result).toEqual({
      total: estimateFare(origin, destination),
      source: 'straight-line',
    });
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
    const {estimateFare} = require('../fare');

    const result = await estimateFareWithRouting(origin, destination);

    expect(result).toEqual({
      total: estimateFare(origin, destination),
      source: 'straight-line',
    });
  });

  it('falls back to the straight-line estimate when the network request throws', async () => {
    jest.doMock('../../config/apiKeys', () => ({
      GOOGLE_ROUTES_API_KEY: 'test-key',
    }));
    (globalThis as any).fetch = jest.fn().mockRejectedValue(new Error('network down'));

    const {estimateFareWithRouting} = require('../routing');
    const {estimateFare} = require('../fare');

    const result = await estimateFareWithRouting(origin, destination);

    expect(result).toEqual({
      total: estimateFare(origin, destination),
      source: 'straight-line',
    });
  });
});
