# LynxMask Mobile — evaluation failure log

This file selects failure cases that are useful as evidence of AI and system evaluation. It is not a complete bug database.

## Benchmark reported an engine problem that was not an engine failure

Observed behavior: an older benchmark suggested poor EMAIL performance.

Investigation: the mismatch came from benchmark comparison logic that confused dot and underscore forms in the local part of an email address. Manual verification showed the engine itself was not the source of the failure.

Evaluation lesson: evaluate the evaluator. A benchmark can be wrong.

## Address recall was limited by OCR, not masking logic

Observed behavior: ADRES recall appeared lower than expected.

Investigation: trace analysis showed the missed addresses were absent from OCR output rather than missed by the masking engine.

Evaluation lesson: classify failures by source before fixing them.

## One aggregate score hid different failure classes

Observed behavior: an older benchmark mixed clean documents, OCR-degraded documents and out-of-scope cases into one recall number.

Response: benchmark v2 separated ENGINE-ONLY, IN-SCOPE ACCEPTED, REJECTED and OUT-OF-SCOPE cases.

Evaluation lesson: useful evaluation preserves causal categories instead of collapsing them into one score.

## Explicit release gates replaced vague quality claims

The Mobile benchmark introduced hard thresholds for accepted in-scope documents, including critical recall, overall recall, engine-error counts and Guard RED hits.

Evaluation lesson: “looks good” is not an acceptance condition.

## Fresh datasets were not treated as regression evidence

Observed behavior: recall varied across freshly generated datasets.

Response: regression was defined as worse behavior on the same fixed core dataset, not normal variation across new synthetic samples.

Evaluation lesson: distinguish evaluation variance from actual regression.

## Architecture component removed after evidence showed no value

Observed behavior: a second processing round remained in the architecture.

Evidence: a 300-document stress benchmark showed zero tokens passing through that path.

Response: the path was removed.

Evaluation lesson: architecture should survive falsification. Components should not remain only because they were part of an earlier design.

## Shared contract risk with Desktop

Risk: Mobile and Desktop maintain separate codebases but depend on compatible token formats, taxonomy and dictionary behavior.

Evaluation lesson: local correctness does not guarantee product-level correctness.

## Human ownership of ambiguous decisions

The project documentation explicitly marks selected product decisions as human-owned rather than delegating them to the coding agent.

Evaluation lesson: human-in-the-loop is not only final review. It also means reserving ambiguous product and architecture decisions for human adjudication.
