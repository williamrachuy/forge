package forge.game.stats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Multimap;
import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.GameEntityView;
import forge.game.GameOutcome;
import forge.game.GameType;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.card.CounterEnumType;
import forge.game.event.GameEvent;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventDayTimeChanged;
import forge.game.event.GameEventGameOutcome;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventMulligan;
import forge.game.event.GameEventPlayerCounters;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventPlayerPoisoned;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTokenCreated;
import forge.game.event.GameEventTurnBegan;
import forge.game.event.GameEventTurnEnded;
import forge.game.event.GameEventTurnPhase;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;

public final class GameStatsCollector {
    private static final int NO_SEAT = -1;

    private final Game game;
    private final SimStatsGameContext context;
    private final boolean turnSnapshots;
    private final List<Player> players;
    private final Map<Integer, Integer> seatByPlayerId = new HashMap<>();
    private final PlayerTally[] tallies;
    private final int[][] attacks;
    private final int[][] combatDamage;
    private final int[][] nonCombatDamage;
    private final int[] monarchTurns;
    private final int[] initiativeTurns;
    private final List<Map<String, Object>> turnRecords = new ArrayList<>();
    private final List<Map<String, Object>> eliminations = new ArrayList<>();
    private final boolean[] eliminated;

    private TurnRecord currentTurn;
    private PhaseType currentPhase;
    private int totalPlayerTurns;
    private int lastMonarchSeat = NO_SEAT;
    private int lastInitiativeSeat = NO_SEAT;
    private int firstMonarchTurn = 0;
    private int firstInitiativeTurn = 0;
    private int monarchChanges;
    private int initiativeChanges;
    private boolean monarchObserved;
    private boolean initiativeObserved;
    private boolean dayNightObserved;
    private boolean poisonObserved;
    private boolean energyObserved;
    private boolean extraTurnsObserved;
    private boolean landStationObserved;
    private int tokensCreated;

    public GameStatsCollector(final Game game, final SimStatsGameContext context, final boolean turnSnapshots) {
        this.game = game;
        this.context = context;
        this.turnSnapshots = turnSnapshots;
        this.players = new ArrayList<>(game.getRegisteredPlayers());
        for (int i = 0; i < players.size(); i++) {
            seatByPlayerId.put(players.get(i).getId(), i);
        }
        this.tallies = new PlayerTally[players.size()];
        for (int i = 0; i < tallies.length; i++) {
            tallies[i] = new PlayerTally();
        }
        this.attacks = new int[players.size()][players.size()];
        this.combatDamage = new int[players.size()][players.size()];
        this.nonCombatDamage = new int[players.size()][players.size()];
        this.monarchTurns = new int[players.size()];
        this.initiativeTurns = new int[players.size()];
        this.eliminated = new boolean[players.size()];
    }

    @Subscribe
    public void receive(final GameEvent event) {
        detectHolderChanges();

        if (event instanceof GameEventTurnBegan turnBegan) {
            onTurnBegan(turnBegan);
        } else if (event instanceof GameEventTurnPhase turnPhase) {
            currentPhase = turnPhase.phase();
        } else if (event instanceof GameEventTurnEnded) {
            onTurnEnded();
        } else if (event instanceof GameEventAttackersDeclared attackersDeclared) {
            onAttackersDeclared(attackersDeclared);
        } else if (event instanceof GameEventBlockersDeclared blockersDeclared) {
            onBlockersDeclared(blockersDeclared);
        } else if (event instanceof GameEventPlayerDamaged playerDamaged) {
            onPlayerDamaged(playerDamaged);
        } else if (event instanceof GameEventSpellAbilityCast spellAbilityCast) {
            onSpellAbilityCast(spellAbilityCast);
        } else if (event instanceof GameEventLandPlayed landPlayed) {
            onLandPlayed(landPlayed);
        } else if (event instanceof GameEventCardChangeZone cardChangeZone) {
            onCardChangeZone(cardChangeZone);
        } else if (event instanceof GameEventTokenCreated) {
            tokensCreated++;
            if (currentTurn != null) {
                currentTurn.tokensCreated++;
            }
        } else if (event instanceof GameEventMulligan mulligan) {
            final int seat = seat(mulligan.player());
            if (seat >= 0) {
                tallies[seat].mulligans++;
            }
        } else if (event instanceof GameEventPlayerPoisoned) {
            poisonObserved = true;
        } else if (event instanceof GameEventPlayerCounters playerCounters) {
            onPlayerCounters(playerCounters);
        } else if (event instanceof GameEventDayTimeChanged) {
            dayNightObserved = true;
        } else if (event instanceof GameEventGameOutcome outcome) {
            totalPlayerTurns = Math.max(totalPlayerTurns, outcome.lastTurnNumber());
        }

        detectHolderChanges();
        detectEliminations();
    }

    public Map<String, Object> finish(final boolean completedNormally, final boolean timeout, final String error,
            final long elapsedMillis) {
        detectHolderChanges();
        detectEliminations();
        if (currentTurn != null && !currentTurn.finished) {
            finishCurrentTurn();
        }

        final GameOutcome outcome = game.getOutcome();
        if (outcome != null) {
            totalPlayerTurns = Math.max(totalPlayerTurns, outcome.getLastTurnNumber());
        }

        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("run", context.toMap());
        result.put("completedNormally", completedNormally);
        result.put("timeout", timeout);
        result.put("error", error);
        result.put("elapsedMillis", elapsedMillis);
        result.put("winnerSeat", winnerSeat(outcome));
        result.put("winReason", outcome == null || outcome.getWinCondition() == null ? null : outcome.getWinCondition().name());
        result.put("totalPlayerTurns", totalPlayerTurns);
        result.put("completedTableRounds", players.isEmpty() ? 0 : totalPlayerTurns / players.size());
        result.put("finalRoundPartialTurns", players.isEmpty() ? 0 : totalPlayerTurns % players.size());
        result.put("features", featureMap());
        result.put("players", playerSummaries());
        result.put("matrices", matrixSummaries());
        result.put("monarch", monarchSummary());
        result.put("initiative", initiativeSummary());
        result.put("eliminations", eliminations);
        result.put("turns", turnSnapshots ? turnRecords : List.of());
        return result;
    }

    private void onTurnBegan(final GameEventTurnBegan event) {
        if (currentTurn != null && !currentTurn.finished) {
            finishCurrentTurn();
        }
        totalPlayerTurns = Math.max(totalPlayerTurns, event.turnNumber());
        final int activeSeat = seat(event.turnOwner());
        if (activeSeat >= 0) {
            tallies[activeSeat].turnsTaken++;
            if (event.turnOwner().getIsExtraTurn()) {
                extraTurnsObserved = true;
            }
        }
        final int monarchSeat = seat(game.getMonarch());
        if (monarchSeat >= 0) {
            monarchTurns[monarchSeat]++;
        }
        final int initiativeSeat = seat(game.getHasInitiative());
        if (initiativeSeat >= 0) {
            initiativeTurns[initiativeSeat]++;
        }
        currentTurn = new TurnRecord(event.turnNumber(), players.isEmpty() ? 0 : ((event.turnNumber() - 1) / players.size()) + 1,
                activeSeat);
        currentPhase = null;
    }

    private void onTurnEnded() {
        if (currentTurn != null && !currentTurn.finished) {
            finishCurrentTurn();
        }
    }

    private void onAttackersDeclared(final GameEventAttackersDeclared event) {
        final int attackerSeat = seat(event.player());
        if (attackerSeat < 0) {
            return;
        }
        for (final Map.Entry<GameEntityView, CardView> entry : event.attackersMap().entries()) {
            final int defenderSeat = seat(entry.getKey());
            tallies[attackerSeat].attacksDeclared++;
            if (currentTurn != null) {
                currentTurn.attacksDeclared++;
            }
            if (defenderSeat >= 0) {
                attacks[attackerSeat][defenderSeat]++;
                if (currentTurn != null) {
                    currentTurn.addMatrixDelta("attacks", attackerSeat, defenderSeat, 1);
                }
            }
        }
    }

    private void onBlockersDeclared(final GameEventBlockersDeclared event) {
        final int defenderSeat = seat(event.defendingPlayer());
        int blockers = 0;
        for (final Multimap<CardView, CardView> blockerMap : event.blockers().values()) {
            blockers += blockerMap.values().size();
        }
        if (defenderSeat >= 0) {
            tallies[defenderSeat].blocksDeclared += blockers;
        }
        if (currentTurn != null) {
            currentTurn.blocksDeclared += blockers;
        }
    }

    private void onPlayerDamaged(final GameEventPlayerDamaged event) {
        final int targetSeat = seat(event.target());
        final int sourceSeat = event.source() == null ? NO_SEAT : seat(event.source().getController());
        if (targetSeat < 0) {
            return;
        }
        tallies[targetSeat].damageTaken += event.amount();
        if (event.combat()) {
            tallies[targetSeat].combatDamageTaken += event.amount();
        }
        if (sourceSeat >= 0) {
            tallies[sourceSeat].damageDealt += event.amount();
            if (event.combat()) {
                combatDamage[sourceSeat][targetSeat] += event.amount();
                tallies[sourceSeat].combatDamageDealt += event.amount();
            } else {
                nonCombatDamage[sourceSeat][targetSeat] += event.amount();
            }
            if (currentTurn != null) {
                currentTurn.addMatrixDelta(event.combat() ? "combatDamage" : "nonCombatDamage", sourceSeat, targetSeat,
                        event.amount());
            }
        }
        final int monarchSeat = seat(game.getMonarch());
        if (monarchSeat == targetSeat) {
            tallies[targetSeat].damageTakenAsMonarch += event.amount();
        }
    }

    private void onSpellAbilityCast(final GameEventSpellAbilityCast event) {
        if (event.si() == null) {
            return;
        }
        final int seat = seat(event.si().getActivatingPlayer());
        if (seat < 0) {
            return;
        }
        if (event.sa() != null && event.sa().isSpell()) {
            tallies[seat].spellsCast++;
            if (currentTurn != null) {
                currentTurn.spellsCast++;
            }
        } else if (!event.si().isTrigger()) {
            tallies[seat].abilitiesActivated++;
        } else {
            tallies[seat].triggeredAbilities++;
        }
    }

    private void onLandPlayed(final GameEventLandPlayed event) {
        final int seat = seat(event.player());
        if (seat >= 0) {
            tallies[seat].landsPlayed++;
            if (currentTurn != null) {
                currentTurn.landsPlayed++;
            }
        }
    }

    private void onCardChangeZone(final GameEventCardChangeZone event) {
        if (event.from() == null || event.to() == null) {
            return;
        }
        final ZoneType from = event.from().zoneType();
        final ZoneType to = event.to().zoneType();
        if (from == ZoneType.Library && to == ZoneType.Hand) {
            final int seat = seat(event.to().player());
            if (seat >= 0) {
                tallies[seat].cardsDrawnApprox++;
                if (seat == seat(game.getMonarch()) && currentPhase == PhaseType.END_OF_TURN) {
                    tallies[seat].monarchExtraDrawsApprox++;
                }
            }
        }
        if (to == ZoneType.Graveyard && currentTurn != null) {
            currentTurn.cardsToGraveyard++;
        }
        if (from == ZoneType.Command && to == ZoneType.Battlefield && event.card() != null
                && event.card().getCurrentState().isLand()) {
            final int seat = seat(event.to().player());
            if (seat >= 0) {
                tallies[seat].landStationUses++;
                landStationObserved = true;
            }
        }
        if (from == ZoneType.Battlefield && to != ZoneType.Battlefield) {
            final int controllerSeat = event.card() == null ? NO_SEAT : seat(event.card().getController());
            if (controllerSeat >= 0) {
                tallies[controllerSeat].permanentsRemovedFromBattlefield++;
            }
        }
    }

    private void onPlayerCounters(final GameEventPlayerCounters event) {
        if (event.type() == null) {
            return;
        }
        if (event.type().is(CounterEnumType.POISON)) {
            poisonObserved = true;
        } else if (event.type().is(CounterEnumType.ENERGY)) {
            energyObserved = true;
        }
    }

    private void detectHolderChanges() {
        final int monarchSeat = seat(game.getMonarch());
        if (monarchSeat != lastMonarchSeat) {
            if (monarchSeat >= 0) {
                monarchObserved = true;
                monarchChanges++;
                if (firstMonarchTurn == 0) {
                    firstMonarchTurn = totalPlayerTurns;
                }
            }
            lastMonarchSeat = monarchSeat;
        }

        final int initiativeSeat = seat(game.getHasInitiative());
        if (initiativeSeat != lastInitiativeSeat) {
            if (initiativeSeat >= 0) {
                initiativeObserved = true;
                initiativeChanges++;
                if (firstInitiativeTurn == 0) {
                    firstInitiativeTurn = totalPlayerTurns;
                }
            }
            lastInitiativeSeat = initiativeSeat;
        }
    }

    private void detectEliminations() {
        for (int i = 0; i < players.size(); i++) {
            if (!eliminated[i] && players.get(i).hasLost()) {
                eliminated[i] = true;
                final Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("seat", i);
                entry.put("name", players.get(i).getName());
                entry.put("turn", totalPlayerTurns);
                entry.put("life", players.get(i).getLife());
                eliminations.add(entry);
            }
        }
    }

    private void finishCurrentTurn() {
        currentTurn.finished = true;
        if (turnSnapshots) {
            currentTurn.lifeBySeat = new int[players.size()];
            currentTurn.handSizeBySeat = new int[players.size()];
            currentTurn.librarySizeBySeat = new int[players.size()];
            currentTurn.battlefieldCountBySeat = new int[players.size()];
            for (int i = 0; i < players.size(); i++) {
                final Player p = players.get(i);
                currentTurn.lifeBySeat[i] = p.getLife();
                currentTurn.handSizeBySeat[i] = p.getCardsIn(ZoneType.Hand).size();
                currentTurn.librarySizeBySeat[i] = p.getCardsIn(ZoneType.Library).size();
                currentTurn.battlefieldCountBySeat[i] = p.getCardsIn(ZoneType.Battlefield).size();
            }
            currentTurn.playersAlive = game.getPlayers().size();
            currentTurn.monarchSeat = seat(game.getMonarch());
            currentTurn.initiativeSeat = seat(game.getHasInitiative());
            turnRecords.add(currentTurn.toMap());
        }
    }

    private Map<String, Object> featureMap() {
        final Map<String, Object> result = new LinkedHashMap<>();
        final boolean battlebox = game.getRules().getGameType() == GameType.Battlebox
                || game.getRules().hasAppliedVariant(GameType.Battlebox);
        result.put("battlebox", battlebox);
        result.put("hasMonarchConfigured", context.getBattleboxMonarch());
        result.put("monarchObserved", monarchObserved);
        result.put("initiativeObserved", initiativeObserved);
        result.put("dayNightObserved", dayNightObserved);
        result.put("poisonObserved", poisonObserved);
        result.put("energyObserved", energyObserved);
        result.put("extraTurnsObserved", extraTurnsObserved);
        result.put("sharedLibraryObserved", battlebox);
        result.put("sharedGraveyardObserved", battlebox);
        result.put("landStationObserved", landStationObserved);
        result.put("tokensCreated", tokensCreated);
        return result;
    }

    private List<Map<String, Object>> playerSummaries() {
        final List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            final Player p = players.get(i);
            final PlayerTally tally = tallies[i];
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("seat", i);
            entry.put("name", p.getName());
            entry.put("won", p.hasWon());
            entry.put("lost", p.hasLost());
            entry.put("life", p.getLife());
            entry.put("poison", p.getCounters(CounterEnumType.POISON));
            entry.put("energy", p.getCounters(CounterEnumType.ENERGY));
            entry.put("handSize", p.getCardsIn(ZoneType.Hand).size());
            entry.put("librarySize", p.getCardsIn(ZoneType.Library).size());
            entry.put("graveyardSize", p.getCardsIn(ZoneType.Graveyard).size());
            entry.put("exileSize", p.getCardsIn(ZoneType.Exile).size());
            entry.put("battlefieldCount", p.getCardsIn(ZoneType.Battlefield).size());
            entry.put("permanentsByType", permanentsByType(p));
            entry.put("creatures", p.getCreaturesInPlay().size());
            entry.put("totalPower", totalPower(p));
            entry.put("totalToughness", totalToughness(p));
            entry.put("cardsDrawnApprox", tally.cardsDrawnApprox);
            entry.put("landsPlayed", tally.landsPlayed);
            entry.put("spellsCast", tally.spellsCast);
            entry.put("abilitiesActivated", tally.abilitiesActivated);
            entry.put("triggeredAbilities", tally.triggeredAbilities);
            entry.put("damageDealt", tally.damageDealt);
            entry.put("damageTaken", tally.damageTaken);
            entry.put("combatDamageDealt", tally.combatDamageDealt);
            entry.put("combatDamageTaken", tally.combatDamageTaken);
            entry.put("attacksDeclared", tally.attacksDeclared);
            entry.put("blocksDeclared", tally.blocksDeclared);
            entry.put("mulligans", tally.mulligans);
            entry.put("turnsTaken", tally.turnsTaken);
            entry.put("landStationUses", tally.landStationUses);
            entry.put("permanentsRemovedFromBattlefield", tally.permanentsRemovedFromBattlefield);
            result.add(entry);
        }
        return result;
    }

    private Map<String, Object> matrixSummaries() {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("attacks", matrixToList(attacks));
        result.put("combatDamage", matrixToList(combatDamage));
        result.put("nonCombatDamage", matrixToList(nonCombatDamage));
        result.put("totalDamage", sumMatrices(combatDamage, nonCombatDamage));
        return result;
    }

    private Map<String, Object> monarchSummary() {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("firstTurn", firstMonarchTurn);
        result.put("finalSeat", seat(game.getMonarch()));
        result.put("changes", monarchChanges);
        result.put("turnsBySeat", toList(monarchTurns));
        final List<Integer> damageTaken = new ArrayList<>();
        final List<Integer> extraDraws = new ArrayList<>();
        for (final PlayerTally tally : tallies) {
            damageTaken.add(tally.damageTakenAsMonarch);
            extraDraws.add(tally.monarchExtraDrawsApprox);
        }
        result.put("damageTakenBySeat", damageTaken);
        result.put("extraDrawsApproxBySeat", extraDraws);
        return result;
    }

    private Map<String, Object> initiativeSummary() {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("firstTurn", firstInitiativeTurn);
        result.put("finalSeat", seat(game.getHasInitiative()));
        result.put("changes", initiativeChanges);
        result.put("turnsBySeat", toList(initiativeTurns));
        return result;
    }

    private Map<String, Object> permanentsByType(final Player player) {
        final Map<String, Object> result = new LinkedHashMap<>();
        int artifacts = 0;
        int battles = 0;
        int creatures = 0;
        int enchantments = 0;
        int lands = 0;
        int planeswalkers = 0;
        for (final Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isArtifact()) {
                artifacts++;
            }
            if (card.isBattle()) {
                battles++;
            }
            if (card.isCreature()) {
                creatures++;
            }
            if (card.isEnchantment()) {
                enchantments++;
            }
            if (card.isLand()) {
                lands++;
            }
            if (card.isPlaneswalker()) {
                planeswalkers++;
            }
        }
        result.put("artifacts", artifacts);
        result.put("battles", battles);
        result.put("creatures", creatures);
        result.put("enchantments", enchantments);
        result.put("lands", lands);
        result.put("planeswalkers", planeswalkers);
        return result;
    }

    private int totalPower(final Player player) {
        int total = 0;
        for (final Card card : player.getCreaturesInPlay()) {
            total += card.getNetPower();
        }
        return total;
    }

    private int totalToughness(final Player player) {
        int total = 0;
        for (final Card card : player.getCreaturesInPlay()) {
            total += card.getNetToughness();
        }
        return total;
    }

    private int winnerSeat(final GameOutcome outcome) {
        if (outcome == null || outcome.getWinningPlayer() == null) {
            return NO_SEAT;
        }
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).getRegisteredPlayer().equals(outcome.getWinningPlayer())) {
                return i;
            }
        }
        return NO_SEAT;
    }

    private int seat(final Player player) {
        return player == null ? NO_SEAT : seatByPlayerId.getOrDefault(player.getId(), NO_SEAT);
    }

    private int seat(final PlayerView player) {
        return player == null ? NO_SEAT : seatByPlayerId.getOrDefault(player.getId(), NO_SEAT);
    }

    private int seat(final GameEntityView entity) {
        return entity instanceof PlayerView player ? seat(player) : NO_SEAT;
    }

    private static List<Integer> toList(final int[] values) {
        final List<Integer> result = new ArrayList<>(values.length);
        for (final int value : values) {
            result.add(value);
        }
        return result;
    }

    private static List<List<Integer>> matrixToList(final int[][] matrix) {
        final List<List<Integer>> result = new ArrayList<>(matrix.length);
        for (final int[] row : matrix) {
            result.add(toList(row));
        }
        return result;
    }

    private static List<List<Integer>> sumMatrices(final int[][] first, final int[][] second) {
        final List<List<Integer>> result = new ArrayList<>(first.length);
        for (int i = 0; i < first.length; i++) {
            final List<Integer> row = new ArrayList<>(first[i].length);
            for (int j = 0; j < first[i].length; j++) {
                row.add(first[i][j] + second[i][j]);
            }
            result.add(row);
        }
        return result;
    }

    private static final class PlayerTally {
        int abilitiesActivated;
        int attacksDeclared;
        int blocksDeclared;
        int cardsDrawnApprox;
        int combatDamageDealt;
        int combatDamageTaken;
        int damageDealt;
        int damageTaken;
        int damageTakenAsMonarch;
        int landStationUses;
        int landsPlayed;
        int monarchExtraDrawsApprox;
        int mulligans;
        int permanentsRemovedFromBattlefield;
        int spellsCast;
        int triggeredAbilities;
        int turnsTaken;
    }

    private static final class TurnRecord {
        final int turnNumber;
        final int roundNumber;
        final int activeSeat;
        final Map<String, int[][]> matrixDeltas = new LinkedHashMap<>();
        boolean finished;
        int attacksDeclared;
        int battlefieldCountBySeat[];
        int blocksDeclared;
        int cardsToGraveyard;
        int handSizeBySeat[];
        int initiativeSeat;
        int landsPlayed;
        int librarySizeBySeat[];
        int lifeBySeat[];
        int monarchSeat;
        int playersAlive;
        int spellsCast;
        int tokensCreated;

        TurnRecord(final int turnNumber, final int roundNumber, final int activeSeat) {
            this.turnNumber = turnNumber;
            this.roundNumber = roundNumber;
            this.activeSeat = activeSeat;
        }

        void addMatrixDelta(final String name, final int sourceSeat, final int targetSeat, final int amount) {
            final int playerCount = Math.max(sourceSeat, targetSeat) + 1;
            int[][] matrix = matrixDeltas.get(name);
            if (matrix == null) {
                matrix = new int[playerCount][playerCount];
                matrixDeltas.put(name, matrix);
            } else if (matrix.length < playerCount) {
                final int[][] expanded = new int[playerCount][playerCount];
                for (int i = 0; i < matrix.length; i++) {
                    expanded[i] = Arrays.copyOf(matrix[i], playerCount);
                }
                matrix = expanded;
                matrixDeltas.put(name, matrix);
            }
            matrix[sourceSeat][targetSeat] += amount;
        }

        Map<String, Object> toMap() {
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("turnNumber", turnNumber);
            result.put("roundNumber", roundNumber);
            result.put("activeSeat", activeSeat);
            result.put("playersAlive", playersAlive);
            result.put("lifeBySeat", toList(lifeBySeat));
            result.put("handSizeBySeat", toList(handSizeBySeat));
            result.put("librarySizeBySeat", toList(librarySizeBySeat));
            result.put("battlefieldCountBySeat", toList(battlefieldCountBySeat));
            result.put("monarchSeat", monarchSeat);
            result.put("initiativeSeat", initiativeSeat);
            result.put("spellsCast", spellsCast);
            result.put("landsPlayed", landsPlayed);
            result.put("attacksDeclared", attacksDeclared);
            result.put("blocksDeclared", blocksDeclared);
            result.put("cardsToGraveyard", cardsToGraveyard);
            result.put("tokensCreated", tokensCreated);
            final Map<String, Object> deltas = new LinkedHashMap<>();
            for (final Map.Entry<String, int[][]> entry : matrixDeltas.entrySet()) {
                deltas.put(entry.getKey(), matrixToList(entry.getValue()));
            }
            result.put("matrixDeltas", deltas);
            return result;
        }
    }
}
