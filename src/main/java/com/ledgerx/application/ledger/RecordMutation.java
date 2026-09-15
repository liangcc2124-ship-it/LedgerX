package com.ledgerx.application.ledger;
public final class RecordMutation {private final String key,method,path,hash;public RecordMutation(String k,String m,String p,String h){key=k;method=m;path=p;hash=h;}public String getIdempotencyKey(){return key;}public String getHttpMethod(){return method;}public String getCanonicalPath(){return path;}public String getRequestHash(){return hash;}}
