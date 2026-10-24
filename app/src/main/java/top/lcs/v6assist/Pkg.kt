package top.lcs.v6assist

import android.content.Context
import android.content.pm.PackageManager

/**
 * 包名识别：B 站有手机版/HD 版/国际版/概念版等多个包名，抖音也有极速版，
 * 所以不能写死一个名字，按前缀扫已安装的包。
 */
object Pkg {

    private val BILI_PREFIX = listOf("tv.danmaku.bili", "com.bilibili")
    private val DOUYIN_PREFIX = listOf("com.ss.android.ugc.aweme")

    private fun scan(c: Context, prefixes: List<String>): List<String> {
        val pm = c.packageManager
        val out = ArrayList<String>()
        try {
            for (pi in pm.getInstalledPackages(0)) {
                val name = pi.packageName ?: continue
                if (prefixes.any { name.startsWith(it) }) out.add(name)
            }
        } catch (e: Throwable) {
            // 个别 ROM 上 getInstalledPackages 可能受限，返回空让调用方回退到默认包名
        }
        out.sort()
        return out
    }

    fun bili(c: Context): List<String> {
        val l = scan(c, BILI_PREFIX)
        return if (l.isEmpty()) listOf("tv.danmaku.bili") else l
    }

    fun douyin(c: Context): List<String> {
        val l = scan(c, DOUYIN_PREFIX)
        return if (l.isEmpty()) listOf("com.ss.android.ugc.aweme") else l
    }

    fun biliLabel(c: Context): String {
        val l = bili(c)
        return if (l.size <= 1) "哔哩哔哩 (${l[0]})"
        else "哔哩哔哩（自动识别 ${l.size} 个包）\n" + l.joinToString("\n") { "  · $it" }
    }

    fun douyinLabel(c: Context): String {
        val l = douyin(c)
        return if (l.size <= 1) "抖音 (${l[0]})"
        else "抖音（自动识别 ${l.size} 个包）\n" + l.joinToString("\n") { "  · $it" }
    }
}
