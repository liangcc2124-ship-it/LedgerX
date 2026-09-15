package com.ledgerx.application.ledger;

public interface CategoryApi {
    CategoryApiResult listCategories(boolean includeArchived, int limit, String cursor) throws CategoryException;
    CategoryApiResult createCategory(CategoryPatch patch, CategoryMutation mutation) throws CategoryException;
    CategoryApiResult replaceCategory(String id, long expectedRevision, CategoryPatch patch,
            CategoryMutation mutation) throws CategoryException;
    CategoryApiResult archiveCategory(String id, long expectedRevision, CategoryMutation mutation)
            throws CategoryException;
    CategoryApiResult mergeCategories(String targetId, long expectedRevision, String[] sourceIds,
            long[] sourceRevisions, CategoryMutation mutation) throws CategoryException;
}
