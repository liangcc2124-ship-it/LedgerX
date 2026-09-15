package com.ledgerx.application.settings;

/** Typed, partial update accepted by the settings application boundary. */
public final class SettingsPatch {
    private final Boolean notificationsEnabled;
    private final Boolean autoBackupEnabled;
    private final Integer autoBackupIntervalDays;
    private final Integer autoBackupRetentionCount;
    private final String lastSettingsSection;
    private final Long safetyBufferMinor;
    private final Boolean hideAllAmounts;
    private final String themeName;

    public SettingsPatch(Boolean notificationsEnabled, Boolean autoBackupEnabled,
            Integer autoBackupIntervalDays, Integer autoBackupRetentionCount,
            String lastSettingsSection, Long safetyBufferMinor, Boolean hideAllAmounts, String themeName) {
        this.notificationsEnabled = notificationsEnabled;
        this.autoBackupEnabled = autoBackupEnabled;
        this.autoBackupIntervalDays = autoBackupIntervalDays;
        this.autoBackupRetentionCount = autoBackupRetentionCount;
        this.lastSettingsSection = lastSettingsSection;
        this.safetyBufferMinor = safetyBufferMinor;
        this.hideAllAmounts = hideAllAmounts;
        this.themeName = themeName;
    }

    public Boolean getNotificationsEnabled() { return notificationsEnabled; }
    public Boolean getAutoBackupEnabled() { return autoBackupEnabled; }
    public Integer getAutoBackupIntervalDays() { return autoBackupIntervalDays; }
    public Integer getAutoBackupRetentionCount() { return autoBackupRetentionCount; }
    public String getLastSettingsSection() { return lastSettingsSection; }
    public Long getSafetyBufferMinor() { return safetyBufferMinor; }
    public Boolean getHideAllAmounts() { return hideAllAmounts; }
    public String getThemeName() { return themeName; }

    public boolean isEmpty() {
        return notificationsEnabled == null && autoBackupEnabled == null && autoBackupIntervalDays == null
                && autoBackupRetentionCount == null && lastSettingsSection == null && safetyBufferMinor == null
                && hideAllAmounts == null && themeName == null;
    }
}
