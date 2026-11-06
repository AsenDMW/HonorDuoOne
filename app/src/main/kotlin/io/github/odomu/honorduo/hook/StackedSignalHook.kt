/*
 * Copyright (C) 2026 yixing233 and HyperDuo contributors
 * Copyright (C) 2026 odomu and HonorDuo contributors
 *
 * This file is part of HonorDuo, a fork of HyperDuo, released under the GNU
 * General Public License v3.0. See the LICENSE file at the repository root, or
 * <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

package io.github.odomu.honorduo.hook

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import io.github.odomu.honorduo.model.TrioConfig
import java.io.File
import java.util.WeakHashMap
import java.util.zip.ZipFile

/**
 * StackedSignal_5G 的 1:1 移植。
 *
 * 「鸿蒙堆叠信号」开启时，本对象按原模块 MainHook 的六个注册点与全部判定条件在
 * SystemUI 进程里执行；关闭时所有回调直接放行，等价于原模块未启用。
 *
 * 资源来自原模块 assets 中加密的 86 张 `.bin`，已按原算法（AES/GCM，key =
 * SHA-256(certDER || salt)）解出为同尺寸 PNG，原样打包进本模块 assets/stacked/。
 * 这里不再解密，只做与原 loadBitmap/decodePng 相同的解码（inDensity=480、不缩放），
 * 因此像素、尺寸与颜色和原模块一致。
 */
internal object StackedSignalHook {

    private const val PKG = "com.android.systemui"
    private const val TAG = "StackedSignal"
    private const val ASSET_DIR = "assets/stacked/"
    private const val IN_DENSITY = 480
    private const val THEME_ZIP = "/data/themes/skin/com.android.systemui"

    private val RETRY_MS = longArrayOf(60L, 250L, 700L)
    private val RECHECK_MS = longArrayOf(0L, 120L, 400L, 1200L, 3000L)

    /** 原模块 PREFIXES，顺序即匹配顺序（five 必须先于普通 signal）。 */
    private val PREFIXES = arrayOf(
        "stat_sys_signal_five_" to "stat_sys_stacked_signal_five_",
        "stat_sys_signal_" to "stat_sys_stacked_signal_",
        "stat_sys_tjt_signal_" to "stat_sys_tjt_stacked_signal_",
        "ic_statusbar_signal_tjt_" to "ic_statusbar_signal_tjt_stacked_",
        "stat_sys_data_fully_connected_" to "stat_sys_stacked_data_fully_connected_",
        "stat_sys_data_connected_" to "stat_sys_stacked_data_connected_",
    )

    private val DROP_SUFFIXES = arrayOf("_fully", "_small")

    private val EXCLUDE_NAMES = arrayOf(
        "stat_sys_signal_flightmode",
        "stat_sys_signal_carrier_network_change",
        "stat_sys_signal_carrier_network_change_animation",
    )

    private val TYPE_PROBES = arrayOf(
        "stat_sys_stacked_data_connected_5g",
        "stat_sys_stacked_data_connected_4g",
        "stat_sys_stacked_data_connected_2g",
        "stat_sys_stacked_data_fully_connected_4g",
        "stat_sys_stacked_signal_type_2g",
    )

    private const val RES_ALIAS_SUFFIX = "_res_0x[0-9a-fA-F]+$"

    private val bitmapCache = HashMap<String, Bitmap>()
    private val missing = HashSet<String>()
    private val nameCache = HashMap<Int, String?>()
    private val applied = WeakHashMap<ImageView, Bitmap>()
    private val appliedName = WeakHashMap<ImageView, String?>()
    private val lastId = WeakHashMap<ImageView, Int>()
    private val struct = WeakHashMap<View, UnitState>()
    private val leftSpacer = WeakHashMap<LinearLayout, View>()
    private val pending = WeakHashMap<LinearLayout, Any>()
    private val relayout = WeakHashMap<LinearLayout, Any>()
    private val dividersOff = WeakHashMap<LinearLayout, Boolean>()

    private var sComboId = 0
    private var sSignalId = 0
    private var sSignalTypeId = 0
    private var sTypeId = 0
    private var sInoutId = 0
    private var sRoamId = 0
    private var sDataId = 0
    private var sSpeedId = 0

    private var sClusterRoot: View? = null
    private var sSpeedView: TextView? = null
    private var sNetSpeedInkPx = 0
    private var sRefTextSize = 0f
    private var sRefLineSpacing = 0f
    private var sAppliedDensity = 0
    private var sTypeW = 0

    private var sModuleApk: String? = null
    private var sThemeZip: ZipFile? = null
    private var sThemeStamp = -1L

    private class UnitState(
        val outer: ViewGroup,
        val bars: ViewGroup,
        val data: View?,
        val roam: View?,
        val row: LinearLayout,
        val typeBox: FrameLayout,
        val barsIdx: Int,
        val barsLp: ViewGroup.LayoutParams?,
        val dataIdx: Int,
        val dataLp: ViewGroup.LayoutParams?,
        val roamIdx: Int,
        val roamLp: ViewGroup.LayoutParams?,
        var typeW: Int,
    )

    /** 开关开启 = 原模块启用。关闭时所有 Hook 直接走原生逻辑。 */
    private fun wanted(): Boolean = TrioConfig.appearance().harmonyStacked

    fun install(module: XposedModule, cl: ClassLoader) {
        sModuleApk = module.moduleApplicationInfo.sourceDir
        val signalCluster = Refl.cls("$PKG.statusbar.SignalClusterView", cl)
        val listener = Refl.cls("$PKG.statusbar.SignalClusterView\$2", cl)
        val hwCluster = Refl.cls("$PKG.statusbar.HwSignalClusterView", cl)

        // 原模块用 findAndHookMethod 精确匹配参数类型；这里同样按类型匹配，
        // 不用「同名 + 参数个数」去猜，避免在重载里挑错方法。
        val hwImageView = Refl.cls("com.hihonor.uikit.phone.hwimageview.widget.HwImageView", cl)

        var hooked = 0
        hooked += hookIcon(
            module,
            Refl.cls("$PKG.utils.HnSignalUtils", cl),
            "HnSignalUtils",
            arrayOf(ImageView::class.java, Int::class.javaPrimitiveType),
        )
        hooked += hookIcon(
            module,
            Refl.cls("$PKG.utils.HwSignalUtil", cl),
            "HwSignalUtil",
            arrayOf(hwImageView, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType),
        )
        hooked += hookAfter(module, listener, "addSignalView", arrayOf(Int::class.javaPrimitiveType), "addSignalView")
        hooked += hookAfter(module, signalCluster, "onFinishInflate", emptyArray(), "inflate")
        hooked += hookAfter(module, signalCluster, "onAttachedToWindow", emptyArray(), "attach")
        hooked += hookAfter(
            module,
            hwCluster,
            "applyHonor",
            arrayOf(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType),
            "applyHonor",
        )

        TrioHooks.log(module, "StackedSignal installed, hooks=$hooked apk=$sModuleApk")
    }

    private fun hookIcon(
        module: XposedModule,
        c: Class<*>?,
        who: String,
        types: Array<Class<*>?>,
    ): Int {
        // HwImageView 解析不到时，原模块的 findAndHookMethod 会抛异常并跳过这条 hook；
        // 这里保持同样的结果。
        if (types.any { it == null }) {
            TrioHooks.log(module, "stacked skip $who.setIconForView: parameter class missing")
            return 0
        }
        val m = methodNamed(c, "setIconForView", types)
        if (m == null) {
            TrioHooks.log(module, "stacked skip $who.setIconForView: method not found")
            return 0
        }
        return try {
            module.hook(m).setId("stacked:$who.setIconForView").intercept { chain ->
                val result = chain.proceed()
                if (!wanted()) return@intercept result
                try {
                    val view = chain.getArg(0) as? ImageView
                    val id = (chain.getArg(1) as? Number)?.toInt()
                    if (view != null && id != null) {
                        if (id <= 0) {
                            applied.remove(view)
                            appliedName.remove(view)
                            lastId.remove(view)
                        } else {
                            lastId[view] = id
                            applyIcon(view, id)
                        }
                    }
                } catch (t: Throwable) {
                    dlog("icon hook error: $t")
                }
                result
            }
            1
        } catch (t: Throwable) {
            TrioHooks.log(module, "stacked skip $who.setIconForView: $t")
            0
        }
    }

    private fun hookAfter(
        module: XposedModule,
        c: Class<*>?,
        name: String,
        types: Array<Class<*>?>,
        tag: String,
    ): Int {
        if (types.any { it == null }) {
            TrioHooks.log(module, "stacked skip ${c?.simpleName}.$name: parameter class missing")
            return 0
        }
        val m = methodNamed(c, name, types)
        if (m == null) {
            TrioHooks.log(module, "stacked skip ${c?.simpleName}.$name: method not found")
            return 0
        }
        return try {
            module.hook(m).setId("stacked:$name").intercept { chain ->
                val result = chain.proceed()
                if (!wanted()) return@intercept result
                try {
                    apply(chain.thisObject, tag)
                } catch (t: Throwable) {
                    dlog("$tag error: $t")
                }
                result
            }
            1
        } catch (t: Throwable) {
            TrioHooks.log(module, "stacked skip ${c?.simpleName}.$name: $t")
            0
        }
    }

    /** 按名字 + 完整参数类型匹配，等价于原模块 findAndHookMethod 的定位方式。 */
    private fun methodNamed(c: Class<*>?, name: String, types: Array<Class<*>?>): java.lang.reflect.Method? {
        if (c == null) return null
        return try {
            c.declaredMethods.firstOrNull { m ->
                m.name == name &&
                    m.parameterTypes.size == types.size &&
                    m.parameterTypes.indices.all { i -> types[i] == m.parameterTypes[i] }
            }?.also { it.isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ 图标替换

    private fun applyIcon(v: ImageView, id: Int): Boolean {
        val group = groupOf(v)
        if (group == null || unitCount(group) < 2) {
            applied.remove(v)
            appliedName.remove(v)
            return false
        }
        val unit = unitOf(v, group) ?: return false
        if (!isUctUnit(unit)) return false
        val res = v.resources ?: return false
        val name = stackedName(res, id) ?: return false

        if (isOurs(v) &&
            name == appliedName[v] &&
            sAppliedDensity == targetDensity(res)
        ) {
            return false
        }

        val bmp = bitmapFor(res, name)
        if (bmp == null) {
            applied.remove(v)
            appliedName.remove(v)
            return false
        }
        val d = drawableFor(res, name) ?: return false
        v.setImageDrawable(d)
        applied[v] = bmp
        appliedName[v] = name
        sAppliedDensity = targetDensity(res)
        scheduleApply(group)
        return true
    }

    private fun drawableFor(res: Resources, name: String): Drawable? {
        val bmp = bitmapFor(res, name) ?: return null
        val d = BitmapDrawable(res, bmp)
        val density = targetDensity(res)
        if (density > 0) d.setTargetDensity(density)
        return d
    }

    private fun bitmapFor(res: Resources, name: String): Bitmap? {
        bitmapCache[name]?.let { return it }
        if (missing.contains(name)) return null
        val bmp = loadBitmap(res, name)
        if (bmp == null) {
            missing.add(name)
            dlog("missing in theme: $name")
        } else {
            bitmapCache[name] = bmp
        }
        return bmp
    }

    private fun loadBitmap(res: Resources, name: String): Bitmap? {
        val embedded = loadEmbedded(name)
        if (embedded != null) return decodePng(embedded, "embedded:$name")
        return loadFromTheme(res, name)
    }

    /**
     * 原模块读 assets/$name.bin 再解密；这里 assets/stacked/$name.png 已是同一份
     * 解出来的 PNG 字节，等价替换。
     */
    private fun loadEmbedded(name: String): ByteArray? {
        val apk = sModuleApk ?: return null
        return try {
            ZipFile(File(apk)).use { zip ->
                val entry = zip.getEntry(ASSET_DIR + name + ".png") ?: return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        } catch (t: Throwable) {
            dlog("asset read failed $name: $t")
            null
        }
    }

    private fun loadFromTheme(res: Resources, name: String): Bitmap? {
        val zip = themeZip() ?: return null
        for (dir in dirs(res)) {
            val entry = zip.getEntry(dir + name + ".png") ?: continue
            return try {
                zip.getInputStream(entry).use { decodePng(it.readBytes(), "theme:${dir}$name") }
            } catch (t: Throwable) {
                null
            }
        }
        return null
    }

    private fun decodePng(data: ByteArray, source: String): Bitmap? {
        val opts = BitmapFactory.Options()
        opts.inDensity = IN_DENSITY
        opts.inScaled = false
        return try {
            BitmapFactory.decodeByteArray(data, 0, data.size, opts)?.also {
                it.density = IN_DENSITY
            }
        } catch (t: Throwable) {
            dlog("decode failed $source: $t")
            null
        }
    }

    private fun themeZip(): ZipFile? {
        val f = File(THEME_ZIP)
        val stamp = (f.lastModified() shl 20) xor f.length()
        val cached = sThemeZip
        if (cached != null && stamp == sThemeStamp) return cached
        try { cached?.close() } catch (_: Throwable) { }
        sThemeZip = null
        sThemeStamp = stamp
        bitmapCache.clear()
        missing.clear()
        nameCache.clear()
        sTypeW = 0
        return try {
            ZipFile(f).also { sThemeZip = it }
        } catch (t: Throwable) {
            dlog("theme zip unavailable: $t")
            null
        }
    }

    private fun dirs(res: Resources): Array<String> {
        val cfg = res.configuration
        val night = (cfg.uiMode and 0x30) == 0x20
        val land = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
        val out = ArrayList<String>(4)
        if (night) {
            if (land) {
                out.add("night/res/drawable-sw360dp-land-xxhdpi/")
                out.add("night/res/drawable-land-xxhdpi/")
            }
            out.add("night/res/drawable-sw360dp-xxhdpi/")
            out.add("night/res/drawable-xxhdpi/")
        } else {
            if (land) {
                out.add("res/drawable-sw360dp-land-xxhdpi/")
                out.add("res/drawable-land-xxhdpi/")
            }
            out.add("res/drawable-sw360dp-xxhdpi/")
            out.add("res/drawable-xxhdpi/")
        }
        return out.toTypedArray()
    }

    private fun targetDensity(res: Resources): Int {
        val ink = netSpeedInkHeight()
        if (ink > 0) {
            return maxOf(1, Math.round(ink * 480.0 / 60.0).toInt())
        }
        return res.displayMetrics.densityDpi
    }

    // ------------------------------------------------------------------ 名称映射

    private fun stackedName(res: Resources, id: Int): String? {
        if (nameCache.containsKey(id)) return nameCache[id]
        var name: String? = null
        try {
            val full = res.getResourceName(id)
            val slash = full.indexOf('/')
            if (slash > 0) name = mapName(full.substring(slash + 1))
        } catch (_: Throwable) {
        }
        nameCache[id] = name
        return name
    }

    private fun mapName(input: String?): String? {
        if (input == null) return null
        for (excluded in EXCLUDE_NAMES) if (excluded == input) return null
        if ("single_stat_sys_signal_connected" == input) {
            return "single_stacked_stat_sys_signal_connected"
        }
        var name: String? = null
        for (p in PREFIXES) {
            if (input.startsWith(p.first)) {
                name = p.second + input.substring(p.first.length)
                break
            }
        }
        val mapped = name ?: return null
        var out = mapped
        for (suffix in DROP_SUFFIXES) {
            if (out.endsWith(suffix)) {
                out = out.substring(0, out.length - suffix.length)
                break
            }
        }
        return out.replace(Regex(RES_ALIAS_SUFFIX), "")
    }

    // ------------------------------------------------------------------ 布局改造

    private fun apply(obj: Any?, why: String) {
        val group = groupOf(obj) ?: return
        rememberRoot(obj)
        applyGroup(group, why)
        val token = Any()
        pending[group] = token
        for (delay in RETRY_MS) {
            group.postDelayed({
                if (pending[group] !== token) return@postDelayed
                if (!group.isAttachedToWindow) applyGroup(group, "$why+$delay")
            }, delay)
        }
    }

    private fun applyGroup(group: LinearLayout?, why: String) {
        if (group == null) return
        val ctx = group.context ?: return
        val res = ctx.resources ?: return
        resolveIds(res)
        if (sClusterRoot == null) rememberRoot(group)

        val eligible = group.visibility == View.VISIBLE && unitCount(group) >= 2
        if (!eligible) {
            keepOriginal(group, why, "single/hidden count=${unitCount(group)} vis=${group.visibility}")
            return
        }

        for (i in 0 until group.childCount) repushIcons(group.getChildAt(i))

        var ready = false
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (!assetsReady(child) || !isUctUnit(child)) {
                ready = false
                break
            }
            ready = true
        }
        if (!ready) {
            keepOriginal(group, why, "not ready")
            return
        }

        if (sNetSpeedInkPx <= 0 && !netSpeedInkMeasured(unit0(group))) {
            scheduleRecheck(group, 0)
        }

        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (!restructureUnit(child) || !pinSize(child)) {
                keepOriginal(group, why, "layout failed")
                return
            }
        }

        setOrientation(group, LinearLayout.VERTICAL)
        if (dividersOff[group] != true) {
            group.setShowDividers(LinearLayout.SHOW_DIVIDER_NONE)
            dividersOff[group] = true
        }
        if (group.gravity != 0x00800015) group.gravity = 0x00800015
        if (group.translationY != 0f) group.translationY = 0f
        applyGaps(group, 0)
    }

    private fun keepOriginal(group: LinearLayout, why: String, detail: String) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            restoreUnit(child)
            restoreIcons(child)
        }
        applyGaps(group, 0)
        setOrientation(group, LinearLayout.HORIZONTAL)
        resetShift(group)
        resetGravity(group)
        dlog("keep original ($why): $detail")
    }

    private fun restructureUnit(unit: View): Boolean {
        val ctx = unit.context ?: return false
        val res = ctx.resources ?: return false
        resolveIds(res)
        if (struct.containsKey(unit)) return true

        val signal = imageOf(unit, sSignalId) ?: return false
        val type = unit.findViewById<View>(sSignalTypeId) ?: return false
        val bars = signal.parent as? ViewGroup ?: return false
        val outer = bars.parent as? ViewGroup ?: return false
        val typeView = unit.findViewById<View>(sTypeId)
        val data = unit.findViewById<View>(sDataId)
        val roam = unit.findViewById<View>(sRoamId)

        val dataLp = data?.layoutParams
        val dataIdx = if (data != null) outer.indexOfChild(data) else -1
        val barsLp = bars.layoutParams
        val barsIdx = outer.indexOfChild(bars)
        val roamLp = roam?.layoutParams
        val roamIdx = if (roam != null) outer.indexOfChild(roam) else -1

        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        val typeBox = FrameLayout(ctx)

        bars.removeView(type)
        typeBox.addView(type)

        if (typeView != null) {
            (typeView.parent as? ViewGroup)?.removeView(typeView)
            typeBox.addView(typeView)
        }
        if (roam != null) {
            (roam.parent as? ViewGroup)?.removeView(roam)
            typeBox.addView(roam)
        }
        if (data != null && data.parent === outer) {
            outer.removeView(data)
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.gravity = Gravity.START or Gravity.TOP
            bars.addView(data, lp)
        }

        outer.removeView(bars)
        row.addView(
            typeBox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        row.addView(
            bars,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        outer.addView(
            row,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        struct[unit] = UnitState(
            outer, bars, data, roam, row, typeBox,
            barsIdx, barsLp, dataIdx, dataLp, roamIdx, roamLp, 0,
        )
        return true
    }

    private fun pinSize(unit: View): Boolean {
        val st = struct[unit] ?: return false
        val res = unit.resources ?: return false
        val signal = imageOf(unit, sSignalId) ?: return false
        val signalDrawable = signal.drawable ?: return false
        val iconW = signalDrawable.intrinsicWidth
        val iconH = signalDrawable.intrinsicHeight
        if (iconW <= 0 || iconH <= 0) return false

        var typeH = 0
        for (id in intArrayOf(sSignalTypeId, sTypeId)) {
            val iv = imageOf(unit, id) ?: continue
            if (!isOurs(iv)) continue
            val d = iv.drawable ?: continue
            val w = d.intrinsicWidth
            val h = d.intrinsicHeight
            if (w > 0 && h > 0) {
                if (w > st.typeW) st.typeW = w
                if (w > sTypeW) sTypeW = w
                if (h > typeH) typeH = h
            }
        }
        var typeW = maxOf(st.typeW, sTypeW)
        if (typeW == 0) typeW = probeTypeW(res)
        if (typeW == 0) {
            for (id in intArrayOf(sSignalTypeId, sTypeId, sRoamId)) {
                val iv = imageOf(unit, id) ?: continue
                if (iv.visibility == View.GONE) continue
                if (iv.drawable == null) continue
                dlog("no stacked type asset in theme, cannot size the type box")
                return false
            }
        }
        st.typeW = maxOf(st.typeW, typeW)

        val width = typeW + iconW
        val height = maxOf(iconH, typeH)
        setSize(unit, width, height)
        setSize(st.row.parent as? View, width, height)
        setSize(st.row, width, height)
        setSize(st.typeBox, typeW, height)
        setSize(st.bars, iconW, height)

        pinIcon(unit, sSignalTypeId, typeW, height)
        pinIcon(unit, sTypeId, typeW, height)
        pinIcon(unit, sRoamId, typeW, height)
        pinIcon(unit, sSignalId, iconW, height)
        pinIcon(unit, sInoutId, iconW, height)

        val data = st.data
        if (data is ViewGroup) {
            setSize(data, width, height)
            val inout = imageOf(data, sInoutId)
            if (inout != null && inout.parent === data) {
                setSize(inout, iconW, height)
            }
        }

        relaxClip(unit)
        val lp = unit.layoutParams
        if (lp is LinearLayout.LayoutParams && (lp.topMargin != 0 || lp.bottomMargin != 0)) {
            lp.topMargin = 0
            lp.bottomMargin = 0
            unit.layoutParams = lp
        }
        return true
    }

    private fun pinIcon(unit: View, id: Int, w: Int, h: Int) {
        val v = imageOf(unit, id) ?: return
        if (v.visibility == View.GONE) return
        setSize(v, w, h)
    }

    private fun probeTypeW(res: Resources): Int {
        if (sTypeW > 0) return sTypeW
        for (name in TYPE_PROBES) {
            val d = drawableFor(res, name) ?: continue
            val w = d.intrinsicWidth
            if (w > 0) {
                sTypeW = w
                return w
            }
        }
        return 0
    }

    private fun restoreUnit(unit: View): UnitState? {
        val st = struct.remove(unit) ?: return null
        val type = unit.findViewById<View>(sSignalTypeId)
        if (type != null && type.parent === st.typeBox) {
            st.typeBox.removeView(type)
            st.bars.addView(type)
        }
        val typeView = unit.findViewById<View>(sTypeId)
        if (typeView != null && typeView.parent === st.typeBox) {
            st.typeBox.removeView(typeView)
            val data = st.data
            if (data is ViewGroup) data.addView(typeView, 0)
        }
        if (st.roam != null && st.roam.parent === st.typeBox) {
            st.typeBox.removeView(st.roam)
        }
        if (st.data != null) {
            (st.data.parent as? ViewGroup)?.removeView(st.data)
        }
        if (st.bars.parent === st.row) {
            (st.row.parent as? ViewGroup)?.removeView(st.bars)
        }
        if (st.row.parent === st.outer) st.outer.removeView(st.row)

        if (st.data != null && st.data.parent == null) {
            st.outer.addView(
                st.data,
                st.dataIdx.coerceIn(0, st.outer.childCount),
                st.dataLp ?: wrapParams(),
            )
        }
        if (st.roam != null && st.roam.parent == null) {
            st.outer.addView(
                st.roam,
                st.roamIdx.coerceIn(0, st.outer.childCount),
                st.roamLp ?: wrapParams(),
            )
        }
        if (st.bars.parent == null) {
            st.outer.addView(
                st.bars,
                st.barsIdx.coerceIn(0, st.outer.childCount),
                st.barsLp ?: wrapParams(),
            )
        }

        resetSize(unit)
        resetSize(st.outer)
        resetSize(st.typeBox)
        resetSize(st.bars)
        resetSize(st.data)
        for (id in intArrayOf(sSignalTypeId, sTypeId, sRoamId, sSignalId, sInoutId)) {
            resetSize(unit.findViewById(id))
        }
        return st
    }

    private fun wrapParams(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun restoreIcons(unit: View) {
        for (id in intArrayOf(sSignalId, sSignalTypeId, sTypeId, sInoutId, sRoamId)) {
            val v = imageOf(unit, id) ?: continue
            if (!isOurs(v)) continue
            val last = lastId[v] ?: 0
            applied.remove(v)
            appliedName.remove(v)
            if (last > 0) {
                try {
                    v.setImageDrawable(v.context.getDrawable(last))
                } catch (t: Throwable) {
                    dlog("restore icon failed: $t")
                }
            }
        }
    }

    private fun repushIcons(unit: View) {
        for (id in intArrayOf(sSignalId, sSignalTypeId, sTypeId, sInoutId, sRoamId)) {
            val v = imageOf(unit, id) ?: continue
            val last = lastId[v] ?: 0
            if (last > 0) applyIcon(v, last)
        }
    }

    private fun resetSize(v: View?) {
        if (v == null) return
        val lp = v.layoutParams ?: return
        if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT &&
            lp.height == ViewGroup.LayoutParams.WRAP_CONTENT
        ) {
            return
        }
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        v.layoutParams = lp
    }

    private fun setSize(v: View?, w: Int, h: Int) {
        if (w == 0 || h == 0 || v == null) return
        var lp = v.layoutParams
        if (lp == null) {
            lp = when (v.parent) {
                is LinearLayout -> LinearLayout.LayoutParams(w, h)
                is FrameLayout -> FrameLayout.LayoutParams(w, h)
                else -> return
            }
            v.layoutParams = lp
            return
        }
        if (lp.width == w && lp.height == h) return
        lp.width = w
        lp.height = h
        v.layoutParams = lp
    }

    private fun setOrientation(group: LinearLayout?, orientation: Int) {
        if (group == null) return
        if (group.orientation != orientation) group.orientation = orientation
    }

    private fun resetShift(group: LinearLayout?) {
        if (group == null) return
        if (group.translationY != 0f) group.translationY = 0f
    }

    private fun resetGravity(group: LinearLayout?) {
        if (group == null) return
        if (group.gravity != 0x00800033) group.gravity = 0x00800033
    }

    private fun relaxClip(view: View?) {
        var v = view
        for (i in 0 until 4) {
            if (v == null) return
            if (v is ViewGroup) {
                v.clipChildren = false
                v.clipToPadding = false
            }
            v = v.parent as? View
        }
    }

    private fun applyGaps(group: LinearLayout, extra: Int) {
        val root = gapRoot(group) ?: return
        try {
            val idx = root.indexOfChild(group.parent as View)
            setLeftSpacer(root, extra, idx)
        } catch (t: Throwable) {
            dlog("gaps error: $t")
        }
    }

    private fun gapRoot(group: LinearLayout): LinearLayout? {
        val p = group.parent as? View ?: return null
        return p.parent as? LinearLayout
    }

    private fun setLeftSpacer(root: LinearLayout?, px: Int, index: Int) {
        if (root == null) return
        var spacer = leftSpacer[root]
        if (spacer != null && spacer.parent !== root) spacer = null
        if (px <= 0) {
            if (spacer != null) {
                root.removeView(spacer)
                leftSpacer.remove(root)
            }
            return
        }
        if (spacer == null) {
            spacer = View(root.context)
            spacer.layoutParams = LinearLayout.LayoutParams(px, 1)
            root.addView(spacer, maxOf(0, index))
            leftSpacer[root] = spacer
            return
        }
        val lp = spacer.layoutParams
        if (lp != null && lp.width != px) {
            lp.width = px
            spacer.layoutParams = lp
        }
    }

    private fun rememberRoot(obj: Any?) {
        var v = obj as? View ?: return
        for (i in 0 until 5) {
            if (findSpeedIn(v) != null) {
                if (sClusterRoot !== v) {
                    sClusterRoot = v
                    sSpeedView = null
                    sNetSpeedInkPx = 0
                }
                return
            }
            v = v.parent as? View ?: return
        }
    }

    private fun findSpeedIn(v: View): TextView? {
        if (sSpeedId == 0) return null
        return try {
            v.findViewById<TextView>(sSpeedId)
        } catch (_: Throwable) {
            null
        }
    }

    private fun netSpeedInkMeasured(v: View?): Boolean {
        if (v == null) return false
        return netSpeedInkHeight() > 0
    }

    private fun netSpeedInkHeight(): Int {
        val root = sClusterRoot ?: return 0
        var tv = sSpeedView
        if (tv == null) {
            tv = findSpeedIn(root) ?: return 0
            sSpeedView = tv
        }
        val textSize = tv.textSize
        val lineSpacing = tv.lineSpacingExtra
        if (sNetSpeedInkPx > 0 && textSize == sRefTextSize && lineSpacing == sRefLineSpacing) {
            return sNetSpeedInkPx
        }
        val paint = Paint()
        paint.textSize = textSize
        val fm = paint.fontMetrics
        val lineH = fm.descent - fm.ascent
        if (lineH <= 0) return 0
        val lines = splitLines(tv.text?.toString().orEmpty())
        val last = Rect()
        paint.getTextBounds(lines.lastOrNull().orEmpty(), 0, lines.lastOrNull()?.length ?: 0, last)
        val first = Rect()
        paint.getTextBounds(lines.firstOrNull().orEmpty(), 0, lines.firstOrNull()?.length ?: 0, first)
        val span = lineH + lineSpacing
        val h = Math.round(span * (lines.size - 1) + last.bottom - first.top).toInt()
        if (h > 0) {
            sNetSpeedInkPx = h
            sRefTextSize = textSize
            sRefLineSpacing = lineSpacing
        }
        return sNetSpeedInkPx
    }

    private fun splitLines(text: String): List<String> {
        val list = text.split('\n').filter { it.isNotBlank() }
        return if (list.isEmpty()) listOf("8", "K/s") else list
    }

    private fun scheduleApply(group: LinearLayout) {
        val token = Any()
        relayout[group] = token
        val runnable = Runnable {
            if (relayout[group] !== token) return@Runnable
            applyGroup(group, "icon")
        }
        group.post(runnable)
    }

    private fun scheduleRecheck(group: LinearLayout, step: Int) {
        if (step >= RECHECK_MS.size) {
            dlog("auto scale: netspeed never measured, keep 100%")
            return
        }
        val runnable = Runnable {
            if (!group.isAttachedToWindow) return@Runnable
            if (!netSpeedInkMeasured(group)) {
                scheduleRecheck(group, step + 1)
            } else {
                dlog("auto scale: ref ready (${sNetSpeedInkPx}px), relayout")
                applyGroup(group, "auto-ref")
            }
        }
        group.postDelayed(runnable, RECHECK_MS[step])
    }

    // ------------------------------------------------------------------ 查找

    private fun resolveIds(res: Resources) {
        if (sComboId != 0) return
        sComboId = findId(res, "mobile_combo")
        sSignalId = findId(res, "mobile_signal")
        sSignalTypeId = findId(res, "mobile_signal_type")
        sTypeId = findId(res, "mobile_type")
        sInoutId = findId(res, "mobile_inout")
        sRoamId = findId(res, "uct_mobile_type_roam")
        sDataId = findId(res, "mobile_data")
        sSpeedId = findId(res, "speed")
        dlog("ids combo=$sComboId signal=$sSignalId type=$sSignalTypeId")
    }

    private fun findId(res: Resources, name: String): Int {
        return try {
            res.getIdentifier(name, "id", PKG)
        } catch (_: Throwable) {
            0
        }
    }

    private fun groupOf(obj: Any?): LinearLayout? {
        if (obj !is View) return null
        resolveIds(obj.resources ?: return null)
        if (sComboId == 0) return null
        return try {
            obj.findViewById<LinearLayout>(sComboId)
        } catch (_: Throwable) {
            null
        }
    }

    private fun groupOf(view: View?): LinearLayout? {
        if (view == null) return null
        resolveIds(view.resources ?: return null)
        if (sComboId == 0) return null
        var v: View = view
        for (i in 0 until 16) {
            v = v.parent as? View ?: return null
            if (v.id == sComboId) return v as? LinearLayout
        }
        return null
    }

    private fun unitOf(view: View, group: LinearLayout): View? {
        var v: View = view
        for (i in 0 until 16) {
            val p = v.parent as? View ?: return null
            if (p === group) return v
            v = p
        }
        return null
    }

    private fun unitCount(group: LinearLayout): Int {
        var n = 0
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (child.findViewById<View>(sSignalId) != null) n++
        }
        return n
    }

    private fun unit0(group: LinearLayout): View {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.findViewById<View>(sSignalId) != null) return child
        }
        return group
    }

    private fun imageOf(unit: View?, id: Int): ImageView? {
        if (unit == null || id == 0) return null
        return try {
            unit.findViewById<ImageView>(id)
        } catch (_: Throwable) {
            null
        }
    }

    private fun isUctUnit(view: View?): Boolean {
        if (view == null) return false
        if (sSignalTypeId == 0) return false
        return try {
            view.findViewById<View>(sSignalTypeId) != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun assetsReady(view: View): Boolean {
        val signal = imageOf(view, sSignalId) ?: return false
        return isOurs(signal)
    }

    private fun isOurs(v: ImageView?): Boolean {
        if (v == null) return false
        val mine = applied[v] ?: return false
        val d = v.drawable as? BitmapDrawable ?: return false
        return d.bitmap === mine
    }

    private fun dlog(msg: String) {
        if (!TrioConfig.debugLog()) return
        TrioHooks.log(TrioHooks.LOG_INFO, "[$TAG] $msg")
    }
}
