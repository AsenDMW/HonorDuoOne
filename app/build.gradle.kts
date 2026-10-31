import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release CI derives both numbers from the pushed tag and passes them in, so the
// version in the APK, the version in the About card and the version the updater
// compares against are all the same value. A local build falls back to the
// checked-in defaults below.
val tagVersionName = providers.gradleProperty("versionName").orNull?.takeIf { it.isNotBlank() }
val tagVersionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull()

// The GitHub slug the in-app updater reads releases from, as "owner/repo". Blank
// leaves the update card as a plain repository link instead of a check that can
// only ever 404.
val repoSlug = providers.gradleProperty("repo").orNull?.trim().orEmpty()

android {
    namespace = "io.github.odomu.honorduo"
    compileSdk = 37

    defaultConfig {
        // Honor Duo One 身份：覆盖安装用户已装的 io.github.honorduoone（versionCode 需 >121）。
        applicationId = "io.github.honorduoone"
        minSdk = 29
        //noinspection TargetSdkVersionUpdate
        targetSdk = 37
        // 1.1.0（150）：正式发布版。同步 Duo 1.0.6 — 新增隐藏隐私指示开关；修复横屏下拉
        // 时钟缩成一点（maxHeight=0 时不缩放字号）；行重新布局后自动补回时钟；hookVisibility
        // 用 VISIBILITY_FLAGS 掩码减少无谓遍历；修复蓝牙电量组件显示时下拉卡顿
        // （applyIconVisibility 仅在未知↔已知翻转时调用）；恢复备份时补推时钟偏移/行距/
        // 农历/日期星期格式/颜色与生效范围等漏推键（仍按本模块设计需重启 SystemUI 后生效）。
        versionCode = tagVersionCode ?: 150
        versionName = tagVersionName ?: "1.1.0"

        buildConfigField("String", "REPO_SLUG", "\"$repoSlug\"")
    }

    // 签名凭据不入库：从 ../HonorDuoOne/keystore.properties 读（该文件由本机保管，
    // 含 storePassword/keyAlias/keyPassword 三项），也可用同名环境变量覆盖。
    val ksProps = Properties().apply {
        val f = rootProject.file("../HonorDuoOne/keystore.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }
    fun ksSecret(key: String): String =
        System.getenv("HDO_${key.uppercase()}") ?: ksProps.getProperty(key).orEmpty()

    signingConfigs {
        // 固定签名：与 Honor Duo One 历史版本同证书，才能覆盖安装。
        // 本机用 ../HonorDuoOne/ks.jks（凭据在 keystore.properties / 环境变量）；
        // 该文件不随仓库分发，CI 上没有时退回仓库内置的 .tools/debug.keystore，
        // 这样流水线仍能产出可直接安装的包，而不是构建中断。
        val own = rootProject.file("../HonorDuoOne/ks.jks")
        create("release") {
            if (own.isFile) {
                storeFile = own
                storePassword = ksSecret("storePassword")
                keyAlias = ksSecret("keyAlias")
                keyPassword = ksSecret("keyPassword")
            } else {
                // 仓库内置的 keystore 是 PKCS12，别名叫 honorduo（不是 Android 默认的
                // androiddebugkey），口令与本机 android 调试签名一致。
                storeFile = rootProject.file(".tools/debug.keystore")
                storePassword = "android"
                keyAlias = "honorduo"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        // Must match the Kotlin target below: AGP fails the build outright on a
        // mismatch. 21 rather than 17 because Miuix ships Java 21 bytecode and
        // miuix-nav's entry points are inline functions, which cannot be inlined
        // into 17-targeted output.
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Keep META-INF/xposed/{java_init.list,scope.list,module.prop} in the APK.
    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // Provided by the Xposed framework at runtime, never bundled.
    compileOnly("io.github.libxposed:api:102.0.0")
    // Needed by the settings app to talk to the framework service.
    implementation("io.github.libxposed:service:102.0.0")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    // The updater does its network work off the main thread and reports state
    // back on it; Compose alone has no scheduling primitive for that.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // @Serializable on the nav routes. miuix-nav only exposes it as a runtime
    // dependency, so the annotation and the inlined serializer() lookup need it
    // declared here to be on the compile classpath.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")

    // Backdrop and squircle shapes. These are NOT Miuix: they are kyant0's libraries, and
    // they are what gives the frosted-glass surfaces their moving, layered look -
    // Miuix's own miuix-blur is the lower-level primitive that `textureBlur` wraps.
    //
    // Both libraries publish under io.github.kyant0; these are their current releases.
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    // The nine affordances the settings screens draw (chevrons, check, circle-dot, lock,
    // close, info, rotate and sliders) are vendored vector drawables under
    // `res/drawable/ic_lucide_*.xml`, taken from Lucide 2.2.1 (ISC). They are copied in
    // rather than pulled from that artifact: its `R` class is a *library* resource table,
    // and importing one into Kotlin is what left `import ...lucide.R` unresolvable in the
    // IDE while still building from the command line.
    // Blurred top bar. The artifact declares minSdk 33, but every effect in it
    // is gated on isRuntimeShaderSupported() and degrades to plain drawing
    // below that, so the manifest override keeps our own minSdk at 29.
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    // Only for the isRuntimeShaderSupported() gate the settings screen applies
    // before it touches any blur API. Blur pulls it in transitively, but the
    // code imports from it directly, so it is declared directly.
    implementation("top.yukonga.miuix.kmp:miuix-shader-android:0.9.4")
    // Compose navigation with a continuous back stack. Exists only from 0.9.4,
    // which is why the whole Miuix stack above moved up with it. Its aar
    // declares minSdk 24, so it needs no manifest override of its own.
    implementation("top.yukonga.miuix.kmp:miuix-nav-android:0.9.4")
}
