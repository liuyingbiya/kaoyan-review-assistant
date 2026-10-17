/**
 * 极简 Markdown 渲染
 *
 * 为什么要自己写：
 *   大模型（DeepSeek）返回的正文是 Markdown 格式 —— 会有 **加粗**、`行内代码`、
 *   - 列表、```代码块```。如果直接塞进 {{ }} 插值，用户看到的是一堆星号和反引号。
 *   引一个完整的 markdown 库（marked / markdown-it）要几百 KB，
 *   而我们只用到四五个语法，不值得。
 *
 * 安全要点（面试会问）：
 *   先做 HTML 转义，再做 Markdown 替换 —— 顺序不能反。
 *   因为模型输出是不可信内容，如果先替换再转义，<script> 之类会被当标签插进 DOM。
 *   先转义就保证最终只有我们生成的 <strong>/<code>/<li> 这些固定标签。
 */
window.AIKB = window.AIKB || {};

(function (ns) {
  const ESCAPE_MAP = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  };

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, (c) => ESCAPE_MAP[c]);
  }

  /** 行内语法：**加粗** 与 `代码` */
  function inline(s) {
    return s
      .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
      .replace(/`([^`\n]+)`/g, '<code class="md-inline">$1</code>');
  }

  /**
   * 块级语法：标题 / 无序列表 / 有序列表 / 代码块 / 段落
   * 只做逐行状态机，够用且好懂。
   */
  function render(src) {
    if (!src) {
      return '';
    }
    const lines = escapeHtml(src).split('\n');
    const out = [];
    let inCode = false;
    let listType = null; // 'ul' | 'ol' | null

    const closeList = () => {
      if (listType) {
        out.push('</' + listType + '>');
        listType = null;
      }
    };

    for (const raw of lines) {
      // 代码块围栏
      if (/^\s*```/.test(raw)) {
        if (inCode) {
          out.push('</code></pre>');
          inCode = false;
        } else {
          closeList();
          out.push('<pre class="md-pre"><code>');
          inCode = true;
        }
        continue;
      }
      if (inCode) {
        out.push(raw);
        continue;
      }
      // 空行：结束列表
      if (/^\s*$/.test(raw)) {
        closeList();
        continue;
      }
      // 标题
      const h = raw.match(/^(#{1,4})\s+(.*)$/);
      if (h) {
        closeList();
        const level = Math.min(h[1].length + 2, 6); // ### → h5，避免压过页面标题
        out.push('<h' + level + ' class="md-h">' + inline(h[2]) + '</h' + level + '>');
        continue;
      }
      // 无序列表
      const ul = raw.match(/^\s*[-*+]\s+(.*)$/);
      if (ul) {
        if (listType !== 'ul') {
          closeList();
          out.push('<ul class="md-ul">');
          listType = 'ul';
        }
        out.push('<li>' + inline(ul[1]) + '</li>');
        continue;
      }
      // 有序列表（1. / 1、/ 1)）
      const ol = raw.match(/^\s*\d+[.、)]\s+(.*)$/);
      if (ol) {
        if (listType !== 'ol') {
          closeList();
          out.push('<ol class="md-ol">');
          listType = 'ol';
        }
        out.push('<li>' + inline(ol[1]) + '</li>');
        continue;
      }
      // 普通段落
      closeList();
      out.push('<p class="md-p">' + inline(raw) + '</p>');
    }

    if (inCode) {
      out.push('</code></pre>');
    }
    closeList();
    return out.join('');
  }

  ns.md = { render, escapeHtml };
})(window.AIKB);
