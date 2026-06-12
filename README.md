# Text Overlay Assistant (Android)

A floating overlay for your phone that reads **incoming messages from any app**,
**summarizes** them, and gives you an **AI-suggested reply** you can copy and
paste — powered by Claude (`claude-opus-4-8`) using your own API key.

When a message arrives, a small bubble floats on top of whatever you're doing.
Tap it and a panel shows the message, a one-line summary, and a ready-to-send
reply in your chosen tone. Hit **Copy reply** and paste it into your chat.

## How it works

| Piece | Role |
|------|------|
| `MessageNotificationListener` | A `NotificationListenerService` that catches notifications from messaging apps (SMS, WhatsApp, Messenger, Telegram, Signal, Instagram, …) and extracts the sender + text. |
| `OverlayService` | A foreground service that draws the draggable bubble + panel using `SYSTEM_ALERT_WINDOW` ("draw over other apps"). |
| `ClaudeClient` | Calls the Claude Messages API (`POST /v1/messages`) directly over HTTPS, using structured outputs to get back `{summary, suggested_reply}`. |
| `SettingsStore` | Stores your API key **encrypted** on-device (`EncryptedSharedPreferences`). |
| `MainActivity` | One-screen setup: paste key, set tone, grant the two permissions, start the overlay. |

## Setup

1. Open the project in **Android Studio** (Giraffe or newer) and let it sync.
   - Building from the command line needs the Gradle wrapper jar. If `./gradlew`
     is missing it, run `gradle wrapper --gradle-version 8.7` once (or just use
     Android Studio, which provides Gradle).
2. Build and install on a device running **Android 8.0 (API 26)+**.
3. In the app:
   - Paste your **Claude API key** (get one at https://console.anthropic.com) and **Save**.
   - Optionally set a **reply tone** (e.g. "friendly and concise", "professional", "playful").
   - Tap **Grant "draw over other apps"** and enable it.
   - Tap **Grant notification access** and enable it for this app.
   - Tap **Start overlay**.
4. Send yourself a text from another phone — the bubble pops up with a summary and a suggested reply.

## Privacy

- The API key is stored encrypted on the device and is sent **only** to
  `api.anthropic.com` in the request header.
- Incoming message text is sent to Claude to generate the summary and reply.
- Nothing is sent anywhere else; there is no backend server.

## Notes / where to extend

- **Which apps are watched:** edit `KNOWN_MESSAGING_APPS` in
  `MessageNotificationListener.kt`, or rely on the `CATEGORY_SOCIAL` fallback.
- **iPhone:** not possible — iOS does not allow drawing an overlay over other
  apps. This design is Android-only by necessity.
- **Auto-send replies:** intentionally *not* implemented — the app copies the
  reply to your clipboard so you stay in control. Sending on your behalf would
  require per-app `RemoteInput` integration.
