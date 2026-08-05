module.exports = {
  preset: '@react-native/jest-preset',
  // react-native-maps ships its src/index.ts as the main entry (untranspiled
  // TypeScript) rather than a prebuilt lib/ -- extends the preset's default
  // pattern rather than replacing it, so other RN packages stay covered.
  transformIgnorePatterns: [
    'node_modules/(?!((jest-)?react-native|@react-native(-community)?|react-native-maps)/)',
  ],
};
