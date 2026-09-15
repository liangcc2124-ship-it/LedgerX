package com.ledgerx.application.settings;

/** Transport-neutral port for settings belonging to the current active profile. */
public interface SettingsApi {
    SettingsApiResult read() throws SettingsException;

    SettingsApiResult update(long expectedRevision, SettingsPatch patch, SettingsMutation mutation)
            throws SettingsException;
}
