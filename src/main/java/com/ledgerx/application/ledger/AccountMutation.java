package com.ledgerx.application.ledger;

public final class AccountMutation {
    private final String idempotencyKey,httpMethod,canonicalPath,requestHash;
    public AccountMutation(String key,String method,String path,String hash){idempotencyKey=key;httpMethod=method;canonicalPath=path;requestHash=hash;}
    public String getIdempotencyKey(){return idempotencyKey;} public String getHttpMethod(){return httpMethod;} public String getCanonicalPath(){return canonicalPath;} public String getRequestHash(){return requestHash;}
}
