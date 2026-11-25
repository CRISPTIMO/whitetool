package ru.whitetool;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;

import java.util.Queue;
import java.util.regex.Pattern;

/**
 * Разбирает сообщения из Telegram и выполняет команды.
 * Вызывается из потока long polling — всё, что трогает Bukkit API,
 * прогоняется через планировщик на главный поток.
 */
public class CommandRouter {

    private final WhiteToolPlugin plugin;
    private final TelegramBot bot;
    private final ConsoleWatcher console;

    public CommandRouter(WhiteToolPlugin plugin, TelegramBot bot, ConsoleWatcher console) {
        this.plugin = plugin;
        this.bot = bot;
        this.console = console;
    }

    public void handleMessage(JsonObject message) {
        if (!message.has("text") || !message.has("from") || !message.has("chat")) {
            return;
        }
        String text = message.get("text").getAsString().trim();
        if (!text.startsWith("/")) {
            return;
        }

        JsonObject chat = message.getAsJsonObject("chat");
        long chatId = chat.get("id").getAsLong();
        String chatType = chat.get("type").getAsString();
        long userId = message.getAsJsonObject("from").get("id").getAsLong();

        // /whitelist@MyBot ник -> /whitelist ник
        String[] parts = text.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        int at = command.indexOf('@');
        if (at > 0) {
            String target = command.substring(at + 1);
            if (!target.equalsIgnoreCase(bot.getBotUsername())) {
                return; // команда адресована другому боту
            }
            command = command.substring(0, at);
        }
        String args = parts.length > 1 ? parts[1].trim() : "";

        if (chatType.equals("private")) {
            handlePrivate(chatId, userId, command, args);
        } else if (chatType.equals("group") || chatType.equals("supergroup")) {
            handleGroup(chatId, userId, command, args);
        }
    }

    // ---------- Группа: только вайтлист ----------

    private void handleGroup(long chatId, long userId, String command, String args) {
        // /id в группе доступен только владельцам из admin-ids (нужен один раз,
        // чтобы узнать group-chat-id для конфига); работает даже до настройки группы
        if (command.equals("/id")) {
            if (plugin.getAdminIds().contains(userId)) {
                bot.sendMessage(chatId, "ID этого чата: " + chatId + "\nВаш user ID: " + userId);
            }
            return;
        }
        if (!command.equals("/whitelist") && !command.equals("/unwhitelist")) {
            return; // в группе бот реагирует только на вайтлист
        }
        long groupId = plugin.getGroupChatId();
        if (groupId == 0 || chatId != groupId) {
            return; // чужая группа — молчим
        }
        if (!isGroupOperator(chatId, userId)) {
            return; // не админ — полная тишина, бот не выдаёт себя
        }
        doWhitelist(chatId, args, command.equals("/whitelist"));
    }

    private boolean isGroupOperator(long chatId, long userId) {
        if (plugin.getAdminIds().contains(userId)) {
            return true;
        }
        if (!plugin.isAllowGroupAdmins()) {
            return false;
        }
        String status = bot.getChatMemberStatus(chatId, userId);
        return "creator".equals(status) || "administrator".equals(status);
    }

    // ---------- ЛС: полный набор ----------

    private void handlePrivate(long chatId, long userId, String command, String args) {
        if (!plugin.getAdminIds().contains(userId)) {
            return; // посторонним в ЛС не отвечаем вообще — бота "не существует"
        }

        switch (command) {
            case "/id":
                bot.sendMessage(chatId, "ID этого чата: " + chatId + "\nВаш user ID: " + userId);
                break;

            case "/start":
            case "/help":
                bot.sendMessage(chatId, helpText());
                break;

            case "/whitelist":
                doWhitelist(chatId, args, true);
                break;

            case "/unwhitelist":
                doWhitelist(chatId, args, false);
                break;

            case "/wlist":
                runConsoleCommand(chatId, "whitelist list");
                break;

            case "/online": {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    StringBuilder sb = new StringBuilder("Онлайн (" + Bukkit.getOnlinePlayers().size() + "):");
                    Bukkit.getOnlinePlayers().forEach(p -> sb.append("\n• ").append(p.getName()));
                    bot.sendMessage(chatId, sb.toString());
                });
                break;
            }

            case "/cmd": {
                if (args.isEmpty()) {
                    bot.sendMessage(chatId, "Использование: /cmd <команда>\nНапример: /cmd op Steve");
                    break;
                }
                String cmd = args.startsWith("/") ? args.substring(1) : args;
                runConsoleCommand(chatId, cmd);
                break;
            }

            case "/console": {
                if (args.equalsIgnoreCase("on")) {
                    if (console.subscribe(chatId)) {
                        bot.sendMessage(chatId, "🟢 Трансляция консоли включена. /console off — выключить.");
                    } else {
                        bot.sendMessage(chatId, "Трансляция уже включена.");
                    }
                } else if (args.equalsIgnoreCase("off")) {
                    if (console.unsubscribe(chatId)) {
                        bot.sendMessage(chatId, "🔴 Трансляция консоли выключена.");
                    } else {
                        bot.sendMessage(chatId, "Трансляция и так выключена.");
                    }
                } else {
                    bot.sendMessage(chatId, "Использование: /console on | /console off\nСейчас: "
                            + (console.isSubscribed(chatId) ? "включена 🟢" : "выключена 🔴"));
                }
                break;
            }

            default:
                bot.sendMessage(chatId, "Неизвестная команда. /help — список команд.");
        }
    }

    private String helpText() {
        return "🤖 WhiteTool — управление сервером\n\n"
                + "Вайтлист:\n"
                + "/whitelist <ник> — добавить в вайтлист\n"
                + "/unwhitelist <ник> — убрать из вайтлиста\n"
                + "/wlist — показать вайтлист\n\n"
                + "Сервер:\n"
                + "/cmd <команда> — выполнить команду в консоли (например /cmd op Steve)\n"
                + "/console on|off — трансляция консоли сюда\n"
                + "/online — кто на сервере\n"
                + "/id — показать ID чата и ваш user ID\n\n"
                + "В группе работают /whitelist и /unwhitelist (только для админов группы).";
    }

    // ---------- Действия ----------

    private void doWhitelist(long chatId, String nick, boolean add) {
        if (nick.isEmpty()) {
            bot.sendMessage(chatId, "Использование: " + (add ? "/whitelist" : "/unwhitelist") + " <ник>");
            return;
        }
        Pattern pattern = plugin.getNickPattern();
        if (!pattern.matcher(nick).matches()) {
            bot.sendMessage(chatId, "❌ Ник «" + nick + "» некорректен (буквы, цифры, _, длина 3–16).");
            return;
        }
        String action = add ? "add" : "remove";
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Вайтлист НЕ прячем — пусть остаётся в логах как обычное действие
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "whitelist " + action + " " + nick);
            if (ok) {
                bot.sendMessage(chatId, add
                        ? "✅ Игрок " + nick + " добавлен в вайтлист."
                        : "✅ Игрок " + nick + " убран из вайтлиста.");
            } else {
                bot.sendMessage(chatId, "❌ Не удалось выполнить команду вайтлиста.");
            }
        });
    }

    /** Выполняет команду в консоли и присылает то, что консоль вывела в ответ. */
    private void runConsoleCommand(long chatId, String cmd) {
        Queue<String> collector = console.startCollecting();
        Bukkit.getScheduler().runTask(plugin, () -> {
            console.beginSuppress(); // прячем вывод команды из консоли/лога, но ловим для Telegram
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } finally {
                console.endSuppress();
            }
        });

        long waitTicks = plugin.getCommandOutputSeconds() * 20L;
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            console.stopCollecting(collector);
            if (collector.isEmpty()) {
                bot.sendMessage(chatId, "▶️ Команда отправлена: " + cmd + "\n(консоль ничего не вывела)");
            } else {
                bot.sendMessage(chatId, "▶️ " + cmd + "\n" + String.join("\n", collector), true);
            }
        }, waitTicks);
    }
}
