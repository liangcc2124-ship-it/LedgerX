package com.ledgerx.application.profile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A stable, sanitized domain failure that the HTTP adapter maps to the global error envelope. */
public final class ProfileException extends Exception {
    private final int httpStatus;
    private final String code;
    private final Map<String, String> fieldErrors;
    private final Map<String, Object> details;

    public ProfileException(int httpStatus, String code, String message) {
        this(httpStatus, code, message, Collections.emptyMap(), Collections.emptyMap());
    }

    public ProfileException(int httpStatus, String code, String message,
            Map<String, String> fieldErrors, Map<String, Object> details) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.fieldErrors = Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public int getHttpStatus() { return httpStatus; }
    public String getCode() { return code; }
    public Map<String, String> getFieldErrors() { return fieldErrors; }
    public Map<String, Object> getDetails() { return details; }
}
