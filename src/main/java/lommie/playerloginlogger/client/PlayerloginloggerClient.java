package lommie.playerloginlogger.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.*;
import java.nio.file.Files;
import java.time.*;
import java.util.*;

public class PlayerloginloggerClient implements ClientModInitializer {
    private static final File SAVE_FILE = new File("player_login_logger_logs.dat");
    private static final File CONFIG_FILE = new File("config/player_login_logger/messages.json");
    private ArrayList<UUID> lastPlayers = new ArrayList<>();

    // Config class for messages with text and color
    private static class MessageConfig {
        MessageEntry join_message;
        MessageEntry first_time_message;

        static class MessageEntry {
            String text;
            String color;
        }
    }

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            if (c.world == null) return;

            ArrayList<UUID> leftPlayers = new ArrayList<>();
            ArrayList<UUID> joinedPlayers = new ArrayList<>();
            ArrayList<UUID> currentPlayers = new ArrayList<>();

            c.world.getPlayers().forEach(i -> currentPlayers.add(i.getUuid()));

            if (!lastPlayers.contains(c.player.getUuid())) {
                lastPlayers = currentPlayers;
            }

            for (UUID id : lastPlayers) {
                if (!currentPlayers.contains(id)) {
                    leftPlayers.add(id);
                }
            }

            for (UUID id : currentPlayers) {
                if (!lastPlayers.contains(id)) {
                    joinedPlayers.add(id);
                }
            }

            for (UUID id : leftPlayers) {
                saveLeftDate(id, c.getCurrentServerEntry().address, LocalDateTime.now());
            }

            for (UUID id : joinedPlayers) {
                joinMessage(id, c);
            }

            lastPlayers = currentPlayers;
        });

        ClientPlayConnectionEvents.DISCONNECT.register((p, c) -> {
            ArrayList<UUID> currentPlayers = new ArrayList<>();
            c.world.getPlayers().forEach(i -> currentPlayers.add(i.getUuid()));
            for (UUID id : currentPlayers) {
                saveLeftDate(id, c.getCurrentServerEntry().address, LocalDateTime.now());
            }
        });
    }

    private void joinMessage(UUID id, MinecraftClient c) {
        MessageConfig config = loadConfig();
        LocalDateTime lastSeen = loadLeftDate(id, c.getCurrentServerEntry().address);
        String message = lastSeen == null ? config.first_time_message.text : config.join_message.text;
        String color = lastSeen == null ? config.first_time_message.color : config.join_message.color;
        c.player.sendMessage(replaceMessage(id, c, message, color), false);
    }

    private MutableText replaceMessage(UUID id, MinecraftClient c, String message, String color) {
        LocalDateTime leftDate = loadLeftDate(id, c.getCurrentServerEntry().address);
        if (leftDate == null) {
            leftDate = LocalDateTime.now(); // Fallback for first-time message
        }

        message = message.replace("$(date)", leftDate.getDayOfMonth() + "/" + leftDate.getMonthValue() + "/" + leftDate.getYear());
        message = message.replace("$(time)", leftDate.getMinute() + " minutes " + leftDate.getHour() + " hours");

        String playerName = c.world.getPlayerByUuid(id) != null ? c.world.getPlayerByUuid(id).getDisplayName().getString() : "{" + id.toString() + "}";
        message = message.replace("$(player)", playerName);

        Duration since = Duration.between(leftDate, LocalDateTime.now());
        message = message.replace("$(since)", ((int) since.toDaysPart()) + " days " + since.toHoursPart() + " hours " + since.toMinutesPart() + " minutes");

        String serverName = c.getCurrentServerEntry().address;
        message = message.replace("$(server)", serverName);

        // Apply color formatting
        Formatting formatting = Formatting.byName(color.toUpperCase());
        if (formatting == null) {
            formatting = Formatting.WHITE; // Fallback to white if color is invalid
        }

        return Text.literal(message).formatted(formatting);
    }

    private void saveLeftDate(UUID id, String address, LocalDateTime date) {
        NbtCompound root = null;

        if (!SAVE_FILE.exists()) {
            try {
                if (SAVE_FILE.getParentFile() != null) {
                    Files.createDirectories(SAVE_FILE.getParentFile().toPath());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            root = new NbtCompound();
        } else {
            try {
                root = NbtIo.read(SAVE_FILE.toPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        NbtCompound idsAndDates = root.getCompound(address);
        if (idsAndDates.isEmpty()) {
            root.put(address, new NbtCompound());
            idsAndDates = root.getCompound(address);
        }

        idsAndDates.putString(id.toString(), date.toString());

        try {
            NbtIo.write(root, SAVE_FILE.toPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private LocalDateTime loadLeftDate(UUID id, String address) {
        if (!SAVE_FILE.exists()) return null;

        try {
            NbtCompound root = NbtIo.read(SAVE_FILE.toPath());
            if (!root.contains(address)) {
                return null;
            }
            NbtCompound idsAndDates = root.getCompound(address);
            String time = idsAndDates.getString(id.toString());
            if (time.isEmpty()) {
                return null;
            }
            return LocalDateTime.parse(time);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private MessageConfig loadConfig() {
        MessageConfig defaultConfig = new MessageConfig();
        defaultConfig.join_message = new MessageConfig.MessageEntry();
        defaultConfig.join_message.text = "Player $(player) joined at $(date) $(time), last seen $(since) ago on $(server).";
        defaultConfig.join_message.color = "yellow";
        defaultConfig.first_time_message = new MessageConfig.MessageEntry();
        defaultConfig.first_time_message.text = "$(player) joined the game. (First time seen on this server)";
        defaultConfig.first_time_message.color = "green";

        if (!CONFIG_FILE.exists()) {
            try {
                Files.createDirectories(CONFIG_FILE.getParentFile().toPath());
                String json = new GsonBuilder().setPrettyPrinting().create().toJson(defaultConfig);
                Files.writeString(CONFIG_FILE.toPath(), json);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        try (Reader reader = new FileReader(CONFIG_FILE)) {
            return new Gson().fromJson(reader, MessageConfig.class);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return defaultConfig;
    }
}
