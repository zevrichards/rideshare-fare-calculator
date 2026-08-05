module.exports = {
  getCurrentPosition: jest.fn((success) =>
    success({
      coords: {
        latitude: 0,
        longitude: 0,
        altitude: null,
        accuracy: 5,
        altitudeAccuracy: null,
        heading: null,
        speed: null,
      },
      timestamp: Date.now(),
    }),
  ),
  watchPosition: jest.fn(() => 1),
  clearWatch: jest.fn(),
  stopObserving: jest.fn(),
  requestAuthorization: jest.fn((success) => success && success()),
  setRNConfiguration: jest.fn(),
};
