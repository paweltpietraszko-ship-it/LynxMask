# LynxMask Mobile

**Android/Kotlin implementation of the LynxMask privacy product.**

LynxMask is one product with two independent platform implementations:

- **Mobile:** this repository — Android / Kotlin
- **Desktop:** https://github.com/paweltpietraszko-ship-it/LynxMask-Desktop — Python / FastAPI / Tauri

The two codebases do not share implementation files, but they are expected to preserve the same product-level contracts for token format, entity taxonomy, self-learning behavior and dictionary exchange.

This repository is included in my AI evaluation portfolio as evidence of hands-on model and system evaluation, benchmark-driven iteration, adversarial testing and human-in-the-loop control. It is not presented as evidence that I am a software engineer or ML engineer.

## My role

My role has been to define expected behavior, decide which failures matter, set evaluation and acceptance targets, challenge AI-generated implementations and keep final product and architecture decisions with the human owner. AI coding agents, especially Claude Code, were used heavily to inspect, implement and revise the codebase.

The recurring workflow is deliberately narrow:

1. Read the actual file first.
2. Diagnose the target behavior.
3. Make one bounded change.
4. Run the relevant test or benchmark.
5. Commit only after evidence.

When the coding agent expands scope, the instruction is to stop and return to the exact task.

## Evaluation focus

The Mobile implementation is strongly benchmark-driven. Its evaluation framework separates:

- **ENGINE-ONLY** tests
- **IN-SCOPE ACCEPTED** documents
- **REJECTED** documents
- **OUT-OF-SCOPE** documents

The product rule is explicit: LynxMask does not need to mask everything an OCR engine can read. It must correctly mask everything it accepts, or reject the document with a clear result.

Release-oriented metrics include critical-entity recall, overall recall, engine-failure counts, OCR-distortion failures and Guard RED hits.

## Why the benchmark matters

A major lesson from this project is that the benchmark itself can be wrong.

Examples from the development record include:

- EMAIL recall appearing broken because the benchmark comparison treated dot and underscore forms differently, while manual verification showed the engine itself was working.
- ADRES recall being limited by OCR input quality rather than masking logic.
- Aggregate recall mixing clean inputs, OCR-degraded inputs and out-of-scope cases until benchmark v2 separated them into causal categories.
- Fresh synthetic datasets varying by several percentage points without that being a real regression.

This changed the evaluation philosophy from “improve the score” to “first determine what the score actually measures.”

## Shared contracts with Desktop

Although Mobile and Desktop are independent implementations, several decisions are product-level contracts and must not drift independently:

- token format
- `TOKEN_RE` compatibility
- self-learning behavior
- `.lynxdict` exchange
- shared entity taxonomy
- selected user-visible behavior

A local PASS on each platform is not enough if the shared contract diverges.

## Repository map

This is a real working repository, so benchmark evidence and some historical project records are intentionally retained rather than hidden for presentation.

- `app/` — Android application source
- `MASTER_LynxMask_Mobile.md` — Mobile state/history record
- `TODO.md` — current task and bug list
- `docs/` — evaluation, privacy and portfolio documentation
- `benchmark_results/` — retained benchmark evidence
- `dataset_clean/`, `dataset_stress/`, `dataset_v2_run/` — controlled evaluation datasets
- `generator*.py`, `run_*benchmark*.bat` — benchmark tooling
- `Google_Play/` — release/presentation assets

`MASTER_LynxMask_Desktop.md` is retained as a historical cross-platform record. The active Desktop implementation is maintained in the separate repository linked above.

See [`docs/REPOSITORY_HYGIENE.md`](docs/REPOSITORY_HYGIENE.md) for the conservative cleanup policy and an explanation of why benchmark material was not deleted merely to reduce repository size.

## What this project demonstrates

- benchmark design and interpretation
- failure-source classification
- regression testing
- OCR-vs-engine diagnosis
- edge-case analysis
- measurable release gates
- coding-agent task scoping
- deterministic acceptance criteria
- human-in-the-loop adjudication
- cross-platform contract governance

See [`docs/FAILURE_LOG.md`](docs/FAILURE_LOG.md) for selected failure cases.

## Project status

LynxMask Mobile is an experimental, actively iterated project. It is included here because the repository contains real evidence of repeated evaluation, falsification, measurement and model-control work rather than a claim of commercial ML engineering experience.
