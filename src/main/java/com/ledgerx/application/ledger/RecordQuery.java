package com.ledgerx.application.ledger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable, already parsed record-list filters. */
public final class RecordQuery {
    private final String status;
    private final LocalDate occurredFrom;
    private final LocalDate occurredToExclusive;
    private final List<String> recordTypes;
    private final List<String> categoryIds;
    private final List<String> accountIds;
    private final String query;
    private final Long amountMinMinor;
    private final Long amountMaxMinor;
    private final int limit;
    private final String cursor;

    public RecordQuery(String status, LocalDate occurredFrom, LocalDate occurredToExclusive,
            List<String> recordTypes, List<String> categoryIds, List<String> accountIds,
            String query, Long amountMinMinor, Long amountMaxMinor, int limit, String cursor) {
        this.status = status;
        this.occurredFrom = occurredFrom;
        this.occurredToExclusive = occurredToExclusive;
        this.recordTypes = immutable(recordTypes);
        this.categoryIds = immutable(categoryIds);
        this.accountIds = immutable(accountIds);
        this.query = query;
        this.amountMinMinor = amountMinMinor;
        this.amountMaxMinor = amountMaxMinor;
        this.limit = limit;
        this.cursor = cursor;
    }

    public static RecordQuery basic(String status, int limit, String cursor) {
        return new RecordQuery(status, null, null, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), null, null, null, limit, cursor);
    }

    private static List<String> immutable(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(values == null ? Collections.emptyList() : values));
    }

    public String getStatus() { return status; }
    public LocalDate getOccurredFrom() { return occurredFrom; }
    public LocalDate getOccurredToExclusive() { return occurredToExclusive; }
    public List<String> getRecordTypes() { return recordTypes; }
    public List<String> getCategoryIds() { return categoryIds; }
    public List<String> getAccountIds() { return accountIds; }
    public String getQuery() { return query; }
    public Long getAmountMinMinor() { return amountMinMinor; }
    public Long getAmountMaxMinor() { return amountMaxMinor; }
    public int getLimit() { return limit; }
    public String getCursor() { return cursor; }

    public String canonical() {
        return String.join("|", status, date(occurredFrom), date(occurredToExclusive),
                String.join(",", recordTypes), String.join(",", categoryIds), String.join(",", accountIds),
                query == null ? "" : query, amountMinMinor == null ? "" : amountMinMinor.toString(),
                amountMaxMinor == null ? "" : amountMaxMinor.toString(), Integer.toString(limit));
    }

    private static String date(LocalDate value) { return value == null ? "" : value.toString(); }
}
