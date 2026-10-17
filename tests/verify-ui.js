/**
 * 前端 UI 冒烟测试（jsdom）
 *
 * 目的：证明页面不是"HTTP 200 但白屏"。
 * 做法：用 jsdom 真加载 http://localhost:8081/，让浏览器脚本（vue / element-plus / 业务代码）
 *       全部执行一遍，然后检查挂载后的 DOM，并模拟一次点击。
 *
 * 能测出：
 *   · 脚本 404、全局变量写错、模板语法错误、挂载失败
 *   · 接口是否真的调通（科目树渲染出来说明 GET /api/subjects 成功了）
 *   · 点击科目是否真的切换了当前科目
 * 测不出：
 *   · 真实视觉样式与布局（jsdom 不渲染），这部分靠肉眼
 *
 * 用法：node verify-ui.js [http://host:port]
 */
const { JSDOM } = require('jsdom');

const BASE = process.argv[2] || 'http://localhost:8081/';

let pass = 0;
let fail = 0;
const failures = [];
const jsErrors = [];

function assert(name, cond, detail) {
  if (cond) {
    pass++;
    console.log(`  PASS  ${name}${detail ? '  (' + detail + ')' : ''}`);
  } else {
    fail++;
    failures.push(name + (detail ? ' — ' + detail : ''));
    console.log(`  FAIL  ${name}${detail ? '  (' + detail + ')' : ''}`);
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  console.log('='.repeat(66));
  console.log('前端 UI 冒烟测试');
  console.log('目标: ' + BASE);
  console.log('='.repeat(66));

  const dom = await JSDOM.fromURL(BASE, {
    runScripts: 'dangerously',
    resources: 'usable',
    pretendToBeVisual: true,
    beforeParse(window) {
      // Element Plus 依赖这些浏览器 API，jsdom 没有，先补齐
      window.matchMedia =
        window.matchMedia ||
        function () {
          return {
            matches: false,
            addListener() {},
            removeListener() {},
            addEventListener() {},
            removeEventListener() {}
          };
        };
      window.ResizeObserver =
        window.ResizeObserver ||
        class {
          observe() {}
          unobserve() {}
          disconnect() {}
        };
      window.requestAnimationFrame = window.requestAnimationFrame || ((cb) => setTimeout(cb, 0));
      window.cancelAnimationFrame = window.cancelAnimationFrame || ((id) => clearTimeout(id));
      window.scrollTo = () => {};

      // jsdom 没有 fetch / EventSource（真实浏览器有）。
      // 用 Node 的 fetch 补上并把相对路径补全，页面里的接口调用才能真的打到后端。
      window.fetch = (input, init) => {
        const url =
          typeof input === 'string' && input.startsWith('/') ? 'http://localhost:8081' + input : input;
        return fetch(url, init);
      };
      window.EventSource = class {
        constructor() {
          setTimeout(() => this.onerror && this.onerror(new Error('jsdom 不模拟 SSE')), 0);
        }
        close() {}
      };

      window.addEventListener('error', (e) => jsErrors.push('error: ' + (e.message || e.type)));
      window.addEventListener('unhandledrejection', (e) =>
        jsErrors.push('unhandledrejection: ' + e.reason)
      );
    }
  });

  // 等外部脚本加载 + Vue 挂载 + 接口返回
  await sleep(7000);

  const { document } = dom.window;

  // ---------- 1. 基本挂载 ----------
  console.log('\n[1] 页面挂载');
  assert('渲染出根布局 .layout', !!document.querySelector('.layout'));
  assert('渲染出左侧栏 .aside', !!document.querySelector('.aside'));
  assert('渲染出顶部栏 .topbar', !!document.querySelector('.topbar'));
  assert('渲染出品牌标题', /考研复习助手/.test((document.querySelector('.brand-title') || {}).textContent || ''));
  assert('Element Plus 生效（有 el-button）', document.querySelectorAll('.el-button').length > 0,
    document.querySelectorAll('.el-button').length + ' 个按钮');

  // ---------- 2. 科目树 ----------
  console.log('\n[2] 科目树');
  const treeNodes = document.querySelectorAll('.el-tree-node');
  assert('科目树已渲染', treeNodes.length > 0, treeNodes.length + ' 个节点');

  const treeText = (document.querySelector('.tree-wrap') || {}).textContent || '';
  assert('包含公共课', treeText.includes('公共课'));
  assert('包含专业课', treeText.includes('专业课'));
  assert('包含英语一', treeText.includes('英语一'));
  assert('包含线性代数', treeText.includes('线性代数'));
  assert('包含政治', treeText.includes('政治'));

  // ---------- 3. 默认选中与状态 ----------
  console.log('\n[3] 默认状态');
  const currentSubject = (document.querySelector('.current-subject') || {}).textContent || '';
  assert('自动选中了一个科目', currentSubject.trim().length > 0, currentSubject.trim());
  const topbarText = (document.querySelector('.topbar') || {}).textContent || '';
  assert('顶部显示了模型状态', /DeepSeek|模拟模式/.test(topbarText), topbarText.trim().slice(0, 40));
  assert('有「资料管理」按钮', /资料管理/.test(topbarText));
  assert('有输入框', !!document.querySelector('.composer textarea'));
  assert('有发送按钮', /发送/.test((document.querySelector('.composer') || {}).textContent || ''));

  // ---------- 4. 点击切换科目 ----------
  console.log('\n[4] 交互：点击线性代数');
  const nodes = Array.from(document.querySelectorAll('.el-tree-node__content'));
  const target = nodes.find((n) => (n.textContent || '').includes('线性代数'));
  if (!target) {
    assert('找到「线性代数」节点', false);
  } else {
    assert('找到「线性代数」节点', true);
    target.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    await sleep(1200);
    const after = (document.querySelector('.current-subject') || {}).textContent || '';
    assert('当前科目切换为线性代数', after.includes('线性代数'), after.trim());
    const emptyTitle = (document.querySelector('.empty-title') || {}).textContent || '';
    assert('空状态提示同步更新', emptyTitle.includes('线性代数'), emptyTitle.trim());
  }

  // ---------- 5. 会话区 ----------
  console.log('\n[5] 会话区');
  const sessionWrap = document.querySelector('.session-wrap');
  assert('会话区已渲染', !!sessionWrap);
  assert('会话区有「历史对话」标题', /历史对话/.test((sessionWrap || {}).textContent || ''));

  // ---------- 6. 无 JS 报错 ----------
  console.log('\n[6] 运行时错误');
  assert('页面无 JS 报错', jsErrors.length === 0, jsErrors.slice(0, 2).join(' | ') || '无');

  // ---------- 汇总 ----------
  console.log('\n' + '='.repeat(66));
  console.log(`结果: ${pass} 项通过, ${fail} 项失败`);
  if (fail > 0) {
    console.log('\n失败项:');
    failures.forEach((f) => console.log('  - ' + f));
    process.exit(1);
  }
  console.log('前端挂载正常、交互正常、无报错');
  dom.window.close();
}

main().catch((e) => {
  console.error('冒烟测试异常: ' + e.message);
  process.exit(1);
});
