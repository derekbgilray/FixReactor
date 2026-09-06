# `35=n` ConfigRule Message Schema

The authoritative field reference for FixReactor's in-band configuration message. This is the
spec someone hand-building a `35=n` message reads.

Defined in `fixreactor-messages/src/main/resources/FIX44-fixreactor.xml`, generated into
`quickfix.fix44.ConfigRule`.

## Design constraints

- **One message configures exactly one rule.** No batching of multiple rules into a
  single message. Register N rules by sending N `ConfigRule` messages. Rationale: the
  whole point of the in-band design is that the message stays hand-buildable — a person
  composing one by hand should only ever reason about one rule at a time.
- **The response template is a repeating group of `(tag, value)` pairs**, not a single
  delimited template string. This works for any `ResponseMsgType` without inventing a
  bespoke escaping/encoding scheme inside a FIX field, and mirrors repeating-group
  patterns already present in stock FIX 4.4 (e.g. `Logon`'s `NoMsgTypes`).

## Fields

Tag range 5000–9999 is the FIX-conventional user-defined range. The highest tag defined
in stock FIX 4.4 is **956**, so the 5000 block is unambiguously clear.

| Tag | Name | Type | Req | Meaning |
| --- | --- | --- | --- | --- |
| 5000 | `RuleID` | STRING | N | Caller-assigned identifier. If it matches an existing rule on this session, that rule is **replaced in place**. Omit to always append a new rule. |
| 5001 | `MatchMsgType` | STRING | Y | MsgType of the *incoming* message this rule applies to (e.g. `D`). |
| 5002 | `MatchTag` | INT | Y | Tag within the incoming message to test. |
| 5003 | `MatchValue` | STRING | Y | Value that `MatchTag` must equal (exact string comparison). |
| 5004 | `ResponseMsgType` | STRING | Y | MsgType of the message to send back (e.g. `3` for Reject). |
| 5005 | `NoResponseFields` | NUMINGROUP | N | Count of field overlays to apply to the response. |
| 5006 | → `ResponseFieldTag` | INT | Y | Tag to set on the response. |
| 5007 | → `ResponseFieldValue` | STRING | Y | Value to set. |

**5008–9999 reserved** for future extensions: rule deletion, rule listing, an
acknowledgement message, and richer match predicates.

## What "default-constructed response" means

An empty `NoResponseFields` group is normal and is what the reference scenario uses. The
engine builds the response generically via
`Session.lookupSession(id).getMessageFactory().create(beginString, responseMsgType)`, then
fills in the correlation fields that the specific message type requires — for `Reject`
that's `RefSeqNum` (45) and `RefMsgType` (372), copied from the triggering message.

Those correlation fields are the *engine's* job, not the rule author's: someone writing a
rule shouldn't need to know that a Reject must echo the inbound sequence number.
`NoResponseFields` exists for static overlays *beyond* that baseline.

## Matching semantics

Linear scan over the session's rules, **first match in registration order wins**.
Deliberately not a priority system and not an AND/OR predicate language. A rule whose
`MatchTag` is absent from the incoming message simply does not match; the scan continues.

Rules are stored per-`SessionID`. Configuring a rule on session A has no effect on
session B.

## Dictionary constraint: matchable tags must be valid for the message type

With `UseDataDictionary=Y`, an inbound message is validated against the dictionary
*before* the rules engine ever sees it. A rule can therefore only match on a tag that the
dictionary permits for that message type — otherwise the message is rejected at the
session layer and never reaches rule matching.

This bit the reference scenario immediately, and is worth knowing before writing rules.

### The tag 66 case

FixReactor's reference scenario matches `35=D` on `66=TEST`. Two facts discovered while
building the dictionary:

1. Tag 66 is **not** unassigned — in stock FIX 4.4 it is `ListID` (STRING).
2. Stock `NewOrderSingle` does **not** include `ListID` among its 70 fields.

So `66=TEST` on a `35=D` would fail dictionary validation out of the box.

**Resolution:** `FIX44-fixreactor.xml` adds `<field name="ListID" required="N"/>` to
`NewOrderSingle`. This keeps the reference scenario as originally specified (match tag
66, value `TEST`) and reuses a real standard FIX field rather than burning a custom tag
on a test fixture. Semantically a list identifier on a single order is
unusual, but the scenario is explicitly a test case, not a business flow.

The alternative — allocating something like tag 5010 `FixReactorTestTag` — was rejected
because it would make the first end-to-end example depend on a FixReactor-proprietary tag,
which reads worse in documentation than a standard one.
