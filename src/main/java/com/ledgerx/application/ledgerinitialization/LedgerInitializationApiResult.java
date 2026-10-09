package com.ledgerx.application.ledgerinitialization;

public final class LedgerInitializationApiResult {
    private final int status;
    private final String responseJson;

    public LedgerInitializationApiResult(int status, String responseJson) {
        this.status = status;
        this.responseJson = responseJson;
    }

    public int getStatus() { return status; }
    public String getResponseJson() { return responseJson; }
}
