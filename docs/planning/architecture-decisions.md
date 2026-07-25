# Architecture Decisions

Durable record of *why* SynFIX is built the way it is — the decisions that constrain
implementation. When a decision here changes, change it here first.

## AD-1: QuickFIX/J 3.0.1, consumed from Maven Central

All `org.quickfixj:*` artifacts are pinned to **3.0.1** via the `quickfixj.version`
property in the root `pom.xml`.

Verified against Maven Central metadata:

| Artifact | Latest release |
| --- | --- |
| `quickfixj-core` | 3.0.1 |
| `quickfixj-base` | 3.0.1 |
| `quickfixj-messages-fix44` | 3.0.1 |
| `quickfixj-codegenerator` | 3.0.2 (3.0.1 also published) |

The codegenerator plugin is pinned separately, to **3.0.2**, via its own
`quickfixj-codegenerator.version` property.

**It also needs an explicit `plexus-utils` plugin dependency** (declared in
`synfix-messages/pom.xml`). Both 3.0.1 and 3.0.2 of the plugin expect
`org.codehaus.plexus.util.FileUtils` to arrive transitively through a provided-scope
`maven-project:2.2.1`. Maven 3.9 no longer exports that to the plugin realm, so the
`generate` goal dies with `NoClassDefFoundError` without the explicit dependency. This is
a plugin packaging bug, not a misconfiguration on our side — revisit if a later plugin
release fixes it.

There is a QuickFIX/J source checkout at `c:\build\quickfixj` (a 3.0.2-SNAPSHOT dev
build). It is a **read-only API reference only** — never a dependency, never a source of
copied build config. SynFIX resolves QuickFIX/J from Maven Central.

## AD-2: Multi-module reactor

- `synfix-messages` — generated FIX 4.4 message classes. Different build mechanics
  (codegen plugin, no hand-written code, changes rarely).
- `synfix-engine` — hand-written engine and tests. Iterates constantly.

Splitting them means engine test runs don't re-trigger codegen unless the dictionary
actually changed, and it leaves a clean seam for the future `synfix-cli` module (which
would depend on `synfix-messages` only — it needs to *compose* `35=n` messages, not run
an engine).

## AD-3: Generate into `quickfix.fix44` / `quickfix.field` — and never depend on the stock messages

The codegenerator regenerates the **entire** FIX 4.4 message set from
`FIX44-synfix.xml` (all stock messages plus our `ConfigRule` addition) into the same
package names the stock artifact uses.

This is the pattern QuickFIX/J's own `customising-quickfixj.md` documents, and it means:

- Standard `quickfix.fix44.*` types (`NewOrderSingle`, `Reject`, …) work unmodified.
- `quickfix.DefaultMessageFactory` finds our custom message automatically — it resolves
  per-version factories reflectively via `Class.forName("quickfix." + version + ".MessageFactory")`,
  and the generated `quickfix.fix44.MessageFactory` already contains a `case` for
  `MsgType=n`. **No hand-written `MessageFactory` is needed.**

**Standing rule: never add `quickfixj-all` or `quickfixj-messages-fix44` as a dependency
anywhere in the reactor.** Two classes with the same fully-qualified name cannot coexist
on a classpath. This is safe today because `quickfixj-core`'s own dependency on
`quickfixj-messages-fix44` is *test-scoped* — it is not transitive into our build. There
is nothing to `<exclude>`; there is only this rule to not violate. Consider a
`maven-enforcer-plugin` banned-dependency rule if this ever gets violated accidentally.

## AD-7: The dictionary is based on `FIX44.modified.xml`, not `FIX44.xml`

`FIX44-synfix.xml` is derived from QuickFIX/J's **`FIX44.modified.xml`**, not the
plain `FIX44.xml` sitting next to it.

The stock `FIX44.xml` cannot be code-generated as-is. Two of its `CHAR`-typed fields
(`MassCancelRejectReason` 532 and `MiscFeeType` 139) carry double-digit enum values like
`99`, which generate invalid Java — `public static final char OTHER = '99';` is an
unclosed character literal. QuickFIX/J's own build hits this too and works around it with
`FIX44.modified.xml`, which comments those values out; their `quickfixj-messages-fix44`
module explicitly excludes `FIX44.xml` from generation for this reason.

If the dictionary is ever re-based against a newer QuickFIX/J, take the `.modified`
variant again and re-apply the SynFIX additions on top (the `ConfigRule` message, the
5000-block fields, and `ListID` on `NewOrderSingle`).

## AD-4: Crossing sessions on one engine process

Two FIX sessions run in a single JVM against a single `SessionSettings` instance:
`SYNFIX_A` (acceptor, faces the system under test) and `SYNFIX_B` (initiator).

Verified against QuickFIX/J 3.0.x source:

- `AbstractSocketAcceptor` and `AbstractSocketInitiator` each **self-filter** by
  `ConnectionType` when iterating settings sections. One settings object can therefore
  mix acceptor and initiator `[SESSION]` blocks, and `SynFixServer` constructs both
  connectors against it.
- A shared `FileStorePath`/`FileLogPath` in `[DEFAULT]` is safe: `FileStore` namespaces
  its files per-`SessionID` via `FileUtil.sessionIdFileName`. The only collision risk is
  two sessions resolving to an identical `SessionID`, which cannot happen here since A
  and B differ on `SenderCompID`/`TargetCompID`.

This is verified empirically by `CrossingSessionsIsolationIT`, not taken on trust — see
[crossing-sessions-isolation.md](crossing-sessions-isolation.md).

### Deployment models considered

1. **Crossing sessions, one process** *(chosen)* — smallest footprint, one container to
   ship. The risk is exactly the state bleed-through the isolation test rules out.
2. **Dual engine** — two fully separate QuickFIX/J instances in the operator's own infra.
   Cleanest isolation and the simplest mental model, at the cost of running two
   processes. This is the documented fallback if the crossing-sessions model ever proves
   unsafe, and remains the better fit for scenarios where two genuinely independent
   processes matter (failover and reconnect testing).
3. **Externally hosted** — out of scope. It would test real network behaviour (TLS,
   firewalls, session negotiation) rather than just message logic, but it reintroduces
   the vendor-onboarding friction this project exists to avoid.

## AD-5: One `Application` instance, session-scoped state

A single `SynFixApplication` serves both sessions, branching on the `SessionID` that
every `quickfix.Application` callback receives. Isolation comes from state being keyed by
`SessionID` (`RuleStore`), not from having separate Java objects per session — so a
second `Application` instance would be duplication for no isolation benefit.

Two invariants for engine code:

- Outbound sends always go through `Session.lookupSession(sessionId).send(...)`. Never
  cache a `Session` reference in a field.
- `fromApp` delegates to `MessageCracker.crack(message, sessionID)`; per-message logic
  lives in `onMessage(SpecificType, SessionID)` overloads.

## AD-6: Java 21

SynFIX targets Java 21 (LTS), built with Eclipse Temurin 21. QuickFIX/J's own jars are
built for Java 8, but that constrains only their bytecode, not consumers.
