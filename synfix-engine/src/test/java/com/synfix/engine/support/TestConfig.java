package com.synfix.engine.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import quickfix.ConfigError;
import quickfix.SessionSettings;

/**
 * Builds engine-side and test-side settings against ephemeral ports and a temp store
 * directory, so integration tests never collide on 9880/9881 or share sequence-number
 * files between runs.
 */
public final class TestConfig {

    public static final String SENDER_A = "SYNFIX_A";
    public static final String TARGET_A = "TESTCLIENT";
    public static final String SENDER_B = "SYNFIX_B";
    public static final String TARGET_B = "COUNTERPARTY_B";

    private TestConfig() {
    }

    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("could not allocate a free port", e);
        }
    }

    /** Session A acceptor on {@code portA}, Session B initiator dialling {@code portB}. */
    public static SessionSettings engine(Path storeDir, int portA, int portB) throws ConfigError {
        String cfg = defaults(storeDir)
                + section("acceptor", SENDER_A, TARGET_A, "SocketAcceptPort=" + portA)
                + section("initiator", SENDER_B, TARGET_B,
                        "SocketConnectHost=localhost\nSocketConnectPort=" + portB);
        return parse(cfg);
    }

    /** The counterparty side: initiator into Session A, acceptor for Session B. */
    public static SessionSettings testClient(Path storeDir, int portA, int portB) throws ConfigError {
        String cfg = defaults(storeDir)
                + section("initiator", TARGET_A, SENDER_A,
                        "SocketConnectHost=localhost\nSocketConnectPort=" + portA)
                + section("acceptor", TARGET_B, SENDER_B, "SocketAcceptPort=" + portB);
        return parse(cfg);
    }

    private static String defaults(Path storeDir) {
        return """
                [DEFAULT]
                FileStorePath=%s
                UseDataDictionary=Y
                DataDictionary=FIX44-synfix.xml
                StartTime=00:00:00
                EndTime=00:00:00
                HeartBtInt=30
                ReconnectInterval=1
                ValidateUserDefinedFields=Y

                """.formatted(storeDir.toString().replace('\\', '/'));
    }

    private static String section(String connectionType, String sender, String target, String extra) {
        return """
                [SESSION]
                BeginString=FIX.4.4
                SenderCompID=%s
                TargetCompID=%s
                ConnectionType=%s
                %s

                """.formatted(sender, target, connectionType, extra);
    }

    private static SessionSettings parse(String cfg) throws ConfigError {
        return new SessionSettings(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
    }
}
