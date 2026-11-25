package ai.respondo.sdk

import ai.respondo.sdk.core.AppSessionTracker
import ai.respondo.sdk.core.IdentifySync
import ai.respondo.sdk.core.RespondoController
import ai.respondo.sdk.internal.InMemoryKeyValueStore
import ai.respondo.sdk.support.FakeHttpEngine
import ai.respondo.sdk.transport.ApiClient
import ai.respondo.sdk.transport.HttpBody
import ai.respondo.sdk.transport.HttpEngine
import ai.respondo.sdk.transport.HttpRequest
import ai.respondo.sdk.transport.HttpResponse
import ai.respondo.sdk.transport.IdentifyOutcome
import ai.respondo.sdk.transport.dto.AppSessionRequestDto
import ai.respondo.sdk.transport.dto.WidgetIdentifyRequestDto
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * identify() со свойствами — сразу `POST /widget/identify` (как веб-виджет), languageOverride —
 * свойством `language_override`; сессия приложения — `POST /widget/app-session`.
 */
class IdentifyAndAppSessionTest {

    private fun api(engine: FakeHttpEngine) = ApiClient("https://api.respondo.ai", engine)

    private fun HttpRequest?.json(): JsonObject =
        Json.parseToJsonElement((this?.body as HttpBody.Json).text).jsonObject

    // ==================== транспорт ====================

    @Test
    fun identify_postsSnakeCaseBodyWithProperties() = runTest {
        val engine = FakeHttpEngine { HttpResponse(200, """{"ok":true}""") }
        val ok = api(engine).identify(
            WidgetIdentifyRequestDto(
                agentId = "agent-1",
                channelId = "chan-1",
                visitorId = "v_1",
                email = "ann@example.com",
                userId = "u-1",
                userHash = "hash",
                name = "Ann",
                properties = mapOf("plan" to "pro", "language_override" to "de"),
            ),
        )
        assertTrue(ok)
        val req = engine.lastRequestFor("/api/v1/widget/identify")
        assertTrue(req!!.url.endsWith("/api/v1/widget/identify"))
        val body = req.json()
        assertEquals("agent-1", body["agent_id"]!!.jsonPrimitive.content)
        assertEquals("chan-1", body["channel_id"]!!.jsonPrimitive.content)
        assertEquals("v_1", body["visitor_id"]!!.jsonPrimitive.content)
        assertEquals("u-1", body["user_id"]!!.jsonPrimitive.content)
        assertEquals("hash", body["user_hash"]!!.jsonPrimitive.content)
        assertEquals("Ann", body["name"]!!.jsonPrimitive.content)
        val props = body["properties"]!!.jsonObject
        assertEquals("pro", props["plan"]!!.jsonPrimitive.content)
        assertEquals("de", props["language_override"]!!.jsonPrimitive.content)
    }

    @Test
    fun identify_reportsFailure() = runTest {
        val engine = FakeHttpEngine { HttpResponse(500, "") }
        assertFalse(api(engine).identify(WidgetIdentifyRequestDto(channelId = "chan-1", properties = mapOf("plan" to "pro"))))
    }

    @Test
    fun appSession_postsPlatformAppAndLocale() = runTest {
        val engine = FakeHttpEngine { HttpResponse(200, """{"ok":true}""") }
        api(engine).appSession(
            AppSessionRequestDto(
                agentId = "agent-1",
                channelId = "chan-1",
                visitorId = "v_1",
                platform = "android",
                appId = "com.example.app",
                appVersion = "3.4.1",
                sdkName = "sdk-android",
                sdkVersion = "0.3.1",
                locale = "ru-RU",
            ),
        )
        val req = engine.lastRequestFor("/api/v1/widget/app-session")
        val body = req.json()
        assertEquals("android", body["platform"]!!.jsonPrimitive.content)
        assertEquals("chan-1", body["channel_id"]!!.jsonPrimitive.content)
        assertEquals("com.example.app", body["app_id"]!!.jsonPrimitive.content)
        assertEquals("3.4.1", body["app_version"]!!.jsonPrimitive.content)
        assertEquals("sdk-android", body["sdk_name"]!!.jsonPrimitive.content)
        assertEquals("0.3.1", body["sdk_version"]!!.jsonPrimitive.content)
        assertEquals("ru-RU", body["locale"]!!.jsonPrimitive.content)
        // Анонимная сессия: пустые поля личности не уходят (explicitNulls = false).
        assertNull(body["email"])
        assertNull(body["user_id"])
    }

    @Test
    fun languageOverride_wins_over_properties_key() {
        val id = RespondoIdentity(properties = mapOf("plan" to "pro", "language_override" to "fr"), languageOverride = " de ")
        assertEquals(mapOf("plan" to "pro", "language_override" to "de"), id.effectiveProperties)
        assertEquals(mapOf("plan" to "pro"), RespondoIdentity(properties = mapOf("plan" to "pro"), languageOverride = " ").effectiveProperties)
    }

    @Test
    fun oldSevenFieldConstructor_stillWorks() {
        // Прежняя сигнатура (до signedUpAt) — Java и собранные библиотеки.
        val id = RespondoIdentity("u-1", "ann@example.com", "Ann", "hash", emptyMap(), mapOf("plan" to "pro"), "de")
        assertEquals("de", id.languageOverride)
        assertNull(id.signedUpAt)
        assertEquals(
            RespondoIdentity(userId = "u-1", email = "ann@example.com", name = "Ann", userHash = "hash", properties = mapOf("plan" to "pro"), languageOverride = "de"),
            id,
        )
    }

    @Test
    fun oldSixFieldConstructor_stillWorks() {
        // Прежняя сигнатура (до languageOverride) — Java и собранные библиотеки.
        val id = RespondoIdentity("u-1", "ann@example.com", "Ann", "hash", emptyMap(), mapOf("plan" to "pro"))
        assertEquals("u-1", id.userId)
        assertNull(id.languageOverride)
        assertEquals(RespondoIdentity(userId = "u-1", email = "ann@example.com", name = "Ann", userHash = "hash", properties = mapOf("plan" to "pro")), id)
    }

    // ==================== контроллер ====================

    /** Контекст без Android-рантайма: контроллеру нужен только applicationContext. */
    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private val identifyBodies = CopyOnWriteArrayList<String>()
    private val sessionBodies = CopyOnWriteArrayList<String>()
    private val registerBodies = CopyOnWriteArrayList<String>()
    private val eventBodies = CopyOnWriteArrayList<String>()
    /** Порядок запросов: "identify" / "session" / "register" / "event". */
    private val order = CopyOnWriteArrayList<String>()
    @Volatile private var identifyStatus = 200
    /** Ворота ответа `/widget/identify`: пока закрыты, ответ не приходит. */
    @Volatile private var identifyGate: CountDownLatch? = null

    private val engine = FakeHttpEngine { req ->
        val text = (req.body as? HttpBody.Json)?.text
        when {
            req.url.endsWith("/api/v1/widget/identify") -> {
                identifyBodies += text!!
                order += "identify"
                identifyGate?.await(5, TimeUnit.SECONDS)
                HttpResponse(identifyStatus, """{"ok":true}""")
            }
            req.url.endsWith("/api/v1/widget/push/register") -> {
                registerBodies += text!!
                order += "register"
                HttpResponse(200, """{"ok":true}""")
            }
            req.url.endsWith("/api/v1/widget/app-session") -> {
                sessionBodies += text!!
                order += "session"
                HttpResponse(200, """{"ok":true}""")
            }
            req.url.endsWith("/api/v1/widget/events") -> {
                eventBodies += text!!
                order += "event"
                HttpResponse(200, """{"ok":true}""")
            }
            req.url.contains("/widget/surveys") -> HttpResponse(200, """{"surveys":[]}""")
            else -> HttpResponse(200, """{"ok":true}""")
        }
    }

    private var now = 1_000_000L

    private fun controller(
        channelId: String? = "chan-1",
        tracker: AppSessionTracker = AppSessionTracker(clock = { now }),
        initialIdentity: RespondoIdentity = RespondoIdentity(),
    ) = RespondoController(
        TestContext(),
        RespondoConfig(agentId = "agent-1", channelId = channelId),
        initialIdentity,
        ApiClient("https://api.respondo.ai", engine),
        OkHttpClient(),
        InMemoryKeyValueStore(),
        sessionTracker = tracker,
        identifySync = IdentifySync(),
    )

    private fun awaitCount(list: List<String>, n: Int) = runBlocking {
        withTimeout(5_000) { while (list.size < n) delay(10) }
    }

    private fun settle() = runBlocking { delay(150) }

    @Test
    fun identifyWithProperties_postsRightAway_andSkipsAnIdenticalRepeat() {
        val c = controller()
        val identity = RespondoIdentity(
            userId = "u-1",
            userHash = "hash",
            properties = mapOf("plan" to "pro"),
            languageOverride = "de",
        )
        c.identify(identity)
        awaitCount(identifyBodies, 1)
        val body = Json.parseToJsonElement(identifyBodies[0]).jsonObject
        assertEquals("chan-1", body["channel_id"]!!.jsonPrimitive.content)
        assertEquals("u-1", body["user_id"]!!.jsonPrimitive.content)
        assertEquals("hash", body["user_hash"]!!.jsonPrimitive.content)
        val props = body["properties"]!!.jsonObject
        assertEquals("pro", props["plan"]!!.jsonPrimitive.content)
        assertEquals("de", props["language_override"]!!.jsonPrimitive.content)

        // Тот же вызов ещё раз — тот же кузов, второй запрос не уходит.
        c.identify(identity)
        settle()
        assertEquals(1, identifyBodies.size)

        // Другие свойства — новый запрос.
        c.identify(identity.copy(properties = mapOf("plan" to "team")))
        awaitCount(identifyBodies, 2)
        c.destroy()
    }

    /** Вход пользователя создаёт контакт (как Intercom Messenger): identify уходит и без свойств. */
    @Test
    fun identifyWithoutProperties_postsIdentify_theLoginCreatesThePerson() {
        // Приложение открыто (ON_START процесса уже был): вход другого контакта — его сессия.
        val c = controller(tracker = AppSessionTracker(clock = { now }).apply { processStarted() })
        c.identify(RespondoIdentity(email = "ann@example.com", name = "Ann"))
        awaitCount(sessionBodies, 1)
        awaitCount(identifyBodies, 1)
        // Сессия нового контакта — после ответа на identify.
        assertEquals(listOf("identify", "session"), order.toList())
        val body = Json.parseToJsonElement(identifyBodies[0]).jsonObject
        assertEquals("ann@example.com", body["email"]!!.jsonPrimitive.content)
        assertEquals("Ann", body["name"]!!.jsonPrimitive.content)
        assertTrue(body["properties"]!!.jsonObject.isEmpty())
        assertNull("дата регистрации не передана — не уходит", body["signed_up_at"])

        // Тот же вход ещё раз — тот же кузов, второй запрос не уходит.
        c.identify(RespondoIdentity(email = "ann@example.com", name = "Ann"))
        settle()
        assertEquals(1, identifyBodies.size)
        c.destroy()
    }

    /**
     * Пуш-токен — к контакту ПОСЛЕ ответа identify, как сессия: регистрация не опережает
     * `/widget/identify` и не создаёт контакт первой.
     */
    @Test
    fun pushReRegister_goesAfterTheIdentifyAnswer() {
        val c = controller()
        c.setPushToken("fcm-token")
        awaitCount(registerBodies, 1)
        order.clear()
        c.identify(RespondoIdentity(userId = "u-1"))
        awaitCount(registerBodies, 2)
        assertEquals("identify", order.first())
        assertEquals("register", order.last())
        assertEquals("u-1", Json.parseToJsonElement(registerBodies[1]).jsonObject["user_id"]!!.jsonPrimitive.content)
        c.destroy()
    }

    @Test
    fun identifyAnswer_createdFlag() = runTest {
        val body = WidgetIdentifyRequestDto(channelId = "chan-1", userId = "u-1", properties = emptyMap())
        var answer = """{"ok":true,"created":true}"""
        val engine = FakeHttpEngine { HttpResponse(200, answer) }
        assertEquals(IdentifyOutcome(sent = true, created = true), api(engine).identifyOutcome(body))
        answer = """{"ok":true}"""
        assertEquals(IdentifyOutcome(sent = true, created = false), api(engine).identifyOutcome(body))
        answer = ""
        assertEquals(IdentifyOutcome(sent = true, created = false), api(engine).identifyOutcome(body))
        val failing = FakeHttpEngine { HttpResponse(500, "") }
        assertEquals(IdentifyOutcome(sent = false, created = false), api(failing).identifyOutcome(body))
    }

    @Test
    fun anonymousIdentityWithoutProperties_doesNotPostIdentify() {
        val c = controller()
        c.identify(RespondoIdentity(name = "Anon", userHash = "hash"))
        settle()
        assertTrue(identifyBodies.isEmpty())
        c.destroy()
    }

    /** signedUpAt — дата регистрации в продукте, Unix-секундами в `signed_up_at`. */
    @Test
    fun signedUpAt_goesAsUnixSeconds() {
        val c = controller()
        c.identify(RespondoIdentity(userId = "u-8", signedUpAt = java.util.Date(1_717_149_600_900L)))
        awaitCount(identifyBodies, 1)
        val body = Json.parseToJsonElement(identifyBodies[0]).jsonObject
        assertEquals(1_717_149_600L, body["signed_up_at"]!!.jsonPrimitive.content.toLong())
        assertNull(RespondoIdentity(userId = "u-8").signedUpAtSeconds)
        c.destroy()
    }

    @Test
    fun failedIdentify_isRetriedByTheSameCall() {
        identifyStatus = 500
        val c = controller()
        val identity = RespondoIdentity(userId = "u-1", properties = mapOf("plan" to "pro"))
        c.identify(identity)
        awaitCount(identifyBodies, 1)
        settle()
        identifyStatus = 200
        c.identify(identity)
        awaitCount(identifyBodies, 2)
        c.destroy()
    }

    @Test
    fun noChannel_noIdentifyAndNoSession() {
        val c = controller(channelId = null)
        c.identify(RespondoIdentity(userId = "u-1", properties = mapOf("plan" to "pro")))
        settle()
        assertTrue(identifyBodies.isEmpty())
        assertTrue(sessionBodies.isEmpty())
        c.destroy()
    }

    @Test
    fun noChannel_firstForegroundDoesNotUseUpTheLaunch() {
        // Общий на процесс трекер: SDK без канала, затем re-init с каналом.
        val shared = AppSessionTracker(clock = { now })
        val silent = controller(channelId = null, tracker = shared)
        silent.onProcessForeground()
        settle()
        assertTrue("без канала отметки нет", sessionBodies.isEmpty())
        silent.destroy()
        now += 60_000
        val c = controller(tracker = shared)
        c.onProcessForeground()
        awaitCount(sessionBodies, 1)
        c.destroy()
    }

    @Test
    fun contactChange_reportsSessionForTheNewContact_foregroundNeedsBackgroundAnd20s() {
        val c = controller()
        // Приложение открыто (первый ON_START) — сессия запуска.
        c.onProcessForeground()
        awaitCount(sessionBodies, 1)
        // Аноним → пользователь: сессия новому контакту сразу, без порога.
        now += 1_000
        c.identify(RespondoIdentity(userId = "u-1", userHash = "hash"))
        awaitCount(sessionBodies, 2)
        sessionBodies.removeAt(0)
        val body = Json.parseToJsonElement(sessionBodies[0]).jsonObject
        assertEquals("android", body["platform"]!!.jsonPrimitive.content)
        assertEquals("u-1", body["user_id"]!!.jsonPrimitive.content)
        assertEquals("hash", body["user_hash"]!!.jsonPrimitive.content)
        assertEquals("sdk-android", body["sdk_name"]!!.jsonPrimitive.content)
        assertTrue(body["locale"]!!.jsonPrimitive.content.isNotBlank())

        // Тот же контакт (имя/хэш) — не новая сессия.
        c.identify(RespondoIdentity(userId = "u-1", name = "Ann", userHash = "hash"))
        // ON_START без ON_STOP — не сессия.
        now += 60_000
        c.onProcessForeground()
        settle()
        assertEquals(1, sessionBodies.size)
        // Смена контакта в фоне (identify из FCM-обработчика) — не сессия: её даст открытие.
        c.onProcessBackground()
        c.identify(RespondoIdentity(userId = "u-bg"))
        settle()
        assertEquals(1, sessionBodies.size)
        c.identify(RespondoIdentity(userId = "u-1", userHash = "hash"))
        now += 25_000
        c.onProcessForeground()
        awaitCount(sessionBodies, 2)

        // 5 с в фоне — та же сессия.
        c.onProcessBackground()
        now += 5_000
        c.onProcessForeground()
        settle()
        assertEquals(2, sessionBodies.size)

        // 25 с в фоне — новая сессия (Intercom: в фоне больше 20 с и возврат).
        c.onProcessBackground()
        now += 25_000
        c.onProcessForeground()
        awaitCount(sessionBodies, 3)

        // Host onBackground/onForeground ведут только реалтайм: сессий не заводят.
        c.onBackground()
        now += 60_000
        c.onForeground()
        settle()
        assertEquals(3, sessionBodies.size)

        // Выход в аноним (reset-подобный identify) — сессию не заводит.
        c.identify(RespondoIdentity())
        settle()
        assertEquals(3, sessionBodies.size)
        c.destroy()
    }

    @Test
    fun noSessionUntilTheProcessReallyComesToTheForeground() {
        // Процесс поднят в фоне (FCM data-пуш, WorkManager): контроллер есть, ON_START нет — сессии нет.
        val c = controller()
        settle()
        assertTrue(sessionBodies.isEmpty())
        // Человек открыл приложение — первый ON_START процесса: сессия запуска.
        c.onProcessForeground()
        awaitCount(sessionBodies, 1)
        val body = Json.parseToJsonElement(sessionBodies[0]).jsonObject
        assertEquals("android", body["platform"]!!.jsonPrimitive.content)
        assertEquals("chan-1", body["channel_id"]!!.jsonPrimitive.content)
        c.destroy()
    }

    /**
     * Сессия запуска (первый ON_START) ждёт ответа identify в полёте — как при
     * `init(context, config, identity)`: иначе отметка создавала контакт по user_id без email.
     */
    @Test
    fun launchSession_waitsForTheIdentifyInFlight() {
        val gate = CountDownLatch(1)
        identifyGate = gate
        val c = controller()
        c.identify(RespondoIdentity(userId = "u-1", email = "user@example.com", userHash = "hash"))
        awaitCount(identifyBodies, 1)
        c.onProcessForeground()
        settle()
        assertTrue("сессия запуска ждёт ответа identify", sessionBodies.isEmpty())
        gate.countDown()
        awaitCount(sessionBodies, 1)
        assertEquals(listOf("identify", "session"), order.toList())
        val body = Json.parseToJsonElement(sessionBodies[0]).jsonObject
        assertEquals("u-1", body["user_id"]!!.jsonPrimitive.content)
        assertEquals("user@example.com", body["email"]!!.jsonPrimitive.content)
        c.destroy()
    }

    /** Сбой identify сессию запуска не держит. */
    @Test
    fun launchSession_goesAfterAFailedIdentifyToo() {
        val gate = CountDownLatch(1)
        identifyGate = gate
        identifyStatus = 500
        val c = controller()
        c.identify(RespondoIdentity(userId = "u-1"))
        awaitCount(identifyBodies, 1)
        c.onProcessForeground()
        settle()
        assertTrue(sessionBodies.isEmpty())
        gate.countDown()
        awaitCount(sessionBodies, 1)
        c.destroy()
    }

    /**
     * `init(context, config, identity)`: стартовый identify ([RespondoController.start] →
     * [RespondoController.syncIdentity]) уходит первым, сессия запуска (первый ON_START) — после его ответа
     * и с email: иначе отметка создавала контакт по user_id без email.
     */
    @Test
    fun launchSession_withInitialIdentity_goesAfterTheStartupIdentify() {
        val gate = CountDownLatch(1)
        identifyGate = gate
        val c = controller(
            initialIdentity = RespondoIdentity(userId = "u-1", email = "user@example.com", userHash = "hash"),
        )
        c.syncIdentity()
        c.onProcessForeground()
        awaitCount(identifyBodies, 1)
        settle()
        assertTrue("сессия запуска ждёт ответа стартового identify", sessionBodies.isEmpty())
        gate.countDown()
        awaitCount(sessionBodies, 1)
        assertEquals(listOf("identify", "session"), order.toList())
        val body = Json.parseToJsonElement(sessionBodies[0]).jsonObject
        assertEquals("u-1", body["user_id"]!!.jsonPrimitive.content)
        assertEquals("user@example.com", body["email"]!!.jsonPrimitive.content)
        c.destroy()
    }

    /**
     * Токен, переданный пока identify в полёте, регистрируется после его ответа и под его личностью — один раз,
     * хотя регистрацию просят и setPushToken, и identify().
     */
    @Test
    fun pushRegister_waitsForTheIdentifyInFlight() {
        val gate = CountDownLatch(1)
        identifyGate = gate
        val c = controller()
        c.identify(RespondoIdentity(userId = "u-1"))
        awaitCount(identifyBodies, 1)
        c.setPushToken("fcm-token")
        settle()
        assertTrue("регистрация ждёт ответа identify", registerBodies.isEmpty())
        gate.countDown()
        awaitCount(registerBodies, 1)
        settle()
        assertEquals("то же тело второй раз не уходит", listOf("identify", "register"), order.toList())
        val body = Json.parseToJsonElement(registerBodies[0]).jsonObject
        assertEquals("fcm-token", body["token"]!!.jsonPrimitive.content)
        assertEquals("u-1", body["user_id"]!!.jsonPrimitive.content)
        c.destroy()
    }

    /**
     * Контроллер разобран (`destroy`, повторный `init`), пока отметка запуска ждала ответа identify: она не
     * уходит, запуск возвращается трекеру — его сообщает следующий контроллер.
     */
    @Test
    fun destroyWhileTheLaunchWaits_theNextControllerReportsTheLaunch() {
        val gate = CountDownLatch(1)
        identifyGate = gate
        val tracker = AppSessionTracker(clock = { now })
        val first = controller(tracker = tracker)
        first.identify(RespondoIdentity(userId = "u-1", userHash = "hash"))
        awaitCount(identifyBodies, 1)
        first.onProcessForeground()
        settle()
        first.destroy()
        gate.countDown()
        settle()
        assertTrue("разобранный контроллер отметку не шлёт", sessionBodies.isEmpty())
        assertTrue("процесс на переднем плане — запуск сообщает следующий", tracker.takeDroppedLaunch())

        val second = controller(tracker = tracker)
        second.onProcessForeground()
        awaitCount(sessionBodies, 1)
        settle()
        assertEquals("запуск — один", 1, sessionBodies.size)
        second.destroy()
    }

    /**
     * `destroy()` (повторный `init`), пока `/widget/identify` в полёте: запрос отменён, ответа нет — дедуп
     * снимается, и новый контроллер той же личности шлёт её снова.
     */
    @Test
    fun destroyDuringIdentify_theNextControllerSendsItAgain() {
        val hang = CompletableDeferred<HttpResponse>()
        val hanging = object : HttpEngine {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                if (request.url.endsWith("/api/v1/widget/identify") && identifyBodies.isEmpty()) {
                    identifyBodies += (request.body as HttpBody.Json).text
                    return hang.await()
                }
                return engine.execute(request)
            }
        }
        val store = InMemoryKeyValueStore()
        val sync = IdentifySync()
        fun make() = RespondoController(
            TestContext(),
            RespondoConfig(agentId = "agent-1", channelId = "chan-1"),
            null,
            ApiClient("https://api.respondo.ai", hanging),
            OkHttpClient(),
            store,
            sessionTracker = AppSessionTracker(clock = { now }),
            identifySync = sync,
        )
        val identity = RespondoIdentity(userId = "u-1", userHash = "hash")
        val first = make()
        first.identify(identity)
        awaitCount(identifyBodies, 1)
        first.destroy()
        settle()

        val second = make()
        second.identify(identity)
        awaitCount(identifyBodies, 2)
        assertEquals("тот же кузов уходит снова", identifyBodies[0], identifyBodies[1])
        second.destroy()
    }

    /** `track()` при identify в полёте: событие уходит после его ответа, с личностью вызова. */
    @Test
    fun track_waitsForTheIdentifyInFlight() {
        val gate = CountDownLatch(1)
        identifyGate = gate
        val c = controller()
        c.identify(RespondoIdentity(userId = "u-1", userHash = "hash"))
        awaitCount(identifyBodies, 1)
        c.track("checkout_opened", mapOf("plan" to "pro"))
        settle()
        assertTrue("событие не создаёт контакт раньше identify", eventBodies.isEmpty())
        gate.countDown()
        awaitCount(eventBodies, 1)
        assertEquals(listOf("identify", "event"), order.filter { it == "identify" || it == "event" })
        val body = Json.parseToJsonElement(eventBodies[0]).jsonObject
        assertEquals("checkout_opened", body["name"]!!.jsonPrimitive.content)
        assertEquals("u-1", body["user_id"]!!.jsonPrimitive.content)
        c.destroy()
    }

    @Test
    fun contactChange_sendsSessionAfterIdentify_withTheName() {
        val c = controller()
        c.onProcessForeground()
        awaitCount(sessionBodies, 1)
        sessionBodies.clear()
        order.clear()
        now += 1_000
        c.identify(RespondoIdentity(userId = "u-1", name = "Ann", properties = mapOf("plan" to "pro")))
        awaitCount(sessionBodies, 1)
        // Сессия — после ответа на identify: новый контакт создаётся с атрибутами и именем.
        assertEquals(listOf("identify", "session"), order.toList())
        val body = Json.parseToJsonElement(sessionBodies[0]).jsonObject
        assertEquals("Ann", body["name"]!!.jsonPrimitive.content)
        assertEquals("u-1", body["user_id"]!!.jsonPrimitive.content)

        // Неудачный identify сессию не держит.
        identifyStatus = 500
        c.identify(RespondoIdentity(userId = "u-2", properties = mapOf("plan" to "team")))
        awaitCount(sessionBodies, 2)
        assertEquals(listOf("identify", "session", "identify", "session"), order.toList())
        c.destroy()
    }
}
