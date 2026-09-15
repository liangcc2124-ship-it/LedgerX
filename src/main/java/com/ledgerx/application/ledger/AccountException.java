package com.ledgerx.application.ledger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AccountException extends Exception {
    private final int httpStatus; private final String code; private final Map<String,String> fieldErrors; private final Map<String,Object> details;
    public AccountException(int status,String code,String message){this(status,code,message,Collections.emptyMap(),Collections.emptyMap());}
    public AccountException(int status,String code,String message,Map<String,String> fields,Map<String,Object> details){super(message);this.httpStatus=status;this.code=code;this.fieldErrors=Collections.unmodifiableMap(new LinkedHashMap<>(fields));this.details=Collections.unmodifiableMap(new LinkedHashMap<>(details));}
    public int getHttpStatus(){return httpStatus;} public String getCode(){return code;} public Map<String,String> getFieldErrors(){return fieldErrors;} public Map<String,Object> getDetails(){return details;}
}
