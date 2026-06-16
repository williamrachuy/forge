package forge.ai.llm.runtime;

import forge.ai.AiCardMemory;
import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.List;

/**
 * Lightweight context snapshot for a single runtime decision point.
 * Built from Forge objects directly — no LLM, no HTTP, no large serialization.
 */
public final class UltronDecisionContext {

    public final Game game;
    public final Player player;
    public final AiCardMemory memory;
    public final List<SpellAbility> candidates;
    public final PhaseType phase;
    public final Player activePlayer;
    public final boolean isPlayerTurn;
    public final boolean stackEmpty;
    public final SpellAbility topStackAbility;    // null if stack empty
    public final boolean topStackControlledBySelf;
    public final UltronTableThreatSummary table;
    public final UltronTurnIntent intent;
    public final long deadlineNanos;

    public UltronDecisionContext(Game game,
                                  Player player,
                                  AiCardMemory memory,
                                  List<SpellAbility> candidates,
                                  UltronTableThreatSummary table,
                                  UltronTurnIntent intent,
                                  long deadlineNanos) {
        this.game = game;
        this.player = player;
        this.memory = memory;
        this.candidates = candidates != null ? candidates : List.of();
        this.phase = game.getPhaseHandler().getPhase();
        this.activePlayer = game.getPhaseHandler().getPlayerTurn();
        this.isPlayerTurn = player.equals(activePlayer);
        this.stackEmpty = game.getStack().isEmpty();
        this.topStackAbility = stackEmpty ? null : game.getStack().peekAbility();
        this.topStackControlledBySelf = topStackAbility != null
                && player.equals(topStackAbility.getActivatingPlayer());
        this.table = table;
        this.intent = intent;
        this.deadlineNanos = deadlineNanos;
    }

    /** True if the decision deadline has been exceeded. */
    public boolean isOverDeadline() {
        return System.nanoTime() > deadlineNanos;
    }
}
