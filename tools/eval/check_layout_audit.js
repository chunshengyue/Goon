#!/usr/bin/env node
/**
 * 无设备回归检查：从 WebPreview.kt 抽出真正在用的审计脚本，用桩 DOM 跑布局与设计两类场景。
 *
 * 这段脚本内嵌在 Kotlin 原始字符串里，语法错误或判定写错都不会体现在编译结果里，
 * 因此需要一个不需要真机和模型的检查手段。
 *
 * 用法：node tools/eval/check_layout_audit.js
 */

const fs = require("fs");
const path = require("path");

const SOURCE = path.join(__dirname, "..", "..", "app", "src", "main", "java", "com", "example", "goon", "core", "WebPreview.kt");

function extractAuditScript() {
  const text = fs.readFileSync(SOURCE, "utf8");
  const marker = 'val script = """';
  const start = text.indexOf(marker);
  if (start < 0) throw new Error("没有在 WebPreview.kt 里找到内嵌脚本");
  const bodyStart = start + marker.length;
  const end = text.indexOf('""".trimIndent()', bodyStart);
  if (end < 0) throw new Error("内嵌脚本没有正常结束");
  return text.slice(bodyStart, end);
}

const BASE_STYLE = {
  display: "block", visibility: "visible", opacity: "1", position: "static",
  zIndex: "auto", pointerEvents: "auto",
  borderRadius: "0px", boxShadow: "none", backgroundImage: "none",
  backgroundColor: "rgba(0, 0, 0, 0)", fontFamily: "system-ui, sans-serif", letterSpacing: "normal",
  color: "rgb(28, 28, 30)", fontSize: "15px", fontWeight: "400"
};

const NO_PSEUDO = { content: "none", width: "auto", height: "auto", borderTopWidth: "0px", backgroundImage: "none", backgroundColor: "rgba(0, 0, 0, 0)" };

function decoration(style) {
  return Object.assign({ content: '""', width: "150px", height: "150px", borderTopWidth: "0px", backgroundImage: "none", backgroundColor: "rgba(0, 0, 0, 0)" }, style);
}

function makeElement(tag, id, rect, style, text, pseudo) {
  return {
    tagName: tag,
    nodeType: 1,
    id: id,
    classes: [],
    textContent: text || "",
    rect: Object.assign({ left: 0, top: 0, width: 0, height: 0, right: 0, bottom: 0 }, rect),
    computed: Object.assign({}, BASE_STYLE, style),
    pseudo: Object.assign({ "::before": NO_PSEUDO, "::after": NO_PSEUDO }, pseudo),
    // 对比度审计只看「元素自己的直接文字」，桩 DOM 用文本节点模拟。
    childNodes: text ? [{ nodeType: 3, textContent: text }] : [],
    parentElement: null,
    getBoundingClientRect() { return this.rect; },
    contains() { return false; },
    closest(selector) {
      if (selector !== "[aria-hidden=true]") return null;
      return this.computed.ariaHidden === true ? this : null;
    },
    getAttribute() { return null; }
  };
}

function runAudit(script, elements, hitTest) {
  global.innerWidth = 390;
  global.innerHeight = 780;
  // 审计脚本会把交互步骤挂到 window.__goonSteps（真机分两次 evaluateJavascript 调用），桩环境给同名对象即可。
  global.window = global;
  global.getComputedStyle = (element, pseudo) => (pseudo ? element.pseudo[pseudo] || NO_PSEUDO : element.computed);
  global.document = {
    title: "audit",
    documentElement: { scrollWidth: 390 },
    body: { children: { length: elements.length } },
    querySelectorAll: (selector) => (selector.indexOf("button") >= 0
      ? elements.filter((element) => ["BUTTON", "INPUT", "A", "SELECT"].indexOf(element.tagName) >= 0)
      : selector.indexOf("section") >= 0
        ? elements.filter((element) => element.tagName === "SECTION" || element.classes.some((name) => ["card", "block", "panel"].indexOf(name) >= 0))
        : elements),
    elementFromPoint: (x, y) => hitTest(elements, x, y)
  };
  // 内嵌脚本以换行开头，直接拼 "return " 会触发自动分号插入，必须加括号。
  const factory = new Function("$steps", "return (" + script + ")");
  return JSON.parse(factory([]));
}

function topMost(elements, x, y) {
  const hits = elements.filter((element) => y >= element.rect.top && y <= element.rect.bottom && x >= element.rect.left && x <= element.rect.right);
  if (!hits.length) return null;
  // 真实浏览器里 fixed/sticky 元素绘制在普通流内容之上，命中测试必须先返回它们，
  // 否则桩 DOM 会把「被底栏盖住的按钮」误判成可点击。
  const overlay = hits.filter((element) => element.computed.position === "fixed" || element.computed.position === "sticky");
  return overlay.length ? overlay[overlay.length - 1] : hits[hits.length - 1];
}

const script = extractAuditScript();
let failed = 0;

function check(name, condition, detail) {
  if (condition) {
    console.log("通过  " + name);
  } else {
    failed++;
    console.log("失败  " + name + "  " + detail);
  }
}

// ---------- 布局 ----------

// 场景一：固定底栏占掉下半屏并盖住按钮——正是用户反馈的「底栏垮了半个屏幕挡住可点击按钮」。
const bar = makeElement("DIV", "tabbar", { left: 0, top: 400, width: 390, height: 380, right: 390, bottom: 780 }, { position: "fixed" });
const covered = makeElement("BUTTON", "submit", { left: 20, top: 430, width: 120, height: 48, right: 140, bottom: 478 }, {}, "提交");
const broken = runAudit(script, [bar, covered], topMost);
check("布局·遮挡：识别出被底栏盖住的按钮", broken.layout.blocked.length === 1 && broken.layout.blocked[0].by === "div#tabbar", JSON.stringify(broken.layout.blocked));
// 模型改不动布局的根因是它看不到几何：只给「谁挡住谁」它只能猜坐标，于是往页面里塞调试代码。
// 下面几条断言把「必须带几何」固定下来，防止以后又被精简掉。
check("布局·遮挡：带上被挡元素的 rect", JSON.stringify(broken.layout.blocked[0].rect) === "[20,430,120,48]", JSON.stringify(broken.layout.blocked[0].rect));
check("布局·遮挡：带上遮挡者的 rect", JSON.stringify(broken.layout.blocked[0].byRect) === "[0,400,390,380]", JSON.stringify(broken.layout.blocked[0].byRect));
check("布局·遮挡：带上遮挡者的定位与层级", broken.layout.blocked[0].byPosition === "fixed" && broken.layout.blocked[0].byZ === "auto", JSON.stringify(broken.layout.blocked[0]));
check("布局·视口：带上视口尺寸", JSON.stringify(broken.layout.viewport) === "[390,780]", JSON.stringify(broken.layout.viewport));
check("布局·底栏：识别出占据下半屏的固定底栏", broken.layout.fixedCoverage > 0.42, "coverage=" + broken.layout.fixedCoverage);
check("布局·底栏：固定底栏说明里带 rect 与层级", /div#tabbar fixed z=/.test(broken.layout.fixedTags[0] || ""), JSON.stringify(broken.layout.fixedTags));
check("布局·判定：坏布局必须不通过", broken.passed === false, "passed=" + broken.passed);

// 场景二：正常底栏与可点控件，不应误报。
const tabbar = makeElement("DIV", "tabbar", { left: 0, top: 712, width: 390, height: 68, right: 390, bottom: 780 }, { position: "fixed" });
const button = makeElement("BUTTON", "add", { left: 20, top: 200, width: 120, height: 48, right: 140, bottom: 248 }, {}, "添加");
const input = makeElement("INPUT", "amount", { left: 20, top: 120, width: 350, height: 46, right: 370, bottom: 166 }, {}, "");
const healthy = runAudit(script, [tabbar, button, input], topMost);
check("布局·正常：无误报遮挡", healthy.layout.blocked.length === 0, JSON.stringify(healthy.layout.blocked));
check("布局·正常：常规底栏不触发超限", healthy.layout.fixedCoverage <= 0.42, "coverage=" + healthy.layout.fixedCoverage);

// 场景三：超出屏幕宽度的元素。
const wide = makeElement("DIV", "wide", { left: 0, top: 100, width: 520, height: 60, right: 520, bottom: 160 }, {}, "");
const overflow = runAudit(script, [wide], topMost);
check("布局·超宽：识别超出屏幕宽度的元素", overflow.layout.overflowCount === 1, "overflowCount=" + overflow.layout.overflowCount);

// ---------- 设计 ----------

// 场景四：默认模板感——五个同款圆角卡片、纯黑阴影、纯色背景，整页没有任何别的结构想法。
const cardStyle = { borderRadius: "16px", boxShadow: "0 10px 26px rgba(0,0,0,0.10)", backgroundColor: "rgb(255, 255, 255)" };
const genericCards = [1, 2, 3, 4, 5].map((n) => makeElement("DIV", "card" + n, { left: 16, top: 80 * n, width: 358, height: 70, right: 374, bottom: 80 * n + 70 }, cardStyle, ""));
const generic = runAudit(script, genericCards, topMost);
check("设计·模板感：单一圆角被识别", generic.design.radii.length === 1, JSON.stringify(generic.design.radii));
check("设计·模板感：纯黑阴影被识别为未着色", generic.design.shadowCount === 5 && generic.design.tintedShadows === 0, JSON.stringify(generic.design));
check("设计·模板感：零装饰被识别", generic.design.decorations === 0, "decorations=" + generic.design.decorations);
check("设计·模板感：卡片墙被识别", generic.designIssues.some((issue) => issue.indexOf("卡片叠卡片") >= 0), JSON.stringify(generic.designIssues));
check("设计·模板感：判定为默认模板且不通过", generic.genericTemplate === true && generic.passed === false, "genericTemplate=" + generic.genericTemplate);

// 场景四点五：**克制的**设计不算模板。这条曾经写反过——旧判据是「只有一种圆角 + 零装饰 + 无渐变
// 就判默认模板失败」，等于逼模型到处加圆角、加伪元素、加渐变，正好是 AI 味的配方。
const restrained = [
  makeElement("DIV", "rule", { left: 16, top: 100, width: 358, height: 60, right: 374, bottom: 160 },
    { borderRadius: "2px", backgroundColor: "rgb(255, 255, 255)", borderTopWidth: "1px", fontSize: "27px" }, "27"),
  makeElement("SPAN", "tag", { left: 16, top: 170, width: 120, height: 20, right: 136, bottom: 190 },
    { borderRadius: "2px", fontSize: "11px" }, "11")
];
const clean = runAudit(script, restrained, topMost);
check("设计·克制：一种圆角 + 零装饰不判模板",
  clean.genericTemplate === false, "radii=" + JSON.stringify(clean.design.radii) + " genericTemplate=" + clean.genericTemplate);

// 场景四点六：形状漂移——四五种不同的圆角值，说明没有形状系统。
const drifted = [1, 2, 3, 4].map((n) => makeElement("DIV", "r" + n, { left: 16, top: 60 * n, width: 300, height: 40, right: 316, bottom: 60 * n + 40 },
  { borderRadius: n * 5 + "px" }, "圆角" + n));
const drift = runAudit(script, drifted, topMost);
check("设计·形状漂移：四种圆角被判为没有系统",
  drift.designIssues.some((issue) => issue.indexOf("形状漂移") >= 0) && drift.genericTemplate === true,
  JSON.stringify(drift.designIssues));

// 场景五：有工艺的页面——多种圆角、同源着色阴影、装饰伪元素、渐变纹理，不应误报。
const crafted = [
  makeElement("DIV", "paper", { left: 0, top: 0, width: 390, height: 780, right: 390, bottom: 780 },
    { backgroundImage: "radial-gradient(circle, rgba(186,130,86,0.14) 0 1px, transparent 2px), linear-gradient(108deg, rgba(255,255,255,0.45), transparent 42%)" },
    "", { "::before": decoration({}) }),
  makeElement("DIV", "hero", { left: 18, top: 40, width: 354, height: 162, right: 372, bottom: 202 },
    { borderRadius: "2px", boxShadow: "0 4px 8px rgba(117,50,45,0.13)", backgroundColor: "rgba(255,250,239,0.76)", fontFamily: '"Noto Serif SC", serif', letterSpacing: "3px" },
    "", { "::after": decoration({ width: "112px", height: "112px", borderTopWidth: "1px" }) }),
  makeElement("SPAN", "badge", { left: 300, top: 150, width: 34, height: 20, right: 334, bottom: 170 },
    { borderRadius: "5px 2px 5px 2px", boxShadow: "0 2px 0 rgba(117,50,45,0.16), inset 0 1px 0 rgba(255,255,255,0.9)", backgroundColor: "rgb(255,248,234)" }, "5")
];
const polished = runAudit(script, crafted, topMost);
check("设计·工艺：多种圆角被识别", polished.design.radii.length >= 2, JSON.stringify(polished.design.radii));
check("设计·工艺：同源着色阴影被识别", polished.design.tintedShadows > 0, "tinted=" + polished.design.tintedShadows);
check("设计·工艺：装饰伪元素被识别", polished.design.decorations >= 2, "decorations=" + polished.design.decorations);
check("设计·工艺：渐变纹理被识别", polished.design.gradients >= 1, "gradients=" + polished.design.gradients);
check("设计·工艺：不被误判为默认模板", polished.genericTemplate === false, JSON.stringify(polished.designIssues));
check("设计·工艺：字距与衬线被识别", polished.design.letterSpacing >= 1 && polished.design.fonts.length >= 2, JSON.stringify(polished.design));

// ---------- 可读性 / 交互 / 结构节奏 ----------

// 场景六：浅底浅字——用户反馈的「纯白背景上加淡黄文字，完全看不清」。
const washed = makeElement("P", "hint", { left: 16, top: 120, width: 300, height: 40, right: 316, bottom: 160 },
  { backgroundColor: "rgb(255, 255, 255)", color: "rgb(247, 234, 164)" }, "淡黄文字压在纯白底上");
const washedReport = runAudit(script, [washed], topMost);
check("可读性·浅底浅字：对比度被判为不足",
  washedReport.contrast.issues.length === 1 && washedReport.contrast.issues[0].ratio < 1.5,
  JSON.stringify(washedReport.contrast));
check("可读性·浅底浅字：说明里带上前景色与实际底色",
  /1\.\d+:1/.test(washedReport.layoutProblems.join(" ")) && washedReport.layoutProblems.join(" ").indexOf("255,255,255") >= 0,
  JSON.stringify(washedReport.layoutProblems));
check("可读性·浅底浅字：判定为不通过", washedReport.passed === false, "passed=" + washedReport.passed);

// 场景七：深底浅字不应误报。文字压在自带背景的按钮上（元素自身底色也要参与合成）。
const onButton = makeElement("BUTTON", "submit2", { left: 16, top: 200, width: 160, height: 48, right: 176, bottom: 248 },
  { backgroundColor: "rgb(184, 54, 42)", color: "rgb(255, 255, 255)" }, "记 1 抽");
const darkReport = runAudit(script, [onButton], topMost);
check("可读性·深底浅字：不误报（元素自身背景参与合成）",
  darkReport.contrast.issues.length === 0 && darkReport.contrast.checked === 1,
  JSON.stringify(darkReport.contrast));

// 场景八：三个区块全是单列纵向堆叠——用户反馈的「排版死板，全是流水账」。
const stacked = [1, 2, 3].map((n) => {
  const section = makeElement("SECTION", "sec" + n, { left: 16, top: 100 * n, width: 358, height: 80, right: 374, bottom: 100 * n + 80 },
    { backgroundImage: "linear-gradient(180deg, rgba(40,30,20,0.9), rgba(20,16,12,0.9))" }, "");
  section.classes = ["block"];
  return section;
});
const stackedReport = runAudit(script, stacked, topMost);
check("结构·流水账：单列纵向堆叠被识别",
  stackedReport.layoutProblems.some((problem) => problem.indexOf("单列纵向堆叠") >= 0),
  JSON.stringify(stackedReport.layoutProblems));

// 场景九：有网格对比的页面不应被判流水账。
const gridded = stacked.map((element) => Object.assign({}, element));
gridded[1] = makeElement("DIV", "metrics", { left: 16, top: 200, width: 358, height: 90, right: 374, bottom: 290 },
  { display: "grid", gridTemplateColumns: "179px 179px" }, "");
check("结构·网格：有并列结构时不报流水账",
  runAudit(script, gridded, topMost).layoutProblems.every((problem) => problem.indexOf("单列纵向堆叠") < 0),
  JSON.stringify(runAudit(script, gridded, topMost).layoutProblems));

// 场景十：父级 opacity:0 —— 整块内容其实看不见（离屏渲染里入场动画停在第一帧就是这个形态）。
const ghostView = makeElement("DIV", "view", { left: 0, top: 100, width: 390, height: 400, right: 390, bottom: 500 }, { opacity: "0" }, "");
const ghostButton = makeElement("BUTTON", "go", { left: 20, top: 160, width: 160, height: 48, right: 180, bottom: 208 }, {}, "开始日课");
ghostButton.parentElement = ghostView;
const ghostReport = runAudit(script, [ghostView, ghostButton], topMost);
check("可见性·祖先透明：识别出被祖先藏起来的可点击元素",
  ghostReport.layoutProblems.some((problem) => problem.indexOf("其实看不见") >= 0),
  JSON.stringify(ghostReport.layoutProblems));
check("可见性·祖先透明：判定为不通过", ghostReport.passed === false, "passed=" + ghostReport.passed);

// 场景十一：元素自己被 display:none 隐藏（关闭的弹层）属于正常写法，不应误报。
const closedDialog = makeElement("DIV", "dlg", { left: 0, top: 0, width: 0, height: 0, right: 0, bottom: 0 }, { display: "none" }, "");
const hiddenButton = makeElement("BUTTON", "ok", { left: 0, top: 0, width: 0, height: 0, right: 0, bottom: 0 }, {}, "确定");
hiddenButton.parentElement = closedDialog;
check("可见性·display:none：关闭的弹层不误报",
  runAudit(script, [closedDialog, hiddenButton], topMost).layoutProblems.every((problem) => problem.indexOf("其实看不见") < 0),
  JSON.stringify(runAudit(script, [closedDialog, hiddenButton], topMost).layoutProblems));

console.log("");
console.log(failed === 0 ? "布局与设计审计检查全部通过" : "审计检查失败 " + failed + " 项");
process.exit(failed === 0 ? 0 : 1);
