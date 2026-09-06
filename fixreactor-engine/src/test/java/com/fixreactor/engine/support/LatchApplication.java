package com.fixreactor.engine.support;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import quickfix.Application;
import quickfix.Message;
import quickfix.SessionID;

/**
 * Test-side counterparty application. Records logon state and captures inbound
 * application messages so tests can await them without sleeping.
 */
public class LatchApplication implements Application {

    private final Set<SessionID> loggedOn = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.BlockingQueue<Message> received =
            new java.util.concurrent.LinkedBlockingQueue<>();

    @Override
    public void onCreate(SessionID sessionId) {
    }

    @Override
    public void onLogon(SessionID sessionId) {
        loggedOn.add(sessionId);
    }

    @Override
    public void onLogout(SessionID sessionId) {
        loggedOn.remove(sessionId);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId) {
    }

    @Override
    public void toApp(Message message, SessionID sessionId) {
    }

    @Override
    public void fromApp(Message message, SessionID sessionId) {
        received.add(message);
    }

    public boolean isLoggedOn(SessionID sessionId) {
        return loggedOn.contains(sessionId);
    }

    public Message awaitMessage(long timeout, TimeUnit unit) throws InterruptedException {
        return received.poll(timeout, unit);
    }

    public int receivedCount() {
        return received.size();
    }
}
