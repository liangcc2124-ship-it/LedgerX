package com.ledgerx.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Java11BaselineTest {
    @Test
    void runsOnJava11() {
        assertEquals(11, Runtime.version().feature(), "M0 must be verified on a Java 11 runtime");
    }
}
