package ru.whitetool;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Минимальный клиент Telegram Bot API на long polling.
 * Без внешних зависимостей: HttpURLConnection + Gson (есть в ядре сервера).
 */
public class TelegramBot {

    private static final int POLL_TIMEOUT_SECONDS = 30;

    private final String token;
    private final String baseUrl;
    private final Proxy proxy;
    private final Logger logger;
    private final Gson gson = new Gson();

    private volatile boolean running = false;
    private Thread pollThread;
    private long offset = 0;
    private String botUsername = "";

    /**
     * @param baseUrl   базовый адрес API (обычно https://api.telegram.org, но можно
     *                  указать свой обратный прокси — обход блокировки Telegram)
     * @param proxy     SOCKS/HTTP прокси или null для прямого соединения
     * @param proxyUser логин прокси (пусто — без авторизации)
     * @param proxyPass пароль прокси
     */
    public TelegramBot(String token, String baseUrl, Proxy proxy,
                       String proxyUser, String proxyPass, Logger logger) {
        this.token = token;
        this.baseUrl = (baseUrl == null || baseUrl.trim().isEmpty())
                ? "https://api.telegram.org"
                : stripTrailingSlash(baseUrl.trim());
        this.proxy = proxy;
        this.logger = logger;

        // Авторизация на прокси (в т.ч. Basic через HTTPS-туннель у HTTP-прокси)
        if (proxy != null && proxyUser != null && !proxyUser.isEmpty()) {
            final String user = proxyUser;
            final char[] pass = (proxyPass == null ? "" : proxyPass).toCharArray();
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() == RequestorType.PROXY) {
                        return new PasswordAuthentication(user, pass);
                    }
                    return null;
                }
            });
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public String getBotUsername() {
        return botUsername;
    }

    /** Запускает поток long polling. Подключение (getMe) выполняется в самом потоке
     *  с бесконечными повторами — сервер может стартовать раньше, чем появится сеть. */
    public void start(Consumer<JsonObject> onMessage) {
        running = true;
        pollThread = new Thread(() -> pollLoop(onMessage), "WhiteTool-Telegram-Poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    public void stop() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    private void pollLoop(Consumer<JsonObject> onMessage) {
        // Фаза 1: подключение. Повторяем до успеха — переживает отсутствие сети при старте сервера.
        while (running && botUsername.isEmpty()) {
            JsonObject me = call("getMe", new JsonObject());
            if (me != null && me.has("username")) {
                botUsername = me.get("username").getAsString();
                logger.fine("[WhiteTool] connected as @" + botUsername);
            } else {
                logger.fine("[WhiteTool] telegram unreachable, retry in 30s");
                sleepQuiet(30000);
            }
        }

        // allowed_updates не меняется — строим один раз, экономим аллокации на каждый опрос
        JsonElement allowedUpdates = gson.toJsonTree(new String[]{"message"});

        // Фаза 2: long polling. Любая ошибка = пауза и повтор, поток не умирает никогда.
        // Одно удерживаемое соединение на 30 сек — трафик близок к нулю, когда сообщений нет.
        while (running) {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("timeout", POLL_TIMEOUT_SECONDS);
                params.addProperty("offset", offset);
                params.add("allowed_updates", allowedUpdates);

                JsonElement result = callRaw("getUpdates", params, (POLL_TIMEOUT_SECONDS + 10) * 1000);
                if (result == null || !result.isJsonArray()) {
                    sleepQuiet(5000);
                    continue;
                }
                JsonArray updates = result.getAsJsonArray();
                long offsetBefore = offset;
                for (JsonElement el : updates) {
                    // Ошибка в одном обновлении не должна ронять обработку остальных
                    try {
                        JsonObject update = el.getAsJsonObject();
                        offset = Math.max(offset, update.get("update_id").getAsLong() + 1);
                        if (update.has("message")) {
                            onMessage.accept(update.getAsJsonObject("message"));
                        }
                    } catch (Exception ex) {
                        logger.fine("[WhiteTool] update error: " + ex.getMessage());
                    }
                }
                // Страховка от busy loop: пачка не пустая, но offset не сдвинулся
                if (updates.size() > 0 && offset == offsetBefore) {
                    offset++;
                    sleepQuiet(5000);
                }
            } catch (Exception ex) {
                if (running) {
                    logger.fine("[WhiteTool] poll error: " + ex.getMessage() + " — retry in 5s");
                    sleepQuiet(5000);
                }
            }
        }
    }

    /** Отправка обычного текстового сообщения. */
    public void sendMessage(long chatId, String text) {
        sendMessage(chatId, text, false);
    }

    /** asHtml=true — текст уже содержит HTML-разметку (например, <pre>). */
    public void sendMessage(long chatId, String text, boolean asHtml) {
        if (text == null || text.isEmpty()) {
            return;
        }
        // Telegram ограничивает сообщение 4096 символами — режем на части
        int maxLen = 3900;
        int index = 0;
        while (index < text.length()) {
            int end = Math.min(index + maxLen, text.length());
            String part = text.substring(index, end);
            index = end;

            JsonObject params = new JsonObject();
            params.addProperty("chat_id", chatId);
            if (asHtml) {
                params.addProperty("text", "<pre>" + escapeHtml(part) + "</pre>");
                params.addProperty("parse_mode", "HTML");
            } else {
                params.addProperty("text", part);
            }
            call("sendMessage", params);
        }
    }

    /** Статус участника чата: creator / administrator / member / left / kicked, либо null при ошибке. */
    public String getChatMemberStatus(long chatId, long userId) {
        JsonObject params = new JsonObject();
        params.addProperty("chat_id", chatId);
        params.addProperty("user_id", userId);
        JsonObject result = call("getChatMember", params);
        if (result != null && result.has("status")) {
            return result.get("status").getAsString();
        }
        return null;
    }

    public static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Вызов метода API, ожидающий объект в result. */
    private JsonObject call(String method, JsonObject params) {
        JsonElement result = callRaw(method, params, 15000);
        return (result != null && result.isJsonObject()) ? result.getAsJsonObject() : null;
    }

    private JsonElement callRaw(String method, JsonObject params, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/bot" + token + "/" + method);
            conn = (HttpURLConnection) (proxy != null ? url.openConnection(proxy) : url.openConnection());
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);

            byte[] body = gson.toJson(params).getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            // new JsonParser().parse(...) вместо JsonParser.parseString(...) —
            // работает и на старом Gson 2.8.0 из ядер 1.13–1.17, и на новых
            @SuppressWarnings("deprecation")
            JsonObject response = new JsonParser().parse(sb.toString()).getAsJsonObject();
            if (!response.has("ok") || !response.get("ok").getAsBoolean()) {
                String desc = response.has("description") ? response.get("description").getAsString() : "unknown";
                // 409 = где-то запущен второй экземпляр бота, 401 = плохой токен
                logger.fine("[WhiteTool] api " + method + ": " + desc);
                return null;
            }
            return response.get("result");
        } catch (Exception ex) {
            logger.fine("[WhiteTool] api " + method + " failed: " + ex.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
