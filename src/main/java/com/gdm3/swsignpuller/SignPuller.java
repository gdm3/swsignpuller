package com.gdm3.swsignpuller;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.*;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.lang.ProcessBuilder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Mod(SignPuller.MODID)
public class SignPuller {
    public static final String MODID = "signpullersw";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Pattern SHOP_HEADER_PATTERN = Pattern.compile("^Shop Information:$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OWNER_PATTERN = Pattern.compile("^Owner: (.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STOCK_PATTERN = Pattern.compile("^Stock: (\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_PATTERN = Pattern.compile("^Item: \\[(.*)\\]$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUY_PATTERN = Pattern.compile("^Buy (\\d+) for (\\d+) Coins$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELL_PATTERN = Pattern.compile("^Sell (\\d+) for (\\d+) Coins$", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private boolean isParsingShop = false;
    private ShopData currentShopData = null;
    private int shopsTracked = 0;

    public SignPuller() {
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("SignPuller Mod Initialized. Passively listening for shop data in chat.");
    }

    @SubscribeEvent
    public void onChatMessageReceived(ClientChatReceivedEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        String cleanMessage = StringUtil.stripColor(event.getMessage().getString()).trim();
        Matcher headerMatcher = SHOP_HEADER_PATTERN.matcher(cleanMessage);

        if (headerMatcher.matches()) {
            if (isParsingShop && currentShopData != null && currentShopData.isValid()) {
                LOGGER.debug("New shop header detected while parsing another. Saving previous shop data.");
                finalizeAndSaveData(mc);
            }

            LOGGER.debug("Detected Shop Header. Starting new shop data capture.");
            isParsingShop = true;
            currentShopData = new ShopData();
            currentShopData.timestamp = LocalDateTime.now();
            currentShopData.playerPosition = mc.player.blockPosition();
            return;
        }

        if (isParsingShop && currentShopData != null) {
            if (parseShopLine(cleanMessage)) {
                LOGGER.debug("Parsed shop line: {}", cleanMessage);
            }
            else {
                LOGGER.debug("Line '{}' did not match shop pattern, finalizing current shop info.", cleanMessage);
                finalizeAndSaveData(mc);
            }
        }
    }

    private void finalizeAndSaveData(Minecraft mc) {
        if (currentShopData != null && currentShopData.isValid()) {
            if (mc.level != null && mc.player != null) {
                currentShopData.claimInfo = getCurrentClaim(mc);
            } else {
                currentShopData.claimInfo = "Unknown (Client Error)";
            }
            writeShopDataToFile(currentShopData);
        } else {
            LOGGER.debug("Finalizing data, but current shop data was not valid. Discarding.");
        }
        resetShopParsingState();
    }


    private boolean parseShopLine(String cleanLine) {
        if (currentShopData == null) return false;

        Matcher ownerMatcher = OWNER_PATTERN.matcher(cleanLine);
        if (ownerMatcher.matches()) {
            currentShopData.owner = ownerMatcher.group(1).trim(); return true;
        }
        Matcher stockMatcher = STOCK_PATTERN.matcher(cleanLine);
        if (stockMatcher.matches()) {
            try { currentShopData.stock = Integer.parseInt(stockMatcher.group(1)); return true;
            } catch (NumberFormatException e) { return false; }
        }
        Matcher itemMatcher = ITEM_PATTERN.matcher(cleanLine);
        if (itemMatcher.matches()) {
            currentShopData.item = itemMatcher.group(1).trim(); return true;
        }
        Matcher buyMatcher = BUY_PATTERN.matcher(cleanLine);
        if (buyMatcher.matches()) {
            try {
                currentShopData.buyQuantity = Integer.parseInt(buyMatcher.group(1));
                currentShopData.buyPrice = Integer.parseInt(buyMatcher.group(2));
                return true;
            } catch (NumberFormatException e) { return false; }
        }
        Matcher sellMatcher = SELL_PATTERN.matcher(cleanLine);
        if (sellMatcher.matches()) {
            try {
                currentShopData.sellQuantity = Integer.parseInt(sellMatcher.group(1));
                currentShopData.sellPrice = Integer.parseInt(sellMatcher.group(2));
                return true;
            } catch (NumberFormatException e) { return false; }
        }
        return false;
    }

    private void resetShopParsingState() {
        this.isParsingShop = false;
        this.currentShopData = null;
        LOGGER.debug("Reset shop parsing state. Ready for new header.");
    }

    private void writeShopDataToFile(ShopData data) {
        File logFile = new File(Minecraft.getInstance().gameDirectory, "shop_log.txt");
        if (data == null || !data.isValid()) {
            LOGGER.warn("Attempted to write invalid or null shop data.");
            return;
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
            String formattedData = data.toLogString();
            writer.write(formattedData);
            writer.newLine();
            LOGGER.info("Appended shop data to {}", logFile.getAbsolutePath());

            shopsTracked++;

            final String itemName = data.item;
            final String ownerName = data.owner;
            final int currentCount = this.shopsTracked;

            Minecraft.getInstance().execute(() -> {
                Player player = Minecraft.getInstance().player;
                if (player != null) {
                    Component message = Component.literal("Logged shop: ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(itemName).withStyle(ChatFormatting.AQUA))
                            .append(Component.literal(" by ").withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(ownerName).withStyle(ChatFormatting.YELLOW))
                            .append(Component.literal(" (" + currentCount + "/20)").withStyle(ChatFormatting.DARK_AQUA)); // Added counter
                    player.sendSystemMessage(message);
                }
            });

            if (shopsTracked >= 20) {
                Minecraft.getInstance().execute(() -> {
                    Player player = Minecraft.getInstance().player;
                    if (player != null) {
                        player.sendSystemMessage(Component.literal("Logged 20 shops. Running analysis script...").withStyle(ChatFormatting.GOLD));
                    }
                });
                runAnalysisScript(logFile.getAbsolutePath());
                shopsTracked = 0;
            }

        } catch (IOException e) {
            LOGGER.error("Failed to write shop log to file: {}", logFile.getAbsolutePath(), e);
        }
    }

    private void runAnalysisScript(String logFilePath) {
        File scriptFile = new File(Minecraft.getInstance().gameDirectory, "analyze_data.py");
        if (!scriptFile.exists()) {
            LOGGER.warn("Python analysis script not found at: {}. Skipping analysis.", scriptFile.getAbsolutePath());
            return;
        }
        String pythonCommand = "python3";

        CompletableFuture.runAsync(() -> {
            final List<String> stdoutLines = new ArrayList<>();
            final List<String> stderrLines = new ArrayList<>();

            try {
                ProcessBuilder pb = new ProcessBuilder(pythonCommand, scriptFile.getAbsolutePath(), logFilePath);
                pb.directory(Minecraft.getInstance().gameDirectory);
                Process process = pb.start();

                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                    String line; while ((line = reader.readLine()) != null) { stdoutLines.add(line); }
                }
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getErrorStream()))) {
                    String line; while ((line = reader.readLine()) != null) { stderrLines.add(line); }
                }

                int exitCode = process.waitFor();

                Minecraft.getInstance().execute(() -> {
                    Player player = Minecraft.getInstance().player;
                    if (player == null) return;
                    player.sendSystemMessage(Component.literal("Python analysis script finished.").withStyle(ChatFormatting.AQUA));
                    if (exitCode == 0) {
                        player.sendSystemMessage(Component.literal("Analysis Result:").withStyle(ChatFormatting.GREEN));
                        stdoutLines.forEach(line -> player.sendSystemMessage(Component.literal(line)));
                    } else {
                        player.sendSystemMessage(Component.literal("Python script failed (code " + exitCode + "):").withStyle(ChatFormatting.RED));
                        stderrLines.forEach(line -> player.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.RED)));
                    }
                });

            } catch (Exception e) {
                LOGGER.error("An error occurred while running the Python script.", e);
                Minecraft.getInstance().execute(() -> {
                    Player player = Minecraft.getInstance().player;
                    if (player != null) {
                        player.sendSystemMessage(Component.literal("An unexpected error occurred running the script. Check logs.").withStyle(ChatFormatting.RED));
                    }
                });
            }
        }).exceptionally(ex -> {
            LOGGER.error("Exception in async Python execution task", ex);
            return null;
        });
    }

    private static final Pattern CLAIM_LINE_PATTERN = Pattern.compile("Current Claim:", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE_IN_BRACKETS_PATTERN = Pattern.compile("\\[([^\\]]*)\\]");

    private static String getCurrentClaim(Minecraft mc) {
        if (mc == null || mc.level == null || mc.player == null) return "Unknown (Client Error)";
        Scoreboard scoreboard = mc.level.getScoreboard();
        Objective sidebarObjective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebarObjective == null) return "No Sidebar";

        List<String> orderedLines = scoreboard.listPlayerScores(sidebarObjective).stream()
                .sorted(Comparator.comparingInt(PlayerScoreEntry::value).reversed())
                .map(entry -> {
                    Team team = mc.level.getScoreboard().getPlayersTeam(entry.owner());
                    Component ownerNameComponent = entry.ownerName() != null ? entry.ownerName() : Component.literal(entry.owner());
                    return PlayerTeam.formatNameForTeam(team, ownerNameComponent).getString();
                })
                .collect(Collectors.toList());

        for (int i = 0; i < orderedLines.size(); i++) {
            String cleanLine = StringUtil.stripColor(orderedLines.get(i)).trim();
            if (CLAIM_LINE_PATTERN.matcher(cleanLine).find()) {
                if (i + 1 < orderedLines.size()) {
                    Matcher valueMatcher = VALUE_IN_BRACKETS_PATTERN.matcher(orderedLines.get(i + 1));
                    if (valueMatcher.find()) {
                        String value = StringUtil.stripColor(valueMatcher.group(1)).trim();
                        return value.isEmpty() ? "Claim Value Empty" : value;
                    }
                }
                return "Claim Value Missing";
            }
        }
        return "Claim Label Missing"; // parse this on backend later
    }

    private static class ShopData {
        LocalDateTime timestamp;
        BlockPos playerPosition;
        String owner = "N/A";
        int stock = -1;
        String item = "N/A";
        int buyQuantity = -1;
        int buyPrice = -1;
        int sellQuantity = -1;
        int sellPrice = -1;
        String claimInfo = "N/A";

        boolean isValid() {
            return !owner.equals("N/A") || !item.equals("N/A") || stock != -1;
        }

        String toLogString() {
            return String.format("SHOP | Timestamp: %s | PlayerPos: %d,%d,%d | Owner: %s | Stock: %d | Item: %s | Buy: %d for %d | Sell: %d for %d | Claim: %s",
                    (timestamp != null) ? timestamp.format(TIMESTAMP_FORMATTER) : "N/A",
                    (playerPosition != null) ? playerPosition.getX() : 0,
                    (playerPosition != null) ? playerPosition.getY() : 0,
                    (playerPosition != null) ? playerPosition.getZ() : 0,
                    owner, stock, item, buyQuantity, buyPrice, sellQuantity, sellPrice, claimInfo);
        }
    }
}