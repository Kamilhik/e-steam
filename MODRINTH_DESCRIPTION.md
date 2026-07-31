# e4steam

## Русский

**e4steam** позволяет играть с друзьями в одиночных мирах Minecraft через Steam — без выделенного сервера и настройки роутера.

Откройте мир для сети, выберите доступ для друзей Steam или режим подключения только по приглашению и отправьте приглашение через оверлей Steam. Также друг может подключиться по короткому адресу `s-...steam` через меню прямого подключения Minecraft.

### Возможности

- игра с друзьями в обычном одиночном мире;
- приглашения через оверлей Steam по сочетанию **Shift + Tab**;
- отдельные режимы «Для друзей Steam» и «Только по приглашению»;
- короткий адрес для прямого подключения;
- цветные кнопки в чате для копирования адреса, приглашения друзей и закрытия соединения;
- подтверждение перед закрытием соединения;
- автоматическое закрытие соединения при выходе из мира;
- повторный запуск соединения командой `/e4steam start`;
- поддержка нескольких языков.

### Как начать игру

1. Запустите Steam и войдите в свой аккаунт.
2. Зайдите в одиночный мир Minecraft.
3. Откройте меню паузы и нажмите **«Открыть для сети»**.
4. Выберите **«Для друзей Steam»** или **«Только по приглашению»**.
5. Пригласите друга через синюю кнопку в чате или оверлей Steam.

Друг также может скопировать полученный адрес `s-...steam`, открыть **«Сетевая игра» → «Прямое подключение»** и вставить его в поле адреса сервера.

У владельца мира и подключающихся друзей должны быть запущены Steam и e4steam. Версии Minecraft, загрузчика и мода должны совпадать.

### Команды

- `/e4steam start` — открыть соединение повторно;
- `/e4steam invite` — открыть приглашение друзей;
- `/e4steam restart` — перезапустить соединение;
- `/e4steam doctor` — проверить готовность Steam и мода.

> e4steam находится на стадии раннего тестирования. Проект создан и поддерживается **Kamilchik** и является отдельным неофициальным форком e4mc.

---

## English

**e4steam** lets you play with friends in your singleplayer Minecraft worlds through Steam — without a dedicated server or router configuration.

Open your world to the network, choose Steam Friends or Invite Only access, and send an invitation through the Steam overlay. Friends can also join through Minecraft's Direct Connection menu using a short `s-...steam` address.

### Features

- play together in a regular singleplayer world;
- invite friends through the Steam overlay with **Shift + Tab**;
- separate Steam Friends and Invite Only access modes;
- short addresses for direct connections;
- colored chat buttons for copying the address, inviting friends, and closing the connection;
- confirmation before closing an active connection;
- automatic shutdown when the host leaves the world;
- reopen a connection with `/e4steam start`;
- support for multiple languages.

### How to play

1. Start Steam and sign in to your account.
2. Enter a singleplayer Minecraft world.
3. Open the pause menu and select **Open to LAN**.
4. Choose **Steam Friends** or **Invite Only**.
5. Invite a friend using the blue chat button or the Steam overlay.

A friend can also copy the provided `s-...steam` address, open **Multiplayer → Direct Connection**, and paste it into the server address field.

The host and every joining player need Steam and e4steam running. Their Minecraft version, mod loader, and e4steam version must match.

### Commands

- `/e4steam start` — open the connection again;
- `/e4steam invite` — open the friend invitation interface;
- `/e4steam restart` — restart the connection;
- `/e4steam doctor` — check whether Steam and the mod are ready.

> e4steam is currently in early testing. The project is created and maintained by **Kamilchik** and is a separate, unofficial fork of e4mc.
