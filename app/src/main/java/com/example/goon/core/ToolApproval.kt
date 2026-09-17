package com.example.goon.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 工具审批。
 *
 * 为什么需要：内置工具都在本地、离线、可回滚（改的是草稿文件，发布才生效），而且用户在发起时已经
 * 通过意图闸门授权过。**MCP 工具不一样**——它是用户自己配的外部服务，语义未知、可能有副作用
 * （发消息、改线上数据、查内部系统），还可能把数据带出本机。所以闸门要加在**外部工具**这一层，
 * 而不是给所有工具一刀切。
 *
 * 设计取舍：**不做挂起/恢复**，做"授权 → 重放"。
 * 挂起一个正在跑的工具循环需要把栈也存下来，而我们本来就有"模型再调一次"这个天然机制：
 * 第一次调用返回"需要用户确认"，用户点允许后写入一条授权，然后给模型发一条追补消息让它再调一次。
 * 好处是复用现成的草稿/追补/事件三条链路，坏处是多一次模型往返——对一个需要人点确认的操作，
 * 这次往返的代价可以接受。
 *
 * 授权粒度：
 * - `once`：只放行一次，用完即焚（默认，适合写操作）。
 * - `session`：本次会话内一直放行（适合 `list`/`get` 这类只读且用户已经信任的服务）。
 * 拒绝不是"报错"而是**给模型的信息**：它应当换个做法或直接说明做不到，而不是换个名字再试一次。
 */
object ToolApproval {
    const val ONCE = "once"
    const val SESSION = "session"

    private fun file(context: Context) = File(context.applicationContext.filesDir, "tool_approvals.json")

    private fun read(context: Context): JSONArray = runCatching {
        val raw = file(context).takeIf { it.exists() }?.readText(Charsets.UTF_8) ?: return JSONArray()
        JSONArray(raw)
    }.getOrDefault(JSONArray())

    private fun write(context: Context, grants: JSONArray) {
        file(context).apply { parentFile?.mkdirs() }.writeText(grants.toString(), Charsets.UTF_8)
    }

    private fun key(scopeKey: String) = scopeKey

    /** 是否有可用授权。不消费。 */
    fun granted(context: Context, scopeKey: String): Boolean = read(context).let { grants ->
        (0 until grants.length()).any { grants.optJSONObject(it)?.optString("key") == key(scopeKey) }
    }

    /** 消费一次授权：`once` 用掉即删，`session` 保留。 */
    fun consume(context: Context, scopeKey: String) {
        val grants = read(context)
        val kept = JSONArray()
        var changed = false
        for (index in 0 until grants.length()) {
            val grant = grants.optJSONObject(index) ?: continue
            if (grant.optString("key") == key(scopeKey) && grant.optString("scope") == ONCE) { changed = true; continue }
            kept.put(grant)
        }
        if (changed) write(context, kept)
    }

    fun grant(context: Context, scopeKey: String, scope: String = ONCE, label: String = ""): JSONArray {
        val grants = read(context)
        val kept = JSONArray()
        for (index in 0 until grants.length()) {
            val grant = grants.optJSONObject(index) ?: continue
            if (grant.optString("key") != key(scopeKey)) kept.put(grant)
        }
        kept.put(JSONObject().put("key", key(scopeKey)).put("scope", scope).put("label", label)
            .put("at", System.currentTimeMillis()))
        write(context, kept)
        return kept
    }

    fun revoke(context: Context, scopeKey: String?) {
        if (scopeKey == null) { write(context, JSONArray()); return }
        val grants = read(context)
        val kept = JSONArray()
        for (index in 0 until grants.length()) {
            val grant = grants.optJSONObject(index) ?: continue
            if (grant.optString("key") != key(scopeKey)) kept.put(grant)
        }
        write(context, kept)
    }

    fun list(context: Context): JSONArray = read(context)

    /** 待用户确认的请求（供 UI 与调试桥展示）。审批是"每条请求一个键"，重复请求合并。 */
    private val pending = linkedMapOf<String, JSONObject>()

    @Synchronized
    fun request(scopeKey: String, label: String, detail: JSONObject): JSONObject {
        val id = scopeKey
        val entry = JSONObject().put("id", id).put("key", scopeKey).put("label", label)
            .put("detail", detail).put("at", System.currentTimeMillis())
        pending[id] = entry
        return entry
    }

    @Synchronized
    fun pending(): JSONArray = JSONArray().apply { pending.values.forEach { put(it) } }

    @Synchronized
    fun resolve(id: String): JSONObject? = pending.remove(id)
}
