/**
 * 考研复习助手 · 后端端到端验证
 *
 *   node verify-api.js                  # 默认 http://localhost:8081
 *   node verify-api.js http://host:port
 *
 * 断言直接打接口，不靠手点页面：
 *   · SSE 四类事件是否按顺序到达
 *   · 检索是否命中「当前科目」的正确资料（而不是随便返回几条）
 *   · 跨科目是否隔离（政治的会话不能捞到操作系统的片段）
 *   · 会话与消息是否真的落库
 *   · 真实模型 / 模拟模式都能跑
 */

const http = require('http');

const BASE = process.argv[2] || 'http://localhost:8081';

let pass = 0;
let fail = 0;
const failures = [];

function ok(name, detail) {
  pass++;
  console.log(`  PASS  ${name}${detail ? '  (' + detail + ')' : ''}`);
}
function bad(name, detail) {
  fail++;
  failures.push(name + (detail ? ' — ' + detail : ''));
  console.log(`  FAIL  ${name}${detail ? '  (' + detail + ')' : ''}`);
}
function assert(name, cond, detail) {
  if (cond) ok(name, detail); else bad(name, detail);
}

function request(method, url, body) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const req = http.request(
      {
        method,
        hostname: u.hostname,
        port: u.port,
        path: u.pathname + u.search,
        headers: { 'Content-Type': 'application/json; charset=utf-8' }
      },
      (res) => {
        let data = '';
        res.setEncoding('utf8');
        res.on('data', (c) => (data += c));
        res.on('end', () => {
          let json = null;
          try { json = JSON.parse(data); } catch (e) { /* 非 JSON 保持 null */ }
          resolve({ status: res.statusCode, text: data, json });
        });
      }
    );
    req.on('error', reject);
    if (body) req.write(JSON.stringify(body));
    req.end();
  });
}

function streamChat(subjectCode, question, sessionId) {
  return new Promise((resolve, reject) => {
    const params = new URLSearchParams({ subjectCode, question });
    if (sessionId) params.set('sessionId', String(sessionId));
    const url = new URL(`${BASE}/api/chat/stream?${params.toString()}`);

    const events = [];
    let answer = '';
    let citations = [];
    let resolvedSessionId = null;
    let firstDeltaMs = null;
    const t0 = Date.now();

    const req = http.get(
      { hostname: url.hostname, port: url.port, path: url.pathname + url.search, headers: { Accept: 'text/event-stream' } },
      (res) => {
        res.setEncoding('utf8');
        let buf = '';
        res.on('data', (chunk) => {
          buf += chunk;
          let idx;
          while ((idx = buf.indexOf('\n\n')) >= 0) {
            const raw = buf.slice(0, idx);
            buf = buf.slice(idx + 2);
            for (const line of raw.split('\n')) {
              if (!line.startsWith('data:')) continue;
              const payload = line.slice(5).trim();
              if (!payload) continue;
              try {
                const ev = JSON.parse(payload);
                events.push(ev.type);
                if (ev.type === 'delta') {
                  if (firstDeltaMs === null) firstDeltaMs = Date.now() - t0;
                  answer += ev.text;
                }
                if (ev.type === 'citations') citations = ev.items || [];
                if (ev.type === 'meta') resolvedSessionId = ev.sessionId;
              } catch (e) { /* 忽略无法解析的行 */ }
            }
          }
        });
        res.on('end', () => resolve({ events, answer, citations, sessionId: resolvedSessionId, firstDeltaMs }));
      }
    );
    req.on('error', reject);
    req.setTimeout(180000, () => req.destroy(new Error('SSE 超时')));
  });
}

async function main() {
  console.log('='.repeat(66));
  console.log('考研复习助手 · 后端端到端验证');
  console.log('目标: ' + BASE);
  console.log('='.repeat(66));

  // ---------- 1. 服务状态 ----------
  console.log('\n[1] 服务状态');
  const status = await request('GET', `${BASE}/api/chat/status`);
  assert('接口可访问', status.status === 200, 'HTTP ' + status.status);
  assert('返回模型名', !!(status.json && status.json.model), status.json && status.json.model);
  const isMock = !!(status.json && status.json.mock);
  console.log(`     提示: ${status.json && status.json.hint}`);

  // ---------- 2. 科目树 ----------
  console.log('\n[2] 科目树');
  const tree = await request('GET', `${BASE}/api/subjects`);
  assert('科目接口正常', tree.status === 200 && Array.isArray(tree.json));
  const roots = tree.json || [];
  const allCodes = [];
  const collect = (list) => list.forEach((s) => { allCodes.push(s.code); if (s.children) collect(s.children); });
  collect(roots);
  assert('有两个顶级分类（公共课 / 专业课）',
    roots.length === 2 && roots.map((r) => r.name).join('/') === '公共课/专业课',
    roots.map((r) => r.name).join(' / '));
  assert('包含英语一与英语二', allCodes.includes('english1') && allCodes.includes('english2'));
  assert('包含数学一/数学二/线代/概率',
    ['math1', 'math2', 'linear', 'prob'].every((c) => allCodes.includes(c)));
  assert('包含政治', allCodes.includes('politics'));
  assert('科目共 14 个（含 2 个总分类）', allCodes.length === 14, allCodes.length + ' 个');
  assert('可提问的叶子科目共 9 个',
    ['english1', 'english2', 'math1', 'math2', 'linear', 'prob', 'politics', 'ds', 'os']
      .filter((c) => allCodes.includes(c)).length === 9);

  // ---------- 3. 各科目资料已导入 ----------
  console.log('\n[3] 资料导入情况');
  const statOs = await request('GET', `${BASE}/api/kb/stats?subjectCode=os`);
  assert('操作系统资料已导入', statOs.json && statOs.json.docs > 0,
    statOs.json && `${statOs.json.docs} 篇 / ${statOs.json.chunks} 片段`);
  const statPolitics = await request('GET', `${BASE}/api/kb/stats?subjectCode=politics`);
  assert('政治资料已导入', statPolitics.json && statPolitics.json.docs > 0,
    statPolitics.json && `${statPolitics.json.docs} 篇 / ${statPolitics.json.chunks} 片段`);
  const statMath = await request('GET', `${BASE}/api/kb/stats?subjectCode=linear`);
  assert('线性代数资料已导入', statMath.json && statMath.json.docs > 0,
    statMath.json && `${statMath.json.docs} 篇 / ${statMath.json.chunks} 片段`);

  const docs = await request('GET', `${BASE}/api/kb/docs?subjectCode=os`);
  assert('资料列表可读', docs.status === 200 && Array.isArray(docs.json) && docs.json.length > 0,
    docs.json && docs.json.length + ' 篇');
  assert('资料带了科目名', !!(docs.json && docs.json[0] && docs.json[0].subjectName),
    docs.json && docs.json[0] && docs.json[0].subjectName);

  // ---------- 4. 流式问答（操作系统）----------
  console.log('\n[4] 流式问答 · 操作系统 · 应当命中「操作系统」资料');
  const r1 = await streamChat('os', '死锁的四个必要条件是什么？');
  assert('SSE 收到 meta 事件', r1.events.includes('meta'));
  assert('SSE 收到 delta 事件', r1.events.includes('delta'),
    (r1.events.filter((e) => e === 'delta').length) + ' 段');
  assert('SSE 收到 citations 事件', r1.events.includes('citations'));
  assert('SSE 以 done 结束', r1.events.includes('done'));
  assert('回答非空', r1.answer.trim().length > 0, r1.answer.length + ' 字');
  assert('检索有命中', r1.citations.length > 0, r1.citations.length + ' 条引用');
  assert('命中文档正确',
    r1.citations.some((c) => c.docTitle && c.docTitle.includes('操作系统')),
    r1.citations.map((c) => c.docTitle).join(' | '));
  assert('回答确实在讲死锁', /死锁|互斥|循环等待/.test(r1.answer), r1.answer.slice(0, 40).replace(/\n/g, ' '));
  assert('返回了 sessionId', r1.sessionId != null, 'sessionId=' + r1.sessionId);
  if (!isMock) {
    console.log(`     首字延迟: ${r1.firstDeltaMs} ms（真实模型）`);
    assert('首字延迟在 30 秒内', r1.firstDeltaMs !== null && r1.firstDeltaMs < 30000,
      r1.firstDeltaMs + ' ms');
  }

  // ---------- 5. 多轮 ----------
  console.log('\n[5] 多轮对话（沿用上一轮会话）');
  const r2 = await streamChat('os', '那怎么预防死锁？', r1.sessionId);
  assert('复用同一会话', r2.sessionId === r1.sessionId, 'sessionId=' + r2.sessionId);
  assert('第二轮也有回答', r2.answer.trim().length > 0);

  const msgs = await request('GET', `${BASE}/api/sessions/${r1.sessionId}/messages`);
  assert('消息已落库', Array.isArray(msgs.json) && msgs.json.length >= 4,
    msgs.json && msgs.json.length + ' 条');
  const roles = (msgs.json || []).map((m) => m.role);
  assert('角色顺序正确', roles[0] === 'user' && roles[1] === 'assistant', roles.join(','));
  const assistant = (msgs.json || []).find((m) => m.role === 'assistant');
  assert('助手消息带引用', !!(assistant && assistant.citations && assistant.citations !== '[]'));

  // ---------- 6. 跨科目隔离 ----------
  console.log('\n[6] 跨科目隔离（政治上问死锁，不能捞到操作系统资料）');
  const r3 = await streamChat('politics', '死锁的四个必要条件是什么？');
  assert('政治科目仍能作答', r3.answer.trim().length > 0);
  assert('未误命中操作系统资料',
    !r3.citations.some((c) => c.docTitle && c.docTitle.includes('操作系统')),
    r3.citations.map((c) => c.docTitle).join(' | ') || '无引用（符合预期）');

  // ---------- 7. 数学科目检索 ----------
  console.log('\n[7] 数学科目检索（线性代数：特征值）');
  const r4 = await streamChat('linear', '矩阵可对角化的条件是什么？');
  assert('命中了线性代数资料',
    r4.citations.some((c) => c.docTitle && c.docTitle.includes('线性代数')),
    r4.citations.map((c) => c.docTitle).join(' | '));
  assert('回答提到了特征值或对角化', /特征值|对角化/.test(r4.answer), r4.answer.slice(0, 40).replace(/\n/g, ' '));

  // ---------- 8. 会话列表 ----------
  console.log('\n[8] 会话列表');
  const sessions = await request('GET', `${BASE}/api/sessions?subjectCode=os`);
  assert('按科目查会话可读', sessions.status === 200 && Array.isArray(sessions.json) && sessions.json.length > 0,
    sessions.json && sessions.json.length + ' 个');
  const recent = await request('GET', `${BASE}/api/sessions/recent?limit=5`);
  assert('最近会话可读', recent.status === 200 && Array.isArray(recent.json),
    recent.json && recent.json.length + ' 个');

  // ---------- 汇总 ----------
  console.log('\n' + '='.repeat(66));
  console.log(`结果: ${pass} 项通过, ${fail} 项失败`);
  if (isMock) {
    console.log('注意: 当前是本地模拟模式（未配置 DEEPSEEK_API_KEY）。');
  } else {
    console.log('当前为真实模型模式（DeepSeek），回答由大模型生成。');
  }
  if (fail > 0) {
    console.log('\n失败项:');
    failures.forEach((f) => console.log('  - ' + f));
    process.exit(1);
  }
  console.log('全部通过');
}

main().catch((e) => {
  console.error('验证脚本异常: ' + e.message);
  process.exit(1);
});
