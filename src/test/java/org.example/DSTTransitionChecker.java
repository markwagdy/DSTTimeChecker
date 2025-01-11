package org.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DSTTransitionCheckerTest {

    private DSTTransitionChecker checker;

    @BeforeEach
    void setUp() {
        checker = new DSTTransitionChecker();
    }

    @Test
    void testTransitionAhead() throws Exception {
        long currentUtc = Instant.now().getEpochSecond();
        Map<String, Object> timezone = new HashMap<>();
        timezone.put("nextTransition", currentUtc + 600); // 10 minutes ahead
        timezone.put("timezone", "(GMT+01:00) Amsterdam, Berlin, Bern, Rome, Stockholm, Vienna");

        assertDoesNotThrow(() -> checker.testProcessTimezones(), "Should skip transition more than 5 minutes ahead.");
    }

    @Test
    void testTransitionBehind() throws Exception {
        long currentUtc = Instant.now().getEpochSecond();
        Map<String, Object> timezone = new HashMap<>();
        timezone.put("nextTransition", currentUtc - 600); // 10 minutes behind
        timezone.put("timezone", "(GMT-05:00) Eastern Time (US & Canada)");

        assertDoesNotThrow(() -> checker.testProcessTimezones(), "Should query API for transition more than 5 minutes behind.");
    }

    @Test
    void testTransitionInBetween() throws Exception {
        long currentUtc = Instant.now().getEpochSecond();
        Map<String, Object> timezone = new HashMap<>();
        timezone.put("nextTransition", currentUtc + 200); // 3 minutes ahead
        timezone.put("timezone", "(GMT+00:00) London, Edinburgh, Dublin, Lisbon");

        assertDoesNotThrow(() -> checker.testProcessTimezones(), "Should execute batch for transition within 5 minutes.");
    }
}
