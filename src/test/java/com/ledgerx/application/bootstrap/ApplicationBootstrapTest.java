package com.ledgerx.application.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationBootstrapTest {
    @Test
    void isolatedTestDataDirectoryTakesPrecedence() {
        Path selected = ApplicationBootstrap.dataDirectoryFromEnvironment(Map.of(
                "LEDGERX_TEST_DATA_DIR", "D:\\isolated\\ledgerx",
                "LEDGERX_DATA_DIR", "D:\\inherited\\ledgerx",
                "LOCALAPPDATA", "C:\\Users\\test\\AppData\\Local"), "C:\\Users\\test");

        assertEquals(Paths.get("D:\\isolated\\ledgerx"), selected);
    }

    @Test
    void configuredDataDirectoryIsHonoredBeforeDefault() {
        Path selected = ApplicationBootstrap.dataDirectoryFromEnvironment(Map.of(
                "LEDGERX_DATA_DIR", "D:\\configured\\ledgerx",
                "LOCALAPPDATA", "C:\\Users\\test\\AppData\\Local"), "C:\\Users\\test");

        assertEquals(Paths.get("D:\\configured\\ledgerx"), selected);
    }

    @Test
    void defaultsToLocalAppDataAndPortableHomeFallback() {
        assertEquals(Paths.get("C:\\Users\\test\\AppData\\Local\\LedgerX"),
                ApplicationBootstrap.dataDirectoryFromEnvironment(
                        Map.of("LOCALAPPDATA", "C:\\Users\\test\\AppData\\Local"), "C:\\Users\\test"));
        assertEquals(Paths.get("C:\\Users\\test\\AppData\\Local\\LedgerX"),
                ApplicationBootstrap.dataDirectoryFromEnvironment(Map.of(), "C:\\Users\\test"));
    }
}
