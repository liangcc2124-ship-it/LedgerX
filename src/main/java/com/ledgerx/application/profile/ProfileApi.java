package com.ledgerx.application.profile;

/** Transport-neutral port for the profile catalog REST resource. */
public interface ProfileApi {
    ProfileApiResult list(boolean includeArchived, int limit, String cursor) throws ProfileException;

    ProfileApiResult create(String id, String name, ProfileMutation mutation) throws ProfileException;

    ProfileApiResult activate(String id, long expectedRevision, ProfileMutation mutation) throws ProfileException;

    ProfileApiResult archive(String id, long expectedRevision, ProfileMutation mutation) throws ProfileException;

    ProfileApiResult findOperation(String idempotencyKey) throws ProfileException;
}
