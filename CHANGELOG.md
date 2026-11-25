# Changelog

## 0.4.0

Upgrading from 0.3.0 — behavior to know before you ship:

- Every signed-in user becomes a contact at once: `identify()` with a
  `userId` or an `email` creates the contact without a chat, so Contacts
  grow by everyone who logs in to your app.
- The SDK reports app sessions (launch, return after 20 seconds or more in
  the background, user switch). Attributes and sessions need `channelId` in
  the config.
- The chat UI follows the conversation language the server sets, not the
  app or device locale.
- The public API only grows: existing integrations compile unchanged.

- Conversation rating. When a conversation is closed in a way that asks the
  customer for a rating (the same closes as in the web widget), the chat shows
  "How was your experience?" at the end of the conversation: a score from 1 to
  5, then an optional comment ("Tell us more", up to 1,000 characters) sent
  with Send. The score can be changed while the conversation stays closed, and
  the comment can be edited once with Edit. The score and the comment are
  saved as the conversation's rating, the same as from the web widget. The
  texts come from the server in the customer's language; the SDK's own strings
  are a fallback. Nothing to set up in the app.
- The chat language is the conversation language set by the server. The
  server takes it from the customer's own words, a teammate's choice, an
  explicit request from the customer, or the language saved on the contact,
  and keeps it for the conversation. The chat switches to it with the first
  reply of a new conversation and follows it from then on; before, the first
  conversation stayed in the device language until it ended. Until the server
  knows the language, the chat uses the device language (or `locale` from the
  SDK config); a conversation continued from an earlier launch starts in the
  language it already had. (The language comes from the `lang` field of every
  `/chat` response, the `conversation_updated` event, and, for a continued
  conversation, `visitor_language` in the widget config.)
- The SDK no longer guesses the language from the text the customer types:
  the chat changes language only when the server's conversation language
  changes.
- The SDK's own strings exist in English, Russian, Ukrainian, German, French,
  Spanish, Portuguese and Polish: in any other language they show in English,
  while the texts from the server follow the conversation language either
  way.
- Chinese conversations: the texts the server sends for the conversation
  (notices, the rating block) follow the customer's script, Traditional or
  Simplified. The SDK's own strings have no Chinese and show in English.
  (The server tags Chinese as `zh-Hans` or `zh-Hant`; the SDK reads these
  tags by their base code.)
- A quick-question chip, an AI suggestion, or the "files sent" text of an
  attachment without a message no longer switches the conversation away from
  the customer's language, even when written in your app's language: the SDK
  marks texts it sends on the customer's behalf, as the web widget does, and
  the server does not take the language from them. (Field `input_source`:
  `quick_question`, `suggestion`, `files_placeholder`, or `typed` for typed
  text; a retry keeps the mark.)
- Incoming markdown keeps paragraph spacing: a blank line between blocks
  renders as a paragraph gap instead of a plain line break.
- `#`…`######` headings render as a bold line without the hashes, and `> `
  quote lines render without the marker.
- `![alt](url)` images render as a link labelled with the alt text (or the
  URL), without a stray `!`.
- A numbered list starts from its first number and counts on, across blank
  lines: items typed as `1.` on every line, or a list split by blank lines,
  show 1, 2, 3; a list that starts at 3 shows 3, 4, 5.
- An escaped `\#` at the start of a line renders as a literal `#` (no
  backslash, no heading).
- Chat attachments are uploaded with `agent_id` and the conversation keys, and the upload no longer gets 401. In 0.3.0 every attachment upload failed.
- Calls made before the first `Respondo.init` (`setPushToken`, `handlePush`,
  `identify`, `track`, ...) are replayed by it, as the docs say (an `init`
  with an identity drops the earlier `identify` calls). Before, `init`
  dropped them, so a push tapped at cold start before `init` did not open
  its conversation or report its open.
- Cold-start push open: a push tapped before the SDK knows the push token
  (`getInitialMessage` answering before `getToken`) reports its open
  (`POST /api/v1/widget/push/opened`) as soon as `setPushToken` passes the
  token (up to 10 taps; `clearPushToken()` and `reset()` drop them). Before,
  that open was lost.
- `init(context, config, identity)`: the launch app session (the process's
  first foreground) is sent after the startup `/widget/identify` answers (or
  fails). Before, both went out together and the session could create the
  contact from the user id alone, without the email, so an imported row
  with that email was not merged. Every app session (launch, return from
  background, contact change), `track()` event (`POST /api/v1/widget/events`)
  and push token registration (`setPushToken`, re-registration after
  `identify()`) waits for any `/widget/identify` in flight (each at most the
  request timeout) the same way (sessions and events keep the identity they
  were made with); a repeated `identify()` with the same identity waits for
  the request in flight, and the same registration (token and identity) is
  not sent twice. A `destroy()` or a repeated `init` while the launch
  session waits does not lose it: the next `init` reports it (at once if the
  app is in the foreground).
- `identify()` with a `userId` or an `email` creates the contact right away
  (`POST /api/v1/widget/identify`), even with no properties — as signing a
  user in to Intercom's Messenger does — so a user who logs in and never
  chats is in Contacts, segments and Series. An anonymous identity with no
  properties still sends nothing; the same body is not sent twice.
- New `RespondoIdentity.signedUpAt` (`java.util.Date?`, Intercom's
  `signedUpAt`): when the person signed up in your product, sent as
  `signed_up_at` (Unix seconds). Without it the contact's signup date stays
  empty — the first app open is not a signup. A signed identity
  (`userHash`) can change a known date; an unsigned one only fills an empty
  date. The seven-argument constructor (up to `languageOverride`) is kept
  for Java callers and prebuilt libraries.
- `identify()` properties reach the contact right away: the SDK sends them to
  `POST /api/v1/widget/identify` (the call the web widget makes), not only
  with the first chat message, so segments, Series and Workflows see them
  for people who never chat. The same set is not sent twice; a failed send
  is retried by the next `identify()`. Identity verification (`userHash`)
  applies as on every widget call. Needs `channelId`.
- New `RespondoIdentity.languageOverride` (BCP-47, Intercom's
  `languageOverride`): the contact's explicit language, sent as the
  `language_override` property; it wins over a `language_override` key in
  `properties`. `null` leaves a stored language as it is.
- App sessions: the SDK reports `POST /api/v1/widget/app-session` when the
  app is really opened (the first foreground of the process the SDK sees —
  `Respondo.init` itself sends nothing, so a process started in the
  background by an FCM data push, WorkManager or a receiver opens no
  session), on a return to the foreground after at least 20 seconds in the
  background (measured from the first move to the background of that
  episode; a repeated background signal does not restart it)
  (Intercom: an app backgrounded for more than 20 seconds and then resumed is
  a new session; opens within 20 seconds are not counted) and when
  `identify()` switches to another known user — that session goes out after
  the user's `/widget/identify` call and carries the name. No push
  permission needed. The contact gets its app platform, first and last time
  seen in the app, session count, app version, and the device language
  (`RespondoConfig.locale` or the system locale) as its profile language
  when it has none. An anonymous open is recorded only for a visitor whose
  contact already exists (chat, push token, identify); it creates no lead.
  The SDK follows the process lifecycle itself (`ProcessLifecycleOwner`):
  rotation and moving between activities are not the background.
  `Respondo.onBackground()` / `onForeground()` now drive only realtime and
  are not needed for sessions. The launch is counted once per process: a
  repeated `Respondo.init` does not start one. `reset()` does not start a
  session. Needs
  `channelId`.
- New dependency: `androidx.lifecycle:lifecycle-process` (same `lifecycle`
  version as `lifecycle-runtime-compose`; brings `androidx.startup`). An app
  that removed `ProcessLifecycleInitializer` from the `androidx.startup`
  InitializationProvider must initialize it itself, or app sessions are not
  counted.
- The WebSocket `identify` frame declares the `app_sessions` feature: the
  server no longer counts this SDK's socket connect as app activity (the
  process may have been started in the background); app activity comes
  from `/widget/app-session`.
- Binary compatibility of `RespondoIdentity`: the old six-field constructor
  `(userId, email, name, userHash, metadata, properties)` is kept, so Java
  callers and prebuilt libraries calling it keep working. Not kept by the
  data class: the synthetic default-argument constructor, `copy()` (now seven
  parameters) and the new `component7()` — prebuilt Kotlin code that calls
  `RespondoIdentity(...)` with named or default arguments, or `copy(...)`,
  must be recompiled against this version. Source compatibility is
  unchanged.
- The chat's own notices speak the customer's language, not the app's UI
  locale: "handed to a person", "back to the AI assistant", "conversation
  closed", and the "Talk to a person" confirmation or failure now use the
  texts the server sends (`handover_card_text`, `status_texts`, the
  `/escalate` message). The SDK's own strings are only a fallback for an
  older backend or no network. A standard handover notice is shown once,
  without a second card under it.
- A handover with no notice of its own (an inbox without the AI agent) now
  shows the "handed to a person" card, as the other SDKs and the web widget
  do.
- A handover reply that echoes the customer's own message shows the
  "handed to a person" card instead of a second copy of that message. When
  polling, the closed and back-to-AI cards always use the texts that came
  with the same poll.
- Setup (works with any SDK version): take `agentId` and `channelId` from
  your Mobile SDK channel (Respondo dashboard → Settings › Channels) and pass
  both; conversations from the app then show as Mobile SDK in the inbox.

## 0.3.1 (not published; ships in 0.4.0)

- Bare `http://` and `https://` links in operator and assistant messages are
  clickable through `RespondoListener.onUrlRequested`, including links
  downgraded from rich HTML.
- Links are trimmed at sentence punctuation, keep balanced parentheses, and
  reject unsafe schemes, malformed hosts, control characters, and code spans.
- Chat messages show local calendar-day separators and message times.
