# Ultron & Theory of Mind: Bluffing, Belief States, and Strategic Latent Structure

**Status:** RESEARCH NOTE (2026-07-25). Not an implementation commitment — a study of what it would
take, grounded in current SOTA and our actual architecture.
**Prompted by:** William's observation that a value net trained purely to predict outcomes will play
a *readable*, non-bluffing style that any human or stronger model can exploit; and his framing of two
latent structures — an "action latent space" (statistical state→value, which is what we train now)
and a "theory of the game / theory of mind" latent (the *what and why*).

---

## 0. The one-paragraph answer

Bluffing is not a feature you bolt on — in imperfect-information games it is **Nash-equilibrium
behavior that emerges only when three conditions hold simultaneously**: (1) the agent reasons over a
**belief state** (a probability distribution over hidden information, including *the opponent's beliefs
about you*), (2) the agent plays a **stochastic / mixed strategy** (a deterministic policy is provably
readable and exploitable), and (3) the **training objective rewards deception** — i.e. self-play or
equilibrium-finding against an opponent that both responds to your observable actions and can be
exploited by manipulating its beliefs. **Our current pipeline satisfies none of these three**, and
each is a distinct, independent gap — so today's Ultron *structurally cannot* bluff, no matter how
much outcome data we feed it. The good news: our setup has one advantage poker engines lack (a
*known, shared card pool*, so the opponent's hidden hand is computable), and the instant-speed
decision logging we just added (William's earlier call) is a hard prerequisite for bluffing that we
now have. This note explains the mechanism precisely and lays out a tiered path.

---

## 1. Why the current architecture cannot bluff (three independent reasons)

Our pipeline: a value network `V(s)` trained by **supervised regression on game outcomes**
(placement / utility), consumed as a **depth-0 afterstate-argmax policy** (enumerate legal moves,
apply each, score the resulting state, pick the best). Each of the following is sufficient on its own
to preclude bluffing; we have all three.

### 1.1 The training signal contains no bluffing (data/objective gap)
We train on Default-vs-Default (soon Ultron-vs-Default) outcomes. The Default AI neither bluffs nor
*can be* bluffed — it does not model what Ultron is representing. Supervised learning on non-bluffing
trajectories yields a non-bluffing function by construction. Bluffing can only be *discovered* by an
optimization process in which deceiving the opponent measurably improves the return — a self-play or
regret-minimization loop where "take an action that looks weak/strong to shape the opponent's next
move" pays off. Predicting `P(win | s)` from a fixed non-deceivable opponent's games never surfaces
that gradient. **This is why supervised value learning, even done perfectly, imitates the data's
strategic level and no higher.** (Cf. the imitation ceiling in behavior-cloning; and our own
TICKET-117 lesson that objective shape dictates learned style.)

### 1.2 The value function has no representation of the opponent's beliefs (architectural gap — the core "theory of mind" hole)
`V(s)` answers *"how good is this position for me?"* from Ultron's own perspective, with the
opponent's hidden cards encoded only as a **hand count**. It does **not** represent, and cannot
answer:
> *"What does my opponent currently believe about my hidden cards? How does the action I am about to
> take (which they can observe) change that belief? And how will their updated belief change the move
> they make?"*

Bluffing **is** that calculation — it is a computation over the opponent's belief update. A
self-perspective scalar value has no belief variable in it, so there is nothing to manipulate. This
is the precise technical meaning of "no theory of mind." It is **architectural, not fixable by more
data**: you must add a belief representation (§3) and/or an explicit opponent model (§4).

The state-of-the-art name for the missing object is the **Public Belief State (PBS)** — the
innovation behind Facebook's **ReBeL**, which reasons over "the probability distribution of different
beliefs each player might have about the current state," and by doing so converts an
imperfect-information game into a tractable perfect-information game *over belief states*
([ReBeL, Brown et al. 2020](https://arxiv.org/pdf/2007.13544)). DeepStack's "deep counterfactual
value networks" do the analogous thing for heads-up poker
([DeepStack, Moravčík et al. 2017](https://arxiv.org/pdf/1701.01724)).

### 1.3 A deterministic argmax policy is readable — bluffing requires calibrated mixing (policy gap — the "temperature" point, made precise)
Even if 1.1 and 1.2 were solved, `argmax_a V(afterstate(s,a))` is **deterministic**: a strong
opponent (or a human over a few games) learns the exact mapping and reads every action. In
imperfect-information games a deterministic policy is provably exploitable — the canonical proof is
rock-paper-scissors, where *any* pure strategy has worst-case value −1 while the uniform mixed policy
is unexploitable at 0 ([Reevaluating Policy Gradient Methods for Imperfect-Information Games,
2025](https://arxiv.org/html/2502.08938)). Bluffing specifically is a **mixed strategy**: cast a
given card as a bluff with probability *p* and as a value play with probability *1−p*, where *p* is
**game-theoretically calibrated** so the opponent cannot profitably call or fold.

This is William's "temperature" intuition — and it sharpens into an important caveat: **temperature is
necessary but nowhere near sufficient.** A flat softmax over `V` adds *undifferentiated noise* — it
makes Ultron randomly worse, not strategically unpredictable. The *frequencies* have to be the right
frequencies, and those come from equilibrium computation or self-play convergence (§2), not from a
temperature knob. Temperature without calibrated frequencies is just self-sabotage that happens to be
harder to read.

---

## 2. How the field actually produces bluffing (SOTA map)

| Approach | Core idea | Bluffing mechanism | Cost / fit for us |
|---|---|---|---|
| **CFR** (counterfactual regret minimization) | Iteratively minimize regret per information set; provably →Nash in 2p zero-sum | Bluff frequencies *are* the equilibrium mixing that minimizes regret | The gold standard; tabular CFR needs enumerable info sets — MTG's are astronomically large. Deep CFR approximates with nets ([Deep CFR, Brown et al. 2018](https://arxiv.org/pdf/1811.00164)) |
| **DeepStack** | Depth-limited CFR search + a **counterfactual value network** at the leaves | Equilibrium bluffing within each re-solved subgame | Superhuman HUNL poker; the value-net-at-leaves idea is directly analogous to our value net — but ours scores *states*, DeepStack's scores *belief states* ([DeepStack](https://arxiv.org/pdf/1701.01724)) |
| **ReBeL** | Self-play RL+Search over **public belief states**; treats the belief-state game as perfect-information | "Assesses the chance its opponent thinks it has a pair of aces" — belief is a first-class variable | The most general and the closest conceptual target; still a large build ([ReBeL](https://arxiv.org/pdf/2007.13544)) |
| **Pluribus** | Depth-limited search + self-play blueprint; **6-player** poker | Equilibrium-ish bluffing that holds up multi-way | Proof that this scales past 2p to multiplayer (relevant to our 4p FFA endgame) |
| **ISMCTS / determinization** | Sample hidden info into "determinizations," search each as perfect-info, aggregate over the **information set** | Bluffing is weak/emergent unless you model the opponent's info set explicitly | **Proven on Magic: The Gathering** ([Cowling, Powley, Whitehouse 2012](https://eprints.whiterose.ac.uk/id/eprint/75048/1/CowlingPowleyWhitehouse2012.pdf)); tractable but has known pathologies (below) |
| **ToMnet / Machine Theory of Mind** | A *separate* network meta-learns to predict another agent's behavior/beliefs from observations | Explicit opponent model you can plan against to *choose* deceptive actions | ([Rabinowitz et al. 2018](https://proceedings.mlr.press/v80/rabinowitz18a/rabinowitz18a.pdf)); a bolt-on head, cheaper than full CFR |

**Two pathologies of the cheap (determinization/PIMC) route that matter for us**, because they explain
why "just sample the opponent's hand and average" underachieves at bluffing:
- **Strategy fusion:** perfect-information search inside each determinization "cheats" by making
  different decisions in states the agent actually cannot distinguish — so it never *needs* to bluff
  (it acts as if it will know the future). This directly suppresses deceptive play.
- **Non-locality:** optimal payoffs in imperfect-info games are not recursively defined over subgames
  the way perfect-info search assumes.
Information Set MCTS (ISMCTS) was invented specifically to reduce these by searching *trees of
information sets* rather than states.

---

## 3. Mapping to Ultron — and our one structural advantage

### 3.1 The advantage poker doesn't have: a *known, shared* pool → computable beliefs
In poker the opponent's hand is drawn from a standard 52-card deck and the belief distribution must
be inferred purely from betting. **Battlebox is different and easier in exactly the right way:** every
card comes from one *known, shared* ~666-card pool, and Ultron has already observed the public
zones (graveyards, what's been cast, the shared-library remainder). So Ultron's belief over the
opponent's hidden hand is **directly computable as a sample from the known remaining pool**, updated
by public information. This is a determinization *freebie*: we can draw plausible opponent hands
without a learned card-inference model. (Poker engines would kill for this.) It makes the §4 belief
machinery dramatically cheaper for us than for the poker line of work.

### 3.2 The two latent spaces, in William's framing
- **"Action latent space = statistical know-how based on state" — yes, this is exactly what we train
  now.** `V(s)`'s hidden layers encode a correlational map: *board/hand/mana features → expected
  outcome*. It captures "what tends to win from here." This is real and valuable and is the correct
  foundation. It is **first-order**: about the world state.
- **"Theory of the game / theory of mind" — a genuinely different latent, and we don't train it.**
  This latent would have to encode a variable for *the opponent's mental state* — their belief over
  Ultron's hidden cards and their policy — and represent Ultron's own hidden information as something
  to be *strategically revealed or concealed*. It is **second-order**: about another agent's model of
  you. AlphaZero's latent space has been shown to spontaneously encode human strategic concepts
  ([Acquisition of Chess Knowledge in AlphaZero, McGrath et al.](https://arxiv.org/pdf/2111.09259)),
  so strategic structure *can* emerge in a value net — **but only over the information the net is
  given and the objective it optimizes.** A self-perspective value net trained on outcomes has no
  opponent-belief variable in its input and no deception term in its loss, so no amount of training
  makes that second-order latent appear. It must be *engineered in*.

### 3.3 Why the instant-speed logging William already pushed for is a bluffing *prerequisite*
Bluffing in Magic lives almost entirely at **instant-speed decision points**: holding up untapped
mana to *represent* a counterspell you may not have; attacking into open mana to *represent* that you
have the trick; passing to *represent* weakness. Our value net could not even *see* these moments
until the priority-window logging landed — the old MAIN1-only capture threw away the entire surface
on which bluffing happens. So that change is not just tactical polish; **it is the observable-decision
substrate any future belief/ToM model has to operate over.** Without it, bluffing is unrepresentable;
with it, it becomes *possible to learn later*. Good instinct, load-bearing for this whole direction.

---

## 4. A tiered, honest path (matched to our compute reality)

Ordered by cost. Each tier is independently valuable; none requires the next.

**Tier 0 — where we are: statistical afterstate value.** `V(s)`, argmax policy. Strong at "what wins
from here" vs a fixed opponent. No belief, no mixing, no deception. Correct foundation; keep it.

**Tier 1 — stochastic policy + self-play (cheap; the minimal precursor to emergent bluffing).**
Two changes, both within reach of the iteration loop we're already building:
1. **A stochastic policy, not a temperature hack.** Add a policy head (or a Boltzmann policy over
   afterstate values) whose mixing is *learned*, and crucially **train it in self-play against
   opponents that respond to observable actions** (hold-up-mana → opponent plays around it). When the
   opponent reacts to what Ultron *represents*, deception becomes +EV and the mixing frequencies get
   reinforced toward calibrated values. This is where *emergent, low-frequency bluffing* first
   appears — the same way it emerged in poker self-play without anyone coding "bluff."
2. **A diverse opponent population (league play), not a single opponent.** Optimizing mixing against
   one fixed opponent overfits to *that* opponent's readable responses — it looks like bluffing but a
   smarter opponent breaks it. A population (Default + prior Ultron bests + self) approximates the
   robustness an equilibrium would give, à la AlphaStar. Our plan §5.3 already calls for mixed
   populations — this is the deeper reason it matters.
**Honest caveat:** Tier 1 yields *emergent, opponent-specific* deception, not certified-unexploitable
equilibrium bluffing. That's fine and is a real step up from a readable argmax bot.

**Tier 2 — explicit belief states via determinization (moderate; exploits our known-pool advantage).**
Represent Ultron's belief over the opponent's hidden hand as **K sampled hands** from the known
shared-pool remainder (§3.1), consistent with public info. Score an action by **averaging value over
the K determinizations** — and, for the deception term, evaluate how the action's *observable*
consequence shifts the opponent's plausible responses. Use **ISMCTS-style information-set search**
(proven on MTG) rather than naive PIMC, to avoid the strategy-fusion pathology that specifically
kills bluffing (§2). **Cost warning grounded in this project's own scars:** determinization multiplies
simulation, and we have just spent an enormous effort taming `GameCopier`'s cost (the "monster"). Any
belief-sampling search must be built on the *cheap* evaluation path (neural value, pruned hidden
info) and hard per-decision budgets, or it re-summons the OOM/hang dragon. K=2–3, one-ply opponent
response — not a full ISMCTS.

**Tier 3 — an explicit opponent model / ToM head (the "theory of mind" made concrete).**
Train a second network (ToMnet-style) that predicts the *opponent's* action distribution given the
observable state (and, later, given a hypothesized opponent hand). Ultron then plans:
*"if I take observable action X, the opponent model predicts response distribution Y; averaged over my
belief about their hand, X is +EV even though it looks −EV at face value — because it makes them
misplay."* **Bluffing = deliberately choosing X to move the opponent model's predicted Y in my
favor.** This is the most direct implementation of theory of mind, and it is a *bolt-on head* far
cheaper than full CFR — especially with our computable beliefs feeding it.

**Tier 4 — equilibrium-grade bluffing (aspirational): Deep CFR / ReBeL-style belief-state value net.**
Replace "predict outcome" with "predict counterfactual value at a public belief state" and search over
belief states. This is the certified-unexploitable version and the true endpoint — but it is a
research program, likely beyond this project's compute/scope, and listed for completeness and honesty
about where the ceiling is.

---

## 5. Concrete near-term recommendations (what to actually do, and when)

1. **Do NOT change course now.** V1 (multi-phase + instant-speed value net) is the right next
   milestone and a prerequisite for everything above. Ship it, gate it, learn whether fixing the
   observability blind spots moves 25%.
2. **When the iteration loop is running (Tier 1 becomes cheap), add a stochastic policy head and turn
   on self-play with a mixed opponent population** — this is the single highest-leverage step toward
   *any* deceptive play, and it rides on infrastructure we're already building. Measure exploitability
   crudely (does a best-response opponent's win rate against Ultron drop as mixing is added?).
3. **Bank the known-pool belief advantage as a first-class asset.** Before building Tier 2/3, add a
   cheap "opponent plausible-hand sampler" from the shared-pool remainder — it is useful on its own
   (better combat/interaction decisions) and is the substrate for belief-state work.
4. **Treat Tier 2+ as gated by the cost discipline this project learned the hard way.** Belief search =
   more simulation; only build it on the neural-eval + hidden-info-pruned + hard-budget path.
5. **Set expectations honestly:** equilibrium-grade, unexploitable bluffing (Tier 4) is a research
   program, not a sprint. Emergent, opponent-adaptive bluffing (Tier 1) is genuinely reachable within
   the architecture we're building. The gap between "readable bot" and "occasionally bluffs" is
   Tier 1; the gap from there to "unbluffable and unexploitable" is Tiers 2–4.

---

## 6. Direct answers to William's framing

- **"Learning to bluff requires theory of mind of an opponent, or at least some withheld information
  by Ultron of whether or not to bluff and under what circumstances."** Correct, and both halves are
  real requirements: theory of mind = §1.2's opponent-belief variable (Tiers 3–4); "withheld
  information / whether to bluff" = the stochastic mixed strategy of §1.3 and Tier 1. You need *both*
  the belief model (to know *when* a bluff works) and the mixing (to make it *unreadable*).
- **"Temperature, theory of mind, and understanding withheld information."** Temperature → §1.3
  (necessary, insufficient; needs calibrated frequencies from self-play). Theory of mind → §1.2 +
  Tier 3. Withheld information → the belief state, §3.1, and our known-pool advantage that makes it
  cheap.
- **"Action latent space = statistical know-how based on state, which is what we train now; theory of
  the game/mind is a different latent structure of what and why."** Exactly right, and the key
  insight of this whole note: the second latent is **second-order (about the opponent's model of
  you)**, is **not present in a self-perspective outcome-trained value net**, and must be engineered
  in via belief representation + opponent model + a deception-rewarding (self-play/equilibrium)
  objective + a stochastic policy. It will not emerge from scaling the first.

---

## Sources
- [ReBeL — Combining Deep RL and Search for Imperfect-Information Games (Brown et al. 2020)](https://arxiv.org/pdf/2007.13544)
- [DeepStack — Expert-Level AI in Heads-Up No-Limit Poker (Moravčík et al. 2017)](https://arxiv.org/pdf/1701.01724)
- [Deep Counterfactual Regret Minimization (Brown et al. 2018)](https://arxiv.org/pdf/1811.00164)
- [Machine Theory of Mind / ToMnet (Rabinowitz et al. 2018)](https://proceedings.mlr.press/v80/rabinowitz18a/rabinowitz18a.pdf)
- [Information Set Monte Carlo Tree Search (Cowling, Powley, Whitehouse 2012) — proven on MTG](https://eprints.whiterose.ac.uk/id/eprint/75048/1/CowlingPowleyWhitehouse2012.pdf)
- [Reevaluating Policy Gradient Methods for Imperfect-Information Games (2025) — deterministic policies are exploitable](https://arxiv.org/html/2502.08938)
- [Acquisition of Chess Knowledge in AlphaZero (McGrath et al.) — strategic concepts in a value net's latent space](https://arxiv.org/pdf/2111.09259)
- [Nash equilibrium strategy solving in two-player imperfect-information games: a survey (2026)](https://link.springer.com/article/10.1007/s10462-026-11533-6)
- [Theory of Mind as Intrinsic Motivation for Multi-Agent RL (2023)](https://arxiv.org/pdf/2307.01158)
