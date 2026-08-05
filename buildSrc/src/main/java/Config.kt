@file:Suppress("UnstableApiUsage")

import org.gradle.api.Project
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * @author Yenaly Liew
 * @time 2023/11/25 025 17:55
 */
object Config {

    val Project.isRelease: Boolean
        get() = gradle.startParameter.taskNames.any { it.contains("Release") }

    object Version {

        const val DEBUG = "debug"
        const val RELEASE = "release"
//        const val CI = "ci"

        /**
         * 创建版本号
         *
         * @return 版本号和版本名
         *
         *         Example:
         *         ci: 0.1.2-ci+21032500
         *         release: 0.1.2-release+21032500
         *         debug (fixed): debug+1
         */
        fun Project.createVersion(
            major: Int, minor: Int, patch: Int
        ): Pair<Int, String> {
            // Release workflow 传入 HA1_VERSION_TAG（如 v1.0.2-release+2608061200）时，
            // 直接用它作为版本号，保证 APK 内 versionCode 与 Git tag 完全一致，
            // 检查更新（checkNeedUpdate 用 tagName 的 + 后数字比较）才不会错位。
            val tagOverride = System.getenv("HA1_VERSION_TAG")
            if (!tagOverride.isNullOrBlank()) {
                val code = tagOverride.substringAfter("+", "").toIntOrNull()
                if (code != null) {
                    val name = tagOverride.removePrefix("v")
                    return code to name
                }
            }
            val source = this.source
            val versionCode: Int
            val versionName: String
            when (source) {
                DEBUG -> {
                    versionCode = 1
                    versionName = "$DEBUG+$versionCode"
                }

                else -> {
                    versionCode = LocalDateTime.now(Clock.systemUTC()).format(
                        DateTimeFormatter.ofPattern("yyMMddHH")
                    ).toInt()
                    versionName = "${major}.${minor}.${patch}-$source+$versionCode"
                }
            }
            return versionCode to versionName
        }

        /**
         * 版本来源，用于区分不同的版本
         *
         * @return ci 或 release 或 debug
         */

        val Project.source: String
            get() = System.getenv("HA1_VERSION_SOURCE") ?: kotlin.run {
                return if (isRelease) RELEASE else DEBUG
            }
    }

    val Project.lastCommitSha: String
        get() = providers.exec {
            commandLine = "git rev-parse --short=7 HEAD".split(' ')
        }.standardOutput.asText.get().trim()

    val thisYear: Int
        get() = LocalDateTime.now(Clock.system(ZoneId.of("UTC+8"))).year
}