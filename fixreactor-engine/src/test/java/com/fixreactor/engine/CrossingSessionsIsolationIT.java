package com.fixreactor.engine;

import static com.fixreactor.engine.support.TestConfig.SENDER_A;
import static com.fixreactor.engine.support.TestConfig.SENDER_B;
import static com.fixreactor.engine.support.TestConfig.TARGET_A;
import static com.fixreactor.engine.support.TestConfig.TARGET_B;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fixreactor.engine.support.TestConfig;
import com.fixreactor.engine.support.TestCounterparty;

import quickfix.Session;
import quickfix.SessionID;
import quickfix.field.ClOrdID;
import quickfix.field.HandlInst;
import quickfix.field.OrdType;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.fix44.NewOrderSingle;

/**
 * Go/no-go gate for the crossing-sessions architecture.
 *
 * <p>Two sessions in one QuickFIX/J process are not assumed to stay isolated just because
 * the config says so. This proves it over real sockets: sequence numbers, logon state,
 * and on-disk stores must all stay independent.
 */
class CrossingSessionsIsolationIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final SessionID sessionA = new SessionID("FIX.4.4", SENDER_A, TARGET_A);
    private final SessionID sessionB = new SessionID("FIX.4.4", SENDER_B, TARGET_B);
    private final SessionID clientA = new SessionID("FIX.4.4", TARGET_A, SENDER_A);
    private final SessionID clientB = new SessionID("FIX.4.4", TARGET_B, SENDER_B);

    @TempDir
    Path tempDir;

    private Path engineStore;
    private FixReactorServer server;
    private TestCounterparty counterparty;

    @BeforeEach
    void setUp() throws Exception {
        int portA = TestConfig.freePort();
        int portB = TestConfig.freePort();
        engineStore = tempDir.resolve("engine");

        // Session B is an initiator, so its counterparty acceptor must be listening first.
        counterparty = new TestCounterparty(
                TestConfig.testClient(tempDir.resolve("client"), portA, portB));
        counterparty.start();

        server = new FixReactorServer(TestConfig.engine(engineStore, portA, portB));
        server.start();

        awaitTrue(() -> Session.lookupSession(sessionA) != null
                && Session.lookupSession(sessionA).isLoggedOn()
                && Session.lookupSession(sessionB) != null
                && Session.lookupSession(sessionB).isLoggedOn());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        if (counterparty != null) {
            counterparty.close();
        }
    }

    @Test
    void sequenceNumbersAdvanceIndependentlyPerSession() throws Exception {
        int sentOnA = 3;
        int sentOnB = 5;

        int senderStartA = Session.lookupSession(sessionA).getExpectedSenderNum();
        int targetStartA = Session.lookupSession(sessionA).getExpectedTargetNum();
        int senderStartB = Session.lookupSession(sessionB).getExpectedSenderNum();

        // Inbound on A: the counterparty's client-A session sends orders to the engine.
        for (int i = 0; i < sentOnA; i++) {
            Session.lookupSession(clientA).send(newOrder("A-" + i));
        }
        // Outbound on B: the engine sends orders out of its own initiator session.
        for (int i = 0; i < sentOnB; i++) {
            Session.lookupSession(sessionB).send(newOrder("B-" + i));
        }

        awaitTrue(() -> Session.lookupSession(sessionA).getExpectedTargetNum() >= targetStartA + sentOnA
                && Session.lookupSession(sessionB).getExpectedSenderNum() >= senderStartB + sentOnB);

        // A received sentOnA messages; its own outbound count is untouched by B's traffic.
        assertEquals(targetStartA + sentOnA, Session.lookupSession(sessionA).getExpectedTargetNum(),
                "session A inbound sequence must advance by exactly what was sent to it");
        assertEquals(senderStartA, Session.lookupSession(sessionA).getExpectedSenderNum(),
                "session A outbound sequence must not move when only session B sends");

        // B sent sentOnB messages, independently of A's inbound traffic.
        assertEquals(senderStartB + sentOnB, Session.lookupSession(sessionB).getExpectedSenderNum(),
                "session B outbound sequence must advance by exactly what it sent");

        assertNotEquals(
                Session.lookupSession(sessionA).getExpectedTargetNum(),
                Session.lookupSession(sessionB).getExpectedSenderNum(),
                "the two sessions must not be sharing a counter");
    }

    @Test
    void loggingOutOneSessionLeavesTheOtherLoggedOn() throws Exception {
        assertTrue(Session.lookupSession(sessionB).isLoggedOn(), "precondition: B logged on");

        Session.lookupSession(sessionA).logout("isolation test");
        awaitTrue(() -> !Session.lookupSession(sessionA).isLoggedOn());

        assertTrue(Session.lookupSession(sessionB).isLoggedOn(),
                "session B must stay logged on when session A logs out");
        assertTrue(counterparty.application().isLoggedOn(clientB),
                "session B's counterparty must also still be logged on");
    }

    @Test
    void eachSessionGetsItsOwnStoreFilesUnderASharedStorePath() throws Exception {
        Session.lookupSession(clientA).send(newOrder("store-check"));
        awaitTrue(() -> Session.lookupSession(sessionA).getExpectedTargetNum() > 1);

        List<String> seqNumFiles;
        try (var files = Files.list(engineStore)) {
            seqNumFiles = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith("seqnums"))
                    .toList();
        }

        List<String> forA = seqNumFiles.stream()
                .filter(n -> n.contains(SENDER_A) && n.contains(TARGET_A)).toList();
        List<String> forB = seqNumFiles.stream()
                .filter(n -> n.contains(SENDER_B) && n.contains(TARGET_B)).toList();

        // FileStore keeps a sender and a target sequence file per session.
        assertEquals(2, forA.size(), "expected 2 sequence files for session A, found: " + seqNumFiles);
        assertEquals(2, forB.size(), "expected 2 sequence files for session B, found: " + seqNumFiles);
        assertTrue(forA.stream().noneMatch(forB::contains),
                "sessions must not share a sequence-number file: " + seqNumFiles);
    }

    private NewOrderSingle newOrder(String clOrdId) {
        NewOrderSingle order = new NewOrderSingle(
                new ClOrdID(clOrdId), new Side(Side.BUY), new TransactTime(), new OrdType(OrdType.MARKET));
        order.set(new Symbol("FIXREACTOR"));
        order.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE));
        return order;
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("condition not met within " + TIMEOUT);
    }
}
