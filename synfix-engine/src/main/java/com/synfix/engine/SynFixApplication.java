package com.synfix.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.Application;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.MessageCracker;
import quickfix.RejectLogon;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;

/**
 * Serves every session on the engine. All per-session state is keyed by {@link SessionID}
 * rather than held in fields, so one instance can back both sides of a crossing-sessions
 * deployment. See docs/planning/architecture-decisions.md AD-5.
 */
public class SynFixApplication extends MessageCracker implements Application {

    private static final Logger LOG = LoggerFactory.getLogger(SynFixApplication.class);

    @Override
    public void onCreate(SessionID sessionId) {
        LOG.info("Session created: {}", sessionId);
    }

    @Override
    public void onLogon(SessionID sessionId) {
        LOG.info("Logon: {}", sessionId);
    }

    @Override
    public void onLogout(SessionID sessionId) {
        LOG.info("Logout: {}", sessionId);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
    }

    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend {
    }

    @Override
    public void fromApp(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        crack(message, sessionId);
    }

    /**
     * The inherited fallback throws {@link UnsupportedMessageType}, which would make the
     * engine business-reject anything it has no explicit handler for. A simulator should
     * stay quiet instead until a rule says otherwise.
     */
    @Override
    protected void onMessage(Message message, SessionID sessionId) {
        LOG.debug("No handler for message on {}: {}", sessionId, message);
    }
}
