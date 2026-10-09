package com.ledgerx.testsupport;

import com.ledgerx.application.ledgerinitialization.LedgerInitializationDraft;
import com.ledgerx.application.ledgerinitialization.LedgerInitializationApi;
import com.ledgerx.application.ledgerinitialization.LedgerInitializationException;
import com.ledgerx.application.ledgerinitialization.LedgerInitializationMutation;
import com.ledgerx.application.profile.ProfileApplicationService;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.UUID;

/** Opens a test ledger with the same public initialization command used by the browser. */
public final class LedgerTestSupport {
    private static final LocalDate TEST_LEDGER_START = LocalDate.of(2025, 1, 1);

    private LedgerTestSupport() { }

    public static ProfileApplicationService openReadyLedger(Path dataRoot, String applicationVersion, Clock clock)
            throws Exception {
        ProfileApplicationService service = ProfileApplicationService.open(dataRoot, applicationVersion, clock);
        if (!"COMPLETED".equals(service.current().getSetupState())) {
            initializeLedger(service, UUID.randomUUID().toString(), "a");
        }
        return service;
    }

    public static void initializeLedger(LedgerInitializationApi api, String key, String hashCharacter)
            throws LedgerInitializationException {
        api.initializeLedger(new LedgerInitializationDraft(TEST_LEDGER_START, TEST_LEDGER_START,
                "0.00", "现金储备", false, Collections.emptyMap()),
                new LedgerInitializationMutation(key, hashCharacter.repeat(64)));
    }
}
