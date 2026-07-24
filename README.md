# Respondo Android SDK

**Respondo is an AI-powered customer support platform — an AI agent plus seamless human handoff. This SDK puts that support experience right inside your Android app, built with Kotlin and Jetpack Compose.**

## Why Respondo

Respondo answers your customers instantly. An AI agent resolves questions in real time from your own knowledge base, and escalates to a human teammate the moment a conversation needs one — with full context, no repetition. Every channel (in-app chat, email, Slack, Discord, Telegram, WhatsApp) lands in the same shared inbox, so support stays in one place.

`respondo-sdk` is the Android client for that platform: a drop-in chat surface for Compose apps, with a Fragment wrapper for View-based ones.

## Features

- **AI + human support chat** in a Material 3 `ModalBottomSheet`, themed from your Respondo widget config.
- **Realtime messaging** over WebSocket, with automatic fallback to SSE and polling plus reconnect.
- **File attachments** — pick, upload, and preview images and files in the chat.
- **Push notifications** (FCM / APNs) — the host app owns the token; the SDK registers it and routes taps back to the right conversation.
- **Identity verification** with HMAC-signed users — the trusted pattern you already know from tools like Intercom.
- **Engagement surfaces**: surveys (NPS / CSAT), news, checklists, and proactive messages.
- **8 UI languages** with automatic locale detection.
- **Compose-first, with a Fragment wrapper** (`RespondoChatFragment`) for XML / View-based projects.

## Requirements

- **minSdk 24**
- **Kotlin 2.x**
- **Android Gradle Plugin 8**

## Install

The library is published to **Maven Central** as `ai.respondo:respondo-sdk`.

```kotlin
// build.gradle.kts (app module)
dependencies {
    implementation("ai.respondo:respondo-sdk:0.1.0")
}
```

Make sure `mavenCentral()` is in your repositories (it is by default in a standard Android project).

## Quick start

```kotlin
import ai.respondo.sdk.Respondo
import ai.respondo.sdk.RespondoConfig
import ai.respondo.sdk.ui.RespondoChatHost

// 1. Initialise once — e.g. in Application.onCreate or your entry Activity.
Respondo.init(
    context = this,
    config = RespondoConfig(
        agentId = "<agent-uuid>",
        channelId = "<channel-uuid>",
    ),
)

// 2. Mount the chat host once, at the root of your Compose tree.
setContent {
    Box(Modifier.fillMaxSize()) {
        MyAppContent()
        RespondoChatHost() // presents the bottom-sheet when Respondo.open() is called
    }
}

// 3. Open the chat from anywhere (a support button, a menu item, …).
Respondo.open()
```

Using XML / Views instead of Compose? Add the Fragment wrapper in place of `RespondoChatHost()`:

```kotlin
supportFragmentManager.beginTransaction()
    .add(android.R.id.content, RespondoChatFragment())
    .commit()
```

### Identify a signed-in user

```kotlin
import ai.respondo.sdk.RespondoIdentity

Respondo.identify(
    RespondoIdentity(
        userId = "u_123",
        email = "jane@example.com",
        userHash = "<hmac-from-your-backend>",
    ),
)

Respondo.reset() // on logout: revoke the session and start a fresh anonymous visitor
```

`userHash` is an HMAC computed by **your** backend over the signed identity — the SDK never computes it and the secret must never ship in the app. See the identity verification guide in the docs at https://respondo.ai/docs. Without a `userHash`, the chat simply works anonymously.

### Push notifications

```kotlin
import ai.respondo.sdk.RespondoPushPayload

// Your app owns the token (e.g. via Firebase Cloud Messaging); the SDK only registers it.
Respondo.setPushToken(fcmToken)
Respondo.clearPushToken() // on logout

// On an incoming data push or a tap on it:
val payload = RespondoPushPayload.from(remoteMessage.data)
if (payload != null) Respondo.handlePush(payload)
```

### Observe unread state

```kotlin
lifecycleScope.launch {
    Respondo.unreadCount.collect { count -> badge.setCount(count) }
}
```

## Where to get agentId and channelId

Open the **Respondo dashboard → Channels → Widget** — the agent and channel IDs are shown there. No API key is required: the SDK talks to your public widget channel.

## License

MIT.

## Links

- Product and dashboard: **https://respondo.ai**
- Documentation and guides: **https://respondo.ai/docs**

---

**Ready to add AI-powered support to your Android app?** Get started at **https://respondo.ai**.
