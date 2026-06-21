module.exports = {
  presets: ['module:@react-native/babel-preset'],
  plugins: [
    [
      'module-resolver',
      {
        root: ['./src'],
        alias: {
          '@core': './src/core',
          '@adapters': './src/game-adapters',
          '@types': './src/types',
          '@ui': './src/ui',
          '@store': './src/store',
          '@utils': './src/utils',
        },
      },
    ],
  ],
};
