package com.example.goon.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * 外部工具的执行日志与幂等语义。
 *
 * 解决的是面试里高频、但工程上常被忽略的一条：**超时不等于未执行**。
 * 现在 `McpClient` 读超时（30s）之后什么都没留下，模型下一轮重试就会产生**第二次副作用**——
 * 对"发消息""扣款""改线上数据"这类操作是不可接受的。
 *
 * 三条语义：
 * 1. **幂等重放**：同一 `key + argsHash` 在 TTL 内已经成功过 → 直接返回上次结果，不再发起调用
 *    （`replayed: true`）。这把"模型重试"从"再来一次"变成"取上次结果"。
 * 2. **超时 = unknown，不是 failed**：连接中断/读超时意味着**服务端可能已经执行完了**。
 *    这种情况一律记 `unknown`，并且**不自动重试**，而是把不确定性显式交回给模型与用户。
 * 3. **只有明确失败才可重试**：服务端回了 4xx/5xx 或 `isError`，说明这次确实没生效，可以重试。
 *
 * 为什么落盘而不是只放内存：应用被杀、设备重启之后，"这件事到底执行过没有"必须还能查到——
 * 恢复时读执行日志，而不是靠模型回忆。
 */
object ToolExecutionLog {
    const val RUNNING = "running"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val UNKNOWN = "unknown"

    /** 幂等窗口：超过这个时间就不再按"同一次调用"处理（用户可能就是想再执行一次）。 */
    private const val TTL_MS = 10 * 60 * 1000L
    private const val MAX_ENTRIES = 200

    private val sequence = AtomicLong(System.currentTimeMillis() % 100000)

    private fun file(context: Context) = File(context.applicationContext.filesDir, "tool_executions.json")

    @Synchronized
    fun all(context: Context): JSONArray = runCatching {
        val raw = file(context).takeIf { it.exists() }?.readText(Charsets.UTF_8) ?: return JSONArray()
        JSONArray(raw)
    }.getOrDefault(JSONArray())

    @Synchronized
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    fun argsHash(arguments: String): String = runCatching {
        MessageDigest.getInstance("SHA-256").digest(arguments.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(12)
    }.getOrDefault("unknown")

    /** 开始一次调用。若命中幂等窗口，返回 [Replay]；否则返回一个已登记的 [Entry]。 */
    sealed interface Start {
        data class Fresh(val id: Long) : Start
        data class Replay(val summary: String, val at: Long) : Start
    }

    @Synchronized
    fun begin(context: Context, key: String, argsHash: String): Start {
        val entries = all(context)
        val now = System.currentTimeMillis()
        for (index in entries.length() - 1 downTo 0) {
            val entry = entries.optJSONObject(index) ?: continue
            if (entry.optString("key") != key || entry.optString("argsHash") != argsHash) continue
            if (now - entry.optLong("at") > TTL_MS) break
            if (entry.optString("status") == SUCCEEDED) {
                return Start.Replay(entry.optString("summary"), entry.optLong("at"))
            }
        }
        val id = sequence.incrementAndGet()
        entries.put(JSONObject().put("id", id).put("key", key).put("argsHash", argsHash)
            .put("status", RUNNING).put("at", now))
        persist(context, entries)
        return Start.Fresh(id)
    }

    @Synchronized
    fun finish(context: Context, id: Long, status: String, summary: String, ms: Long) {
        val entries = all(context)
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            if (entry.optLong("id") != id) continue
            entry.put("status", status).put("summary", summary.take(400)).put("ms", ms).put("doneAt", System.currentTimeMillis())
        }
        persist(context, entries)
    }

    /** 同一 `key+argsHash` 最近一次处于 unknown 的记录（用于"重试前先查执行日志"）。 */
    @Synchronized
    fun uncertain(context: Context, key: String, argsHash: String): JSONObject? {
        val entries = all(context)
        for (index in entries.length() - 1 downTo 0) {
            val entry = entries.optJSONObject(index) ?: continue
            if (entry.optString("key") != key || entry.optString("argsHash") != argsHash) continue
            return entry.takeIf { it.optString("status") == UNKNOWN }
        }
        return null
    }

    private fun persist(context: Context, entries: JSONArray) {
        while (entries.length() > MAX_ENTRIES) entries.remove(0)
        runCatching {
            file(context).apply { parentFile?.mkdirs() }.writeText(entries.toString(), Charsets.UTF_8)
        }
    }
}
