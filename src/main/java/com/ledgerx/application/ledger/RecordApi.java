package com.ledgerx.application.ledger;
public interface RecordApi {
    RecordApiResult listRecords(String status, int limit, String cursor) throws RecordException;
    RecordApiResult getRecord(String id) throws RecordException;
    default RecordApiResult listRecords(RecordQuery query) throws RecordException {
        return listRecords(query.getStatus(), query.getLimit(), query.getCursor());
    }
    default RecordApiResult getRecord(String id, String status) throws RecordException {
        return getRecord(id);
    }
    RecordApiResult createRecord(RecordPatch patch, RecordMutation mutation) throws RecordException;
    RecordApiResult replaceRecord(String id,long expectedRevision,RecordPatch patch,RecordMutation mutation)throws RecordException;
    RecordApiResult trashRecord(String id,long expectedRevision,RecordMutation mutation)throws RecordException;
    RecordApiResult restoreRecord(String id,long expectedRevision,RecordMutation mutation)throws RecordException;
}
