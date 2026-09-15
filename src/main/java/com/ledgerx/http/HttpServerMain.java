package com.ledgerx.http;

import java.time.Duration;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

public final class HttpServerMain {
    private HttpServerMain() {
    }

    public static void main(String[] args) throws Exception {
        LedgerHttpServer server = LedgerHttpServer.fromEnvironment(System.out);
        CountDownLatch stopRequested = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(Duration.ofSeconds(2)), "ledgerx-http-stop"));
        server.start();
        startStopReader(stopRequested);
        startParentMonitor(stopRequested);
        try {
            stopRequested.await();
        } finally {
            server.stop(Duration.ofSeconds(2));
        }
    }

    private static void startStopReader(CountDownLatch stopRequested) {
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    if ("LEDGERX_STOP".equals(line.trim())) {
                        stopRequested.countDown();
                        return;
                    }
                }
            } catch (IOException ignored) {
                // Parent monitoring remains the fallback when stdin is unavailable.
            }
        }, "ledgerx-stop-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private static void startParentMonitor(CountDownLatch stopRequested) {
        String configured = System.getenv("LEDGERX_PARENT_PID");
        if (configured == null || configured.trim().isEmpty()) {
            return;
        }
        final long parentPid;
        try {
            parentPid = Long.parseLong(configured);
            if (parentPid <= 0L) {
                throw new NumberFormatException("non-positive");
            }
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("LEDGERX_PARENT_PID must be a positive process id", ex);
        }
        Thread monitor = new Thread(() -> {
            while (true) {
                if (!ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false)) {
                    stopRequested.countDown();
                    return;
                }
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "ledgerx-parent-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }
}
