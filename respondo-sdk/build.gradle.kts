import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    `maven-publish`
}

// Координаты публикации берём из gradle.properties — единый источник версии.
val sdkGroupId = providers.gradleProperty("respondo.sdk.group").get()
val sdkArtifactId = providers.gradleProperty("respondo.sdk.artifact").get()
val sdkVersionName = providers.gradleProperty("respondo.sdk.version").get()

android {
    namespace = "ai.respondo.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        // Версия SDK. Синхронизируется с суффиксом User-Agent (SdkInfo.VERSION).
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersionName\"")
        consumerProguardFiles("consumer-rules.pro")
    }

    // Публикуем единственный вариант release с JAR исходников для потребителей.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    implementation(libs.coil.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
}

// Компонент release создаётся AGP лениво — публикацию объявляем в afterEvaluate.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = sdkGroupId
                artifactId = sdkArtifactId
                version = sdkVersionName

                pom {
                    name.set("Respondo Android SDK")
                    description.set(
                        "Native Respondo support-chat SDK for Android: chat, news, surveys, " +
                            "banners, checklists and push notifications over the Respondo widget API.",
                    )
                    url.set("https://respondo.ai")
                    organization {
                        name.set("Respondo")
                        url.set("https://respondo.ai")
                    }
                    scm {
                        url.set("https://bitbucket.org/hub2026/respondo")
                        connection.set("scm:git:https://bitbucket.org/hub2026/respondo.git")
                    }
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/license/mit")
                        }
                    }
                }
            }
        }
        // Публикация в локальный Maven-репозиторий (~/.m2) выполняется встроенной
        // задачей publishToMavenLocal — отдельный repositories-блок не нужен.
        // Реестр (Maven Central) подключается позже, когда заведут аккаунт Sonatype.
    }
}
