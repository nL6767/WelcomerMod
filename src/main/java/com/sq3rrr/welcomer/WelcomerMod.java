package com.sq3rrr.welcomer;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WelcomerMod implements ClientModInitializer {

    public static final String MOD_ID = "welcomer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final boolean DEBUG = true;

    private final MinecraftClient client = MinecraftClient.getInstance();
    private final Pattern joinPattern = Pattern.compile("^(.*?) joined the game$");

    // Config paths
    private Path configDir;
    private Path messagesFile;
    private Path ignoreFile;
    private Path selfMessagesFile;


    // Welcome messages
    private final List<String> welcomeMessages = new ArrayList<>();
    private final Deque<String> messageQueue = new ArrayDeque<>();

    // Cooldowns & state
    private static final long GREET_COOLDOWN_MS = 5 * 60 * 1000L;
    private final Map<String, Long> lastGreeted = new ConcurrentHashMap<>();
    private boolean enabled = false;

    // Self-greet
    private boolean enabledSelfGreet = false;
    private static final long SELF_COOLDOWN_MS = 5 * 60 * 1000L;
    private long lastSelfGreet = 0;

    private final List<String> selfMessages = Arrays.asList(
            "Hello everyone!",
            "Hope you're all having a great time!",
            "Happy to be here with you guys!",
            "Let's have a fun block game session!",
            "Greetings from me!",
            "wow I'm here!",
            "I arrived!",
            "I m here!",
            "I made it!",
            "wow I joined",
            "There I am.",
            "Oh, look, it's me!"
    );
    private final Deque<String> selfMessageQueue = new ArrayDeque<>();

    // Ignore list (persisted on change)
    private final Set<String> ignoredPlayers = ConcurrentHashMap.newKeySet();

    // Scheduler for self-greet delays and other safe tasks
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "welcomer-scheduler");
        t.setDaemon(true);
        return t;
    });

    // ----------------------------
    // Server mode system
    // ----------------------------
    private enum Mode {
        CONSTANTIAM, DEFAULT, HYPERSAFE
    }
    private Mode serverMode = Mode.DEFAULT;

    private long perPlayerCooldown = GREET_COOLDOWN_MS;
    private long globalCooldown = 30_000L; // Default mode global cooldown
    private long lastGlobalGreet = 0;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Welcomer] Initializing no-stats version...");

        // Setup config paths
        configDir = client.runDirectory.toPath().resolve("config");
        messagesFile = configDir.resolve("welcomer_messages.txt");
        ignoreFile = configDir.resolve("welcomer_ignore.txt");
        selfMessagesFile = configDir.resolve("welcomer_selfmessages.txt");

        // Load welcome messages and ignore list
        loadMessages();
        loadIgnoreList();
        loadSelfMessages();

        // Refill queues
        refillMessageQueueIfEmpty();
        refillSelfQueue();

        // Register chat listener
        registerChatListener();

        // Register commands
        registerCommands();

        // Auto self-greet on join (client player)
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            scheduler.schedule(this::greetSelfWithCooldown, 2, TimeUnit.SECONDS); // safe 2s delay
        });

        logDebug("WelcomerMod initialized.");
    }

    // ----------------------------
    // MESSAGES & IGNORE LIST I/O
    // ----------------------------
    private void loadMessages() {
        welcomeMessages.clear();
        try {
            if (!Files.exists(configDir)) Files.createDirectories(configDir);

            if (Files.exists(messagesFile)) {
                List<String> lines = Files.readAllLines(messagesFile, StandardCharsets.UTF_8);
                for (String l : lines) {
                    String t = l.trim();
                    if (!t.isEmpty()) welcomeMessages.add(t);
                }
            } else {
                // Fallback defaults
                welcomeMessages.add("%s welcome!");
                welcomeMessages.add("Hey %s, nice to see you!");
                welcomeMessages.add("Welcome %s!");
                Files.write(messagesFile,
                        Arrays.asList("# Welcomer messages", "# %s replaced with player name"),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE);
                Files.write(messagesFile, welcomeMessages, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }

        } catch (IOException e) {
            LOGGER.error("[Welcomer] Failed to load messages", e);
        }

        // DEBUG LOG: total loaded messages
        logDebug("Loaded " + welcomeMessages.size() + " welcome messages from file.");
    }

    private void loadSelfMessages() {
        selfMessageQueue.clear();

        try {
            if (!Files.exists(configDir)) Files.createDirectories(configDir);

            if (Files.exists(selfMessagesFile)) {

                List<String> lines = Files.readAllLines(selfMessagesFile, StandardCharsets.UTF_8);
                List<String> tmp = new ArrayList<>();

                for (String l : lines) {
                    String t = l.trim();
                    if (!t.isEmpty()) {
                        tmp.add(t);
                    }
                }

                if (!tmp.isEmpty()) {
                    Collections.shuffle(tmp);
                    selfMessageQueue.addAll(tmp);
                    logDebug("Loaded " + tmp.size() + " custom self-greet messages.");
                    return;
                }
            }

            // Fallback to built-in defaults
            List<String> fallback = new ArrayList<>(selfMessages);
            Collections.shuffle(fallback);
            selfMessageQueue.addAll(fallback);
            logDebug("Using fallback self-greet messages. Count: " + fallback.size());

            // Write template file if missing
            if (!Files.exists(selfMessagesFile)) {
                Files.write(selfMessagesFile,
                        Arrays.asList(
                                "# Self-greet messages",
                                "# One message per line",
                                "# If this file is empty, fallback messages will be used."
                        ),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE
                );
                Files.write(selfMessagesFile, selfMessages, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }

        } catch (IOException e) {
            LOGGER.error("[Welcomer] Failed to load self-greet messages", e);
        }
    }


    private void loadIgnoreList() {
        ignoredPlayers.clear();
        try {
            if (!Files.exists(configDir)) Files.createDirectories(configDir);
            if (Files.exists(ignoreFile)) {
                List<String> lines = Files.readAllLines(ignoreFile, StandardCharsets.UTF_8);
                for (String l : lines) {
                    String s = l.trim();
                    if (!s.isEmpty()) ignoredPlayers.add(s);
                }
            } else {
                Files.write(ignoreFile, Collections.emptyList(), StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            }
        } catch (IOException e) {
            LOGGER.error("[Welcomer] Failed to load ignore list", e);
        }
        logDebug("Loaded " + ignoredPlayers.size() + " ignored players from file.");
    }

    private void persistIgnoreList() {
        try {
            List<String> lines = new ArrayList<>(ignoredPlayers);
            Collections.sort(lines, String.CASE_INSENSITIVE_ORDER);
            Files.write(ignoreFile, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            logDebug("Ignore list persisted with " + lines.size() + " players.");
        } catch (IOException e) {
            LOGGER.error("[Welcomer] Failed to persist ignore list", e);
        }
    }

    // ----------------------------
    // MESSAGE QUEUES
    // ----------------------------
    private void refillMessageQueueIfEmpty() {
        if (messageQueue.isEmpty()) {
            if (welcomeMessages.isEmpty()) {
                logDebug("Welcome messages list is empty — cannot refill message queue.");
                return;
            }
            List<String> tmp = new ArrayList<>(welcomeMessages);
            Collections.shuffle(tmp);
            messageQueue.addAll(tmp);
            logDebug("Message queue refilled with perfect shuffle.");
        }
    }

    private void refillSelfQueue() {
        if (selfMessageQueue.isEmpty()) {
            if (!Files.exists(selfMessagesFile) || selfMessagesFile.toFile().length() == 0) {
                // fallback to built-in defaults
                List<String> tmp = new ArrayList<>(selfMessages);
                Collections.shuffle(tmp);
                selfMessageQueue.addAll(tmp);
                logDebug("Self-greet queue refilled with fallback messages. Queue size: " + selfMessageQueue.size());
            } else {
                // use loaded messages from file
                loadSelfMessages(); // ensures selfMessageQueue is filled from file
                logDebug("Self-greet queue refilled from file. Queue size: " + selfMessageQueue.size());
            }
        }
    }


    // ----------------------------
    // GREETING
    // ----------------------------
    private void greetPlayer(String player) {
        if (!enabled || player == null || player.isEmpty() || ignoredPlayers.contains(player)) {
            logDebug("Skipped greeting for player: " + player);
            return;
        }

        long now = System.currentTimeMillis();

        /*
         Mode rules (applied here):
         - DEFAULT: only per-player cooldown matters (global ignored).
         - CONSTANTIAM: global cooldown checked first, then per-player cooldown.
         - HYPERSAFE: same as CONSTANTIAM but with longer cooldowns.
         */

        if (serverMode == Mode.DEFAULT) {
            // DEFAULT: per-player only
            long last = lastGreeted.getOrDefault(player, 0L);
            if (now - last < perPlayerCooldown) {
                logDebug("Per-player cooldown active for " + player + ". Skipping greeting.");
                return;
            }
            lastGreeted.put(player, now);
            // Do NOT bump lastGlobalGreet (global ignored in DEFAULT)
        } else {
            // CONSTANTIAM or HYPERSAFE: enforce global cooldown first (if >0), then per-player
            if (globalCooldown > 0 && now - lastGlobalGreet < globalCooldown) {
                logDebug("Global cooldown active. Skipping greeting for player: " + player);
                return;
            }
            long last = lastGreeted.getOrDefault(player, 0L);
            if (now - last < perPlayerCooldown) {
                logDebug("Per-player cooldown active for " + player + ". Skipping greeting.");
                return;
            }
            // passed both checks
            lastGreeted.put(player, now);
            lastGlobalGreet = now;
        }

        refillMessageQueueIfEmpty();
        String raw = messageQueue.poll();
        if (raw == null) {
            logDebug("Message queue empty after refill, skipping greeting for player: " + player);
            return; // defensive null check
        }

        String msg = raw.contains("%s") ? String.format(raw, player) : raw + " " + player;

        client.execute(() -> {
            if (client.player != null && client.player.networkHandler != null) {
                client.player.networkHandler.sendChatMessage(msg);
                logDebug("Greeted " + player + ": " + msg + " | Messages left in queue: " + messageQueue.size());
            }
        });
    }

    private void greetSelfWithCooldown() {
        if (!enabledSelfGreet || client.player == null) {
            logDebug("Self-greet skipped: enabledSelfGreet=" + enabledSelfGreet + ", client.player=" + client.player);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastSelfGreet < SELF_COOLDOWN_MS) {
            logDebug("Self-greet cooldown active. Time remaining: " + (SELF_COOLDOWN_MS - (now - lastSelfGreet)) + "ms");
            return;
        }

        lastSelfGreet = now;
        refillSelfQueue();
        String msg = selfMessageQueue.poll();
        if (msg == null) {
            logDebug("Self-greet queue empty after refill, skipping self-greet.");
            return; // defensive null check
        }
        logDebug("Self-greet message selected: " + msg);

        scheduler.schedule(() -> client.execute(() -> {
            try {
                if (client.player != null && client.player.networkHandler != null) {
                    client.player.networkHandler.sendChatMessage(msg);
                    logDebug("Self-greet sent: " + msg + " | Self queue size: " + selfMessageQueue.size());
                }
            } catch (Exception ex) {
                LOGGER.error("[Welcomer] Self-greet scheduler error", ex);
            }
        }), 2, TimeUnit.SECONDS);
    }

    // ----------------------------
    // CHAT LISTENER
    // ----------------------------
    private void registerChatListener() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String msg = message.getString();
            Matcher m = joinPattern.matcher(msg);
            if (m.matches()) {
                String playerName = m.group(1);

                if (client.player != null && playerName.equals(client.player.getName().getString())) return;

                greetPlayer(playerName);
            }
        });
    }

    // ----------------------------
    // COMMANDS
    // ----------------------------
    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                    ClientCommandManager.literal("welcomer")




                            // Toggle main Welcomer
                            .then(ClientCommandManager.literal("toggle")
                                    .executes(ctx -> {
                                        enabled = !enabled;
                                        sendClientMessage("Welcomer is now: " + (enabled ? "§aON" : "§cOFF"));
                                        logDebug("Welcomer toggled = " + enabled);
                                        return 1;
                                    })
                            )

                            // Toggle self-greet
                            .then(ClientCommandManager.literal("selfgreet")
                                    .then(ClientCommandManager.literal("toggle")
                                            .executes(ctx -> {
                                                enabledSelfGreet = !enabledSelfGreet;
                                                sendClientMessage("Self-greet is now: " + (enabledSelfGreet ? "§aON" : "§cOFF"));
                                                logDebug("Self-greet toggled = " + enabledSelfGreet);
                                                return 1;
                                            })
                                    )
                            )

                            // Show status of toggles
                            .then(ClientCommandManager.literal("status")
                                    .executes(ctx -> {
                                        String msg = "Welcomer: " + (enabled ? "§aON" : "§cOFF") +
                                                " | Self-greet: " + (enabledSelfGreet ? "§aON" : "§cOFF") +
                                                " | Mode: " + serverMode.name();
                                        sendClientMessage(msg);
                                        logDebug("Status requested: " + msg);
                                        return 1;
                                    })
                            )

                            // Ignore a player
                            .then(ClientCommandManager.literal("ignore")
                                    .then(ClientCommandManager.argument("player", StringArgumentType.string())
                                            .executes(ctx -> {
                                                String p = StringArgumentType.getString(ctx, "player");
                                                if (p != null && !p.isEmpty()) {
                                                    ignoredPlayers.add(p);
                                                    persistIgnoreList();
                                                    sendClientMessage("§cNow ignoring §f" + p);
                                                    logDebug("Player ignored: " + p);
                                                }
                                                return 1;
                                            })
                                    )
                            )

                            // Unignore a player
                            .then(ClientCommandManager.literal("unignore")
                                    .then(ClientCommandManager.argument("player", StringArgumentType.string())
                                            .executes(ctx -> {
                                                String p = StringArgumentType.getString(ctx, "player");
                                                if (p != null && !p.isEmpty()) {
                                                    ignoredPlayers.remove(p);
                                                    persistIgnoreList();
                                                    sendClientMessage("§aRemoved from ignore: §f" + p);
                                                    logDebug("Player unignored: " + p);
                                                }
                                                return 1;
                                            })
                                    )
                            )

                            // Server mode command
                            .then(ClientCommandManager.literal("mode")
                                    .then(ClientCommandManager.literal("constantiam")
                                            .executes(ctx -> {
                                                serverMode = Mode.CONSTANTIAM;
                                                perPlayerCooldown = 5 * 60 * 1000L; // 5 min per player
                                                globalCooldown = 5 * 60 * 1000L;    // 5 min global cooldown
                                                sendClientMessage("Server mode set to CONSTANTIAM");
                                                logDebug("Server mode switched to CONSTANTIAM");
                                                return 1;
                                            })
                                    )
                                    .then(ClientCommandManager.literal("default")
                                            .executes(ctx -> {
                                                serverMode = Mode.DEFAULT;
                                                perPlayerCooldown = 5 * 60 * 1000L; // 5 min per player
                                                globalCooldown = 0;                 // global cooldown ignored
                                                sendClientMessage("Server mode set to DEFAULT");
                                                logDebug("Server mode switched to DEFAULT");
                                                return 1;
                                            })
                                    )
                                    .then(ClientCommandManager.literal("hypersafe")
                                            .executes(ctx -> {
                                                serverMode = Mode.HYPERSAFE;
                                                perPlayerCooldown = 10 * 60 * 1000L; // 10 min per player
                                                globalCooldown = 10 * 60 * 1000L;    // 10 min global cooldown
                                                sendClientMessage("Server mode set to HYPERSAFE");
                                                logDebug("Server mode switched to HYPERSAFE");
                                                return 1;
                                            })
                                    )
                            )

                            .then(ClientCommandManager.literal("info")
                                    .executes(ctx -> {
                                        sendClientMessage("§b§lWelcomer §7v1.0.0");
                                        sendClientMessage("§b—§f A lightweight join-greeter for anarchy servers");
                                        sendClientMessage(" ");
                                        sendClientMessage("§bGitHub: §fhttps://github.com/sq3rrr/WelcomerMod");
                                        sendClientMessage("§bby: §fnL66ercatgirl67");
                                        sendClientMessage("§bShoutouts: §fanarchy.ac • OpenAI • Constantiam");
                                        sendClientMessage(" ");
                                        sendClientMessage("§eAdjust configs! Defaults are for Constantiam.");
                                        return 1;
                                    })
                            )



                            // Config commands: reload/add messages
                            .then(ClientCommandManager.literal("config")

                                    // Reload welcome messages from file
                                    .then(ClientCommandManager.literal("reload")
                                            .executes(ctx -> {
                                                loadMessages();
                                                refillMessageQueueIfEmpty();
                                                sendClientMessage("Welcome messages reloaded!");
                                                logDebug("Messages reloaded via command.");
                                                return 1;
                                            })
                                    )

                                    // Reload self-greet messages from file
                                    .then(ClientCommandManager.literal("reloadself")
                                            .executes(ctx -> {
                                                loadSelfMessages();
                                                refillSelfQueue();
                                                sendClientMessage("Self-greet messages reloaded!");
                                                logDebug("Self-greet messages reloaded via command.");
                                                return 1;
                                            })
                                    )

                                    // Add new welcome message
                                    .then(ClientCommandManager.literal("add")
                                            .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                                                    .executes(ctx -> {
                                                        String newMsg = StringArgumentType.getString(ctx, "message");
                                                        if (newMsg != null && !newMsg.isEmpty()) {
                                                            try {
                                                                if (!Files.exists(configDir)) Files.createDirectories(configDir);
                                                                Files.writeString(messagesFile, newMsg + "\n", StandardCharsets.UTF_8,
                                                                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                                                                welcomeMessages.add(newMsg);
                                                                sendClientMessage("§aAdded new welcome message:§f " + newMsg);
                                                                logDebug("Added new message: " + newMsg);
                                                            } catch (IOException e) {
                                                                sendClientMessage("§4Failed to save new message.");
                                                                LOGGER.error("[Welcomer] Failed to write new message", e);
                                                            }
                                                        }
                                                        return 1;
                                                    })
                                            )
                                            // Add new self-greet message
                                            .then(ClientCommandManager.literal("addself")
                                                    .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                                                            .executes(ctx -> {
                                                                String newMsg = StringArgumentType.getString(ctx, "message");
                                                                if (newMsg != null && !newMsg.isEmpty()) {
                                                                    try {
                                                                        if (!Files.exists(configDir)) Files.createDirectories(configDir);
                                                                        Files.writeString(selfMessagesFile, newMsg + "\n", StandardCharsets.UTF_8,
                                                                                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                                                                        selfMessageQueue.add(newMsg);
                                                                        sendClientMessage("§aAdded new self-greet message:§f " + newMsg);
                                                                        logDebug("Added new self-greet message: " + newMsg);
                                                                    } catch (IOException e) {
                                                                        sendClientMessage("§4Failed to save new self-greet message.");
                                                                        LOGGER.error("[Welcomer] Failed to write new self-greet message", e);
                                                                    }
                                                                }
                                                                return 1;
                                                            })
                                                    )


                                            )

                                    )
                            )
            );
        });
    }

    // ----------------------------
    // UTILITIES
    // ----------------------------
    private void sendClientMessage(String text) {
        client.execute(() -> {
            if (client.player != null) client.player.sendMessage(Text.literal("§b[Welcomer] §r" + text));
        });
    }

    private void logDebug(String s) {
        if (DEBUG) LOGGER.info("[DEBUG] " + s);
    }
}
