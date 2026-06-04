package forge.game.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.game.GameType;

public final class SimStatsGameContext {
    private final String runName;
    private final int gameIndex;
    private final long baseSeed;
    private final long gameSeed;
    private final String configHash;
    private final GameType format;
    private final int playerCount;
    private final List<String> deckNames;
    private final List<String> aiProfiles;
    private final Boolean battleboxMonarch;

    public SimStatsGameContext(final String runName, final int gameIndex, final long baseSeed, final long gameSeed,
            final String configHash, final GameType format, final int playerCount, final List<String> deckNames,
            final List<String> aiProfiles, final Boolean battleboxMonarch) {
        this.runName = runName;
        this.gameIndex = gameIndex;
        this.baseSeed = baseSeed;
        this.gameSeed = gameSeed;
        this.configHash = configHash;
        this.format = format;
        this.playerCount = playerCount;
        this.deckNames = new ArrayList<>(deckNames);
        this.aiProfiles = new ArrayList<>(aiProfiles);
        this.battleboxMonarch = battleboxMonarch;
    }

    Map<String, Object> toMap() {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("runName", runName);
        result.put("gameIndex", gameIndex);
        result.put("baseSeed", baseSeed);
        result.put("gameSeed", gameSeed);
        result.put("configHash", configHash);
        result.put("format", format.name());
        result.put("playerCount", playerCount);
        result.put("deckNames", deckNames);
        result.put("aiProfiles", aiProfiles);
        result.put("battleboxMonarch", battleboxMonarch);
        return result;
    }

    public String getRunName() {
        return runName;
    }

    public int getGameIndex() {
        return gameIndex;
    }

    public long getBaseSeed() {
        return baseSeed;
    }

    public long getGameSeed() {
        return gameSeed;
    }

    public String getConfigHash() {
        return configHash;
    }

    public GameType getFormat() {
        return format;
    }

    public int getPlayerCount() {
        return playerCount;
    }

    public List<String> getDeckNames() {
        return deckNames;
    }

    public List<String> getAiProfiles() {
        return aiProfiles;
    }

    public Boolean getBattleboxMonarch() {
        return battleboxMonarch;
    }
}
