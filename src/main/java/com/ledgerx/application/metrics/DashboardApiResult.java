package com.ledgerx.application.metrics;

public final class DashboardApiResult {
    private final int status;
    private final String responseJson;
    private final String etag;
    private final String location;

    public DashboardApiResult(int status, String responseJson, String etag, String location) {
        this.status = status; this.responseJson = responseJson; this.etag = etag; this.location = location;
    }

    public int getStatus() { return status; }
    public String getResponseJson() { return responseJson; }
    public String getEtag() { return etag; }
    public String getLocation() { return location; }
}
