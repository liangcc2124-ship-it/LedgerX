package com.ledgerx.application.settings;

/** Completed settings response, including the ETag required for exact replay. */
public final class SettingsApiResult {
    private final int status;
    private final String responseJson;
    private final String etag;

    public SettingsApiResult(int status, String responseJson, String etag) {
        this.status = status;
        this.responseJson = responseJson;
        this.etag = etag;
    }

    public int getStatus() { return status; }
    public String getResponseJson() { return responseJson; }
    public String getEtag() { return etag; }
}
