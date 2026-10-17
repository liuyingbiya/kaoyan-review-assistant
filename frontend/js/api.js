/**
 * 后端接口封装
 *
 * 约定：
 *   · 前端与后端同源部署（后端托管静态资源），所以 BASE 用相对路径 /api
 *   · 非 SSE 接口出错时后端返回 { message }，这里统一抛出 Error(message)
 *   · 所有请求都走这里，改接口地址只改一个地方
 */
window.AIKB = window.AIKB || {};

(function (ns) {
  const BASE = '/api';

  async function request(path, options) {
    const res = await fetch(BASE + path, {
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      ...options
    });
    const text = await res.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch (e) {
      data = null;
    }
    if (!res.ok) {
      throw new Error((data && data.message) || '请求失败（HTTP ' + res.status + '）');
    }
    return data;
  }

  ns.api = {
    // ---------- 科目 ----------
    subjectTree: () => request('/subjects'),
    subjectFlat: () => request('/subjects/flat'),

    // ---------- 模型状态 ----------
    status: () => request('/chat/status'),

    // ---------- 资料 ----------
    docs: (subjectCode) => request('/kb/docs?subjectCode=' + encodeURIComponent(subjectCode)),
    allDocs: () => request('/kb/docs/all'),
    stats: (subjectCode) => request('/kb/stats?subjectCode=' + encodeURIComponent(subjectCode)),
    importText: (payload) =>
      request('/kb/import', { method: 'POST', body: JSON.stringify(payload) }),

    /**
     * 上传 .txt / .md 文件。
     * 注意：这里不能用 request() —— 它会给请求加 Content-Type: application/json，
     * 而 multipart 的 Content-Type 必须由浏览器自己生成（里面要带 boundary），
     * 手动设置会导致后端解析不出文件。
     */
    uploadFile: (subjectCode, file, title) => {
      const fd = new FormData();
      fd.append('file', file);
      let url = BASE + '/kb/upload?subjectCode=' + encodeURIComponent(subjectCode);
      if (title) {
        url += '&title=' + encodeURIComponent(title);
      }
      return fetch(url, { method: 'POST', body: fd }).then(function (res) {
        return res.text().then(function (text) {
          let data = null;
          try {
            data = text ? JSON.parse(text) : null;
          } catch (e) {
            data = null;
          }
          if (!res.ok) {
            throw new Error((data && data.message) || '上传失败（HTTP ' + res.status + '）');
          }
          return data;
        });
      });
    },
    deleteDoc: (id) => request('/kb/docs/' + id, { method: 'DELETE' }),

    // ---------- 会话 ----------
    sessions: (subjectCode) => request('/sessions?subjectCode=' + encodeURIComponent(subjectCode)),
    recentSessions: (limit) => request('/sessions/recent?limit=' + (limit || 5)),
    messages: (sessionId) => request('/sessions/' + sessionId + '/messages'),
    renameSession: (sessionId, title) =>
      request('/sessions/' + sessionId + '?title=' + encodeURIComponent(title), { method: 'PUT' }),
    deleteSession: (sessionId) => request('/sessions/' + sessionId, { method: 'DELETE' })
  };

  /**
   * 发起流式问答（SSE）。
   *
   * 为什么用 EventSource 而不是 fetch：
   *   EventSource 是浏览器对 SSE 的原生封装，自动解析 data: 行、断线自动重连，
   *   比用 fetch 手动读 ReadableStream 拼 buffer 简单得多。
   *   代价是它只能发 GET，所以问题放在 query 里（URLSearchParams 会自动编码中文）。
   *
   * @param {Object} opts
   * @param {string}  opts.subjectCode 叶子科目编码
   * @param {string}  opts.question    问题
   * @param {number?} opts.sessionId   多轮时传上一轮返回的 id
   * @param {Function} opts.onMeta      ({sessionId, model, mock})
   * @param {Function} opts.onDelta     (text)
   * @param {Function} opts.onCitations (items)
   * @param {Function} opts.onDone      ()
   * @param {Function} opts.onError     (message)
   * @returns {EventSource}
   */
  ns.openChatStream = function (opts) {
    const params = new URLSearchParams({
      subjectCode: opts.subjectCode,
      question: opts.question
    });
    if (opts.sessionId) {
      params.set('sessionId', String(opts.sessionId));
    }

    const es = new EventSource(BASE + '/chat/stream?' + params.toString());

    es.onmessage = function (e) {
      let ev = null;
      try {
        ev = JSON.parse(e.data);
      } catch (err) {
        return;
      }
      switch (ev.type) {
        case 'meta':
          opts.onMeta && opts.onMeta(ev);
          break;
        case 'delta':
          opts.onDelta && opts.onDelta(ev.text);
          break;
        case 'citations':
          opts.onCitations && opts.onCitations(ev.items || []);
          break;
        case 'done':
          es.close();
          opts.onDone && opts.onDone();
          break;
        case 'error':
          es.close();
          opts.onError && opts.onError(ev.message || '未知错误');
          break;
        default:
          break;
      }
    };

    es.onerror = function () {
      es.close();
      opts.onError && opts.onError('连接中断');
    };

    return es;
  };

  /** 后端把 citations 存成 JSON 字符串，读出来要解析一次 */
  ns.parseCitations = function (str) {
    if (!str) return [];
    try {
      const v = JSON.parse(str);
      return Array.isArray(v) ? v : [];
    } catch (e) {
      return [];
    }
  };
})(window.AIKB);
