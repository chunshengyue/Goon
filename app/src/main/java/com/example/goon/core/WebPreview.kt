package com.example.goon.core

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID

/** Shared resource policy for the user runtime and isolated Agent verification. */
class WebPreview(private val project: WebProject, inspection: Boolean = false) {
    val origin = WebMiniAppStore.origin(project.manifest.id + if (inspection) "-inspect-${UUID.randomUUID()}" else "")
    val entryUrl = "$origin/${project.manifest.entry}"
    val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
    /**
     * 页面自己 console.error 出来的东西。
     * 它和 errors 分开：errors 是宿主拦下的违规（外部资源、缺文件），必须让验证失败；
     * 页面日志只作为诊断信息回报，否则模型为了量尺寸在页面里加一行 console.error，验证就再也过不了。
     */
    val consoleMessages = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val csp = "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; media-src 'self' data:; connect-src 'self'; frame-src 'none'; worker-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    private fun issue(message: String) { synchronized(errors) { if (errors.size < 30) errors += message.take(240) } }
    private fun log(message: String) { synchronized(consoleMessages) { if (consoleMessages.size < 20) consoleMessages += message.take(240) } }
    fun resource(context: Context, uri: Uri): WebResourceResponse {
        val allowed = uri.scheme == "https" && uri.encodedAuthority == Uri.parse(origin).encodedAuthority && uri.userInfo == null
        val path = uri.path.orEmpty().removePrefix("/")
        // 素材按 manifest 声明的清单发放：路径形如 assets/<名字>，只有 manifest.assets 里出现过的名字才放行。
        // 这样运行时的可见面严格等于项目声明，生成出来的应用无法枚举用户的其它素材。
        if (allowed && path.startsWith("assets/")) {
            val name = path.removePrefix("assets/")
            val bytes = if (name in project.manifest.assets) AssetStore.read(context, name) else null
            if (bytes == null) issue("未声明的素材或素材不存在：${name.take(80)}")
            return WebResourceResponse(
                AssetStore.mimeOf(name), null,
                if (bytes == null) 404 else 200, if (bytes == null) "Missing" else "OK",
                mapOf("X-Content-Type-Options" to "nosniff", "Cache-Control" to "no-store"),
                ByteArrayInputStream(bytes ?: ByteArray(0))
            )
        }
        val content = if (allowed) runCatching { WebMiniAppStore.path(path); project.files[path] }.getOrNull() else null
        val status = if (!allowed) 403 else if (content == null) 404 else 200
        if (status != 200 && path != "favicon.ico") issue(if (!allowed) "已阻止外部资源。" else "缺少本地资源：${path.take(120)}")
        val mime = when (path.substringAfterLast('.')) {
            "html" -> "text/html"; "css" -> "text/css"; "js", "mjs" -> "application/javascript"
            "json" -> "application/json"; "svg" -> "image/svg+xml"; else -> "text/plain"
        }
        val headers = mapOf("Content-Security-Policy" to csp, "X-Content-Type-Options" to "nosniff", "Cache-Control" to "no-store")
        return WebResourceResponse(mime, "UTF-8", status, if (status == 200) "OK" else "Blocked", headers, ByteArrayInputStream((content ?: "").toByteArray(Charsets.UTF_8)))
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context, onReady: (WebView) -> Unit = {}, onFailure: (String) -> Unit = {}): WebView =
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = "storage" in project.manifest.permissions
            settings.useWideViewPort = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = false
            settings.blockNetworkLoads = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            setDownloadListener { _, _, _, _, _ -> issue("下载未开放。") }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) log("JS 第 ${message.lineNumber()} 行：${message.message()}")
                    return true
                }
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = resource(view.context, request.url)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    return (uri.scheme != "https" || uri.encodedAuthority != Uri.parse(origin).encodedAuthority).also {
                        if (it) issue("外部导航未开放。")
                    }
                }
                override fun onPageFinished(view: WebView, url: String) { if (url.startsWith("$origin/")) onReady(view) }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    issue("资源加载失败：${error.errorCode}")
                    if (request.isForMainFrame) onFailure("页面加载失败，可返回后重试。")
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    issue("网页渲染进程已退出。"); onFailure("小程序运行中断，请重新打开。"); view.destroy(); return true
                }
            }
        }

    companion object {
        /**
         * 冻结动画与过渡。
         *
         * 为什么必须做：**离屏 WebView 没有窗口，CSS 动画的时间轴不会推进**，带 `both`/`backwards`
         * 填充的入场动画会一直停在第一帧。实测「每日诗词」小程序的 `main > .view.is-in{animation:fadeUp
         * .2s ease-out both}`（from 是 `opacity: 0`）在离屏渲染里永远不动，于是截图只有页头和底栏，
         * 正文整块空白——看起来像小程序坏了，其实只是我们画不出来。
         *
         * 注入 `animation: none` 后元素回到基础样式，也就是动画播完的样子；`getAnimations().finish()`
         * 再兜住用 Web Animations API 写的动画。顺带让审计读到的是**稳定状态**，不再是过渡中间态。
         */
        private const val FREEZE = """(() => {
          const style = document.createElement('style');
          style.textContent = '*,*::before,*::after{animation:none !important;transition:none !important}';
          document.documentElement.appendChild(style);
          try { document.getAnimations && document.getAnimations().forEach(a => { try { a.finish(); } catch (e) {} }); } catch (e) {}
          return true;
        })()"""

        /**
         * 预览图的渲染尺寸 = 设备真实像素。以前固定 390×780dp，在 1240×2772 / 560dpi 的机器上
         * 得到的是 1114×1505（横向 3.5 倍缩到聊天列宽、纵向只有 430dp），比例 0.74——比真机
         * 的 0.45 胖得多，看起来完全不像手机。审计视口同一个原因也是错的（354dp 的机器按 390dp 验）。
         */
        private fun deviceSize(context: Context): Pair<Int, Int> {
            val metrics = context.resources.displayMetrics
            return metrics.widthPixels.coerceAtLeast(320) to metrics.heightPixels.coerceAtLeast(480)
        }

        /** 把已经布局好的 WebView 画进一张位图。失败返回 null。 */
        private fun frame(view: WebView): android.graphics.Bitmap? {
            val width = view.width
            val height = view.height
            if (width <= 0 || height <= 0) return null
            val bitmap = runCatching {
                android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            }.getOrNull() ?: return null
            val canvas = android.graphics.Canvas(bitmap)
            // WebView 自身底色是白的；先铺白再叠内容，和用户真看到的一致。
            canvas.drawColor(android.graphics.Color.WHITE)
            return runCatching { view.draw(canvas); bitmap }.getOrNull()
        }

        /**
         * 纯色帧检测。软件层绘制失败、页面还没画出来、或渲染进程没起来时，draw() 会得到一张
         * 单色图；把它当成预览图存下来会让人以为「小程序就是一片白」。
         */
        private fun blank(bitmap: android.graphics.Bitmap): Boolean {
            val width = bitmap.width
            val height = bitmap.height
            val total = width * height
            val step = (total / 20000).coerceAtLeast(1)
            var min = 255
            var max = 0
            var index = 0
            while (index < total) {
                val color = bitmap.getPixel(index % width, index / width)
                val luma = ((color shr 16 and 0xFF) * 299 + (color shr 8 and 0xFF) * 587 + (color and 0xFF) * 114) / 1000
                if (luma < min) min = luma
                if (luma > max) max = luma
                if (max - min > 12) return false
                index += step
            }
            return true
        }

        /** Executes app interactions in a temporary origin, never in the user's stored data. */
        fun verify(context: Context, project: WebProject, steps: JSONArray = JSONArray(), shotKey: String? = null, callback: (JSONObject) -> Unit) {
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val preview = WebPreview(project, inspection = true)
                var finished = false
                var started = false
                var web: WebView? = null
                var shot: String? = null
                fun finish(report: JSONObject) {
                    if (finished) return
                    finished = true
                    report.put("errors", JSONArray(synchronized(preview.errors) { preview.errors.toList() })).put("scope", "isolated_webview")
                    report.put("consoleMessages", JSONArray(synchronized(preview.consoleMessages) { preview.consoleMessages.toList() }))
                    report.put("passed", report.optBoolean("passed") && preview.errors.isEmpty())
                    shot?.let { report.put("shot", it) }
                    web?.stopLoading(); web?.destroy()
                    WebStorage.getInstance().deleteOrigin(preview.origin)
                    callback(report)
                }
                /**
                 * 截「打开小程序时看到的那一屏」。
                 *
                 * 为什么不在交互步骤之后截：那样截到的是「点完一串按钮之后的样子」——可能是弹层，
                 * 可能是被点空的列表，用户看到会以为小程序就是这个状态。预览图的语义是封面，
                 * 应该是首屏。所以顺序是：冻结动画 → 审计 → **截图** → 交互与断言。
                 *
                 * 需要 LAYER_TYPE_SOFTWARE —— 硬件加速时页面由独立渲染进程合成，draw() 只能拿到空白。
                 */
                fun capture(view: WebView) {
                    if (shotKey == null || shot != null) return
                    val bitmap = frame(view)
                    shot = when {
                        bitmap == null -> "render_failed"
                        blank(bitmap) -> "blank"
                        ShotStore.write(context, shotKey, bitmap) == null -> "write_failed"
                        else -> "ok"
                    }
                }
                val timeout = Runnable { finish(JSONObject().put("passed", false).put("reason", "预览验证超过 15 秒。")) }
                handler.postDelayed(timeout, 15000)
                /**
                 * 交互与断言单独跑一遍（截图之后）。`__goonSteps` 由审计脚本定义，
                 * 复用同一份 DOM 状态与同一个作用域里的点击指纹逻辑。
                 */
                fun runSteps(view: WebView, report: JSONObject, done: (JSONObject) -> Unit) {
                    val json = steps.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
                    view.evaluateJavascript("window.__goonSteps ? window.__goonSteps($json) : '{}'") { raw ->
                        val parsed = runCatching { JSONObject(JSONArray("[$raw]").getString(0)) }.getOrNull()
                        val assertions = parsed?.optJSONArray("assertions") ?: JSONArray()
                        report.put("assertions", assertions)
                        report.put("clicks", parsed?.optJSONArray("clicks") ?: JSONArray())
                        val allPassed = (0 until assertions.length()).all { assertions.getJSONObject(it).optBoolean("passed") }
                        val dead = (0 until report.getJSONArray("clicks").length())
                            .map { report.getJSONArray("clicks").getJSONObject(it) }
                            .filter { !it.optBoolean("changed") }
                            .map { it.optString("selector") }
                        if (dead.isNotEmpty()) {
                            report.getJSONArray("layoutProblems").put(
                                "这些点击没有引起任何 DOM 变化：${dead.joinToString("、")}。用户点下去不会看到任何反馈。" +
                                    "反馈要同步插入 DOM（先把弹层/列表显出来，再播入场动画）——异步（setTimeout 之后再插 DOM）" +
                                    "既让用户觉得卡，也让断言不稳定。"
                            )
                            report.put("passed", false)
                        }
                        if (!allPassed) report.put("passed", false)
                        done(report)
                    }
                }
                runCatching {
                    web = preview.create(context, onReady = { view ->
                        if (!started && !finished) {
                            started = true
                            // 先冻结动画再等待：离屏 WebView 里动画时间轴不推进，带 both 填充的入场动画
                            // 会一直停在第一帧（opacity: 0），截图与审计看到的都是「还没出现」的状态。
                            view.evaluateJavascript(FREEZE, null)
                            handler.postDelayed({
                                if (!finished) {
                                    val script = """
                                        (() => {
                                          const results = [];
                                          // 点击是否真的产生了效果：对整页 HTML 取指纹，比较点击前后的差异。
                                          // 用户遇到过的真实问题是「点记 1 抽没有任何反应」，而当时的断言只查了一个数字，
                                          // 断言通过、按钮是死的。指纹比对能把这种按钮直接暴露出来。
                                          const sig = () => {
                                            const html = (document.documentElement && document.documentElement.outerHTML) || '';
                                            let h = 5381;
                                            for (let i = 0; i < html.length; i++) h = ((h * 33) ^ html.charCodeAt(i)) >>> 0;
                                            const fields = Array.from(document.querySelectorAll('input,select,textarea')).map(e => e.value + '/' + (e.checked ? 1 : 0)).join(',');
                                            return h + ':' + fields;
                                          };
                                          const clickEffects = [];
                                          /* 有效可见性：只看元素自己的计算样式是不够的——父级 opacity: 0 或者
                                             visibility: hidden 时，子元素依旧"可见"，但用户什么都看不到。
                                             每日诗词小程序的整块正文就是这么消失的（入场动画停在第一帧），
                                             而当时的审计一条问题都没报。 */
                                          const visible = el => {
                                            const r = el.getBoundingClientRect();
                                            if (r.width <= 0 || r.height <= 0) return false;
                                            let opacity = 1;
                                            for (let n = el; n && n.nodeType === 1; n = n.parentElement) {
                                              const s = getComputedStyle(n);
                                              if (s.display === 'none' || s.visibility === 'hidden') return false;
                                              opacity *= Number(s.opacity);
                                              if (opacity <= 0.05) return false;
                                            }
                                            return true;
                                          };
                                          const interactive = Array.from(document.querySelectorAll('button,a,input,select,textarea,[role=button]')).filter(visible);
                                          // 只报「谁被谁挡住」模型改不动样式，它只能靠猜；这里连几何一起给出，
                                          // 模型才不用往页面里塞 console 调试代码（那会让验证一直失败，实测烧掉 17 轮）。
                                          const rectOf = el => { const r = el.getBoundingClientRect(); return [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)]; };
                                          const nameOf = el => el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + (el.classList && el.classList.length ? '.' + Array.from(el.classList).slice(0, 2).join('.') : '');
                                          const chainOf = el => { const parts = [nameOf(el)]; let p = el.parentElement, depth = 0; while (p && p !== document.documentElement && depth < 3) { parts.push(nameOf(p)); p = p.parentElement; depth++; } return parts.join(' < '); };
                                          const layout = { viewport: [innerWidth, innerHeight], blocked: [], fixedCoverage: 0, fixedTags: [], overflowCount: 0, overflowSamples: [], smallTargets: [] };
                                          for (const el of interactive) {
                                            const r = el.getBoundingClientRect();
                                            const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
                                            if (cx < 0 || cy < 0 || cx > innerWidth || cy > innerHeight) continue;
                                            const hit = document.elementFromPoint(cx, cy);
                                            if (hit && hit !== el && !el.contains(hit) && !hit.contains(el)) {
                                              const hs = getComputedStyle(hit);
                                              layout.blocked.push({el: nameOf(el), chain: chainOf(el), rect: rectOf(el), text: (el.textContent || '').trim().slice(0, 24), by: nameOf(hit), byChain: chainOf(hit), byRect: rectOf(hit), byPosition: hs.position, byZ: hs.zIndex, byPointerEvents: hs.pointerEvents});
                                            }
                                          }
                                          for (const el of document.querySelectorAll('body *')) {
                                            const s = getComputedStyle(el), r = el.getBoundingClientRect();
                                            if (!visible(el)) continue;
                                            if (r.right > innerWidth + 2 || r.left < -2) { layout.overflowCount++; if (layout.overflowSamples.length < 5) layout.overflowSamples.push(nameOf(el) + ' ' + rectOf(el).join(',')); }
                                            if (s.position !== 'fixed' && s.position !== 'sticky') continue;
                                            if (r.width < innerWidth * 0.5 || r.top < innerHeight * 0.5) continue;
                                            const coverage = Math.min(r.height, innerHeight) / innerHeight;
                                            if (coverage > layout.fixedCoverage) { layout.fixedCoverage = Math.round(coverage * 100) / 100; layout.fixedTags = [nameOf(el) + ' ' + s.position + ' z=' + s.zIndex + ' rect=' + rectOf(el).join(',')]; }
                                          }
                                          layout.smallTargets = interactive.filter(el => { const r = el.getBoundingClientRect(); return r.height < 44 || r.width < 24; }).slice(0, 10).map(el => nameOf(el) + ' ' + rectOf(el)[2] + 'x' + rectOf(el)[3]);
                                          const layoutProblems = [];
                                          if (layout.blocked.length) layoutProblems.push(layout.blocked.length + ' 个可点击元素被其它元素遮挡，用户点不到。layout.blocked 里有各自的 rect 与遮挡者的 rect/position/z-index/pointer-events，按它定位问题：被挡住多半是 z-index 不够、浮层没设 pointer-events，或上方元素铺满了整块区域。');
                                          if (layout.fixedCoverage > 0.42) layoutProblems.push('固定底栏占据 ' + Math.round(layout.fixedCoverage * 100) + '% 屏幕高度，压住了正文。layout.fixedTags 给出是哪个元素及它的 rect。');
                                          if (layout.overflowCount) layoutProblems.push(layout.overflowCount + ' 个元素超出屏幕宽度，会出现横向滚动，样例见 layout.overflowSamples。');
                                          /* 自己被样式判定为「显示」，却被祖先的 opacity/visibility 藏起来的元素。
                                             典型来源有两种：入场动画停在第一帧（离屏渲染里最常见），
                                             以及用 opacity: 0 代替 display: none 隐藏的弹层——后者还会继续占位、
                                             继续吃掉点击，用户点上去就是「没反应」。 */
                                          /* 只看「被祖先的 opacity 藏起来」这一种：display:none 与 visibility:hidden
                                             都不占位也不吃点击，是正常的条件渲染写法，报出来只是噪声。 */
                                          const ghost = [];
                                          for (const el of document.querySelectorAll('body *')) {
                                            const tag = el.tagName.toLowerCase();
                                            if (tag !== 'button' && tag !== 'a' && !el.getAttribute('role')) continue;
                                            const label = (el.textContent || '').trim().slice(0, 12);
                                            if (label.length < 2) continue;
                                            if (!el.getBoundingClientRect().width) continue;
                                            let blocked = null;
                                            for (let n = el; n && n.nodeType === 1; n = n.parentElement) {
                                              const s = getComputedStyle(n);
                                              if (s.display === 'none' || s.visibility === 'hidden') { blocked = null; break; }
                                              if (Number(s.opacity) <= 0.05) { blocked = 'opacity'; break; }
                                            }
                                            if (blocked) ghost.push(nameOf(el) + '「' + label + '」');
                                          }
                                          if (ghost.length) layoutProblems.push('这些可点击元素其实看不见（祖先的 opacity/visibility 把它藏了，不是 display: none）：' + ghost.slice(0, 5).join('、') + '。如果这是入场动画的起始帧，说明动画没播完就停住了；如果是用来隐藏弹层/遮罩，请改用 display: none 或 visibility: hidden —— 只把 opacity 置 0 的元素仍然占位、仍然会吃掉点击，用户点上去就是「没反应」。');
                                          // 文字对比度。审美无法自动判定，但「看不清」是可以算出来的：
                                          // 文字色与它背后的真实底色算 WCAG 对比度，不足 4.5（大字号 3.0）就是硬伤。
                                          const rgbaOf = c => {
                                            const m = String(c).match(/rgba?\(([^)]+)\)/);
                                            if (!m) return null;
                                            const p = m[1].split(',').map(x => parseFloat(x));
                                            return [p[0] || 0, p[1] || 0, p[2] || 0, p.length > 3 ? p[3] : 1];
                                          };
                                          const over = (fg, bg) => {
                                            const a = fg[3] + bg[3] * (1 - fg[3]);
                                            if (a === 0) return [0, 0, 0, 0];
                                            const mix = i => (fg[i] * fg[3] + bg[i] * bg[3] * (1 - fg[3])) / a;
                                            return [mix(0), mix(1), mix(2), a];
                                          };
                                          const luma = c => {
                                            const f = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
                                            return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2]);
                                          };
                                          const ratio = (fg, bg) => {
                                            const a = luma(fg), b = luma(bg);
                                            return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
                                          };
                                          /* 背景候选：自身到 body 的实色叠加结果，加上各层渐变里的色标。
                                             渐变算不出唯一底色，但可以取「对文字最有利的那个色」——如果连最优解都不够清楚，
                                             那就是真的看不清；否则标为 unverified，不猜。 */
                                          const backdrops = el => {
                                            const solids = [];
                                            const stops = [];
                                            for (let n = el; n; n = n.parentElement) {
                                              const s = getComputedStyle(n);
                                              const c = rgbaOf(s.backgroundColor);
                                              if (c && c[3] > 0) solids.push(c);
                                              if (s.backgroundImage && s.backgroundImage !== 'none') {
                                                for (const hit of s.backgroundImage.match(/rgba?\([^)]+\)/g) || []) {
                                                  const g = rgbaOf(hit);
                                                  if (g && g[3] > 0.5) stops.push(g);
                                                }
                                              }
                                              if (c && c[3] >= 0.999) break;
                                            }
                                            let base = [0, 0, 0, 0];
                                            for (let i = solids.length - 1; i >= 0; i--) base = over(solids[i], base);
                                            if (base[3] < 0.999) base = over(base, [255, 255, 255, 1]);
                                            const list = [base];
                                            for (const st of stops) list.push(over(st, base));
                                            return list;
                                          };
                                          const contrast = { checked: 0, unverified: 0, issues: [], worst: [] };
                                          /* 字号样本：层级太平是"AI 味"最主要的来源之一——
                                             所有文字都在 13–16px 之间，页面就没有重点。 */
                                          const typeSizes = [];
                                          for (const el of document.querySelectorAll('body *')) {
                                            if (!visible(el) || (el.closest && el.closest('[aria-hidden=true]'))) continue;
                                            const own = Array.from(el.childNodes || []).filter(n => n.nodeType === 3).map(n => n.textContent.trim()).join('').trim();
                                            if (!own) continue;
                                            const s = getComputedStyle(el);
                                            if (s.webkitTextFillColor === 'rgba(0, 0, 0, 0)' || s.color === 'rgba(0, 0, 0, 0)') continue;
                                            const fg = rgbaOf(s.color);
                                            if (!fg || fg[3] < 0.05) continue;
                                            // 从元素自己开始取底色：按钮、卡片这类元素本身就带背景，文字是压在自己的背景上，
                                            // 从父节点开始会把「白字压红底」误判成「白字压白底」。
                                            const list = backdrops(el);
                                            if (!list.length) { contrast.unverified++; continue; }
                                            let best = 0;
                                            for (const bg of list) best = Math.max(best, ratio(over(fg, bg), bg));
                                            const size = parseFloat(s.fontSize);
                                            const need = (size >= 24 || (size >= 18.66 && parseInt(s.fontWeight, 10) >= 700)) ? 3 : 4.5;
                                            const entry = { el: nameOf(el), text: own.slice(0, 18), ratio: Math.round(best * 100) / 100, need: need, color: s.color,
                                              bg: 'rgb(' + list[0].slice(0, 3).map(v => Math.round(v)).join(',') + ')' };
                                            contrast.checked++;
                                            if (size > 0) typeSizes.push(size);
                                            if (best < need) contrast.issues.push(entry);
                                          }
                                          contrast.issues.sort((a, b) => a.ratio - b.ratio);
                                          /* 留 0.5 的余量再判失败：4.34:1 这种贴着 4.5 的值落在测量噪声里，
                                             真拿去改色反而会把一套好配色改坏（实测用户认可的原型就是 4.34:1）。 */
                                          const hard = contrast.issues.filter(c => c.ratio < c.need - 0.5);
                                          const soft = contrast.issues.filter(c => c.ratio >= c.need - 0.5);
                                          contrast.softIssues = soft;
                                          const contrastProblems = hard.slice(0, 6).map(c => '「' + c.text + '」(' + c.el + ') 对比度只有 ' + c.ratio + ':1，需要 ' + c.need + ':1（前景 ' + c.color + '，底色 ' + c.bg + '）');
                                          if (contrastProblems.length) layoutProblems.push('有 ' + hard.length + ' 处文字看不清：' + contrastProblems.join('；') + '。文字与它背后的实际底色要拉开层次；深底配浅字、浅底配深字，不要浅底浅字或深底深字。');

                                          /* 结构节奏：整页都是「一个区块一行、纵向堆到底」的流水账，是可检测的。
                                             多列/网格/时间轴才算有排版的对比。 */
                                          let grids = 0;
                                          for (const el of document.querySelectorAll('body *')) {
                                            if (!visible(el)) continue;
                                            const s = getComputedStyle(el);
                                            const cols = (s.gridTemplateColumns || '').split(' ').filter(x => x && x !== 'none').length;
                                            if (s.display.indexOf('grid') === 0 && cols > 1) grids++;
                                            else if (s.display.indexOf('flex') === 0 && s.flexDirection === 'row' && el.children.length > 1 && el.getBoundingClientRect().height > 40) grids++;
                                          }
                                          const sections = document.querySelectorAll('section, .card, .block, .panel, [class*=section]').length;
                                          if (sections >= 3 && grids === 0) layoutProblems.push('整页 ' + sections + ' 个区块全是单列纵向堆叠，没有一处网格或分栏对比，读起来是流水账。挑一处用 grid 做并列对比（指标格、双列信息、标签墙），或把一组同质条目改成表格/时间轴/分隔线结构。');
                                          /* 交互放在审计之后：审计要看的是「用户打开时看到的那一屏」。
                                             先点开弹层再审计的话，遮罩会合法地盖住整页，每次都会误报一堆「被遮挡」。
                                             交互单独一个 evaluateJavascript 调用，因为**截图要插在审计和交互之间**。 */
                                          window.__goonSteps = steps => {
                                          const results = [];
                                          for (const s of steps) {
                                            try {
                                              const el = s.selector ? document.querySelector(s.selector) : null;
                                              if (s.selector && !el) throw Error('未找到元素 ' + s.selector);
                                              if (s.action === 'click') {
                                                const before = sig();
                                                el.click();
                                                clickEffects.push({ selector: s.selector, changed: sig() !== before });
                                              }
                                              else if (s.action === 'type') {
                                                el.value = s.text || '';
                                                el.dispatchEvent(new Event('input', {bubbles:true}));
                                                el.dispatchEvent(new Event('change', {bubbles:true}));
                                              } else if (s.action === 'assertText') {
                                                if (!el.textContent.includes(s.text)) throw Error('文本断言未通过');
                                              } else if (s.action === 'assertCount') {
                                                if (document.querySelectorAll(s.selector).length !== s.count) throw Error('数量断言未通过');
                                              } else if (s.action === 'assertVisible') {
                                                if (!el.getClientRects().length || getComputedStyle(el).visibility === 'hidden') throw Error('元素不可见');
                                              } else throw Error('不支持的交互动作');
                                              results.push({action:s.action, passed:true});
                                            } catch(e) { results.push({action:s.action, passed:false, error:String(e)}); break; }
                                          }
                                          return JSON.stringify({ assertions: results, clicks: clickEffects });
                                          };
                                          // 设计审计：审美无法自动判定，但「套默认模板」会在代码里留下可检测的痕迹。
                                          const design = { radii: [], shadowCount: 0, tintedShadows: 0, decorations: 0, fonts: [], letterSpacing: 0, gradients: 0, cardLike: 0, boxed: 0, roundedBoxed: 0, designIssues: [] };
                                          const motifs = {};
                                          const palette = {};
                                          const radiusSeen = {};
                                          // 材质背景通常画在 body / html 上，扫描必须带上它们本身，否则纹理会被漏掉。
                                          for (const el of document.querySelectorAll('html, body, body *')) {
                                            if (!visible(el)) continue;
                                            const s = getComputedStyle(el);
                                            const radius = String(s.borderRadius || '0px').replace(/\s+/g, ' ').trim();
                                            if (radius !== '0px' && radius !== '0px 0px 0px 0px' && radiusSeen[radius] !== true) { radiusSeen[radius] = true; design.radii.push(radius); }
                                            // 配色系统：把文字色与实色底都按 8 级量化后去重，种数过多说明没有一个收敛的色板。
                                            for (const value of [s.color, s.backgroundColor]) {
                                              const m = String(value).match(/rgba?\(([^)]+)\)/);
                                              if (!m) continue;
                                              const parts = m[1].split(',').map(Number);
                                              if (parts.length > 3 && parts[3] < 0.9) continue;
                                              const key = [0, 1, 2].map(i => Math.round((parts[i] || 0) / 8)).join(',');
                                              palette[key] = 1;
                                            }
                                            if (s.boxShadow && s.boxShadow !== 'none') {
                                              design.shadowCount++;
                                              const colors = s.boxShadow.match(/rgba?\([^)]+\)/g) || [];
                                              for (const color of colors) {
                                                const nums = color.replace(/[^0-9.,]/g, '').split(',').map(Number);
                                                if (nums.length >= 3 && Math.max(Math.abs(nums[0] - nums[1]), Math.abs(nums[1] - nums[2]), Math.abs(nums[0] - nums[2])) >= 12) design.tintedShadows++;
                                              }
                                            }
                                            // content:"" 是最常见的装饰写法（圆环、角框、纹样），不能当成空装饰排除；
                                            // 只要伪元素真的绘制出了尺寸、边框或填充，就算一处装饰。
                                            const paints = p => {
                                              if (!p || !p.content || p.content === 'none' || p.content === 'normal') return false;
                                              const sized = (p.width && p.width !== 'auto' && p.width !== '0px') || (p.height && p.height !== 'auto' && p.height !== '0px');
                                              const bordered = p.borderTopWidth && p.borderTopWidth !== '0px';
                                              const filled = (p.backgroundImage && p.backgroundImage !== 'none') || (p.backgroundColor && p.backgroundColor !== 'rgba(0, 0, 0, 0)');
                                              return Boolean(sized || bordered || filled);
                                            };
                                            for (const pseudo of [getComputedStyle(el, '::before'), getComputedStyle(el, '::after')]) if (paints(pseudo)) design.decorations++;
                                            const family = String(s.fontFamily || '').split(',')[0].replace(/["']/g, '').trim();
                                            if (family && design.fonts.indexOf(family) < 0) design.fonts.push(family);
                                            if (s.letterSpacing && s.letterSpacing !== 'normal' && s.letterSpacing !== '0px') design.letterSpacing++;
                                            if (s.backgroundImage && s.backgroundImage !== 'none' && s.backgroundImage.indexOf('gradient') >= 0) design.gradients++;
                                            if (radius !== '0px' && s.boxShadow && s.boxShadow !== 'none' && s.backgroundColor && s.backgroundColor !== 'rgba(0, 0, 0, 0)') design.cardLike++;
                                            // 有底色或边框的"盒子"里，有多少是圆角的。圆角本身没错，错在满页都是。
                                            const boxed = (s.backgroundColor && s.backgroundColor !== 'rgba(0, 0, 0, 0)') || (s.backgroundImage && s.backgroundImage !== 'none') || s.borderTopWidth !== '0px';
                                            const boxRect = el.getBoundingClientRect();
                                            if (boxed && boxRect.width > 48 && boxRect.height > 24) {
                                              design.boxed++;
                                              if (parseFloat(radius) >= 8) design.roundedBoxed++;
                                            }
                                            /* 结构母题：同一页用了几种「骨架」。这是"有设计"和"AI 味"之间最可测的差别——
                                               生成产物常常整页只有一种骨架（一张大卡 + 几枚按钮 + 底部标签），
                                               而手工打磨的页面会同时用指标格、时间轴、色条提示段、折叠条目、筛选 chips。 */
                                            const cols = (s.gridTemplateColumns || '').split(' ').filter(x => x && x !== 'none').length;
                                            if (s.display.indexOf('grid') === 0 && cols > 1 && boxRect.width > 120) motifs.grid = 1;
                                            if (parseFloat(s.borderLeftWidth || '0') >= 2 && boxed) motifs.accent = 1;
                                            if (/dashed|dotted/.test(s.borderTopStyle + s.borderLeftStyle + s.borderBottomStyle)) motifs.dashed = 1;
                                            if (el.tagName === 'DETAILS') motifs.collapse = 1;
                                            if (s.overflowX === 'auto' && el.children.length >= 3) motifs.strip = 1;
                                          }
                                          if (design.radii.length <= 1) design.designIssues.push('全页只有一种圆角，缺少形状节奏');
                                          if (design.decorations === 0) design.designIssues.push('没有任何装饰性伪元素（圆环、角标、分隔纹样等），页面缺少质感');
                                          if (design.fonts.length <= 1 && design.letterSpacing === 0) design.designIssues.push('只有一种字体且没有字距调整，标题与正文缺少对比');
                                          if (design.shadowCount > 0 && design.tintedShadows === 0) design.designIssues.push('阴影是纯黑或灰色，没有与配色同源的色调');
                                          if (design.cardLike >= 3 && design.gradients === 0) design.designIssues.push('三个以上区块都是同款卡片叠卡片，缺少网格、分隔线这类结构表达');
                                          /* 字号跨度与圆角泛滥：这两条是"AI 味"最可测的两个来源。 */
                                          let typeSpan = 0;
                                          if (typeSizes.length >= 6) {
                                            const lo = Math.min.apply(null, typeSizes), hi = Math.max.apply(null, typeSizes);
                                            typeSpan = Math.round((hi / lo) * 100) / 100;
                                            // 阈值按真实页面标定：用户认可的参考原型是 2.45 倍，生成产物通常在 2.0 上下。
                                            // 2.2 以下是"明显平"，1.8 以下才判失败——这条量的是层级，不是风格。
                                            if (typeSpan < 2.2) design.designIssues.push('整页字号只在 ' + Math.round(lo) + '–' + Math.round(hi) + 'px 之间（跨度 ' + typeSpan + ' 倍）：层级偏平，用户扫不出重点。小标签 10–12px、正文 14–15px、标题 20–27px、关键数字 27–34px，跨度做到 2.4 倍以上。');
                                          }
                                          const roundedRatio = design.boxed ? Math.round((design.roundedBoxed / design.boxed) * 100) / 100 : 0;
                                          if (design.boxed >= 6 && roundedRatio > 0.8) design.designIssues.push('有底色/边框的 ' + design.boxed + ' 个区块里 ' + design.roundedBoxed + ' 个都是圆角（' + Math.round(roundedRatio * 100) + '%）：满屏圆角卡片是最典型的 AI 味。默认应该是直角 + 1px 细线，圆角留给少数控件（头像、圆环数字、非对称徽章）。');
                                          /* 形状系统：**种数多了才是问题，不是少了**。
                                             这一条曾经写反过——旧判据是「只有一种圆角就判默认模板失败」，
                                             等于逼着模型到处加圆角值，正好是 AI 味的配方（"同一屏 2 种圆角就开始漂移"）。
                                             现在按形状漂移判：不同圆角值 ≥4 种视为没有形状系统。50% 这类圆形组件不算。 */
                                          const shapeRadii = design.radii.filter(r => r.indexOf('%') < 0);
                                          if (shapeRadii.length >= 4) design.designIssues.push('全页出现 ' + shapeRadii.length + ' 种不同圆角（' + shapeRadii.slice(0, 5).join(' / ') + '）：这是形状漂移，不是形状节奏。定 1–2 档并全局复用，圆形头像/圆环用 50% 单独归类。');
                                          const motifKinds = Object.keys(motifs);
                                          if (sections >= 3 && motifKinds.length < 2) design.designIssues.push('整页只用了 ' + motifKinds.length + ' 种结构骨架（' + (motifKinds.join('、') || '无') + '）：读起来就是"流水账 + 按钮"。至少用两种——多列指标格、时间轴、表格式列表、色条提示段、可折叠条目、横向筛选 chips 里挑，而且不要都用同一张卡片表达。');
                                          const paletteSize = Object.keys(palette).length;
                                          if (paletteSize > 18) design.designIssues.push('整页统计到 ' + paletteSize + ' 种不同的文字色/实色底：色板没有收敛，读起来是"每个区块各自决定颜色"。收敛到 1 个主色 + 1 个强调色 + 一套中性灰阶，深浅靠同一色的不同明度而不是新颜色。');
                                          /* 判「默认模板」的口径必须是**一致性**，不能是**装饰量**。
                                             旧口径是「只有一种圆角 + 零装饰性伪元素 + 无渐变或纹理 → 失败」——
                                             那等于下命令"去加圆角、加伪元素、加渐变"，而这三样正是 AI 味的配方，
                                             而且它会枪毙掉真正克制的设计（一种圆角、纯色底、零装饰完全可以做得很好）。
                                             现在只保留三种可证伪的一致性缺陷：卡片墙、层级太平、形状漂移。 */
                                          // 卡片墙的判据是"清一色圆角卡片 + 没有任何别的结构想法"，不是"有圆角"。
                                          // 一个统一的圆角系统本身是好设计；只有当整页只剩这一种表达时才是模板。
                                          const cardWall = design.boxed >= 5 && roundedRatio > 0.8 && Object.keys(motifs).length < 2;
                                          const flatType = typeSizes.length >= 6 && typeSpan > 0 && typeSpan < 1.8;
                                          const shapeDrift = shapeRadii.length >= 4;
                                          const genericTemplate = cardWall || flatType || shapeDrift;
                                          if (genericTemplate) {
                                            const why = cardWall ? '满屏圆角卡片' : flatType ? '字号层级太平' : '形状漂移（圆角值太多）';
                                            design.designIssues.unshift('被判定为默认模板风格（' + why + '）。这三条判的都是"没有系统"：形状要么 1–2 档要么乱，字号要么拉开要么平，盒子要么有结构区分要么清一色。先定系统再改细节，不要靠继续加圆角、阴影、渐变来救。');
                                          }
                                          return JSON.stringify({
                                            passed: !!document.body && !!document.body.children.length && layoutProblems.length === 0 && !genericTemplate,
                                            title: document.title, nodes: document.querySelectorAll('*').length,
                                            horizontalOverflow: document.documentElement.scrollWidth > innerWidth + 1,
                                            controls: Array.from(document.querySelectorAll('button,input,select,a')).slice(0,40).map(e => ({tag:e.tagName,id:e.id,text:(e.textContent||'').slice(0,60)})),
                                            layout: layout, layoutProblems: layoutProblems,
                                            contrast: { checked: contrast.checked, unverified: contrast.unverified, issues: contrast.issues.slice(0, 6) },
                                            design: { radii: design.radii, shadowCount: design.shadowCount, tintedShadows: design.tintedShadows, decorations: design.decorations, fonts: design.fonts, letterSpacing: design.letterSpacing, gradients: design.gradients, cardLike: design.cardLike, boxed: design.boxed, roundedBoxed: design.roundedBoxed, typeSpan: typeSpan, motifs: Object.keys(motifs), palette: paletteSize, shapeRadii: shapeRadii.length },
                                            contrastSoft: contrast.softIssues.slice(0, 6),
                                            designIssues: design.designIssues, genericTemplate: genericTemplate
                                          });
                                        })()
                                    """.trimIndent()
                                    view.evaluateJavascript(script) { raw ->
                                        if (!finished) {
                                            handler.removeCallbacks(timeout)
                                            val report = runCatching { JSONObject(JSONArray("[$raw]").getString(0)) }.getOrElse { JSONObject().put("passed", false).put("reason", "无法读取页面状态。") }
                                            if (report.optBoolean("horizontalOverflow")) report.put("passed", false)
                                            capture(view)
                                            runSteps(view, report) { finish(it) }
                                        }
                                    }
                                }
                            }, 600)
                        }
                    }, onFailure = { finish(JSONObject().put("passed", false).put("reason", it)) })
                    val (width, height) = deviceSize(context)
                    // 软件层必须在 loadUrl 之前设：硬件加速时页面由独立渲染进程合成，draw() 拿到的是一张空白，
                    // 于是预览图会变成纯白（用户就是这么看到「纯白背景」的）。
                    web!!.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                    web!!.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
                    web!!.layout(0, 0, width, height)
                    web!!.loadUrl(preview.entryUrl)
                }.onFailure { handler.removeCallbacks(timeout); finish(JSONObject().put("passed", false).put("reason", "预览启动失败。")) }
            }
        }
    }
}
