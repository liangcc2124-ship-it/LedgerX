package com.ledgerx.application.backup;

public final class BackupApiResult {
    private final int status;
    private final String responseJson;
    private final String location;

    public BackupApiResult(int status, String responseJson, String location) {
        this.status = status;
        this.responseJson = responseJson;
        this.location = location;
    }

    public int getStatus() { return status; }
    public String getResponseJson() { return responseJson; }
    public String getLocation() { return location; }
}
