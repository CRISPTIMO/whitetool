# WhiteTool

Spigot/Paper плагин для управления Minecraft сервером через Telegram. Вайтлист из групповой беседы, выполнение команд и стриминг консоли через личку бота. Работает на серверах 1.13–1.21.

## Сборка

```powershell
& ".tools\apache-maven-3.9.6\bin\mvn.cmd" -q clean package
```

Результат: `target\WhiteTool-1.0.0.jar` → кинуть в `plugins/` сервера.

## Настройка

Скопируй `config.yml` и вставь `telegram.bot-token`. Токен выдаёт `@BotFather`.
