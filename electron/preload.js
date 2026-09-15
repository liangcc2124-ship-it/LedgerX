'use strict';

const { contextBridge, ipcRenderer } = require('electron');

function invoke(channel) {
  return ipcRenderer.invoke(channel);
}

contextBridge.exposeInMainWorld('desktop', Object.freeze({
  openDataFolder: () => invoke('desktop.openDataFolder'),
  openLogsFolder: () => invoke('desktop.openLogsFolder'),
  retryBackend: () => invoke('desktop.retryBackend')
}));
