# Ultron v4: Learned Evaluation for Battlebox — Thesis and Implementation Plan

**Status:** PROPOSED (2026-07-23)
**Author:** Claude (Fable 5), from a full read of FORGE_TRACKER.md, the v3 codebase, and external prior art.
**Supersedes:** the lost `ultron-v3-search-and-learning.md` §Phase 3+ ("learned value function").
**Lives in-repo deliberately** — the v3 plan doc lived in `~/agents/` and no longer exists. History is the point.

---

## 1. Thesis

**Ultron v4 keeps the v3 search skeleton and replaces its evaluation heart — the hand-tuned
`GameStateEvaluator` — with a small neural network trained on real game outcomes, then closes the
loop with expert iteration.** No end-to-end deep RL, no fixed action space, no LLM in the loop.

Three claims, each grounded in evidence from this repo's own history:

**Claim 1 — The bottleneck is evaluation quality, not decision plumbing.**
Three iterations have now proven the plumbing works and the evaluation doesn't:
- v1 (LLM-per-decision): unplayably slow, sim-untestable.
- v2 (heuristic runtime + per-card win-rate learning): 14.7% win rate — *worse* than the 24.7%
  all-Default baseline. Per-card scalar learning declared a dead end (tracker, 2026-07-03).
- v3 (simulation search): all four decision surfaces (main phase, attacks, blocks, stack response)
  route through `GameCopier`/`GameSimulator` search with 230+ passing tests — but every candidate is
  scored by `GameStateEvaluator`, which is a pile of admittedly hand-picked constants (`MONARCH_VALUE=8`,
  65/35 life split, `50 + 30*CMC`) whose own comments say "Phase 3's learned value function replaces
  this." The scoring function is now the lowest-quality component in the pipeline, and it is *also*
  the most expensive (see Claim 3).

**Claim 2 — Candidate-scoring architecture sidesteps the action-space problem entirely.**
The "ton of input nodes" worry dissolves once you commit to the frame v3 already built: the game
engine enumerates legal candidates; the network only ever *scores states*. This is TD-Gammon's
architecture (afterstate evaluation), not AlphaZero's (policy over a fixed action space). The
network needs zero action outputs. Its input is a fixed-size encoding of a game state — and with a
666-card pool, cards are a small learned embedding table, not 30,000 input nodes. MageZero
(AlphaZero-style MTG on XMage, the strongest public prior art) hit 66% vs minimax with exactly this
family of approach, on CPU, at ~250 games/hour — but needed 300-sim MCTS per move. We can't afford
that on 4 cores; TD-Gammon-style one-ply search + strong learned V(s) is the sample- and
compute-efficient point on the same curve, with shallow PUCT as a later upgrade if throughput allows.

**Claim 3 — The learned evaluator is also the fix for TICKET-V3-207's sustained-cost problem.**
This is the non-obvious synergy that makes v4 more than a quality play.
`GameStateEvaluator.getScoreForGameState()` currently performs a **full `GameCopier` deep copy plus
a combat simulation through `COMBAT_DAMAGE`** (`simulateUpcomingCombatThisTurn`) on *every single
evaluation* — and V3-207's jstack evidence shows `GameCopier.makeCopy()` (with `SharedPlayerZone`'s
4× view-update fan-out) is precisely where all the time goes. A neural evaluation is a
~0.1 ms feed-forward pass with **no game copy at all**. Replacing the evaluator removes the entire
innermost copy/simulate layer from the search tree. The candidate-level copy (apply the spell,
resolve it) remains, but the per-candidate *evaluation* copies — the multiplied inner layer — vanish.
v4 is therefore simultaneously the quality fix and a large part of the performance fix.

**The training method is expert iteration (ExIt), bootstrapped from cheap logged games:**
1. Bootstrap: log outcome-labeled states from Default-vs-Default games (cheap: no simulation
   overhead, ~2-3 min/game, 500 games already proven to run cleanly on this box) and train V0
   by supervised learning on "who ended up winning from this state."
2. Deploy: Ultron's existing search scores candidates with V0 instead of the heuristic.
3. Iterate: Ultron-in-the-loop games generate better-distributed states; retrain; gate each
   candidate model with the existing `gate.py` paired-seed methodology; promote only on a win.
4. Later: add a policy-prior head trained to imitate the search's own choices, used to order and
   prune candidates (fewer expensive candidate copies), and eventually to answer trivial decisions
   with no search at all (interactive-play latency).

---

## 2. Constraints that shape everything

| Constraint | Value | Consequence |
|---|---|---|
| CPU | 4 cores (Dell XPS 15) | 2-3 sim workers max; no MCTS with 300-sim budgets |
| RAM | 15 GB (~7 available) | 2 workers × 3g heap ceiling (measured, TICKET-V3-001) |
| GPU | GTX 1050 4GB, CUDA 12.4 | Fine for ≤5M-param nets in PyTorch; useless for sim |
| Game throughput (Default 4p) | ~1.8-3 min/game, 2 workers | ~30-60 games/hour → 500-1000 games/day realistic |
| Game throughput (Ultron v3 today) | **DNF** (times out at 2400s) | Phase 0 perf work is a hard prerequisite |
| Card pool | 666 cards + basics/tokens | Card vocab ~750 IDs; embedding table, not input nodes |
| Baseline | Default = 24.7% [21.1, 28.7] Wilson 95% | The number to beat, with `gate.py` significance |
| Prior best Ultron | 28% (v2, N=25 — not significant) | No Ultron variant has *ever* passed a real gate |

The throughput ceiling is the defining constraint: we get **thousands of games, not millions**.
Every design choice below optimizes samples-per-game (multi-seat perspectives, many states per
game, auxiliary targets) and quality-per-sample (search amplification) rather than raw game count.

---

## 3. Prior art (searched 2026-07-23)

- **[MageZero](https://github.com/WillWroble/MageZero)** — AlphaZero-style RL on XMage. Sparse
  feature hashing (2M slots) → transformer encoder → multi-head policy + value; PUCT self-play.
  Results: 66% vs minimax (UWTempo), ~250 games/hr on 13 CPU threads. Validates: MTG is learnable
  at hobby scale; sim throughput is everyone's bottleneck; value+search works. Differs: 1v1,
  deck-specific agents, full MCTS budget we don't have.
- **[LearnForge](https://github.com/thesilencelies/LearnForge)** — RL on Forge itself (old,
  stalled). Validates Forge-as-environment; shows the cost of not having a measurement harness
  (we have one: simstats + gate.py).
- **[Learning With Generalised Card Representations for MTG (arXiv 2407.05879)](https://arxiv.org/abs/2407.05879)**
  — card-feature representations generalize to unseen cards; for a *fixed* pool, learned ID
  embeddings + handcrafted card features are the right call and generalization machinery is
  unnecessary complexity. Supports our fixed-pool embedding choice.
- **TD-Gammon (Tesauro 1995)** — the canonical existence proof for this exact architecture:
  stochastic multi-branch game, afterstate value net, shallow search, self-play TD learning,
  superhuman on 1990s hardware. Our plan is TD-Gammon with a modern optimizer and a harder game.
- **Negative prior art from this repo:** per-card scalar win-rate learning (v2, TICKET-123) failed
  at 14.7%. Lesson: credit assignment at the card level without state context is too coarse. The
  value net is the antidote — it scores *states*, so card values are contextual by construction.

---

## 4. Architecture

### 4.1 State encoding (Java-side, single source of truth)

One fixed-length `float[]` per (game state, perspective player). Target size ~1,000-1,500 floats.
All encoding lives in **one Java class** (`UltronStateEncoder`) used identically for training-data
logging and inference — train/serve skew is eliminated structurally, not by discipline. Python
never re-implements feature extraction; it consumes logged vectors.

**Card representation.** A static table, built once at startup from the Forge card DB, keyed by
card name → dense per-card feature vector (~48 floats): mana value, color identity (5), card types
(8), P/T (creatures), keyword flags (~25: flying, deathtouch, lifelink, haste, trample, ward,
flash, …), oracle-text-derived flags (removal / counterspell / board-wipe / card-draw / ramp /
token-maker — reuse `UltronStackThreatAnalyzer`'s existing API-type classification), and a learned
**card ID embedding (dim 16)** concatenated at train time (the ID goes into the log; the embedding
lives in the net). Unknown IDs (new pool cards) hit a shared "UNK" row — pool updates degrade
gracefully instead of crashing.

**Zone encoding (permutation-invariant pooling — this is what kills the input-node explosion).**
Each zone is encoded as element-wise **sum + max pooling** over its cards' feature vectors, plus a
count. Per-card dynamic state (tapped, summon-sick, damage, +1/+1 counters, attached auras) is
appended to that card's vector before pooling for battlefield zones. Zones encoded:
- Self: hand (full knowledge), battlefield (creatures pooled separately from non-creatures, lands
  as counts by color production), graveyard, exile, commander zone.
- Each opponent (×3, turn-order relative to self): battlefield (same split), hand *count* only,
  graveyard, commander zone. Eliminated opponents: zero block + `eliminated` flag.
- Shared: graveyard (Battlebox), stack (top 3 entries: card vector + controller slot + targets-me
  flag), current plane ID (Planechase, small vocab, one-hot or tiny embedding), land station state.
- Shared library: count only (contents hidden; composition is public knowledge and static — the
  net learns pool priors through the embeddings themselves).

**Global scalars.** Turn number, phase one-hot (~12), who is active player / has priority (slot
one-hot), life totals ÷ 20, poison, energy, monarch holder (slot one-hot + none), initiative,
mana available now (by color), lands playable, cards drawn this turn, attackers declared this
combat, players remaining, per-opponent commander damage taken.

**Perspective and seat symmetry.** Everything is encoded relative to the evaluated player ("self"
block first, opponents in turn order after self). One network serves all four seats. This is also
the data multiplier: every logged state yields **4 training samples** (one per perspective).

### 4.2 Network

Deliberately small — the dataset is thousands of games, not millions:

```
input (~1.2k floats, card-ID slots resolved through a 750×16 embedding inside the net)
  → Linear 512 → ReLU → LayerNorm
  → Linear 256 → ReLU → LayerNorm
  → value head: Linear 4 → softmax     (win probability per seat, self-relative order)
  → aux heads (train-time only): own final placement (4-way), game length bucket (8-way),
    table-share-in-2-turns (4-way regression, §5.1 label 3)
```

~700K parameters. Trains in minutes per epoch on the GTX 1050; inference is a few dozen
microseconds in plain Java. The vector value head (all 4 seats) rather than a scalar is
deliberate: it forces the net to model the whole table (who is actually winning), which is exactly
what multiplayer threat assessment is, and it gives 4× the gradient signal per sample. The scalar
Ultron uses at decision time is `V = p_win[self]`. Auxiliary heads are a standard small-data
regularizer; they are dropped at export.

Upgrade path (only if gates stall): attention pooling over battlefield cards instead of sum/max;
policy-prior head (§6 Phase 3); deeper trunk. Do not start there.

### 4.3 Java inference + model artifact

No ONNX, no JNI, no dependency. A 2-layer MLP + embedding lookup is ~150 lines of Java
(`forge.ai.nn.UltronValueNet`): load weights, matmul, done. Model artifact = one file
(`ultron-nn-vN.bin`): header (schema hash, feature count, layer dims) + float32 weights, written
by the Python trainer, loaded at startup, selected via `ULTRON_NN_MODEL_PATH`. **The encoder schema
hash is embedded in both the training logs and the model file and checked at load** — a model
trained on encoder v3 refuses to run against encoder v4.

### 4.4 Integration point

Introduce an interface where the constant pile currently lives:

```java
public interface StateEvaluator {           // forge.ai.simulation
    Score getScoreForGameState(Game game, Player aiPlayer);
}
```

- `HeuristicStateEvaluator` — the current `GameStateEvaluator`, unchanged, stays the default for
  every non-Ultron profile. Zero behavior change for the Default AI.
- `NeuralStateEvaluator` — encode state → forward pass → map `p_win[self] ∈ [0,1]` to the integer
  `Score` scale (`round(p * 100_000)`) so `SpellAbilityPicker`'s existing comparisons work
  untouched. Terminal states keep the existing `MAX_VALUE`/`MIN_VALUE` short-circuit.
- **`summonSickValue` handling** (`SpellAbilityPicker.java:226` compares it, not `value`): run the
  forward pass twice, second time with summon-sick own creatures masked out of the battlefield
  pooling. Two forward passes ≈ 0.2 ms, still ~1000× cheaper than today's copy+combat-sim, and it
  preserves the "don't pre-combat-cast for no reason" semantics without touching the picker.
- Wired only through `UltronPlayerController`'s three guarded entry points via `UltronConfig`
  (`ULTRON_NN_EVAL=true`). Existing `RuntimeException` → `answeredBy=inherited` fallback and the
  40s decision timeout already protect against a broken model file.

---

## 5. Training methodology

### 5.1 Data pipeline

- **Logger:** `UltronStateLogger` (new, modeled on `UltronOfflineDecisionLogger` but logging
  binary feature vectors, not prose). At every Ultron decision point *and* at each turn's main
  phases for all players, write: schema hash, game ID, turn, phase, per-perspective feature
  vectors (4×), acting seat, per-alive-player heuristic board scores (the raw inputs to the
  U(s) anchor, §5.1 — logged raw, normalized at train time so the anchor formula can change
  without regenerating data), and (post-game, appended by `SimulateStats`) per-seat elimination
  turn / placement + game length. Format: one `.npz`-friendly flat binary or gzipped JSONL of float arrays per shard;
  merged like `games.jsonl` is today.
- **Sampling:** cap ~200 logged states per game (uniform over decision points) to bound
  correlation within a game; always include turn-1 and final-3-turns states.
- **Labels (composite bootstrap target — see design note below):**
  1. **Placement** (primary): finish order 1st-4th by elimination time, converted to a soft
     outcome distribution (e.g. 1st→[.70,.15,.10,.05] over self-relative seats). Placement, not
     winner-take-all, is the primary outcome label: in chaotic 4p games the two strong players
     who kill each other get 2nd/3rd credit instead of zero, so "coincidental winner" states are
     not the only positively-labeled states.
  2. **Heuristic utility anchor U(s)** (annealed): per-alive-player board strength from existing
     Forge evaluators — `ComputerUtil.evaluateBoardPosition` per player and/or v2's
     `UltronThreatModel`/`UltronOpponentProfile.boardValue` (cheap, relational, already a
     "feature provider" per TICKET-V3-103) — softmax-normalized across the table into a
     "table share" vector (same geometry as the value head). V0 value target =
     `α·placement + (1-α)·U(s)` with α starting ≈0.5 and annealed toward 1.0 as real
     Ultron-in-the-loop games accumulate. The anchor gives early nets sane priors without
     capping later nets at the heuristic's quality.
  3. **Future table share** (auxiliary head): U(s) evaluated 2 turns later on the same game's
     log — progress prediction ("is this player's relative position improving"), which is where
     board-trajectory signal genuinely helps credit assignment. (Predicting *current* U(s) from
     s would be a near-identity map — computed from the same features the net sees — and is
     deliberately not a target.)
  TD(λ≈0.9) blending — interpolate toward the *next* logged state's model value — from
  iteration 2 on, once a model exists to bootstrap from. Timeout games: **discard entirely**
  (consistent with gate.py's denominator policy; a timeout's "winner" is noise).
  **Design note — why U(s) is an anchor and aux, never the primary target:** a net trained to
  predict a hand-crafted utility converges to an imitation of that heuristic (which we could run
  directly, no NN needed), and board-strength objectives specifically teach durdling — winning
  requires *spending* board advantage, which a board-strength target scores as a loss. This repo
  already ran that experiment: TICKET-117, "slow engines overvalued," part of v2's 14.7%. Outcome
  labels (placement) are the only signal that knows tempo and closing matter; 4p outcome noise is
  variance (averages out over thousands of perspective-samples), while a heuristic target is bias
  (doesn't).
- **Split discipline:** train/validation split **by game ID** (never by state — states within a
  game are heavily correlated and leak).

### 5.2 Trainer (`tools/nn/`)

Python 3.13 + PyTorch (CUDA 12.4 wheel) in a venv under `tools/nn/.venv`. AdamW, lr 1e-3 cosine
decay, batch 1024, weight decay 1e-4, early stopping on held-out-game log-loss (patience 5),
~30 epoch budget per iteration (expect early stop well before). Loss = cross-entropy on the
4-way value head against the composite target of §5.1 (`α·placement + (1-α)·U(s)`, α annealed)
+ 0.25 × each aux head. Deterministic seed; every run writes
`runs/<date>/metrics.json` + the exported `.bin` + a copy of its config. Report: held-out
log-loss, winner-prediction accuracy, calibration curve by game-stage (early/mid/late).
**Offline metrics are diagnostics only — promotion is decided by `gate.py` win rates, nothing else.**

### 5.3 Opponent curriculum (answers the 1v1 / FFA / vs-Default / self-play question)

**REVISED 2026-07-24 (William's call): bootstrap on 1v1 Monarch Battlebox first, then transfer to
4-player FFA.** This supersedes the original "4p FFA from day one, 1v1 as debugging lane only"
position below, and it is a better plan than what it replaces:
- **It routes around the measured blocker.** TICKET-V4-001/V4-002 established that the dominant
  remaining cost — static-ability/replacement-effect recomputation — scales with *permanent count*,
  in both CPU (jstack) and allocation (the 4g OOM) terms. A 2-player board is roughly half the size,
  so 1v1 attacks the cost driver directly rather than waiting on an engine-caching fix that would be
  correctness-sensitive surgery.
- **It buys sample throughput, which is the binding constraint on the whole plan** (§2). Shorter
  games with smaller boards mean more games/hour and therefore more training samples per day.
- **The encoder needs no change.** 1v1 is representable as "a 4-player game where two seats are
  already eliminated" — the zero-block + `eliminated` flag path §4.1 already specifies. The 4-way
  value head is kept, with the two dead slots masked, so a 1v1-trained net **transfers directly**
  into 4p fine-tuning instead of needing a new architecture.

**Curriculum:**
- **Stage A (bootstrap, 1v1 Monarch):** Ultron vs Default, seat-rotated. All-Default 1v1 games for
  the initial outcome-labeled corpus, then mixed as below.
- **Stage B (transfer, 4p FFA Monarch):** fine-tune the Stage A net on 4-player data once 4p games
  are cheap enough to generate at volume (either via the caching ticket or because the NN eval has
  removed enough per-evaluation cost, §1 Claim 3).

**Gate math differs by lane and must not be mixed up:** the 1v1 null hypothesis is **50%**, not the
4-player 25%. `gate.py` comparisons for Stage A need the right null, and Stage A results are NOT
comparable to the TICKET-V3-007 all-Default 4-player control (24.7%). A Stage A win rate of 30%
would be a *disaster* in 1v1 while being a success in 4p — do not let that confusion into a report.

**What Stage A cannot teach, and why Stage B is not optional:** multi-opponent threat triage,
politics/kingmaking, and "who at the table do I attack" have no 1v1 analogue. A Stage-A-only net
will be weak at exactly the reasoning 4-player FFA is made of. Stage A is a cheap way to learn
card/board/tempo value; Stage B is where the multiplayer game actually gets learned. Do not ship
a 1v1-trained net into a 4-player lane and call the plan done.

--- *original position, retained for the record:* ---

Train and evaluate on the **target distribution from day one: 4-player FFA Battlebox + Monarch**
(William's stated default — it's already `battleboxMonarch=true` in every v3 config and the
evaluator/encoder both know about monarch).

- **Iteration 0 (bootstrap):** all-Default tables. Cheapest data by far (no simulation overhead;
  the 500-game control run completed cleanly at 3g heap), and outcome labels are unbiased "how do
  Battlebox games actually end" signal. Off-policy for Ultron, but V0 only needs to be better
  than hand-picked constants — a low bar.
- **Iterations 1+:** mixed-population tables — 1×Ultron(current) + 3×Default, 2+2, and
  Ultron(current) vs Ultron(previous-best) vs 2×Default, seat-rotated as TICKET-V3-002 already
  provides. Mixing prevents both failure modes: pure self-play collapse into a self-referential
  meta, and pure vs-Default overfitting to one exploitable opponent. Default never leaves the
  population — it is the fixed measuring stick.
- ~~**1v1** is a debugging lane only (fast, low-variance sanity checks of encoder/net changes), never
  a training target~~ — **superseded 2026-07-24, see the revision at the head of this section.** The
  reasoning that 4p FFA's politics/threat-assessment structure is the actual game still stands and is
  why Stage B exists; what changed is that 1v1 is now the bootstrap lane rather than excluded outright.
- **Commander/Planechase:** encoder slots reserved from day one (commander zone, commander damage,
  plane ID); training on those variants is deferred until the core gate passes (§6 Phase 4).

### 5.4 Promotion gate (unchanged from v3 discipline — this is the part v2 skipped)

A candidate model is promoted to "current best" only if, on a seat-rotated, same-seed paired run
(`run_parallel.sh` + `gate.py`):
1. It beats the all-Default null (25%) with one-sided p < 0.05, and
2. It does not lose to the previous best Ultron by more than noise (when a previous best exists).

N per gate: 300 games minimum (detects ~33%+ vs 25% at p<0.05); the headline Phase 2 gate stays
at the v3 plan's **N=600, ≥30%**. Never claim a win from N=25 again.

---

## 6. Implementation phases

Each phase is sized for implementation sessions (Opus/Sonnet-class), has a hard gate, and lands
tickets in FORGE_TRACKER under `EPIC: ULTRON-V4`. Phases 1-2 are sequenced so that **every piece
is testable without any trained model existing yet.**

### Phase 0 — Unblock the simulator (prerequisite; no NN work)
The whole plan is dead if games don't finish. Two known, diagnosed defects:
- **P0.1** Fix `SharedPlayerZone.onChanged()` view fan-out during simulation copies (V3-207's
  jstack-confirmed suspect): skip `updateZoneForView` when the mutated `Game` is a simulation copy
  (find the existing real-vs-copy discriminator; `GameCopier`-produced games are never rendered).
  Verify with the established live-jstack method + a real game reaching natural completion.
- **P0.2** Instrument and, if needed, cache `PaperCard→Card` construction in `GameCopier` for the
  shared library (hypothesis 1 in the orchestrator summary; measure first).
- **P0.3** Fix `GameCopier` stack copying (`COPY_STACK` default-false → countermagic permanently
  scored `MIN_VALUE`, TICKET-V3-206). Cheap, high-value, and the regression test already exists
  to invert (`testCounterspellCandidateCannotBeEvaluated...`).
- **Gate:** one full Ultron-vs-3×Default game completes naturally in < 15 min; 10-game smoke run
  with 0 timeouts; then re-baseline: what win rate does v3's heuristic search *actually* get?
  (Never yet measured — this number is also v4's control.)

### Phase 1 — Encoder + logging (pure Java, no model)
- **P1.1** `UltronCardFeatureTable`: static per-card features from the card DB; golden-file test
  for a dozen known cards; UNK handling.
- **P1.2** `UltronStateEncoder`: full state → `float[]`; schema hash; unit tests on the existing
  4p Battlebox fixtures (`GameCopierBattleboxFidelityTest`'s convention); invariance tests
  (perspective rotation maps opponent blocks correctly; eliminated-player masking).
- **P1.3** `UltronStateLogger` + `SimulateStats` outcome back-fill; config key `stats.nnLogging`.
- **P1.4** Encoder microbenchmark: < 1 ms per state (it will be far under).
- **Gate:** a 20-game logged run produces parseable data; a Python notebook round-trips it;
  per-feature sanity report (ranges, dead features, NaNs).

### Phase 2 — Bootstrap value net V0 + integration (first NN on the field)
- **P2.1** Generate bootstrap corpus: 1,500-2,000 all-Default games with logging (≈ 3-5 days
  wall-clock at 2 workers; runs unattended with the existing watchdog lessons applied).
  ~1.5M perspective-samples.
- **P2.2** `tools/nn/` trainer per §5.2; train V0; offline report.
- **P2.3** `forge.ai.nn.UltronValueNet` Java inference + `.bin` loader; **parity test:** Python
  and Java forward passes agree to 1e-5 on 100 logged states (the single most important test in
  the whole plan).
- **P2.4** `StateEvaluator` interface + `NeuralStateEvaluator` (incl. summon-sick double pass);
  wire behind `ULTRON_NN_EVAL`; existing timeout/fallback machinery wraps it.
- **P2.5** Measure per-decision latency vs heuristic path (expect large speedup from removing
  the eval-layer combat sim — quantify it).
- **Gate (the headline):** N=600 seat-rotated paired run, Ultron+NN vs 3×Default: **≥30% win rate,
  p<0.05 vs 25% null**, and ≥ the Phase 0 heuristic-search re-baseline. If it beats the null but
  not 30%, iterate once on features/net-size before touching anything architectural.

### Phase 3 — Expert iteration loop (the compounding phase)
- **P3.1** `tools/nn/iterate.sh`: generate N games with current best (mixed population per §5.3,
  logging on) → retrain (TD(λ) targets now) → gate at N=300 → promote or reject; every artifact
  versioned. Target cadence: one iteration ≈ 2-3 days unattended.
- **P3.2** Policy-prior head: log the search's chosen candidate + candidate features at each
  decision; train a small ranking head; use it to **order and prune** `SpellAbilityPicker`
  candidates (top-k before any copy is made). This cuts the remaining expensive per-candidate
  copies and is the ExIt "apprentice" — measure both win rate and games/hour impact.
- **P3.3** League hygiene: keep last 3 promoted models; every 3rd gate includes previous-best
  as an opponent to detect cycling/regression.
- **Gate:** 3 consecutive promoted iterations, or a plateau — plateau triggers §7 upgrades, not
  panic. Stretch: ≥40% sustained vs 3×Default at N=600.

### Phase 4 — Variants and interactive play
- **P4.1** Monarch is in from day one. Add Commander tables (encoder slots already reserved):
  mixed-variant training data, one gate run per variant.
- **P4.2** Planechase: plane-ID embedding goes live (the desktop UI + shared-deck work just
  landed on this branch); planar-die decisions become candidates like any other (walk/roll
  scored via afterstate value).
- **P4.3** Interactive latency: policy-head-only fast path for trivial decisions (single
  candidate, empty-stack pass), search+value reserved for main phase/combat/stack; ship as the
  default Ultron profile experience.
- **P4.4** (only if gates show interaction weakness) hidden-info handling beyond the encoder's
  hand-count features: cheap determinization — sample 2-3 plausible opponent hands from the known
  shared-pool remainder, average values. This is v3's Phase 4 belief-state idea, right-sized.

---

## 7. Risks and pre-committed responses

| Risk | Likelihood | Response (decided now, not under duress) |
|---|---|---|
| Phase 0 perf fixes don't get games finishing | Medium | The NN eval itself removes the inner copy layer (Claim 3). If still DNF: shrink candidate breadth further; a 5-min game budget with occasional inherited-fallback decisions is acceptable for *training data generation* even if imperfect |
| V0 no better than heuristic constants | Low-Med | Diagnose with calibration-by-game-stage; likeliest cause is encoder bugs → the per-feature sanity report and invariance tests exist precisely for this |
| ExIt plateaus / cycles | Medium | League play (P3.3); TD(λ) target tuning; only then architecture upgrades (attention pooling) |
| Train/serve skew | Low (by construction) | Single Java encoder + Java/Python parity test + schema-hash enforcement |
| Overfits Default's exploitable habits | Medium | Mixed populations from iteration 1; gate includes previous-best Ultron |
| 4p politics/kingmaking confuses value learning | Medium | Vector value head models the whole table; monarch/threat features are explicit; accept that FFA win rate has an irreducible political variance term — that's what N=600 gates are for |
| GPU too small / torch install pain | Low | 700K params trains on CPU in acceptable time if the 1050 misbehaves |
| Card pool changes (Cube Cobra groom) mid-corpus | Medium | Embeddings keyed by name with UNK fallback; log the pool snapshot hash per run; retrain embeddings on pool change, trunk transfers |
| Utility anchor teaches durdling (board hoarding over closing) | Medium | U(s) is anchor/aux only, never primary target (§5.1 design note); α annealed to 1.0; TICKET-117 is the documented precedent — any gate showing long games + high board score + low win rate triggers dropping α immediately |
| Regression to v2's unverified-claims failure mode | — | **No number without a gate.py run attached. This is the discipline v3 established; v4 inherits it as law** |

## 8. What v4 explicitly does NOT do

- No LLM in the decision loop (stays demoted per the remodel; chat/table-talk unaffected).
- No full MCTS/PUCT until throughput proves ≥ ~250 games/hr headroom (MageZero's floor).
- No generalization to the full 30k-card cardpool — fixed-pool embeddings on purpose.
- No 2-player training targets, no non-Battlebox formats.
- No replacement of Forge's legality/targeting machinery — the engine proposes, the net disposes.

## 9. Immediate next actions

1. Land this doc + `EPIC: ULTRON-V4` stub in FORGE_TRACKER (this session).
2. Phase 0 session: P0.1 SharedPlayerZone sim-copy guard (diagnosed, contained, high certainty) —
   the single highest-leverage change in the entire plan.
3. Phase 1 sessions can start in parallel with Phase 0 verification: the encoder needs no working
   full games, only the existing test fixtures.

## Sources

- [MageZero — AlphaZero-Style RL for MTG](https://github.com/WillWroble/MageZero)
- [LearnForge — RL on Forge](https://github.com/thesilencelies/LearnForge)
- [Learning With Generalised Card Representations for MTG (arXiv 2407.05879)](https://arxiv.org/abs/2407.05879)
- [OpenMTG](https://github.com/CraigBanach/openmtg), [open-mtg-env](https://github.com/daniellawson9999/open-mtg-env) — surveyed, less applicable
- Tesauro, *Temporal Difference Learning and TD-Gammon*, CACM 1995 — the architecture's existence proof
