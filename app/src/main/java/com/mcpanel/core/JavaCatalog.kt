package com.mcpanel.core

/**
 * Download catalogue for Java runtimes and the portable Linux environment.
 *
 * Chinese mainland mirror: Tsinghua University TUNA (https://mirrors.tuna.tsinghua.edu.cn)
 * Official source: Adoptium API + Ubuntu CD images.
 */
object JavaCatalog {

    const val ROOTFS_FILE = "ubuntu-base-22.04.5-base-arm64.tar.gz"

    data class JreSpec(
        val major: Int,
        val fileName: String,
        val label: String,
        val approxMb: Int
    )

    val specs: List<JreSpec> = listOf(
        JreSpec(8, "OpenJDK8U-jre_aarch64_linux_hotspot_8u504b01.tar.gz", "Temurin 8u504", 39),
        JreSpec(17, "OpenJDK17U-jre_aarch64_linux_hotspot_17.0.20.1_1.tar.gz", "Temurin 17.0.20.1", 46),
        JreSpec(21, "OpenJDK21U-jre_aarch64_linux_hotspot_21.0.13_9.tar.gz", "Temurin 21.0.13", 49),
        JreSpec(25, "OpenJDK25U-jre_aarch64_linux_hotspot_25.0.5_8.tar.gz", "Temurin 25.0.5", 52)
    )

    fun spec(major: Int): JreSpec = specs.firstOrNull { it.major == major } ?: specs.last()

    fun jreUrls(major: Int, source: String): List<String> {
        val spec = spec(major)
        return if (source == Prefs.SOURCE_OFFICIAL) {
            listOf(
                "https://api.adoptium.net/v3/binary/latest/$major/ga/linux/aarch64/jre/hotspot/normal/eclipse"
            )
        } else {
            listOf(
                "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/$major/jre/aarch64/linux/${spec.fileName}",
                "https://mirrors.bfsu.edu.cn/Adoptium/$major/jre/aarch64/linux/${spec.fileName}",
                "https://mirror.nju.edu.cn/Adoptium/$major/jre/aarch64/linux/${spec.fileName}",
                "https://api.adoptium.net/v3/binary/latest/$major/ga/linux/aarch64/jre/hotspot/normal/eclipse"
            )
        }
    }

    fun rootfsUrls(source: String): List<String> {
        return if (source == Prefs.SOURCE_OFFICIAL) {
            listOf(
                "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/$ROOTFS_FILE"
            )
        } else {
            listOf(
                "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/22.04/release/$ROOTFS_FILE",
                "https://mirrors.bfsu.edu.cn/ubuntu-cdimage/ubuntu-base/releases/22.04/release/$ROOTFS_FILE",
                "https://mirror.nju.edu.cn/ubuntu-cdimage/ubuntu-base/releases/22.04/release/$ROOTFS_FILE",
                "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/$ROOTFS_FILE"
            )
        }
    }

    fun sourceLabel(source: String): String =
        if (source == Prefs.SOURCE_OFFICIAL) "官方源" else "中国大陆镜像源"
}
