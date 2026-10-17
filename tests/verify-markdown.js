/**
 * Markdown 渲染单元测试
 *
 * 为什么要单独测这个：
 *   1. 大模型返回的正文是 Markdown，渲染错了用户会看到一堆 ** 和 ` `
 *   2. 渲染结果是塞进 v-html 的 —— 如果没转义，模型输出里的 <script> 会真的执行。
 *      这是 XSS 风险，必须用断言把"先转义、后替换"的顺序钉死。
 *
 * 用法：node verify-markdown.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

let pass = 0;
let fail = 0;
const failures = [];

function assert(name, cond, detail) {
  if (cond) {
    pass++;
    console.log(`  PASS  ${name}${detail ? '  (' + detail + ')' : ''}`);
  } else {
    fail++;
    failures.push(name + ' — ' + (detail || '断言失败'));
    console.log(`  FAIL  ${name}${detail ? '  (' + detail + ')' : ''}`);
  }
}

// 在一个假的 window 上执行 markdown.js（它挂到 window.AIKB.md）
const code = fs.readFileSync(path.join(__dirname, '..', 'frontend', 'js', 'markdown.js'), 'utf8');
const sandbox = { window: {} };
vm.createContext(sandbox);
vm.runInContext(code, sandbox);
const md = sandbox.window.AIKB.md;

console.log('='.repeat(66));
console.log('Markdown 渲染单元测试');
console.log('='.repeat(66));

console.log('\n[1] 基础语法');
assert('加粗 **x**', md.render('**结论**：可以').includes('<strong>结论</strong>'));
assert('行内代码 `x`', md.render('用 `MATCH` 检索').includes('<code class="md-inline">MATCH</code>'));
assert('无序列表', md.render('- 第一点\n- 第二点').includes('<li>第一点</li>'), '生成 <ul><li>');
assert('有序列表', md.render('1. 甲\n2. 乙').includes('<li>甲</li>'), '生成 <ol><li>');
assert('标题', md.render('## 小标题').includes('<h4 class="md-h">小标题</h4>'),
  '## → h4（整体降两级，避免压过页面标题）');
assert('代码块', md.render('```\nSELECT 1\n```').includes('<pre class="md-pre"><code>'));
assert('普通段落', md.render('一句话').includes('<p class="md-p">一句话</p>'));

console.log('\n[2] 安全：必须转义 HTML（模型输出不可信）');
const xss1 = md.render('<script>alert(1)</script>');
assert('<script> 被转义', xss1.includes('&lt;script&gt;') && !xss1.includes('<script>'),
  xss1.slice(0, 60));
const xss2 = md.render('<img src=x onerror=alert(1)>');
assert('<img onerror> 被转义', !xss2.includes('<img'), xss2.slice(0, 60));
const xss3 = md.render('**<b>bold</b>**');
assert('加粗里的标签也被转义', !xss3.includes('<b>') && xss3.includes('&lt;b&gt;'), xss3.slice(0, 80));
const quote = md.render('他说："你好"');
assert('引号被转义', quote.includes('&quot;'), quote.slice(0, 60));

console.log('\n[3] 边界情况');
assert('空字符串返回空', md.render('') === '');
assert('null 返回空', md.render(null) === '');
assert('纯空行不崩', typeof md.render('\n\n\n') === 'string');
assert('未闭合代码块也能收尾', md.render('```\nabc').includes('</code></pre>'));
assert('列表后接段落能正确闭合', md.render('- a\n\n普通段落').includes('</ul><p class="md-p">普通段落</p>'));

console.log('\n[4] 真实模型回答的样子');
const real = md.render('死锁的四个必要条件是：**互斥、占有并等待、不可剥夺、循环等待**（资料1、资料2）。\n\n- **互斥**：资源不能被多个进程同时占用\n- **循环等待**：存在进程资源的环形链');
assert('真实回答能渲染出加粗', real.includes('<strong>互斥、占有并等待'));
assert('真实回答能渲染出列表', real.includes('<li>'));
assert('真实回答没有残留星号', !/\*\*/.test(real), '渲染后不应再出现 **');

console.log('\n' + '='.repeat(66));
console.log(`结果: ${pass} 项通过, ${fail} 项失败`);
if (fail > 0) {
  console.log('\n失败项:');
  failures.forEach((f) => console.log('  - ' + f));
  process.exit(1);
}
console.log('全部通过');
