from __future__ import annotations

from dataclasses import dataclass, replace

from core.actions import ActionDecision
from core.answer_contracts import (
    AnswerEnvelope,
    AnswerType,
    ClaimCandidate,
    GenerationTrace,
    ResponseMode,
    SourceBinding,
)
from self_check.contracts import BodyClaimSpan, CheckReport


class RewriteAmbiguityError(RuntimeError):
    """Fail closed when frozen self-check spans cannot identify a safe deletion."""

    error_code = "rewrite_span_ambiguous"

    def __init__(self) -> None:
        super().__init__(self.error_code)


@dataclass(frozen=True)
class RewriteResult:
    envelope: AnswerEnvelope
    removed_claim_ids: tuple[str, ...]
    removed_spans: tuple[BodyClaimSpan, ...]
    changed: bool


class ClaimRewriteEngine:
    """Delete only self-check-frozen, unsupported factual body spans."""

    _BODY_FIELDS = ("short_answer", "main_answer")
    _SHORT_FALLBACK_QUESTION = "请问需要我继续说明已审核资料吗？"
    _CLARIFICATION_QUESTION = "请问能补充资料以解决当前证据不足吗？"

    def rewrite(self, envelope: AnswerEnvelope, report: CheckReport) -> RewriteResult:
        if report.action_decision is not ActionDecision.REWRITE_ONLY:
            raise RewriteAmbiguityError()

        unsupported_ids = self._unsupported_claim_ids(report)
        if not unsupported_ids:
            raise RewriteAmbiguityError()
        claims_by_id = {claim.claim_id: claim for claim in envelope.claim_candidates}
        if any(claim_id not in claims_by_id for claim_id in unsupported_ids):
            raise RewriteAmbiguityError()

        intervals, removed_spans = self._frozen_removal_intervals(
            envelope, report.claim_completeness.body_claim_spans, claims_by_id, unsupported_ids
        )
        rewritten_short = self._remove_intervals(
            envelope.short_answer, intervals["short_answer"]
        )
        rewritten_main = self._remove_intervals(
            envelope.main_answer, intervals["main_answer"]
        )

        remaining_claims = tuple(
            claim
            for claim in envelope.claim_candidates
            if claim.claim_id not in unsupported_ids
        )
        if not any(claim.claim_type.is_factual for claim in remaining_claims):
            clarification = self._clarification(envelope)
            return RewriteResult(
                envelope=clarification,
                removed_claim_ids=tuple(
                    claim.claim_id
                    for claim in envelope.claim_candidates
                    if claim.claim_id in unsupported_ids
                ),
                removed_spans=removed_spans,
                changed=True,
            )

        if not rewritten_short:
            rewritten_short = self._SHORT_FALLBACK_QUESTION
        if not rewritten_main:
            rewritten_main = self._SHORT_FALLBACK_QUESTION
        bindings = self._remaining_bindings(envelope.source_bindings, remaining_claims)
        evidence_refs = tuple(
            dict.fromkeys(
                evidence_id
                for binding in bindings
                for evidence_id in binding.evidence_ids
            )
        )
        rewritten = replace(
            envelope,
            short_answer=rewritten_short,
            main_answer=rewritten_main,
            evidence_refs=evidence_refs,
            source_bindings=bindings,
            visual_refs=tuple(
                visual
                for visual in envelope.visual_refs
                if visual.evidence_id in set(evidence_refs)
            ),
            claim_candidates=remaining_claims,
            display_blocks=(),
            generation_trace=replace(
                envelope.generation_trace,
                agent_action=ActionDecision.REWRITE_ONLY.value,
            ),
        )
        return RewriteResult(
            envelope=rewritten,
            removed_claim_ids=tuple(
                claim.claim_id
                for claim in envelope.claim_candidates
                if claim.claim_id in unsupported_ids
            ),
            removed_spans=removed_spans,
            changed=True,
        )

    @staticmethod
    def _unsupported_claim_ids(report: CheckReport) -> frozenset[str]:
        supported_ids = {
            support.claim_id
            for support in report.claim_supports
            if support.status.value == "unsupported"
        }
        issue_ids = {
            issue.claim_id
            for issue in report.issues
            if issue.recovery == "rewrite_only" and issue.claim_id is not None
        }
        return frozenset((*supported_ids, *issue_ids))

    def _frozen_removal_intervals(
        self,
        envelope: AnswerEnvelope,
        frozen_spans: tuple[BodyClaimSpan, ...],
        claims_by_id: dict[str, ClaimCandidate],
        unsupported_ids: frozenset[str],
    ) -> tuple[dict[str, list[tuple[int, int]]], tuple[BodyClaimSpan, ...]]:
        spans_by_field: dict[str, list[BodyClaimSpan]] = {
            field_name: [] for field_name in self._BODY_FIELDS
        }
        for span in frozen_spans:
            if span.field_name not in spans_by_field:
                raise RewriteAmbiguityError()
            body = getattr(envelope, span.field_name)
            if (
                span.start < 0
                or span.end <= span.start
                or span.end > len(body)
                or body[span.start : span.end] != span.text
            ):
                raise RewriteAmbiguityError()
            spans_by_field[span.field_name].append(span)

        for field_spans in spans_by_field.values():
            prior: BodyClaimSpan | None = None
            for span in sorted(field_spans, key=lambda item: (item.start, item.end, item.text)):
                if prior is not None and span.start < prior.end and (
                    span.start,
                    span.end,
                    span.text,
                ) != (prior.start, prior.end, prior.text):
                    raise RewriteAmbiguityError()
                if prior is None or span.end > prior.end:
                    prior = span

        intervals: dict[str, list[tuple[int, int]]] = {
            field_name: [] for field_name in self._BODY_FIELDS
        }
        selected: list[BodyClaimSpan] = []
        for claim_id in unsupported_ids:
            claim = claims_by_id[claim_id]
            for field_name in self._BODY_FIELDS:
                body = getattr(envelope, field_name)
                matching = [
                    span
                    for span in spans_by_field[field_name]
                    if span.text == claim.text
                ]
                occurs = body.count(claim.text)
                if occurs == 0:
                    if matching:
                        raise RewriteAmbiguityError()
                    continue
                if occurs != 1 or len(matching) != 1:
                    raise RewriteAmbiguityError()
                span = matching[0]
                if any(
                    other.claim_id not in unsupported_ids and other.text == span.text
                    for other in claims_by_id.values()
                ):
                    raise RewriteAmbiguityError()
                interval = (span.start, span.end)
                if interval not in intervals[field_name]:
                    intervals[field_name].append(interval)
                    selected.append(span)

        if not selected:
            raise RewriteAmbiguityError()
        for field_name, field_intervals in intervals.items():
            ordered = sorted(field_intervals)
            for (_, prior_end), (next_start, _) in zip(ordered, ordered[1:]):
                if next_start < prior_end:
                    raise RewriteAmbiguityError()
            intervals[field_name] = ordered
        selected.sort(
            key=lambda span: (self._BODY_FIELDS.index(span.field_name), span.start, span.end)
        )
        return intervals, tuple(selected)

    @staticmethod
    def _remove_intervals(body: str, intervals: list[tuple[int, int]]) -> str:
        rewritten = body
        for start, end in reversed(intervals):
            rewritten = rewritten[:start] + rewritten[end:]
        return rewritten.strip()

    @staticmethod
    def _remaining_bindings(
        bindings: tuple[SourceBinding, ...], claims: tuple[ClaimCandidate, ...]
    ) -> tuple[SourceBinding, ...]:
        claim_ids = {claim.claim_id for claim in claims}
        return tuple(binding for binding in bindings if binding.claim_id in claim_ids)

    def _clarification(self, envelope: AnswerEnvelope) -> AnswerEnvelope:
        return replace(
            envelope,
            answer_type=AnswerType.CLARIFICATION,
            response_mode=ResponseMode.CLARIFICATION_ONLY,
            short_answer=self._CLARIFICATION_QUESTION,
            main_answer=self._CLARIFICATION_QUESTION,
            evidence_refs=(),
            source_bindings=(),
            visual_refs=(),
            uncertainty_notes=tuple(
                dict.fromkeys((*envelope.uncertainty_notes, "evidence_insufficient"))
            ),
            safety_notes=(),
            follow_up_questions=(self._CLARIFICATION_QUESTION,),
            claim_candidates=(),
            display_blocks=(),
            generation_trace=replace(
                envelope.generation_trace,
                agent_action=ActionDecision.REWRITE_ONLY.value,
            ),
        )
