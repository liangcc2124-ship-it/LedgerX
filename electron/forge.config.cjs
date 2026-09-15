const path = require('node:path');

module.exports = {
  packagerConfig: {
    asar: true,
    name: 'LedgerX',
    executableName: 'LedgerX',
    extraResource: [
      path.join(__dirname, 'packaging', 'java'),
      path.join(__dirname, 'packaging', 'resources'),
    ],
  },
  makers: [
    {
      name: '@electron-forge/maker-squirrel',
      config: {
        name: 'ledgerx',
        authors: 'LedgerX',
        description: 'LedgerX desktop ledger',
        setupExe: 'LedgerXSetup.exe',
        title: 'LedgerX',
      },
    },
  ],
};
