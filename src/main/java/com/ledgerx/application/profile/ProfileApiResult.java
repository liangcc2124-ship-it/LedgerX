package com.ledgerx.application.profile;

/** A completed API response that can safely be persisted for idempotent replay. */
public final class ProfileApiResult {
    private final int status;
    private final String responseJson;
    private final String etag;
    private final String location;

    public ProfileApiResult(int status, String responseJson, String etag, String location) {
        this.status = status;
        this.responseJson = responseJson;
        this.etag = etag;
        this.location = location;
    }

    public int getStatus() { return status; }
    public String getResponseJson() { return responseJson; }
    public String getEtag() { return etag; }
    public String getLocation() { return location; }
}
