# AI Messages (Android)

A native **SMS/MMS texting app** that replaces your phone's default messaging
app, with **Claude built in**. Open any conversation and tap **Summarize** to get
a one-line recap, or **Suggest reply** to drop a ready-to-send response (in your
chosen tone) straight into the compose box — powered by Claude
(`claude-haiku-4-5`) using your own API key.

This is a real default SMS app: it sends and receives texts itself — no floating
bubble, no overlay.

## How it works

| Piece | Role |
|------|------|
| `MainActivity` | Conversation list (the home screen). Requests the SMS permissions and the default-SMS-app role, then shows your threads. |
| `ThreadActivity` | A single conversation: message bubbles, a compose box, **Send**, and the **Summarize** / **Suggest reply** Claude actions. |
| `SmsRepository` | Reads threads & messages from the system Telephony provider, sends texts via `SmsManager`, and resolves contact names. |
| `SmsDeliverReceiver` | Receives incoming SMS (as the default app), writes them to the inbox, and posts a notification. |
| `MmsDeliverReceiver` / `HeadlessSmsSendService` | Required components so the app is eligible to be the default SMS app (MMS receipt + "respond via message"). |
| `ClaudeClient` | Calls the Claude Messages API (`POST /v1/messages`) directly over HTTPS, using structured outputs to get back `{summary, suggested_reply}`. |
| `SettingsStore` | Stores your API key **encrypted** on-device (`EncryptedSharedPreferences`). |
| `SettingsActivity` | Paste your Claude API key and set the reply tone. |

## Setup

1. Install the APK (see the **Build / install** section below) on a device
   running **Android 8.0 (API 26)+** that has a SIM / can send SMS.
2. Open **AI Messages**. Tap the setup banner to **allow SMS & contacts**, then
   **make it your default SMS app** when prompted.
3. Open **Settings** (gear icon) → paste your **Claude API key**
   (get one at https://console.anthropic.com) and set a **reply tone** → **Save**.
4. Open any conversation (or tap **+** to start one). Use **Summarize** to recap
   the thread, or **Suggest reply** to fill the compose box with a Claude-written
   reply you can edit before sending.

## Build / install

A GitHub Actions workflow (`.github/workflows/build-apk.yml`) compiles a debug
APK on every push and publishes it to the **`latest-debug`** GitHub Release, so
it can be downloaded directly on a phone (no sign-in) and sideloaded:

> https://github.com/RandyRTorres/IV-sedation-course/releases/latest

To build locally, open the project in **Android Studio** (Giraffe or newer) and
let it sync, or run `gradle assembleDebug` (Gradle 8.7).

## Privacy

- The API key is stored encrypted on the device and is sent **only** to
  `api.anthropic.com` in the request header.
- Conversation text is sent to Claude **only when you tap** Summarize or
  Suggest reply. Nothing is sent anywhere else; there is no backend server.

## Notes / limitations

- **SMS/MMS only.** This replaces your texting app for SMS. It does **not** — and
  on Android cannot — replace WhatsApp, iMessage, Instagram, Telegram, etc.;
  those are closed platforms no third-party app can own.
- **MMS** (picture messages) are received-eligible but not fully rendered in this
  version — the focus is SMS text conversations.
- **iPhone:** not possible — iOS does not allow third-party default messaging
  apps. This design is Android-only by necessity.
