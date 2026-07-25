package com.synfix.engine.support;

import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.FileStoreFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.ScreenLogFactory;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;
import quickfix.SocketInitiator;

/** The other side of the wire for integration tests: real sockets, no mocks. */
public class TestCounterparty implements AutoCloseable {

    private final LatchApplication application = new LatchApplication();
    private final SocketAcceptor acceptor;
    private final SocketInitiator initiator;

    public TestCounterparty(SessionSettings settings) throws ConfigError {
        MessageStoreFactory storeFactory = new FileStoreFactory(settings);
        MessageFactory messageFactory = new DefaultMessageFactory();
        ScreenLogFactory logFactory = new ScreenLogFactory(false, false, false);

        acceptor = new SocketAcceptor(application, storeFactory, settings, logFactory, messageFactory);
        initiator = new SocketInitiator(application, storeFactory, settings, logFactory, messageFactory);
    }

    public void start() throws ConfigError {
        acceptor.start();
        initiator.start();
    }

    public LatchApplication application() {
        return application;
    }

    @Override
    public void close() {
        initiator.stop();
        acceptor.stop();
    }
}
