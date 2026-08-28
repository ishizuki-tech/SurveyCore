# SurveyCore

SurveyCore is a generic Android survey framework for structured, offline-first interviews with optional on-device Small Language Model (SLM) assistance.

The long-term goal is not to turn SurveyCore into a rule-heavy validator. The goal is to use an on-device language model as a **semantic interviewer** that can:

- understand a respondent's answer in context,
- evaluate how sufficiently the accumulated answer addresses an interview goal,
- identify the most important information that still remains missing or ambiguous,
- generate one useful follow-up question when needed,
- stop asking when the answer is sufficiently complete.

Survey progression, state, limits, storage, branching, and completion remain deterministic Kotlin responsibilities.

---

# Core Product Direction

The central interaction model is:

```text
Prepared Major Question
        ↓
Respondent Answer
        ↓
      SLM once
        ↓
Evaluate accumulated respondent evidence
        ↓
┌─────────────────────────────────────────────┐
│ REMAINING_GAP: <description | NONE>         │
│ STATUS: DONE | FOLLOW_UP                    │
│ QUESTION: <one question | NONE>             │
│ SUFFICIENCY: <0..100>                       │
└─────────────────────────────────────────────┘
        ↓
Survey Core
        ↓
DONE      → next major question
FOLLOW_UP → ask generated follow-up
```

The key rule is:

> **One respondent turn = one SLM inference.**

This does **not** mean one major survey question always uses only one inference.

If the SLM generates a follow-up and the respondent answers it, that new respondent turn triggers another SLM inference.

Example:

```text
Major Question:
How much yield do you lose because of fall armyworm?

Respondent:
20

SLM:
REMAINING_GAP: Unit of measurement is unclear.
STATUS: FOLLOW_UP
QUESTION: Do you mean 20 percent or 20 bags per acre?
SUFFICIENCY: 80

Respondent:
percent

SLM:
REMAINING_GAP: NONE
STATUS: DONE
QUESTION: NONE
SUFFICIENCY: 100
```

The original answer and all follow-up exchanges are treated as accumulated respondent evidence.

---

# Design Principles

## 1. SLM owns semantic interpretation

The SLM should perform tasks such as:

- interpreting short or incomplete answers in context,
- understanding follow-up question/answer pairs,
- determining what information still remains missing or ambiguous,
- estimating response sufficiency,
- identifying the most valuable next information to request,
- wording a concise and natural follow-up question.

Kotlin should not accumulate survey-specific semantic rules such as:

```text
isUnit(...)
hasYieldMagnitude(...)
isThreeSeasonAverage(...)
isPhoneModel(...)
```

Those belong to semantic interpretation and should remain model responsibilities.

---

## 2. Kotlin owns deterministic survey control

Survey Core remains responsible for:

- current survey node,
- survey progression,
- answer storage,
- follow-up history,
- follow-up limits,
- branching,
- completion,
- malformed AI response handling,
- model/runtime failure handling.

The model must never directly select the next survey node.

---

## 3. Protocol validation is mechanical, not semantic

Kotlin may validate mechanical properties of the SLM protocol, for example:

- exactly four nonblank output lines,
- exact field order,
- `SUFFICIENCY` is an integer from 0 through 100,
- `STATUS` is `DONE` or `FOLLOW_UP`,
- `DONE` requires `REMAINING_GAP: NONE`,
- `DONE` requires `QUESTION: NONE`,
- `DONE` requires `SUFFICIENCY` from 81 through 100,
- `FOLLOW_UP` requires a real remaining gap,
- `FOLLOW_UP` requires a real question,
- `FOLLOW_UP` requires `SUFFICIENCY` from 0 through 80.

Kotlin should not decide whether a number is a magnitude, whether a word is a unit, or whether a response semantically satisfies an interview goal.

Malformed or contradictory model output becomes an explicit AI failure rather than silently advancing the survey.

---

## 4. Sufficiency is completeness, not probability

`SUFFICIENCY` represents how completely the accumulated respondent evidence addresses the interview goal.

It is **not** a calibrated probability.

Conceptually:

```text
0–20    Unusable, unrelated, or almost entirely insufficient
21–40   Relevant information exists, but major information is missing
41–60   Partially addresses the interview goal
61–80   Mostly addresses the goal, but an important gap remains
81–99   Sufficient for the interview goal; remaining detail is optional
100     Fully addresses the configured interview goal
```

The protocol uses a deterministic control boundary:

```text
0..80   → FOLLOW_UP
81..100 → DONE
```

---

# Response Evaluation Protocol

The current response protocol is intentionally small and line-oriented:

```text
REMAINING_GAP: <short description or NONE>
STATUS: DONE|FOLLOW_UP
QUESTION: <one follow-up question or NONE>
SUFFICIENCY: <one integer>
```

`REMAINING_GAP` is intentionally the first field.

It means:

> Information that still remains missing or ambiguous **after considering all accumulated respondent evidence**.

A resolved, historical, or already answered gap must not be emitted as `REMAINING_GAP`.

Follow-up example:

```text
REMAINING_GAP: Unit of measurement is unclear.
STATUS: FOLLOW_UP
QUESTION: Please specify the unit of measurement.
SUFFICIENCY: 70
```

Completed example:

```text
REMAINING_GAP: NONE
STATUS: DONE
QUESTION: NONE
SUFFICIENCY: 100
```

The protocol is intentionally not JSON.

A simple line-oriented format has proven easier to inspect and evaluate with small on-device models.

---

# Why `REMAINING_GAP` Replaced `GAP`

Earlier experiments used:

```text
GAP: ...
STATUS: ...
QUESTION: ...
SUFFICIENCY: ...
```

Gemma 3n E4B frequently understood that the accumulated response was complete and returned:

```text
GAP: Unit of measurement for yield loss.
STATUS: DONE
QUESTION: NONE
SUFFICIENCY: 100
```

The semantic `DONE` decision was correct, but the output was mechanically contradictory because `GAP` was still populated.

Renaming the field to:

```text
REMAINING_GAP
```

made the intended meaning explicit:

```text
REMAINING_GAP = what is still missing now
```

With the same Agriculture Q1 multi-turn scenario, Gemma 3n E4B then completed the full flow successfully.

The parser remains strict. It was **not** relaxed to repair contradictory model output.

---

# SLM Input Context

For each respondent turn, the model receives:

```text
SURVEY QUESTION
<prepared major question>

INTERVIEW GOAL
<what the survey designer wants to understand>

RESPONDENT EVIDENCE
<original answer plus previous follow-up exchanges>

OUTPUT CONTRACT
<REMAINING_GAP / STATUS / QUESTION / SUFFICIENCY>
```

For the first respondent turn, the original answer is shown directly.

For later turns, accumulated evidence is serialized as a conversation transcript:

```text
RESPONDENT:
20

INTERVIEWER:
Do you mean 20 percent or 20 bags per acre?

RESPONDENT:
percent
```

The model must evaluate the complete transcript as one accumulated response rather than evaluating the latest short answer in isolation.

---

# Survey Definition Direction

Survey-specific meaning should live primarily in the survey definition rather than in Kotlin.

Example:

```yaml
- id: Q1
  type: question

  prompt: >
    How much yield do you lose because of fall armyworm?
    Please think back over the last 3 seasons.
    Percent or bags per acre are fine.

  followUp:
    enabled: true
    goal: >
      Understand the respondent's fall armyworm yield loss over the
      last three seasons. A sufficient answer must make both the
      approximate yield-loss magnitude and its measurement unit
      unambiguous. The respondent may use percent, bags per acre,
      or another clear unit. If the respondent gives only a number
      while the survey question offers multiple possible units,
      the answer is not yet sufficient and the interviewer should
      ask which unit the number uses.
    maxQuestions: 2
```

The SLM interprets the `goal` semantically.

Survey Core enforces `maxQuestions` mechanically.

---

# Runtime Architecture

Survey-level AI logic is independent from the model runtime.

```text
                         Survey Definition
                                │
                                ▼
                           SurveyEngine
                                │
                                ▼
                         SurveyController
                                │
                                ▼
                             SurveyAi
                                │
                                ▼
                          ModelSurveyAi
                                │
                                ▼
                   ResponseEvaluationPrompt
                                │
                                ▼
                     TextGenerationBackend
                                │
              ┌─────────────────┴─────────────────┐
              │                                   │
              ▼                                   ▼
 ProcessingTextGenerationBackend          LiteRtLmBackend
              │                                   │
              ▼                                   ▼
        LlamaCppBackend                         Gemma
              │
              ▼
         Qwen / GGUF
```

The same:

- survey definition,
- response-evaluation models,
- response-evaluation prompt,
- response parser,
- `ModelSurveyAi`,
- `SurveyController`,
- `SurveyEngine`

should work across supported model runtimes.

---

# ModelSurveyAi

`ModelSurveyAi.evaluateResponse()` is the unified SLM path.

For one respondent turn it:

```text
ensure backend ready
        ↓
build one response-evaluation prompt
        ↓
backend.generate(...) exactly once
        ↓
parse one response
        ↓
Done | FollowUp | Failed
```

Important behavior:

- one respondent turn uses exactly one generation call,
- temperature is `0.0`,
- output is limited to a small protocol response,
- cancellation is propagated,
- backend exceptions become explicit failures,
- no Qwen/Gemma branch exists in `ModelSurveyAi`.

---

# TextGenerationBackend

`TextGenerationBackend` is the runtime-independent generation boundary.

```text
GenerationRequest
       ↓
TextGenerationBackend
       ↓
GenerationResult
```

The survey layer does not depend directly on llama.cpp or LiteRT-LM.

---

# ProcessingTextGenerationBackend

Model-family-specific preprocessing/postprocessing is implemented as a decorator rather than embedded in survey logic.

```text
GenerationRequest
       ↓
Prompt Processor
       ↓
Runtime Backend
       ↓
Output Processor
       ↓
GenerationResult
```

Current Qwen path:

```text
ProcessingTextGenerationBackend
    ├── QwenNoThinkPromptProcessor
    ├── QwenThinkingOutputProcessor
    └── LlamaCppBackend
```

Current Gemma path:

```text
LiteRtLmBackend
```

Survey-level logic remains unchanged.

---

# llama.cpp / Qwen

`LlamaCppBackend` is the GGUF runtime adapter.

It owns:

- GGUF model loading,
- persistent native model/context lifetime,
- request isolation,
- tokenization,
- inference,
- sampler configuration,
- native resource release.

It does not own:

- Qwen-specific prompt semantics,
- survey semantics,
- sufficiency policy,
- survey progression.

Current comparison model:

```text
Qwen3-1.7B-Q4_K_M.gguf
```

---

# LiteRT-LM / Gemma

`LiteRtLmBackend` is the LiteRT-LM runtime adapter.

It owns:

- LiteRT-LM engine lifecycle,
- model loading,
- fresh conversation creation per generation request,
- deterministic sampling configuration,
- inference,
- runtime cleanup.

It does not own:

- survey semantics,
- response evaluation policy,
- survey-specific prompt logic.

Current comparison models:

```text
gemma-3n-E2B-it-int4.litertlm
gemma-3n-E4B-it-int4.litertlm
```

---

# Reference Survey

## Agriculture Survey

The Agriculture Survey remains the first reference implementation.

Q1:

> How much yield do you lose because of fall armyworm?
> Please think back over the last 3 seasons.
> Percent or bags per acre are fine.

Current Q1 evaluation cases:

```text
Q1-A
Respondent: 20
Expected: FOLLOW_UP

Q1-B
Respondent: 20 percent
Expected: DONE

Q1-C
Respondent: I don't know
Expected: FOLLOW_UP

Q1-D
Respondent: 20
Model asks follow-up
Respondent: percent
Expected: Q1 completes and Survey Core advances to Q2
```

The end-to-end Q1-D test runs:

```text
Agriculture YAML
      ↓
SurveyEngine
      ↓
SurveyController
      ↓
ModelSurveyAi
      ↓
runtime backend
      ↓
real on-device model
```

---

# Current Model Comparison

The current evaluation is intentionally small. It is a development benchmark, not a final model-quality study.

All three models use the same Agriculture survey definition and the same high-level SurveyCore evaluation flow.

## Gemma 3n E4B int4 / LiteRT-LM

Using the current `REMAINING_GAP` protocol:

```text
Single-turn: 3/3 ✅
Multi-turn:  1/1 ✅
Overall:     4/4 ✅
```

Representative timing from the latest run:

```text
initMs=1589
avgSingleMs=34103
multiTurn1Ms=49746
multiTurn2Ms=44610
evaluationTotalMs=196667
```

Current interpretation:

- strongest decision quality of the tested models,
- correctly accumulated `20` + follow-up answer `percent`,
- correctly advanced from Q1 to Q2,
- currently the leading model for SurveyCore response evaluation.

A follow-up wording/grounding issue remains.

For an input of:

```text
20
```

E4B generated:

```text
Could you please specify whether the 20% refers to a percentage or bags per acre?
```

The model incorrectly inserted `%` into the respondent evidence.

Decision quality passed, but interviewer grounding quality still needs explicit evaluation.

---

## Gemma 3n E2B int4 / LiteRT-LM

Earlier `GAP`-protocol baseline:

```text
Single-turn: 3/3 ✅
Multi-turn:  0/1 ❌
avgSingleMs≈22323
```

Under the current `REMAINING_GAP` protocol:

```text
Single-turn: 1/3 ❌
Multi-turn:  0/1 ❌
```

Latest timing:

```text
initMs=1292
avgSingleMs=24040
multiTurn1Ms=32645
multiTurn2Ms=37691
evaluationTotalMs=142456
```

E2B remains faster than E4B, but the current protocol produces materially worse semantic behavior.

---

## Qwen3 1.7B Q4_K_M / llama.cpp

With the previous GAP-first comparison baseline:

```text
Single-turn: 1/3 ❌
Multi-turn:  0/1 ❌
```

Representative timing:

```text
initMs=2383
avgSingleMs=39225
multiTurn1Ms=80503
multiTurn2Ms=90875
evaluationTotalMs=289054
```

Observed issues included:

- unnecessary follow-up after `20 percent`,
- malformed protocol output for `I don't know`,
- failure to resolve the multi-turn `20` + `percent` answer,
- substantially slower multi-turn inference in this test.

Qwen remains useful as a backend-comparison target, but it is not currently the leading model for this task.

---

# Current Model Ranking

For the current Agriculture Q1 development benchmark:

```text
1. Gemma 3n E4B int4 / LiteRT-LM
   Decision quality:       best
   Multi-turn accumulation: works
   Protocol consistency:   works with REMAINING_GAP
   Follow-up grounding:    still needs work
   Speed:                  slower than E2B

2. Gemma 3n E2B int4 / LiteRT-LM
   Faster, but current REMAINING_GAP behavior regressed

3. Qwen3 1.7B Q4_K_M / llama.cpp
   Lower decision/protocol quality and slower in this benchmark
```

This ranking is provisional and based on a very small evaluation set.

---

# Important Current Finding

The strongest result from the current experiment is not simply that E4B is larger.

The protocol wording itself materially affected model behavior.

```text
E4B + GAP
single=3/3
multi=0/1
```

The multi-turn semantic decision was close to correct, but the model emitted a historical gap together with `STATUS: DONE`.

After changing the field to:

```text
REMAINING_GAP
```

the same E4B evaluation became:

```text
single=3/3
multi=1/1
```

This suggests that protocol labels should describe their semantic meaning as explicitly as possible, especially for small local models.

---

# Testing Strategy

SurveyCore separates deterministic software testing from model-quality evaluation.

## Unit / deterministic tests

Current checks include:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleDebugAndroidTest
git diff --check
```

Unit tests cover:

- response protocol parsing,
- prompt construction,
- exactly-one-generation behavior,
- `DONE`,
- `FOLLOW_UP`,
- malformed output,
- failure propagation,
- survey state behavior.

At the end of the current work session:

```text
testDebugUnitTest: BUILD SUCCESSFUL
98 tests completed
```

After the protocol migration, stale `GAP:` fixtures in `ModelSurveyAiTest` were updated to `REMAINING_GAP:`.

---

## Real-device model evaluation

`AgricultureQ1ModelComparisonTest` runs the same benchmark against multiple real model backends.

Current model methods:

```text
gemmaE2B()
gemmaE4B()
qwen17B()
```

Compact log output:

```text
RESULT|model=...
SUMMARY|model=...
```

Example result extraction:

```bash
adb logcat -d -s Q1ModelComparison:D '*:S' \
  | grep -E 'RESULT|SUMMARY'
```

Model-quality mismatches are logged as `pass=false` so a complete comparison run can still produce a summary.

Infrastructure failures remain test failures.

---

# Model Storage

Models are intentionally not committed to Git.

Current device-side model set used for comparison:

```text
Qwen3-1.7B-Q4_K_M.gguf
gemma-3n-E2B-it-int4.litertlm
gemma-3n-E4B-it-int4.litertlm
```

They are stored in application-private storage under:

```text
files/models/
```

Instrumentation workflows should avoid reinstall patterns that erase application-private model files unnecessarily.

---

# Current Development Branch

Active branch:

```text
slm-response-evaluation
```

Latest committed checkpoint before the current `REMAINING_GAP` work:

```text
0697beb
Checkpoint Gemma E2B response evaluation baseline
```

The current `REMAINING_GAP` protocol, parser/test updates, and model-comparison test changes are working-tree changes until a new checkpoint is committed.

No Git remote is required for the current local development workflow.

---

# Current Working Direction

The unified response-evaluation architecture is now implemented and exercised end-to-end.

The major architecture work completed so far includes:

- response-evaluation request/result models,
- one-call-per-respondent-turn `ModelSurveyAi.evaluateResponse()`,
- accumulated follow-up evidence,
- transcript-based multi-turn evaluation,
- strict line-oriented response parser,
- deterministic SurveyController / SurveyEngine integration,
- Agriculture YAML integration,
- LiteRT-LM backend,
- llama.cpp backend,
- Qwen prompt/output processing,
- real-device model comparison,
- E2B / E4B / Qwen comparison,
- `REMAINING_GAP` protocol experiment,
- E4B single-turn and multi-turn success on the current Q1 benchmark.

---

# Development Roadmap

## B0 — Response Evaluation Models

Status: ✅ Complete

Implemented:

```text
ResponseEvaluationRequest
ResponseEvaluationResult
ResponseFollowUpExchange
```

The request carries survey/question context and accumulated respondent evidence without runtime-specific model details.

---

## B1 — Response Evaluation Prompt

Status: ✅ Complete / actively refined

Implemented:

```text
ResponseEvaluationPromptBuilder
```

Current design:

```text
REMAINING_GAP first
STATUS second
QUESTION third
SUFFICIENCY last
```

Multi-turn evidence is represented as a respondent/interviewer transcript.

---

## B2 — Response Evaluation Parser

Status: ✅ Complete

Implemented:

```text
ResponseEvaluationParser
```

The parser validates the protocol mechanically and rejects contradictory output.

Current first field:

```text
REMAINING_GAP:
```

The legacy:

```text
GAP:
```

is intentionally rejected by the current parser.

---

## B3 — ModelSurveyAi Unified Evaluation

Status: ✅ Complete

Implemented:

```text
ModelSurveyAi.evaluateResponse()
```

One respondent turn performs exactly one backend generation.

No model-family branching is required in `ModelSurveyAi`.

---

## B4 — SurveyController Integration

Status: ✅ Complete

Unified response evaluation is integrated with deterministic survey control.

Target flow is working:

```text
Major Answer
     ↓
evaluateResponse()
     ↓
DONE ───────────→ next major question

FOLLOW_UP
     ↓
ask generated question
     ↓
respondent answer
     ↓
evaluateResponse() again
```

---

## B5 — Agriculture / Qwen Verification

Status: ✅ Evaluated, quality limitations found

Qwen3 1.7B runs through the same SurveyCore abstraction.

The runtime integration works, but current benchmark quality is below Gemma E4B.

---

## B6 — Agriculture / Gemma Verification

Status: ✅ Complete

Both Gemma 3n E2B and E4B run through `LiteRtLmBackend`.

E4B currently provides the strongest response-evaluation behavior.

---

## B7 — Model Comparison and Calibration

Status: 🚧 Current

Current comparison set covers:

```text
20
20 percent
I don't know
20 → generated follow-up → percent
```

Next evaluation work should expand beyond simple decision correctness.

Important future metrics:

- follow-up grounding,
- hallucinated facts in generated questions,
- follow-up relevance,
- unnecessary repetition,
- priority of the requested missing information,
- malformed output rate,
- latency,
- consistency across repeated runs,
- more survey questions,
- more realistic respondent language.

The immediate next issue is **follow-up grounding quality**, especially preventing a model from changing:

```text
20
```

into:

```text
20%
```

inside a generated question.

---

## B8 — Mobile Phone Survey

Status: ⏳ Planned

Add the second reference survey to verify genericity.

Acceptance criterion:

```text
Agriculture Survey ─┐
                    ├──> Same SurveyEngine
Mobile Survey ──────┘
```

and:

```text
Qwen ─┐
      ├──> Same ModelSurveyAi response-evaluation logic
Gemma ┘
```

---

## B9 — Branching, Choices, and Multilingual Support

Status: ⏳ Planned

Expand generic capabilities:

```text
BOOLEAN
SINGLE_CHOICE
MULTI_CHOICE
Conditional branches
Localized prompts
Localized interview goals
Survey language selection
```

Branch decisions remain deterministic.

---

## B10 — Voice / Whisper

Status: ⏳ Planned

Add voice only after text-based response evaluation is stable.

```text
Microphone
    ↓
Whisper.cpp
    ↓
Transcribed Text
    ↓
SurveyController
    ↓
SLM Response Evaluation
```

Voice remains an input adapter.

SurveyEngine remains text/structured-data based.

---


# English-only Whisper.cpp ASR Development

The first ASR milestone is intentionally isolated from SurveyController and all
SLM backends.

Runtime structure:

```text
app/src/debug/WhisperCppSmokeActivity
        ↓
SpeechRecognitionBackend
        ↓
WhisperCppBackend
        ↓
WhisperCppNative / JNI
        ↓
:asr-whispercpp native library
        ↓
whisper.cpp v1.9.3
```

The `:asr-whispercpp` Android library has its own CMake build so whisper.cpp's
bundled GGML copy does not share CMake targets with the llama.cpp build in
`:app`.

The current baseline is deliberately narrow:

```text
language:       English only
model:          ggml-base.en.bin
sample rate:    16 kHz
channels:       mono
PCM input:      FloatArray
runtime:        CPU
```

The smoke-test UI and its sample WAV exist only in the Debug source set. The
application uses a Debug-only dependency on `:asr-whispercpp`, so the current
Release application path remains independent from this ASR experiment.

Prepare the pinned native source:

```bash
./scripts/setup_whisper_cpp.sh
```

Build and install the Debug app:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Download and install the English model into application-private storage:

```bash
./scripts/download_whisper_base_en.sh
./scripts/install_whisper_base_en.sh
```

Run the isolated smoke test:

```bash
./scripts/run_whisper_smoke_test.sh
```

Relevant logcat:

```bash
adb logcat -s WhisperCppSmoke:D WhisperCppNative:I '*:S'
```

Expected data flow:

```text
samples/hello_survey.wav
        ↓
Pcm16WavDecoder
        ↓
16 kHz mono FloatArray
        ↓
WhisperCppBackend
        ↓
English transcript + inference timing + RTF
```

Microphone capture is intentionally deferred until this deterministic WAV path
is stable on the target Android device.

---

# Current Architecture Summary

```text
                         Survey Definition
                                │
                                ▼
                           SurveyEngine
                                │
                                ▼
                         SurveyController
                                │
                                ▼
                             SurveyAi
                                │
                                ▼
                          ModelSurveyAi
                                │
                                ▼
                 ResponseEvaluationPromptBuilder
                                │
                                ▼
                     TextGenerationBackend
                                │
               ┌────────────────┴────────────────┐
               │                                 │
               ▼                                 ▼
ProcessingTextGenerationBackend           LiteRtLmBackend
               │                                 │
               ▼                                 ▼
         LlamaCppBackend                       Gemma
               │
               ▼
          Qwen / GGUF
```

Survey configuration remains independent:

```text
Agriculture YAML ─┐
                  │
Mobile YAML ──────┼──> Same Survey Core
                  │
Future Surveys ───┘
```

---

# Definition of Success

SurveyCore succeeds when a new survey can normally be added by supplying configuration rather than writing survey-specific Kotlin logic.

For any prepared question, the framework should be able to:

```text
ask question
    ↓
receive respondent answer
    ↓
use one on-device SLM inference
    ↓
evaluate accumulated response sufficiency
    ↓
identify the most important remaining gap
    ↓
generate one grounded follow-up when needed
    ↓
repeat only after a new respondent turn
    ↓
continue deterministic survey flow
```

The same survey should be executable using different model runtimes without changing SurveyEngine or survey-specific application logic.

The current leading path is:

```text
Gemma 3n E4B int4
        ↓
LiteRT-LM
        ↓
ModelSurveyAi
        ↓
REMAINING_GAP response evaluation
```

The next quality milestone is to preserve the current E4B decision accuracy while improving the grounding and wording of generated follow-up questions.
