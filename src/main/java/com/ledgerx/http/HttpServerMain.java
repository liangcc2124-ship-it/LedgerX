package com.ledgerx.http;

import com.ledgerx.application.bootstrap.ApplicationBootstrap;
import com.ledgerx.application.bootstrap.DataDirectoryLock;

import java.time.Duration;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class HttpServerMain {
    private HttpServerMain() {
    }

    public static void main(String[] args) throws Exception {
        Path dataDirectory = ApplicationBootstrap.dataDirectoryFromEnvironment(
                System.getenv(), System.getProperty("user.home"));
        try (DataDirectoryLock ignored = DataDirectoryLock.acquire(dataDirectory);
                LedgerHttpServer server = LedgerHttpServer.fromEnvironment(System.out)) {
            CountDownLatch shutdownComplete = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(
                    new Thread(() -> {
                        server.stop(Duration.ofSeconds(2));
                        shutdownComplete.countDown();
                    }, "ledgerx-http-stop"));
            server.start();
            printBrowserAddress(server.origin());
            shutdownComplete.await();
        }
    }

    private static void printBrowserAddress(String origin) {
        System.out.println("LedgerX 网页地址: " + origin + "/");
        System.out.println("关闭浏览器标签页不会停止本机服务。按 Ctrl+C 停止。");
    }
}
