# Memory contract v1 compatibility rules

This directory publishes Protobuf package `yilan.memory.v1` and Java package
`com.yilan.memory.contract.v1`. The following rules are normative for every
producer and consumer.

## Schema evolution

- Published field numbers are never reused, including after a field is removed.
- New fields are additive and optional; readers must preserve and tolerate
  unknown fields. Existing field types, numbers, cardinality, defaults, and
  semantics are not changed in place.
- Enum zero values remain unspecified. Existing nonzero enum values are never
  renumbered or repurposed.
- Any breaking changes require a new package version such as
  `yilan.memory.v2` and a separate compatibility and migration gate.
- `schema_version` is checked on every request and response. Capability
  negotiation must succeed before an online client uses newly required fields.

## Authenticated identity binding

Learner identity comes only from a signed gRPC metadata session credential.
Request bodies never contain a trusted learner or user identifier. CloudEvents
`subject` is an opaque routing claim and must be verified against the same
authenticated metadata identity before acceptance.

The service returns `identity_binding_digest` bound to the authenticated
subject, session, and request. A client must compare that value with its local
binding and discard the entire remote context on any mismatch. Identity,
request ID, schema, type, source-closure, or budget validation failure is never
partially accepted.

## Deadlines and status semantics

The Python online memory wait has a hard deadline of 150 ms and performs no
inline retry. `APPLIED` means at least one fully governed item was selected.
`EMPTY` is a legitimate empty result, including anonymous, disabled, irrelevant,
or zero-budget cases. `DEGRADED` is a technical failure result. DEGRADED responses contain no usable MemoryItem and clients discard the entire remote
long-term context before continuing the evidence-backed answer path.

Diagnostic codes and trace identifiers are operational metadata only and are
never exposed in the answer body. A `DEGRADED` result cannot carry a partial
context as a fallback.

## Closed memory value schema

`MemoryCandidate.valueJson` and every emitted `MemoryItem.value_json` use the
closed schema version `memory-value/v1`. Java applies this schema at candidate
construction and again before a persisted item may enter a gRPC context. A
failure at the second boundary degrades the entire response and emits no
context.

Only a JSON object with exactly one string member is valid for a safe memory
type (JSON structural whitespace is allowed):

- `PREFERENCE`: `{"answer_style":"concise"}`, `"detailed"`, or
  `"step_by_step"`.
- `MASTERY`: `{"mastery_level":"beginner"}`, `"developing"`, or
  `"proficient"`.
- `MISCONCEPTION`: exactly `misconception_code` with a value matching
  `[a-z][a-z0-9_]{0,63}`.
- `REFLECTION`: exactly `reflection_code` with a value matching
  `[a-z][a-z0-9_]{0,63}`.

Unknown, extra, duplicate, escaped or disguised members; non-string values;
arrays; nested values; `null`; numbers; and free text are invalid. The unsafe
`AVIATION_FACT` and `OPTIMIZATION` types accept only the exact empty-object
governance sentinel `{}` (with structural whitespace); they are never safe
gRPC output types. Any key or non-object/free-text body for those types is
invalid.

## Embedding compatibility

An embedding profile is the tuple of model ID, model version, dimension, and
normalization. An embedding profile mismatch disables only the semantic
retrieval channel for that request; structured, keyword, temporal, and valid
cache channels may continue if they remain within the declared budget. Peers
must not truncate, pad, renormalize, or otherwise guess a compatible vector.

## Events and worker isolation

Event delivery is at least once and the idempotency key is the pair of
CloudEvents `id` and `event_schema_version`. `DUPLICATE` is a successful durable
receipt; `REJECTED` is reserved for non-retryable contract, policy, or content
violations.

`CandidateProposalService` is stateless and advisory. Its source spans and model
metadata are untrusted inputs to Java governance. The worker contract exposes
no persistence, promotion, confirmation, disable, forget, or deletion RPC, and
the worker never becomes a long-term-memory authority.

### Event source authority metadata

`MemoryEventEnvelope.source_kind` is an additive optional v1 field. Its zero
value `SOURCE_KIND_UNSPECIFIED` is accepted only as the legacy compatibility
case and Java persists it as `GENERAL`; existing producers therefore retain
their previous safe behavior. Java validates the enum and its category binding
before persistence. The authority values are `GENERAL`,
`EXPLICIT_DECLARATION`, `EPISODE`, and `SCORED_ASSESSMENT`.

`source_kind` is Java-validated authority metadata, not a worker/candidate
claim. `GENERAL` cannot satisfy explicit, episode, or scored evidence rules:
only `EXPLICIT_DECLARATION` can satisfy explicit-preference evidence, only
`EPISODE` can count toward reflection episode evidence, and only
`SCORED_ASSESSMENT` can satisfy mastery scored evidence. Unknown enum values
are rejected before any durable event write.

### Event consent revision

`MemoryEventEnvelope.consent_revision` is an additive proto3 optional field.
When present, it declares the producer-observed authorization/consent version
at the time the event was created; an explicit `0` is distinct from an absent
declaration. This value is untrusted: before any durable event write, the
service must compare it with the signed metadata identity binding and current authorization policy.
A missing `consent_revision` is a non-retryable input or authorization error.
Batch submission is atomic for this requirement: a batch
with a missing or invalid consent revision must not be partially accepted.

### Event privacy level

`MemoryEventEnvelope.privacy_level` is an additive v1 field at tag 14. It uses
the `PrivacyLevel` values `LOW`, `STANDARD`, and `HIGH`; zero
`PRIVACY_LEVEL_UNSPECIFIED` is not a legacy default. Missing, unspecified, and
unknown privacy values are rejected before any durable event write, and a batch
containing one is not partially accepted.

The Java authority preserves ordering without adding a new domain level: wire
`LOW` maps to stored `STANDARD`, wire `STANDARD` maps to stored `SENSITIVE`,
and wire `HIGH` maps to stored `HIGH`. These are internal storage levels only;
the three wire levels must not be folded together or hardcoded to one level.
