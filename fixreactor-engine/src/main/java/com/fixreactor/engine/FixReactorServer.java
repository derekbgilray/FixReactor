package com.fixreactor.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.FileStoreFactory;
import quickfix.Initiator;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.SLF4JLogFactory;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;
import quickfix.SocketInitiator;

/**
 * Boots every configured session in a single process. Acceptor and initiator sessions may
 * be mixed in one settings file: each connector only claims the sessions whose
 * {@code ConnectionType} matches it. See docs/planning/architecture-decisions.md AD-4.
 */
public class FixReactorServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FixReactorServer.class);

    private final SocketAcceptor acceptor;
    private final Initiator initiator;

    public FixReactorServer(SessionSettings settings) throws ConfigError {
        FixReactorApplication application = new FixReactorApplication();
        MessageStoreFactory storeFactory = new FileStoreFactory(settings);
        MessageFactory messageFactory = new DefaultMessageFactory();
        SLF4JLogFactory logFactory = new SLF4JLogFactory(settings);

        acceptor = new SocketAcceptor(application, storeFactory, settings, logFactory, messageFactory);
        initiator = new SocketInitiator(application, storeFactory, settings, logFactory, messageFactory);
    }

    public void start() throws ConfigError {
        acceptor.start();
        initiator.start();
        LOG.info("FixReactor started: {} acceptor session(s), {} initiator session(s)",
                acceptor.getSessions().size(), initiator.getSessions().size());
    }

    @Override
    public void close() {
        initiator.stop();
        acceptor.stop();
    }

    public SocketAcceptor getAcceptor() {
        return acceptor;
    }

    public Initiator getInitiator() {
        return initiator;
    }

    public static void main(String[] args) throws Exception {
        SessionSettings settings = args.length > 0
                ? loadSettings(Path.of(args[0]))
                : loadDefaultSettings();

        FixReactorServer server = new FixReactorServer(settings);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        Thread.currentThread().join();
    }

    private static SessionSettings loadSettings(Path path) throws ConfigError, IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new SessionSettings(in);
        }
    }

    private static SessionSettings loadDefaultSettings() throws ConfigError, IOException {
        try (InputStream in = FixReactorServer.class.getResourceAsStream("/fixreactor.cfg")) {
            if (in == null) {
                throw new ConfigError("fixreactor.cfg not found on the classpath");
            }
            return new SessionSettings(in);
        }
    }
}
