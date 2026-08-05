describe('searchDestination', () => {
  beforeEach(() => {
    jest.resetModules();
  });

  afterEach(() => {
    jest.restoreAllMocks();
    delete (globalThis as any).fetch;
  });

  it('returns an empty array when no API key is configured', async () => {
    jest.doMock('../../config/apiKeys', () => ({GOOGLE_ROUTES_API_KEY: ''}));
    const fetchSpy = jest.fn();
    (globalThis as any).fetch = fetchSpy;

    const {searchDestination} = require('../geocoding');
    const result = await searchDestination('Arima Hospital');

    expect(fetchSpy).not.toHaveBeenCalled();
    expect(result).toEqual([]);
  });

  it('returns an empty array for a blank query without calling fetch', async () => {
    jest.doMock('../../config/apiKeys', () => ({GOOGLE_ROUTES_API_KEY: 'test-key'}));
    const fetchSpy = jest.fn();
    (globalThis as any).fetch = fetchSpy;

    const {searchDestination} = require('../geocoding');
    const result = await searchDestination('   ');

    expect(fetchSpy).not.toHaveBeenCalled();
    expect(result).toEqual([]);
  });

  it('maps places results to name/location pairs', async () => {
    jest.doMock('../../config/apiKeys', () => ({GOOGLE_ROUTES_API_KEY: 'test-key'}));
    (globalThis as any).fetch = jest.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        places: [
          {
            displayName: {text: 'Arima Hospital'},
            location: {latitude: 10.636997, longitude: -61.283994},
          },
          {
            displayName: {text: 'Arima Velodrome'},
            location: {latitude: 10.637, longitude: -61.284},
          },
        ],
      }),
    });

    const {searchDestination} = require('../geocoding');
    const result = await searchDestination('Arima Hospital');

    expect(result).toEqual([
      {name: 'Arima Hospital', location: {latitude: 10.636997, longitude: -61.283994}},
      {name: 'Arima Velodrome', location: {latitude: 10.637, longitude: -61.284}},
    ]);
  });

  it('falls back to the formatted address when there is no display name', async () => {
    jest.doMock('../../config/apiKeys', () => ({GOOGLE_ROUTES_API_KEY: 'test-key'}));
    (globalThis as any).fetch = jest.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        places: [
          {
            formattedAddress: '123 Queen Mary Ave, Arima',
            location: {latitude: 10.637, longitude: -61.284},
          },
        ],
      }),
    });

    const {searchDestination} = require('../geocoding');
    const result = await searchDestination('Arima');

    expect(result[0].name).toBe('123 Queen Mary Ave, Arima');
  });

  it('filters out results with no location', async () => {
    jest.doMock('../../config/apiKeys', () => ({GOOGLE_ROUTES_API_KEY: 'test-key'}));
    (globalThis as any).fetch = jest.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        places: [{displayName: {text: 'No Location'}}],
      }),
    });

    const {searchDestination} = require('../geocoding');
    const result = await searchDestination('Arima');

    expect(result).toEqual([]);
  });

  it('returns an empty array on a non-OK response', async () => {
    jest.doMock('../../config/apiKeys', () => ({GOOGLE_ROUTES_API_KEY: 'test-key'}));
    (globalThis as any).fetch = jest.fn().mockResolvedValue({ok: false, status: 403});

    const {searchDestination} = require('../geocoding');
    const result = await searchDestination('Arima');

    expect(result).toEqual([]);
  });

  it('returns an empty array when the network request throws', async () => {
    jest.doMock('../../config/apiKeys', () => ({GOOGLE_ROUTES_API_KEY: 'test-key'}));
    (globalThis as any).fetch = jest.fn().mockRejectedValue(new Error('network down'));

    const {searchDestination} = require('../geocoding');
    const result = await searchDestination('Arima');

    expect(result).toEqual([]);
  });
});
