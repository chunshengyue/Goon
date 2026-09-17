package com.example.goon.core

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class WebMiniAppManifest(
    val id: String,
    val name: String,
    val version: String = "0.1.0",
    val entry: String = "index.html",
    val permissions: List<String> = listOf("storage"),
    val network: List<String> = emptyList(),
    /**
     * 本项目声明要用的素材名（来自宿主素材库）。
     *
     * 用**显式声明**而不是「整个素材目录都可读」：运行时的可见面因此严格等于这份清单，
     * 既满足「小程序能引用用户给的图」，又不让任何一个生成出来的应用能枚举全部用户素材
     * （这是 PRD 7.1「运行时不应直接访问素材区」在保留隔离前提下的落地方式）。
     */
    val assets: List<String> = emptyList(),
    val runtimeVersion: Int = 1
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("version", version); put("entry", entry)
        put("permissions", JSONArray(permissions)); put("network", JSONArray(network))
        put("assets", JSONArray(assets))
        put("runtime", "web"); put("runtimeVersion", runtimeVersion)
    }
    companion object {
        fun fromJson(value: String): WebMiniAppManifest = JSONObject(value).let { json ->
            require(json.optString("runtime", "web") == "web") { "不支持的运行时。" }
            WebMiniAppManifest(json.getString("id"), json.getString("name"), json.optString("version", "0.1.0"),
                json.optString("entry", "index.html"),
                json.optJSONArray("permissions")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                json.optJSONArray("network")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                json.optJSONArray("assets")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                json.optInt("runtimeVersion", 1))
        }
    }
}

data class WebProject(val manifest: WebMiniAppManifest, val files: Map<String, String>, val revision: String = "") {
    fun toJson() = JSONObject().put("manifest", manifest.toJson()).put("files", JSONObject(files)).put("revision", revision)
}

data class WebRevision(val id: String, val createdAt: Long)

class WebMiniAppStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "mini_apps")

    companion object {
        private val lock = Any()
        const val MAX_BYTES = 2 * 1024 * 1024
        fun path(value: String): String {
            require(value.length in 1..180 && value.split('/').all { it.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*")) && it !in setOf(".", "..") }) { "无效的项目相对路径。" }
            return value
        }
        fun validate(manifest: WebMiniAppManifest, files: Map<String, String>) {
            require(manifest.id.matches(Regex("[A-Za-z0-9_-]{3,48}"))) { "项目 ID 无效。" }
            require(manifest.name.isNotBlank() && manifest.name.length <= 80 && manifest.version.length in 1..32) { "名称或版本无效。" }
            require(manifest.runtimeVersion == 1) { "运行时版本不受支持。" }
            require(manifest.network.isEmpty() && manifest.permissions.all { it == "storage" }) { "当前仅开放本地 storage；网络与设备能力尚未接入。" }
            require(manifest.assets.size <= 60) { "单个项目最多声明 60 个素材。" }
            require(manifest.assets.all { it.length in 1..120 && !it.contains('/') && !it.contains('\\') && !it.contains("..") }) { "素材名无效。" }
            path(manifest.entry)
            require(manifest.entry.endsWith(".html") && files[manifest.entry]?.isNotBlank() == true) { "缺少 HTML 入口文件。" }
            require(files.size in 1..128) { "项目必须包含 1–128 个文件。" }
            require(files.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } <= MAX_BYTES) { "项目超过 2 MiB。" }
            files.forEach { (name, content) ->
                path(name)
                require(name != "manifest.json" && name.substringAfterLast('.') in setOf("html", "css", "js", "mjs", "json", "svg", "txt")) { "不支持的文本资源格式：$name" }
                require(content.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "文件超过 512 KiB：$name" }
                require(files.keys.none { it.startsWith("$name/") }) { "文件与目录路径冲突：$name" }
            }
        }
        fun origin(id: String): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
            return "https://p-$hash.invalid"
        }
    }

    private fun directory(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{3,48}"))) { "项目 ID 无效。" }
        return File(root, id)
    }
    private fun current(id: String): String? {
        val pointer = AtomicFile(File(directory(id), "current"))
        return runCatching { pointer.openRead().bufferedReader().use { it.readText() } }.getOrNull()?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
    }
    fun has(id: String): Boolean = runCatching { current(id) != null }.getOrDefault(false)
    fun load(id: String): WebProject? = synchronized(lock) {
        val rev = current(id) ?: return@synchronized null
        readRevision(id, rev)
    }
    fun readRevision(id: String, revision: String): WebProject = synchronized(lock) {
        require(revision.matches(Regex("[a-f0-9-]{36}"))) { "版本 ID 无效。" }
        val dir = File(directory(id), "revisions/$revision")
        val manifest = WebMiniAppManifest.fromJson(File(dir, "manifest.json").readText(Charsets.UTF_8))
        require(manifest.id == id)
        val files = dir.walkTopDown().filter { it.isFile && it != File(dir, "manifest.json") }
            .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText(Charsets.UTF_8) }
        validate(manifest, files)
        WebProject(manifest, files, revision)
    }
    fun manifest(id: String): WebMiniAppManifest? = load(id)?.manifest
    fun files(id: String): Map<String, String> = load(id)?.files.orEmpty()
    fun projects(): List<ProjectSummary> = synchronized(lock) {
        root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.mapNotNull { dir ->
            runCatching { load(dir.name)?.let { ProjectSummary(it.manifest.id, it.manifest.name, "Web 小程序 · ${it.files.size} 个文件", File(dir, "current").lastModified()) } }.getOrNull()
        }
    }

    // Immutable revisions plus one atomic pointer keep interrupted writes invisible.
    fun save(manifest: WebMiniAppManifest, files: Map<String, String>, expectedRevision: String? = null): String = synchronized(lock) {
        validate(manifest, files)
        if (expectedRevision != null) require((current(manifest.id) ?: "") == expectedRevision) { "项目已被其他任务修改，请重新读取。" }
        val rev = UUID.randomUUID().toString()
        val dir = File(directory(manifest.id), "revisions/$rev")
        require(dir.mkdirs()) { "无法创建项目版本。" }
        try {
            File(dir, "manifest.json").writeText(manifest.toJson().toString(), Charsets.UTF_8)
            files.forEach { (name, value) -> File(dir, name).apply { parentFile?.mkdirs(); writeText(value, Charsets.UTF_8) } }
            readRevision(manifest.id, rev)
            setCurrent(manifest.id, rev)
            runCatching { revisions(manifest.id).filter { it.id != rev }.drop(9).forEach { File(directory(manifest.id), "revisions/${it.id}").deleteRecursively() } }
            rev
        } catch (error: Exception) { dir.deleteRecursively(); throw error }
    }
    private fun setCurrent(id: String, revision: String) {
        val pointer = AtomicFile(File(directory(id), "current"))
        val stream = pointer.startWrite()
        try { stream.write(revision.toByteArray()); pointer.finishWrite(stream) }
        catch (error: Exception) { pointer.failWrite(stream); throw error }
    }
    fun revisions(id: String): List<WebRevision> = synchronized(lock) {
        File(directory(id), "revisions").listFiles().orEmpty().filter { it.isDirectory && File(it, "manifest.json").isFile }
            .map { WebRevision(it.name, it.lastModified()) }.sortedByDescending { it.createdAt }
    }
    fun restore(id: String, revision: String): String = synchronized(lock) {
        val project = readRevision(id, revision)
        save(project.manifest, project.files, current(id))
    }
    fun delete(id: String): Boolean = synchronized(lock) {
        val dir = directory(id)
        if (!dir.isDirectory) return@synchronized false
        val tombstone = File(root, ".deleted-${UUID.randomUUID()}")
        require(dir.renameTo(tombstone)) { "无法删除项目。" }
        tombstone.deleteRecursively()
        true
    }
}
