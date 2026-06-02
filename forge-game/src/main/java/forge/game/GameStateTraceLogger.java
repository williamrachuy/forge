package forge.game;

import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.Event;
import forge.game.event.GameEvent;
import forge.game.event.GameEventAddLog;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCardAttachment;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventCardCounters;
import forge.game.event.GameEventCardDamaged;
import forge.game.event.GameEventCardForetold;
import forge.game.event.GameEventCardModeChosen;
import forge.game.event.GameEventCardPhased;
import forge.game.event.GameEventCardPlotted;
import forge.game.event.GameEventCardRegenerated;
import forge.game.event.GameEventCardSacrificed;
import forge.game.event.GameEventCardStatsChanged;
import forge.game.event.GameEventCardTapped;
import forge.game.event.GameEventCombatEnded;
import forge.game.event.GameEventCombatUpdate;
import forge.game.event.GameEventDayTimeChanged;
import forge.game.event.GameEventDoorChanged;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventGameOutcome;
import forge.game.event.GameEventGameRestarted;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventManaBurn;
import forge.game.event.GameEventManaPool;
import forge.game.event.GameEventMulligan;
import forge.game.event.GameEventPlayerControl;
import forge.game.event.GameEventPlayerCounters;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventPlayerLivesChanged;
import forge.game.event.GameEventPlayerPoisoned;
import forge.game.event.GameEventPlayerPriority;
import forge.game.event.GameEventPlayerRadiation;
import forge.game.event.GameEventPlayerShardsChanged;
import forge.game.event.GameEventPlayerStatsChanged;
import forge.game.event.GameEventRandomLog;
import forge.game.event.GameEventScry;
import forge.game.event.GameEventShuffle;
import forge.game.event.GameEventSnapshotRestored;
import forge.game.event.GameEventSpeedChanged;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventSpellRemovedFromStack;
import forge.game.event.GameEventSpellResolved;
import forge.game.event.GameEventSprocketUpdate;
import forge.game.event.GameEventSubgameEnd;
import forge.game.event.GameEventSubgameStart;
import forge.game.event.GameEventSurveil;
import forge.game.event.GameEventTurnBegan;
import forge.game.event.GameEventTurnEnded;
import forge.game.event.GameEventTurnPhase;
import forge.game.event.GameEventZone;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.SpellAbilityView;
import forge.game.spellability.StackItemView;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

final class GameStateTraceLogger {
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final DateTimeFormatter LOG_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final int MAX_ZONE_CARDS = Integer.getInteger("forge.deepGameTrace.maxZoneCards", 40);
    private static final ZoneType[] CHECKPOINT_ZONES = {
            ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command
    };

    private final Game game;
    private final Path file;
    private final BufferedWriter writer;

    private int deltaIndex;
    private int checkpointTurn = Integer.MIN_VALUE;
    private Player checkpointPlayer;

    private GameStateTraceLogger(final Game game0, final Path file0, final BufferedWriter writer0) {
        game = game0;
        file = file0;
        writer = writer0;
        writeHeader();
    }

    static GameStateTraceLogger create(final Game game) {
        if (!isEnabled()) {
            return null;
        }
        try {
            final Path dir = getTraceDirectory();
            Files.createDirectories(dir);
            final Path file = dir.resolve(FILE_TIMESTAMP.format(LocalDateTime.now()) + "-game-" + game.getId() + ".log");
            return new GameStateTraceLogger(game, file, Files.newBufferedWriter(file, StandardCharsets.UTF_8));
        } catch (final IOException ex) {
            System.err.println("Unable to create Forge deep game trace: " + ex.getMessage());
            return null;
        }
    }

    void event(final Event event) {
        if (!(event instanceof GameEvent gameEvent)) {
            trace("event " + event.getClass().getSimpleName() + " " + safe(event));
            return;
        }

        if (gameEvent instanceof GameEventTurnBegan) {
            writeTurnCheckpoint("turn-began");
            return;
        }

        maybeWriteTurnCheckpoint();
        writeDelta(gameEvent);
    }

    void trace(final String message) {
        maybeWriteTurnCheckpoint();
        writeStamped("note " + context() + " message=" + quote(message));
    }

    private void writeHeader() {
        writeRaw("# Forge Deep Game Trace");
        writeRaw("");
        writeRaw("trace_file=" + file);
        writeRaw("game_id=" + game.getId());
        writeRaw("title=" + quote(game.getMatch().getTitle()));
        writeRaw("format=" + game.getRules().getGameType());
        writeRaw("players=" + playerList());
        writeRaw("mode=turn-checkpoint-plus-deltas");
        writeRaw("");
        flush();
    }

    private void maybeWriteTurnCheckpoint() {
        final PhaseHandler phase = game.getPhaseHandler();
        final Player turnPlayer = phase.getPlayerTurn();
        if (turnPlayer == null) {
            return;
        }
        if (checkpointPlayer == turnPlayer && checkpointTurn == phase.getTurn()) {
            return;
        }
        writeTurnCheckpoint("implicit-turn-start");
    }

    private void writeTurnCheckpoint(final String reason) {
        final PhaseHandler phase = game.getPhaseHandler();
        final Player turnPlayer = phase.getPlayerTurn();
        if (turnPlayer == null) {
            return;
        }

        checkpointPlayer = turnPlayer;
        checkpointTurn = phase.getTurn();

        writeRaw("");
        writeRaw("## Turn " + phase.getTurn() + " - " + turnPlayer);
        writeStamped("checkpoint reason=" + reason + " " + context());
        writeRaw(fullStateCheckpoint());
        flush();
    }

    private void writeDelta(final GameEvent event) {
        final Delta delta = describe(event);
        final String line = "delta#" + String.format("%06d", ++deltaIndex)
                + " " + context()
                + " scope=" + delta.scope
                + " actor=" + quote(delta.actor)
                + " event=" + event.getClass().getSimpleName()
                + " summary=" + quote(delta.summary);
        writeStamped(line);
        for (final String detail : delta.details) {
            writeRaw("  " + detail);
        }
        flush();
    }

    private String fullStateCheckpoint() {
        final StringBuilder sb = new StringBuilder();
        final PhaseHandler phase = game.getPhaseHandler();
        sb.append("  game: turn=").append(phase.getTurn())
                .append(" phase=").append(phase.getPhase())
                .append(" playerTurn=").append(phase.getPlayerTurn())
                .append(" priority=").append(phase.getPriorityPlayer())
                .append(" monarch=").append(game.getMonarch())
                .append(" battleboxMonarchChoiceMade=").append(game.isBattleboxMonarchChoiceMade())
                .append(" battleboxMonarchEnabled=").append(game.isBattleboxMonarchEnabled());
        sb.append('\n').append("  stack: ").append(stackSummary());

        for (final Player player : game.getPlayers()) {
            sb.append('\n').append("  player ").append(player).append(": ")
                    .append("life=").append(player.getLife())
                    .append(" counters=").append(counterSummary(player.getCounters()))
                    .append(" mana=").append(manaSummary(player))
                    .append(" hand=").append(player.getZone(ZoneType.Hand).size())
                    .append(" library=").append(player.getZone(ZoneType.Library).size())
                    .append(" landsPlayed=").append(player.getLandsPlayedThisTurn()).append('/').append(player.getMaxLandPlays())
                    .append(" maxHand=").append(player.getMaxHandSize())
                    .append(" speed=").append(player.getSpeed())
                    .append(" blessing=").append(player.hasBlessing());

            for (final ZoneType zone : CHECKPOINT_ZONES) {
                sb.append('\n')
                        .append("    ").append(zone).append(": ")
                        .append(zoneCards(player, zone, zone == ZoneType.Battlefield));
            }
        }
        return sb.toString();
    }

    private String stackSummary() {
        final StringJoiner joiner = new StringJoiner(", ", "[", "]");
        for (final SpellAbilityStackInstance si : game.getStack()) {
            joiner.add(si.toString());
        }
        return joiner.toString();
    }

    private String zoneCards(final Player player, final ZoneType zone, final boolean detailed) {
        final StringJoiner joiner = new StringJoiner(", ", "[", "]");
        int count = 0;
        for (final Card card : player.getZone(zone)) {
            if (count++ >= MAX_ZONE_CARDS) {
                joiner.add("...+" + (player.getZone(zone).size() - MAX_ZONE_CARDS));
                break;
            }
            joiner.add(detailed ? cardDetail(card) : cardRef(card));
        }
        return joiner.toString();
    }

    private String cardDetail(final Card card) {
        final StringBuilder sb = new StringBuilder(cardRef(card));
        sb.append("{owner=").append(card.getOwner())
                .append(",controller=").append(card.getController());
        final Zone zone = card.getZone();
        if (zone != null) {
            sb.append(",zone=").append(zone.getZoneType());
        }
        if (card.isTapped()) {
            sb.append(",tapped=true");
        }
        if (card.isCreature()) {
            sb.append(",pt=").append(card.getNetPower()).append('/').append(card.getNetToughness());
        }
        if (card.getDamage() > 0) {
            sb.append(",damage=").append(card.getDamage());
        }
        if (card.hasSickness()) {
            sb.append(",summoningSick=true");
        }
        final String counters = counterSummary(card.getCounters());
        if (!"{}".equals(counters)) {
            sb.append(",counters=").append(counters);
        }
        sb.append('}');
        return sb.toString();
    }

    private static String cardRef(final Card card) {
        if (card == null) {
            return "null";
        }
        return card.getName() + "#" + card.getId();
    }

    private static String cardRef(final CardView card) {
        if (card == null) {
            return "null";
        }
        return card.getName() + "#" + card.getId();
    }

    private static String cardViewDetail(final CardView card) {
        if (card == null) {
            return "null";
        }
        final StringBuilder sb = new StringBuilder(cardRef(card));
        sb.append("{owner=").append(name(card.getOwner()))
                .append(",controller=").append(name(card.getController()))
                .append(",zone=").append(card.getZone());
        if (card.isTapped()) {
            sb.append(",tapped=true");
        }
        if (card.getCurrentState() != null && card.getCurrentState().isCreature()) {
            sb.append(",pt=").append(card.getCurrentState().getPower()).append('/').append(card.getCurrentState().getToughness());
        }
        if (card.getDamage() > 0) {
            sb.append(",damage=").append(card.getDamage());
        }
        final String counters = counterSummary(card.getCounters());
        if (!"{}".equals(counters)) {
            sb.append(",counters=").append(counters);
        }
        sb.append('}');
        return sb.toString();
    }

    private static String cardsSummary(final Collection<CardView> cards) {
        if (cards == null) {
            return "[]";
        }
        final StringJoiner joiner = new StringJoiner(", ", "[", "]");
        int count = 0;
        for (final CardView card : cards) {
            if (count++ >= MAX_ZONE_CARDS) {
                joiner.add("...+" + (cards.size() - MAX_ZONE_CARDS));
                break;
            }
            joiner.add(cardViewDetail(card));
        }
        return joiner.toString();
    }

    private String manaSummary(final Player player) {
        final StringJoiner joiner = new StringJoiner(",", "{", "}");
        for (final byte color : ManaAtom.MANATYPES) {
            final int amount = player.getManaPool().getAmountOfColor(color);
            if (amount > 0) {
                joiner.add(MagicColor.toShortString(color) + "=" + amount);
            }
        }
        return joiner.toString();
    }

    private String context() {
        final PhaseHandler phase = game.getPhaseHandler();
        return "turn=" + phase.getTurn()
                + " playerTurn=" + quote(name(phase.getPlayerTurn()))
                + " phase=" + phase.getPhase()
                + " priority=" + quote(name(phase.getPriorityPlayer()));
    }

    private String playerList() {
        final StringJoiner joiner = new StringJoiner(", ", "[", "]");
        for (final Player player : game.getPlayers()) {
            joiner.add(player.toString());
        }
        return joiner.toString();
    }

    private Delta describe(final GameEvent event) {
        final Delta delta = new Delta();
        delta.scope = "game";
        delta.actor = "game";
        delta.summary = safe(event);
        delta.detail("raw", safe(event));

        if (event instanceof GameEventAddLog e) {
            delta.scope = "log";
            delta.actor = actor(e.sourceCard());
            delta.summary = e.message();
            delta.detail("logType", e.type());
            delta.detail("source", cardViewDetail(e.sourceCard()));
        } else if (event instanceof GameEventAttackersDeclared e) {
            delta.scope = "combat";
            delta.actor = name(e.player());
            delta.detail("attackers", safe(e.attackersMap()));
        } else if (event instanceof GameEventBlockersDeclared e) {
            delta.scope = "combat";
            delta.actor = name(e.defendingPlayer());
            delta.detail("blockers", safe(e.blockers()));
        } else if (event instanceof GameEventCardAttachment e) {
            delta.scope = "card";
            delta.actor = actor(e.equipment());
            delta.detail("equipment", cardViewDetail(e.equipment()));
            delta.detail("oldEntity", safe(e.oldEntity()));
            delta.detail("newTarget", safe(e.newTarget()));
        } else if (event instanceof GameEventCardChangeZone e) {
            delta.scope = "zone";
            delta.actor = actor(e.card());
            delta.summary = cardRef(e.card()) + " moved " + zoneRef(e.from()) + " -> " + zoneRef(e.to());
            delta.detail("card", cardViewDetail(e.card()));
            delta.detail("from", zoneRef(e.from()));
            delta.detail("to", zoneRef(e.to()));
        } else if (event instanceof GameEventCardCounters e) {
            delta.scope = "card";
            delta.actor = actor(e.card());
            delta.summary = cardRef(e.card()) + " " + e.type() + " counters " + e.oldValue() + " -> " + e.newValue();
            delta.detail("card", cardViewDetail(e.card()));
            delta.detail("counter", e.type());
            delta.detail("oldValue", e.oldValue());
            delta.detail("newValue", e.newValue());
        } else if (event instanceof GameEventCardDamaged e) {
            delta.scope = "damage";
            delta.actor = actor(e.source());
            delta.summary = cardRef(e.source()) + " dealt " + e.amount() + " " + e.type() + " damage to " + cardRef(e.card());
            delta.detail("target", cardViewDetail(e.card()));
            delta.detail("source", cardViewDetail(e.source()));
            delta.detail("amount", e.amount());
            delta.detail("damageType", e.type());
        } else if (event instanceof GameEventCardForetold e) {
            delta.scope = "player";
            delta.actor = name(e.activatingPlayer());
        } else if (event instanceof GameEventCardModeChosen e) {
            delta.scope = "choice";
            delta.actor = name(e.player());
            delta.detail("cardName", e.cardName());
            delta.detail("mode", e.mode());
            delta.detail("random", e.random());
        } else if (event instanceof GameEventCardPhased e) {
            delta.scope = "card";
            delta.actor = actor(e.card());
            delta.detail("card", cardViewDetail(e.card()));
            delta.detail("phasedOut", e.phaseState());
        } else if (event instanceof GameEventCardPlotted e) {
            delta.scope = "player";
            delta.actor = name(e.activatingPlayer());
            delta.detail("card", cardViewDetail(e.card()));
        } else if (event instanceof GameEventCardRegenerated e) {
            delta.scope = "card";
            delta.actor = actor(firstCard(e.cards()));
            delta.detail("cards", cardsSummary(e.cards()));
        } else if (event instanceof GameEventCardSacrificed e) {
            delta.scope = "zone";
            delta.actor = actor(e.card());
            delta.detail("card", cardViewDetail(e.card()));
        } else if (event instanceof GameEventCardStatsChanged e) {
            delta.scope = "card";
            delta.actor = actor(firstCard(e.cards()));
            delta.detail("transform", e.transform());
            delta.detail("cards", cardsSummary(e.cards()));
        } else if (event instanceof GameEventCardTapped e) {
            delta.scope = "card";
            delta.actor = actor(e.card());
            delta.summary = cardRef(e.card()) + (e.tapped() ? " tapped" : " untapped");
            delta.detail("card", cardViewDetail(e.card()));
            delta.detail("tapped", e.tapped());
        } else if (event instanceof GameEventCombatEnded e) {
            delta.scope = "combat";
            delta.actor = name(game.getPhaseHandler().getPlayerTurn());
            delta.detail("attackers", cardsSummary(e.attackers()));
            delta.detail("blockers", cardsSummary(e.blockers()));
        } else if (event instanceof GameEventCombatUpdate e) {
            delta.scope = "combat";
            delta.actor = name(game.getPhaseHandler().getPlayerTurn());
            delta.detail("attackers", cardsSummary(e.attackers()));
            delta.detail("blockers", cardsSummary(e.blockers()));
        } else if (event instanceof GameEventDayTimeChanged e) {
            delta.scope = "game";
            delta.detail("daytime", e.daytime());
        } else if (event instanceof GameEventDoorChanged e) {
            delta.scope = "card";
            delta.actor = name(e.activatingPlayer());
            delta.detail("card", cardViewDetail(e.card()));
            delta.detail("roomState", e.state());
            delta.detail("unlock", e.unlock());
        } else if (event instanceof GameEventGameFinished) {
            delta.scope = "game";
            delta.summary = "Game finished";
        } else if (event instanceof GameEventGameOutcome e) {
            delta.scope = "game";
            delta.summary = "Game outcome winner=" + e.winningPlayerName();
            delta.detail("lastTurnNumber", e.lastTurnNumber());
            delta.detail("winningPlayer", e.winningPlayerName());
            delta.detail("matchSummary", e.matchSummary());
            delta.detail("outcomes", safe(e.outcomeStrings()));
        } else if (event instanceof GameEventGameRestarted e) {
            delta.scope = "game";
            delta.actor = name(e.whoRestarted());
        } else if (event instanceof GameEventGameStarted e) {
            delta.scope = "game";
            delta.summary = e.gameType() + " game started; firstTurn=" + name(e.firstTurn());
            delta.detail("firstTurn", name(e.firstTurn()));
            delta.detail("players", safe(e.players()));
        } else if (event instanceof GameEventLandPlayed e) {
            delta.scope = "zone";
            delta.actor = name(e.player());
            delta.summary = name(e.player()) + " played land " + cardRef(e.land());
            delta.detail("land", cardViewDetail(e.land()));
        } else if (event instanceof GameEventManaBurn e) {
            delta.scope = "mana";
            delta.actor = name(e.player());
            delta.detail("causedLifeLoss", e.causedLifeLoss());
            delta.detail("amount", e.amount());
        } else if (event instanceof GameEventManaPool e) {
            delta.scope = "mana";
            delta.actor = name(e.player());
            delta.summary = name(e.player()) + " mana pool " + e.mode() + " " + manaName(e.manaColor());
            delta.detail("mode", e.mode());
            delta.detail("manaColor", manaName(e.manaColor()));
        } else if (event instanceof GameEventMulligan e) {
            delta.scope = "player";
            delta.actor = name(e.player());
        } else if (event instanceof GameEventPlayerControl e) {
            delta.scope = "player";
            delta.actor = name(e.player());
            delta.detail("newLobbyPlayerName", e.newLobbyPlayerName());
            delta.detail("newControllerIsHuman", e.newControllerIsHuman());
        } else if (event instanceof GameEventPlayerCounters e) {
            delta.scope = "player";
            delta.actor = name(e.receiver());
            delta.summary = name(e.receiver()) + " " + e.type() + " counters " + e.oldValue() + " plus " + e.amount();
            delta.detail("counter", e.type());
            delta.detail("oldValue", e.oldValue());
            delta.detail("amount", e.amount());
        } else if (event instanceof GameEventPlayerDamaged e) {
            delta.scope = "damage";
            delta.actor = actor(e.source());
            delta.summary = name(e.target()) + " took " + e.amount()
                    + (e.combat() ? " combat" : "") + (e.infect() ? " infect" : "") + " damage from " + cardRef(e.source());
            delta.detail("target", name(e.target()));
            delta.detail("source", cardViewDetail(e.source()));
            delta.detail("amount", e.amount());
            delta.detail("combat", e.combat());
            delta.detail("infect", e.infect());
        } else if (event instanceof GameEventPlayerLivesChanged e) {
            delta.scope = "player";
            delta.actor = name(e.player());
            delta.summary = name(e.player()) + " lives " + e.oldLives() + " -> " + e.newLives();
            delta.detail("oldLives", e.oldLives());
            delta.detail("newLives", e.newLives());
        } else if (event instanceof GameEventPlayerPoisoned e) {
            delta.scope = "player";
            delta.actor = name(e.source());
            delta.summary = name(e.receiver()) + " poison " + e.oldValue() + " plus " + e.amount();
            delta.detail("receiver", name(e.receiver()));
            delta.detail("source", name(e.source()));
            delta.detail("oldValue", e.oldValue());
            delta.detail("amount", e.amount());
        } else if (event instanceof GameEventPlayerPriority e) {
            delta.scope = "priority";
            delta.actor = name(e.priority());
            delta.summary = "Priority to " + name(e.priority());
            delta.detail("turn", name(e.turn()));
            delta.detail("phase", e.phase());
            delta.detail("priority", name(e.priority()));
        } else if (event instanceof GameEventPlayerRadiation e) {
            delta.scope = "player";
            delta.actor = name(e.source());
            delta.detail("receiver", name(e.receiver()));
            delta.detail("source", name(e.source()));
            delta.detail("change", e.change());
        } else if (event instanceof GameEventPlayerShardsChanged e) {
            delta.scope = "player";
            delta.actor = name(e.player());
            delta.summary = name(e.player()) + " shards " + e.oldShards() + " -> " + e.newShards();
            delta.detail("oldShards", e.oldShards());
            delta.detail("newShards", e.newShards());
        } else if (event instanceof GameEventPlayerStatsChanged e) {
            delta.scope = "player";
            delta.detail("players", safe(e.players()));
            delta.detail("updateCards", e.updateCards());
            if (e.updateCards()) {
                delta.detail("cardUpdateCount", e.allCards() == null ? 0 : e.allCards().size());
            }
        } else if (event instanceof GameEventRandomLog e) {
            delta.scope = "log";
            delta.summary = e.message();
        } else if (event instanceof GameEventScry e) {
            delta.scope = "zone";
            delta.actor = name(e.player());
            delta.detail("toTop", e.toTop());
            delta.detail("toBottom", e.toBottom());
        } else if (event instanceof GameEventShuffle e) {
            delta.scope = "zone";
            delta.actor = name(e.player());
        } else if (event instanceof GameEventSnapshotRestored e) {
            delta.scope = "game";
            delta.detail("start", e.start());
        } else if (event instanceof GameEventSpeedChanged e) {
            delta.scope = "player";
            delta.actor = name(e.player());
            delta.summary = name(e.player()) + " speed " + e.oldValue() + " -> " + e.newValue();
            delta.detail("oldValue", e.oldValue());
            delta.detail("newValue", e.newValue());
        } else if (event instanceof GameEventSpellAbilityCast e) {
            delta.scope = "stack";
            delta.actor = actor(e.si());
            delta.summary = actor(e.si()) + " put " + stackItemSummary(e.si()) + " on stack";
            delta.detail("stackIndex", e.stackIndex());
            delta.detail("source", cardViewDetail(e.si() == null ? null : e.si().getSourceCard()));
            delta.detail("targets", e.targetDescription());
            delta.detail("ability", safe(e.sa()));
        } else if (event instanceof GameEventSpellRemovedFromStack e) {
            delta.scope = "stack";
            delta.actor = actor(e.sa());
            delta.detail("ability", safe(e.sa()));
        } else if (event instanceof GameEventSpellResolved e) {
            delta.scope = "stack";
            delta.actor = actor(e.spell());
            delta.summary = "Resolved " + safe(e.spell()) + (e.hasFizzled() ? " (fizzled)" : "");
            delta.detail("spell", safe(e.spell()));
            delta.detail("fizzled", e.hasFizzled());
            delta.detail("stackDescription", e.stackDescription());
        } else if (event instanceof GameEventSprocketUpdate e) {
            delta.scope = "card";
            delta.actor = actor(e.contraption());
            delta.detail("contraption", cardViewDetail(e.contraption()));
            delta.detail("oldSprocket", e.oldSprocket());
            delta.detail("sprocket", e.sprocket());
        } else if (event instanceof GameEventSubgameEnd e) {
            delta.scope = "game";
            delta.detail("maingame", e.maingame() == null ? null : e.maingame().getId());
            delta.detail("message", e.message());
        } else if (event instanceof GameEventSubgameStart e) {
            delta.scope = "game";
            delta.detail("subgame", e.subgame() == null ? null : e.subgame().getId());
            delta.detail("message", e.message());
        } else if (event instanceof GameEventSurveil e) {
            delta.scope = "zone";
            delta.actor = name(e.player());
            delta.detail("toLibrary", e.toLibrary());
            delta.detail("toGraveyard", e.toGraveyard());
        } else if (event instanceof GameEventTurnEnded) {
            delta.scope = "turn";
            delta.actor = name(game.getPhaseHandler().getPlayerTurn());
            delta.summary = "Turn ended";
        } else if (event instanceof GameEventTurnPhase e) {
            delta.scope = "phase";
            delta.actor = name(e.playerTurn());
            delta.summary = name(e.playerTurn()) + " " + e.phaseDesc() + e.phase().nameForUi + " phase";
            delta.detail("playerTurn", name(e.playerTurn()));
            delta.detail("phase", e.phase());
            delta.detail("phaseDesc", e.phaseDesc());
        } else if (event instanceof GameEventZone e) {
            delta.scope = "zone";
            delta.actor = name(e.player());
            delta.summary = zoneRef(e.player(), e.zoneType()) + " " + e.mode()
                    + (e.card() == null ? "" : " " + cardRef(e.card()));
            delta.detail("zone", zoneRef(e.player(), e.zoneType()));
            delta.detail("mode", e.mode());
            delta.detail("card", cardViewDetail(e.card()));
            delta.detail("ability", safe(e.sa()));
        }

        return delta;
    }

    private static CardView firstCard(final Collection<CardView> cards) {
        if (cards == null || cards.isEmpty()) {
            return null;
        }
        return cards.iterator().next();
    }

    private static String stackItemSummary(final StackItemView stackItem) {
        if (stackItem == null) {
            return "null";
        }
        final String text = stackItem.getText();
        return text == null || text.isBlank() ? safe(stackItem) : text;
    }

    private static String actor(final StackItemView stackItem) {
        return stackItem == null ? "game" : name(stackItem.getActivatingPlayer());
    }

    private static String actor(final SpellAbilityView sa) {
        if (sa == null || sa.getHostCard() == null) {
            return "game";
        }
        return actor(sa.getHostCard());
    }

    private static String actor(final CardView card) {
        if (card == null) {
            return "game";
        }
        final PlayerView controller = card.getController();
        if (controller != null) {
            return name(controller);
        }
        return name(card.getOwner());
    }

    private static String zoneRef(final ZoneView zone) {
        if (zone == null) {
            return "null";
        }
        return zoneRef(zone.player(), zone.zoneType());
    }

    private static String zoneRef(final PlayerView player, final ZoneType zoneType) {
        return name(player) + "." + zoneType;
    }

    private static String manaName(final byte color) {
        if (color == 0) {
            return "generic";
        }
        return MagicColor.toShortString(color);
    }

    private static String name(final Player player) {
        return player == null ? "none" : player.toString();
    }

    private static String name(final PlayerView player) {
        return player == null ? "none" : player.getName();
    }

    private static String counterSummary(final Map<?, Integer> counters) {
        if (counters == null || counters.isEmpty()) {
            return "{}";
        }
        final StringJoiner joiner = new StringJoiner(",", "{", "}");
        for (final Map.Entry<?, Integer> entry : counters.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                joiner.add(entry.getKey() + "=" + entry.getValue());
            }
        }
        return joiner.length() <= 2 ? "{}" : joiner.toString();
    }

    private static boolean isEnabled() {
        final String configured = System.getProperty("forge.deepGameTrace");
        if (configured != null && !configured.isBlank()) {
            return isTruthy(configured);
        }
        final String env = System.getenv("FORGE_DEEP_GAME_TRACE");
        if (env != null && !env.isBlank()) {
            return isTruthy(env);
        }
        return hasConfiguredTraceDirectory();
    }

    private static Path getTraceDirectory() {
        final String configured = System.getProperty("forge.deepGameTraceDir");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        final String env = System.getenv("FORGE_DEEP_GAME_TRACE_DIR");
        if (env != null && !env.isBlank()) {
            return Paths.get(env);
        }
        return Paths.get(System.getProperty("user.home"), ".forge", "deep-game-trace");
    }

    private static boolean isTruthy(final String value) {
        return value != null && ("1".equals(value.trim()) || "true".equalsIgnoreCase(value.trim())
                || "yes".equalsIgnoreCase(value.trim()) || "on".equalsIgnoreCase(value.trim()));
    }

    private static boolean hasConfiguredTraceDirectory() {
        final String configured = System.getProperty("forge.deepGameTraceDir");
        if (configured != null && !configured.isBlank()) {
            return true;
        }
        final String env = System.getenv("FORGE_DEEP_GAME_TRACE_DIR");
        return env != null && !env.isBlank();
    }

    private void writeStamped(final String message) {
        writeRaw(LOG_TIMESTAMP.format(LocalDateTime.now()) + " " + message);
    }

    private void writeRaw(final String message) {
        try {
            writer.write(message);
            writer.newLine();
        } catch (final IOException ex) {
            System.err.println("Unable to write Forge deep game trace: " + ex.getMessage());
        }
    }

    private void flush() {
        try {
            writer.flush();
        } catch (final IOException ex) {
            System.err.println("Unable to flush Forge deep game trace: " + ex.getMessage());
        }
    }

    private static String quote(final Object value) {
        return "\"" + safe(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") + "\"";
    }

    private static String safe(final Object value) {
        try {
            return String.valueOf(value);
        } catch (final RuntimeException ex) {
            return "<toString failed: " + ex.getClass().getSimpleName() + ">";
        }
    }

    private static final class Delta {
        private String scope;
        private String actor;
        private String summary;
        private final List<String> details = new ArrayList<>();

        private void detail(final String key, final Object value) {
            details.add(key + "=" + quote(value));
        }
    }
}
