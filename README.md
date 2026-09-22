# ⚔️  Forge Battlebox

A fork of [**Card-Forge/forge**](https://github.com/Card-Forge/forge) that adds **Battlebox** as a first-class game variant, playable against the AI or with friends over the network.

[![Latest release](https://img.shields.io/github/v/release/williamrachuy/forge?style=flat-square&label=release)](https://github.com/williamrachuy/forge/releases/latest)

---

## ✨ Introduction
**Battlebox** is a shared-pool way to play Magic: instead of bringing your own deck, every player draws from **one communal library** built from a single curated box of cards, plays lands from a **shared land station**, and fights over a **shared graveyard**. Nobody out-builds anyone; the game is decided at the table.

Everything else — the rules engine, cards, AI, and all of Forge's other modes — comes from the original project. For Forge itself, see the [Card-Forge repository](https://github.com/Card-Forge/forge), its [User Guide](https://github.com/Card-Forge/forge/wiki/User-Guide), and the Forge [Discord](https://discord.gg/HcPJNyD66a).

**Note:** Forge operates independently and is not affiliated with Wizards of the Coast. This fork is not an official Forge release — please report Battlebox issues here, not upstream.

---

## 🌟 Key Features
- **📚 Shared Library:** One library for the whole table, sampled fresh from the box each game. A card becomes yours when you draw it.
- **🏞️ Land Station:** Lands aren't drawn — each player may play them from a shared pool in the command zone.
- **🪦 Shared Graveyard:** One graveyard for everyone; graveyard effects can reach anything that died, whoever it belonged to.
- **👑 Optional Monarch, Commanders & Planechase:** Toggle each from the lobby.
- **🌐 Network Play:** Host or join Battlebox games over LAN or the internet, 2–4 players, with AI filling empty seats.
- **🧩 Deck-File Configuration:** Life totals, hand sizes, library size and land seeding live in the Battlebox deck file — no code changes to tune the format.

---

## 🎲 Battlebox Formats

### 🅰️ Type 1 — Curated Land Station
The land station is the box's own `[LandStation]` section, plus one extra set of basic lands for each player beyond the second.

### 🅱️ Type 2 — Basic Land Station
The `[LandStation]` section is ignored. The station is **one of each basic land per player** (2 players = 10 lands, 3 = 15, 4 = 20), using the prints chosen in the deck's `[BasicLandsSet]`.

Everything else — shared library, shared graveyard, options, and deck metadata — is identical between the two types.

### ⚙️ Options
Set by the host in the lobby's **Battlebox Options** panel:
- **Play with Monarch** — the first player to deal combat damage to an opponent becomes the monarch.
- **Play with Commanders** — the box's `[Commanders]` pool sits in the shared command zone; each player may claim and cast **one** commander per game.
- **Play with Planechase** — the table shares a planar deck built from the box's `[Planechase]` section.

---

## 🛠️ Installation Steps

### 📥 Desktop (Windows, Linux, macOS)
1. **Latest Release:** Download `Forge-Battlebox-<version>.zip` from the [Releases page](https://github.com/williamrachuy/forge/releases/latest).
2. **Java Requirement:** Install **Java 17 or later**, 64-bit ([Temurin 21](https://adoptium.net) recommended).
3. **Extract:** Unzip to a folder you own (not *Program Files*) — Forge writes files next to itself.
4. **Launch:** Run `forge.exe` or `forge.cmd` on Windows, `forge.sh` on Linux/macOS.
   - **Tip:** Windows SmartScreen may warn about the unsigned exe — choose *More info* → *Run anyway*.

Full steps are in `README.txt` inside the zip.

---

## 🎮 Playing Battlebox

### 🧑‍💻 Against the AI
1. Put a Battlebox deck in your Forge decks folder under `battlebox/` (`%APPDATA%\Forge\decks\battlebox\` on Windows, `~/.forge/decks/battlebox/` on Linux). The release zip ships ready-made decks in `battlebox-decks/`.
2. In **Constructed**, tick **Battlebox** or **Battlebox Type 2** under variants.
3. Pick the Battlebox deck for the first player — the other seats draw from the same shared library, so they need no deck.
4. Choose your options and start.

### 🌐 Online Multiplayer
- **Host:** *Online Multiplayer* → *Host a Game*, apply a Battlebox variant, and pick the deck. Only the host needs the deck file.
- **Join:** *Online Multiplayer* → *Join a Game* and enter the host's address, e.g. `203.0.113.7:36743`.
- **Reaching the host:** allow incoming **TCP 36743** (UPnP or a router port forward), or use a VPN such as Tailscale or ZeroTier when the host is behind carrier-grade NAT.
- **⚠️ Same build required:** every player must run the **same release** as the host — compare the `version` and `commit` lines in `BUILD.txt`. An official Forge build will not connect to a Battlebox game.

### 📦 Deck File Format
A Battlebox deck is a normal `.dck` file with extra sections and metadata:

| Section            | Purpose                                                        |
|--------------------|----------------------------------------------------------------|
| **`[metadata]`**   | Format settings (see below)                                    |
| **`[Main]`**       | The box — the shared library is sampled from here each game    |
| **`[LandStation]`**| Type 1 land station (lands only)                               |
| **`[BasicLandsSet]`** | Basic land prints used for seeding and the Type 2 station   |
| **`[Commanders]`** | Commander pool for the *Play with Commanders* option           |
| **`[Planechase]`** | Planar deck for the *Play with Planechase* option              |

| Metadata key                 | Default | Meaning                                                   |
|------------------------------|---------|-----------------------------------------------------------|
| `BattleboxStartingLife`      | 20      | Starting life                                             |
| `CommanderStartingLife`      | = life  | Starting life when commanders are enabled                 |
| `BattleboxStartingHandSize`  | 7       | Opening hand size                                         |
| `BattleboxMaxHandSize`       | 7       | Maximum hand size                                         |
| `PlayerLibrarySize`          | 40      | Library cards contributed per player                      |
| `CommanderPlayerLibrarySize` | = size  | Per-player library size when commanders are enabled       |
| `SeedBasicLands`             | true    | Add one of each basic per player to the shared library    |

`= life` / `= size`: falls back to `BattleboxStartingLife` / `PlayerLibrarySize`.

Invalid setups (for example a library larger than the box, or non-lands in the station) are rejected in the lobby with a clear error, before the match starts.

---

## 🧱 Building From Source
Requires **Java 17+** and **Maven 3.9+**.
```bash
mvn -pl forge-gui-desktop -am -DskipTests package
```
To produce the same single-zip package as the releases (and optionally publish one with the GitHub CLI):
```bash
tools/package_friends.sh              # build + zip into dist/
tools/package_friends.sh --release    # build + zip + GitHub release (clean, pushed tree required)
```

---

## 🤝 Contributing
Battlebox issues and pull requests are welcome here. For anything that isn't Battlebox-specific — card scripts, rules, the AI, other game modes — please contribute to [Card-Forge/forge](https://github.com/Card-Forge/forge) following its [Contributing Guidelines](CONTRIBUTING.md).

---

## ℹ️ About This Fork
This fork tracks upstream Forge and periodically merges it in, so cards and rules fixes from the Forge community arrive here too. All credit for Forge belongs to the [Card-Forge contributors](https://github.com/Card-Forge/forge/graphs/contributors).

---

**📄 License:** [GPL-3.0](LICENSE)
<div align="center" style="display: flex; align-items: center; justify-content: center;">
    <div style="margin-left: auto;">
        <a href="#top">
            <img src="https://img.shields.io/badge/Back%20to%20Top-000000?style=for-the-badge&logo=github&logoColor=white" alt="Back to Top">
        </a>
    </div>
</div>
