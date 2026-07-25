# SynFIX

[![build](https://github.com/derekbgilray/SynFIX/actions/workflows/build.yml/badge.svg)](https://github.com/derekbgilray/SynFIX/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**A self-hosted FIX counterparty you can program from inside the FIX session itself.**

SynFIX stands up a real FIX session and plays the other side of it, so you can exercise a
new workflow — message formats, session behaviour, business logic — before you spend
money or calendar time on vendor certification or bilateral testing.

The session is real. Only the counterparty's *behaviour* is synthetic.

---

## The problem

Testing a FIX workflow usually means finding someone to test against. That means a vendor
relationship, an onboarding process, scheduled certification windows, and a contract —
before you have validated that your own tag 55 is even populated correctly.

The existing simulators in this space (FIXSIM, Esprow, PhiFIX, EPAM's B2BITS client
simulator, Tradepoint, FixFlyer's CertiFlyer) are capable tools, but they broadly assume
either a real counterparty relationship or a hosted, managed engagement.

SynFIX assumes **zero counterparty**. One firm, testing its own systems, in its own
infrastructure, on its own schedule.

## What makes it different

**No vendor relationship, no signup, no hosted service.** The distribution artifact is a
single 5 MB JAR. Run it on a laptop or in your own infra and connect to it.

**Configuration travels in-band.** Response rules are pushed as FIX messages — a custom
`35=n` — over the same session under test, rather than through a web portal or REST API.
That means no second control plane to secure, no per-environment config URL to manage,
and if you already archive FIX traffic for compliance, your configuration history is
already in the log.

The honest trade-off: hand-building a custom FIX message is not something every team
wants to do. A thin CLI to compose `35=n` messages is on the roadmap — deliberately a
local tool, not a hosted one, so nothing leaves the session.

**What is *not* a differentiator:** conditional auto-response itself. Every tool listed
above already does "if tag X, respond Y" well. The rules engine here is table stakes; the
distribution and configuration model is the interesting part.

---

## Status

Early, but real and tested. The session engine works and is verified by integration tests
that run actual FIX sessions over sockets.

| Component | State |
| --- | --- |
| Multi-session engine (acceptor + initiator, one process) | Working, integration-tested |
| Session isolation guarantees | [Verified](docs/planning/crossing-sessions-isolation.md) |
| Custom FIX 4.4 dictionary + `ConfigRule` (`35=n`) message | Generated and building |
| Rules engine (match inbound → send templated response) | **Not yet implemented** |
| `35=n` composer CLI | Planned |

The rules engine is the next piece of work. Until it lands, SynFIX will hold a session
and log traffic, but will not auto-respond.

---

## Quick start

Requires JDK 21. Maven is not needed — the wrapper handles it.

```bash
git clone https://github.com/derekbgilray/SynFIX.git
cd SynFIX
./mvnw clean verify
java -jar synfix-engine/target/synfix.jar
```

That starts a FIX 4.4 acceptor on port 9880:

```
INFO  event              FIX.4.4:SYNFIX_A->TESTCLIENT: Created session
INFO  SynFixServer       SynFIX started: 1 acceptor session(s), 0 initiator session(s)
```

Point your system at `localhost:9880` with `SenderCompID=TESTCLIENT`,
`TargetCompID=SYNFIX_A`, and log on.

To use your own settings, pass a path: `java -jar synfix.jar /path/to/my.cfg`. The
[default config](synfix-engine/src/main/resources/synfix.cfg) is commented and is the
best starting point.

---

## How it works

### Two sessions, one process

SynFIX runs a `SocketAcceptor` and a `SocketInitiator` against a **single**
`SessionSettings`. Each connector claims only the sessions whose `ConnectionType` matches
it, so acceptor and initiator sessions can be mixed freely in one config file and shipped
as one container.

The obvious risk is that two sessions sharing a process also end up sharing state.
`CrossingSessionsIsolationIT` rules that out over real sockets rather than by trusting the
library: sequence numbers advance independently per session, logging one session out
leaves the other logged on, and each session gets its own message store under a shared
store path.

That test is a **gate**, not a regression check — it was written before the engine was
built, because a failure would have meant changing the architecture rather than fixing a
bug. Full write-up: [crossing-sessions-isolation.md](docs/planning/crossing-sessions-isolation.md).

### Configuration as a FIX message

`35=n` (`ConfigRule`) carries one rule: what inbound message to match, and what to send
back.

| Tag | Field | Meaning |
| --- | --- | --- |
| 5000 | `RuleID` | Optional. Re-sending the same ID replaces that rule in place. |
| 5001 | `MatchMsgType` | Inbound MsgType to match, e.g. `D` |
| 5002 | `MatchTag` | Tag to test |
| 5003 | `MatchValue` | Value it must equal |
| 5004 | `ResponseMsgType` | MsgType to send back, e.g. `3` |
| 5005 | `NoResponseFields` | Repeating group of `(5006 tag, 5007 value)` overlays |

To make a `35=D` carrying `66=TEST` come back as a Reject:

```
35=n | 5001=D | 5002=66 | 5003=TEST | 5004=3
```

The response is built generically from `ResponseMsgType`, and the engine fills in the
correlation fields that message type requires — a Reject echoes the triggering message's
`RefSeqNum` and `RefMsgType` — so whoever writes the rule does not have to know that.
Field overlays are for anything beyond that baseline.

Full spec: [35n-message-schema.md](docs/planning/35n-message-schema.md).

### Module layout

```
synfix-messages   FIX 4.4 message classes generated from a custom dictionary
synfix-engine     Session bootstrap, application callbacks, tests
```

They are split because generated code and hand-written code have different build
rhythms: iterating on the engine should not re-run code generation.

The custom dictionary regenerates the whole FIX 4.4 message set into `quickfix.fix44`,
so standard message types work unmodified and QuickFIX/J's `DefaultMessageFactory`
discovers `MsgType=n` on its own — no hand-written factory.

---

## Design notes

The reasoning behind the build lives in [docs/planning/](docs/planning/), including the
things that did not go to plan:

- **[Architecture decisions](docs/planning/architecture-decisions.md)** — module split,
  why the generated classes deliberately occupy the same package as the stock ones, the
  deployment models considered and rejected.
- **[`35=n` schema](docs/planning/35n-message-schema.md)** — field reference, and why a
  repeating group beat a templated string.
- **[Isolation verification](docs/planning/crossing-sessions-isolation.md)** — what was
  actually tested, and the one assertion that could go flaky.

Three findings worth calling out, because each cost real time:

1. **Stock `FIX44.xml` cannot be code-generated.** Two `CHAR` fields carry double-digit
   enum values, which emit `char OTHER = '99'` — an unclosed character literal. QuickFIX/J
   hits this in its own build and ships a `FIX44.modified.xml` to work around it.
2. **`quickfixj-codegenerator` 3.0.1 and 3.0.2 are broken under Maven 3.9**, expecting
   `plexus-utils` via a provided-scope `maven-project:2.2.1` that Maven no longer exports
   to the plugin realm.
3. **Tag 66 is `ListID`, and stock `NewOrderSingle` does not include it** — so the
   canonical `66=TEST` example fails dictionary validation before any rule sees it.

---

## Built with

[QuickFIX/J](https://github.com/quickfix-j/quickfixj) 3.0.1 · Java 21 · Maven

## Roadmap

- Rules engine: match inbound messages and emit configured responses
- End-to-end scenario coverage beyond the session layer
- A local CLI for composing `35=n` messages
- Rule persistence across restarts, and an acknowledgement for `35=n`

## License

MIT — see [LICENSE](LICENSE).
