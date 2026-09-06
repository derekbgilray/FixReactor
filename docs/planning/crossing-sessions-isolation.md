# Crossing-Sessions Isolation

The single-process, two-session ("crossing sessions") model is the intended default
distribution artifact, adopted with an explicit caution: **do not assume it "just works"
because it's "just QuickFIX config."** The specific risk is sequence-number or
logon-state cross-contamination between the two sides.

This document records what was verified and how.

**Status: verified.** `CrossingSessionsIsolationIT` passes against QuickFIX/J 3.0.1 on
Java 21. The crossing-sessions model is cleared for use — see Results.

## What the design relies on

Read from QuickFIX/J 3.0.x source (`c:\build\quickfixj`, reference checkout):

1. `AbstractSocketAcceptor` instantiates a session only when its `ConnectionType` is
   explicitly `acceptor`; `AbstractSocketInitiator` claims sessions whose
   `ConnectionType` is `initiator` or unset. Both connectors can therefore be built
   against one shared `SessionSettings` and each will manage only its own sessions.
2. Session identity is the tuple
   `(BeginString, SenderCompID, TargetCompID[, SessionQualifier])`.
3. `FileStore` derives per-session filenames from `FileUtil.sessionIdFileName(sessionID)`,
   so a `FileStorePath` shared via `[DEFAULT]` does not collide — sequence-number files
   are namespaced per session automatically.

Points 1–3 are *source reading*. The test below is what turns them into evidence.

## Test design — `CrossingSessionsIsolationIT`

Go/no-go gate. Runs before any rules-engine code exists, so a failure here changes the
architecture rather than invalidating work built on top of it.

Setup: boot `FixReactorServer` against a test-scoped config using a temporary
`FileStorePath` and ephemeral ports (never the hardcoded 9880/9881 — CI port clashes).
Session A is the engine's acceptor; a bare test `SocketInitiator` connects to it.
Session B is the engine's initiator; a bare test `SocketAcceptor` receives its logon.

Assertions:

1. **Sequence-number independence.** Send differing message counts on each side. Each
   session's sequence numbers must advance by exactly its own count — so the two sides
   end at different, individually-correct values. A shared/static counter bug shows up
   here as A's count leaking into B.
2. **Logon-state independence.** Log Session A out. Session B must still report
   `isLoggedOn() == true`.
3. **Store isolation on disk.** After the run, distinct per-`SessionID` sequence-number
   files must exist under the store path. This checks `FileUtil.sessionIdFileName`
   namespacing empirically rather than trusting the source reading above.

## Results

Verified against **QuickFIX/J 3.0.1, Java 21 (Temurin), Maven 3.9.16**. All three
assertions pass; no cross-contamination found. `FixReactorServer` runs one `SocketAcceptor`
and one `SocketInitiator` over a single `SessionSettings`, and both connectors correctly
claimed only their own session.

| Check | Result |
| --- | --- |
| Sequence numbers advance per-session | Pass — A's inbound advanced by exactly its own 3 messages while B sent 5; A's outbound counter did not move at all. |
| Logout isolation | Pass — logging Session A out left Session B logged on, on both the engine and counterparty side. |
| Store file isolation under a shared `FileStorePath` | Pass — exactly 2 sequence files per session (`senderseqnums` + `targetseqnums`), no overlap. |

Store files are named `FIX.4.4-<Sender>-<Target>.{senderseqnums,targetseqnums,body,header,session}`
by `FileUtil.sessionIdFileName`, which is what makes the shared `[DEFAULT]` store path
safe.

**Conclusion: the crossing-sessions model holds.** The dual-engine fallback described in
[architecture-decisions.md](architecture-decisions.md) is not needed.

### Caveat on the "outbound counter unchanged" assertion

`sequenceNumbersAdvanceIndependentlyPerSession` asserts Session A's *outbound* sequence
number is untouched while only Session B sends. That holds because `HeartBtInt=30`
comfortably exceeds the test's runtime — a heartbeat on A would legitimately bump it. If
this test ever goes flaky on a slow machine, raise `HeartBtInt` in the test config rather
than weakening the assertion; it is the assertion that most directly catches a shared
counter.
