# Repository hygiene and structure

This repository is a real working project, not a reconstructed demo. Some historical material is intentionally retained because it documents how the Mobile implementation and its evaluation process evolved.

## What belongs in the root

The root contains the Android project, benchmark tooling and the current owner-level project records.

Key entries:

- `app/` — Android application source
- `MASTER_LynxMask_Mobile.md` — historical/state record for the Mobile implementation
- `TODO.md` — current working task and bug list
- `docs/` — evaluation, privacy and portfolio documentation
- `benchmark_results/` — retained benchmark evidence
- `dataset_clean/`, `dataset_stress/`, `dataset_v2_run/` — controlled evaluation datasets retained because they support reproducible testing
- `generator*.py` and `run_*benchmark*.bat` — benchmark generation/execution tooling
- `Google_Play/` — release/presentation assets

## Historical material

Some older briefs and cross-platform records remain in the repository because they are part of the development history. They should not be interpreted as the current source of truth unless a current document explicitly points to them.

In particular, `MASTER_LynxMask_Desktop.md` is a historical cross-platform record. The active Desktop implementation now lives in the separate `LynxMask-Desktop` repository.

## Local files that do not belong in Git

User-specific IDE state and local Claude Code permission state are excluded from the repository. These can contain machine-specific paths, device preferences or local tool permissions and are not required to build, test or evaluate LynxMask.

The repository therefore ignores:

- `.idea/`
- `.claude/settings.local.json`
- Gradle/local build output
- local machine properties
- regenerable source datasets listed in `.gitignore`

## Why large benchmark material is retained

The evaluation datasets and benchmark outputs are not treated as random clutter. They are part of the evidence chain behind claims made in the portfolio README and `docs/FAILURE_LOG.md`.

They should only be removed after proving that the same tests remain reproducible from a smaller canonical fixture or generator. No such destructive cleanup is performed merely to make the repository look smaller.

## Repository size

Cleaning the current tree does not rewrite Git history. Historical blobs may therefore continue to make the repository much larger than the visible working tree.

History rewriting is deliberately out of scope for routine portfolio cleanup because it can invalidate clones, references and old commits. It should only be considered after a separate large-object audit and backup.
