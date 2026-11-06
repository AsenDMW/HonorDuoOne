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
        // 1.1.5（158）：蓝牙电量正方环几何预烘焙——进度段 Path、字号、基线按
        // （尺寸/电量/画法/字重/字体世代）缓存，下拉反色动画逐帧只换颜色重放，
        // 不再每帧 PathMeasure 截段与文字测量；adoptSystemTypeface 换基字时递增
        // 字体世代号，一并修掉字重缓存不随基字失效的旧隐患。
        // 1.1.4（157）：移植 HonorDuo 1.0.7 的 af95135（农历分配跳过、FontMetrics 复用）
        // 与 7489aa8（时钟恢复加固：detached 检测、live 守卫、重试防死循环、stepBarClock
        // 自愈、横屏 transient 亮屏补放恢复）。
        // 1.1.6（159）：通知中心天气修复（新增 query_home_city_weather call 查询
        // 兜底 cursor；天气变化时通过 TimeManager 强制刷新日期行）；
        // 补 META-INF/xposed/scope.list 使 LSPosed 显示推荐作用域。
        // 1.1.7（160）：搬 HonorDuoBar 1.3.3 字形裁切修复——字形始终按设定尺寸绘制
        // （不再随行高收缩），沿父链放开 clipChildren/clipToPadding；防烧屏位移改
        // 画布内 canvas.translate，不再移动 View 本身。
        // 1.1.8（161）：通知中心日期行点击跳转系统天气 App（搬 HonorDuo 1.0.7
        // hookDateClick，包名 com.hihonor.android.totemweather，走 StatusBar
        // startActivityDismissingKeyguard 收起通知栏）；新增设置开关。
        // 1.1.9（162）：功能开关新增「鸿蒙堆叠信号」互斥项——开启后 TrioAppearance.enabled
        // 整体为假（TrioHooks.hook 统一透传、字形撤离），关闭后按各开关原值恢复；
        // 开启前弹确认对话框，状态写入独立键，不改动其它开关的持久化值。
        // 1.1.10（163）：新增「关闭全部」动作行（先确认再逐项关闭）与关于页
        // 「已开启显示项」入口（清单可单独关、也可全部关）；两者共用同一份功能项
        // 清单，互斥项开启时统一置灰，不留绕过闸门的入口。
        // 1.1.11（164）：把 StackedSignal_5G 1:1 移植进本模块——86 张加密 .bin
        // 全部解出为原尺寸 PNG 内置到 assets/stacked；按原模块 xposed_init 的六个
        // 注册点（共 7 个 hook）用 libxposed 管线重放同一套判定、图标选择与双卡
        // 堆叠排版，开关开启即等价于原模块启用；关闭全部改为包含模块总开关；
        // 关于页「已开启」卡片标题右侧补 chevron。
        // 1.1.12（165）：修通知中心天气。下拉卡顿——天气供应商通知原先每条都走
        // force 查询（绕过 30 分钟缓存），查询自身又可能再发 notify，形成连续查询；
        // 每次数据变化还在主线程重排整条状态栏并立刻催宿主重算日期行。现改为通知
        // 合并 + 最小间隔 + 查询回响静默，状态栏 sync 仅在开启「时钟天气」时才跑，
        // 日期行重算延后到展开动画结束；日期行结果按分钟/天气/开关缓存。点击天气
        // 跳日历——原 hook 目标类漏了 .viewmodel 包，onClickDate 根本没挂上，走的是
        // 宿主原生日历跳转；已修正类名并按天气应用启动 Activity 的显式组件打开，
        // 天气应用不存在时不跳转（只记日志）。
        // 1.1.10（167）：面向用户的重发版本，内容为 1.1.12 的成品行为。1.1.13 的
        // 「日期段/天气段区域拆分」实测不可用，代码已整段撤除，日期行回到整行接管：
        // 开关开启时点日期行即打开系统天气应用，不再区分点击位置。因为这会顶掉宿主
        // 原生的日历跳转，开关拨到开启时先弹确认对话框说明「日历跳转不可用」，确认
        // 后才写入。1.1.12 的下拉卡顿修复（通知合并、最小间隔、查询回响静默、
        // 状态栏 sync 门控、日期行重算延迟与缓存）原样保留。
        // versionCode 必须高于此前已构建的 166，避免设备上覆盖安装失败。
        versionCode = tagVersionCode ?: 167
        versionName = tagVersionName ?: "1.1.10"

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
