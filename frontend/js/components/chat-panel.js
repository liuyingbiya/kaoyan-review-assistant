/**
 * 对话面板组件
 *
 * 三块：消息列表（含流式草稿）、引用来源折叠、输入框。
 *
 * 为什么把"正在生成的回答"单独放一个 draft 而不是直接在 messages 里改最后一条？
 *   因为源数据（messages）应该只保存「已完成」的回答，
 *   流式过程中的半成品是纯 UI 状态，混进去会让"刷新后重新拉历史"变得难以对齐。
 */
window.AIKB = window.AIKB || {};

window.AIKB.ChatPanel = {
  name: 'ChatPanel',
  props: {
    subjectName: { type: String, default: '' },
    messages: { type: Array, default: () => [] },
    draft: { type: String, default: '' },
    citations: { type: Array, default: () => [] },
    streaming: { type: Boolean, default: false },
    examples: { type: Array, default: () => [] },
    // 使用范围声明（后端 /api/chat/status 下发），开始界面直接展示
    notice: { type: String, default: '' },
    // 本次提问被分析出的关键词
    keywords: { type: Array, default: () => [] }
  },
  emits: ['send'],
  data() {
    return { question: '' };
  },
  watch: {
    // 有新内容就自动滚到底部（否则流式输出时用户看不到最新文字）
    draft() {
      this.scrollToBottom();
    },
    'messages.length'() {
      this.scrollToBottom();
    }
  },
  methods: {
    scrollToBottom() {
      this.$nextTick(() => {
        const el = this.$refs.scroller;
        if (el) {
          el.scrollTop = el.scrollHeight;
        }
      });
    },
    /** 模型输出是 Markdown，渲染成 HTML 再塞进气泡（先转义再替换，防注入） */
    rendered(m) {
      return window.AIKB.md.render(m.content);
    },
    send() {
      const q = this.question.trim();
      if (!q || this.streaming) {
        return;
      }
      this.$emit('send', q);
      this.question = '';
    },
    askExample(q) {
      this.question = q;
      this.send();
    },
    formatScore(score) {
      if (score === null || score === undefined) {
        return '-';
      }
      return Number(score).toFixed(2);
    }
  },
  computed: {
    renderedDraft() {
      return window.AIKB.md.render(this.draft);
    }
  },
  mounted() {
    this.scrollToBottom();
  },
  template: `
  <div style="display:flex;flex-direction:column;flex:1;min-height:0">
    <div class="messages" ref="scroller">
      <div v-if="!messages.length && !draft" class="empty">
        <div class="empty-title">{{ subjectName || '请选择科目' }}</div>

        <!-- 使用范围声明：开始界面最显眼的位置，避免用户问无关问题 -->
        <div v-if="notice" class="scope-notice">
          <span class="scope-icon">!</span>
          <span>{{ notice }}</span>
        </div>

        <div class="empty-tip">试试这样问：</div>
        <div class="examples">
          <el-button
            v-for="q in examples"
            :key="q"
            size="small"
            plain
            @click="askExample(q)">{{ q }}</el-button>
        </div>
      </div>

      <div
        v-for="(m, i) in messages"
        :key="i"
        class="msg"
        :class="m.role === 'user' ? 'msg-user' : 'msg-assistant'">
        <div class="avatar">{{ m.role === 'user' ? '我' : 'AI' }}</div>
        <div class="bubble">
          <!-- 关键词分析结果：让用户看见系统是怎么理解这个问题的 -->
          <div v-if="m.keywords && m.keywords.length" class="kw-row">
            <span class="kw-label">关键词</span>
            <el-tag
              v-for="k in m.keywords"
              :key="k"
              size="small"
              effect="plain"
              class="kw-tag">{{ k }}</el-tag>
          </div>

          <div class="answer-text md" v-html="rendered(m)"></div>

          <el-collapse v-if="m.citations && m.citations.length" class="citations">
            <el-collapse-item :title="'引用来源（' + m.citations.length + ' 条）'" :name="String(i)">
              <div v-for="(c, ci) in m.citations" :key="ci" class="citation">
                <div class="citation-head">
                  <el-tag size="small" effect="plain" :type="c.web ? 'warning' : ''">
                    {{ c.web ? '联网' : c.docTitle }}
                  </el-tag>
                  <!-- 联网结果给可点击的链接；本地资料显示段落号与相关度 -->
                  <a v-if="c.url" :href="c.url" target="_blank" rel="noopener noreferrer" class="cite-link">
                    {{ c.web ? c.docTitle : c.url }}
                  </a>
                  <span v-else class="muted">第 {{ c.seq }} 段 · 相关度 {{ formatScore(c.score) }}</span>
                </div>
                <div class="citation-body">{{ c.content }}</div>
                <div v-if="c.url" class="citation-url">{{ c.url }}</div>
              </div>
            </el-collapse-item>
          </el-collapse>
        </div>
      </div>

      <div v-if="draft" class="msg msg-assistant">
        <div class="avatar">AI</div>
        <div class="bubble">
          <div v-if="keywords && keywords.length" class="kw-row">
            <span class="kw-label">关键词</span>
            <el-tag
              v-for="k in keywords"
              :key="k"
              size="small"
              effect="plain"
              class="kw-tag">{{ k }}</el-tag>
          </div>
          <div class="answer-text md"><span v-html="renderedDraft"></span><span v-if="streaming" class="cursor">▍</span></div>
          <div v-if="streaming" class="muted" style="margin-top:6px">正在生成…</div>
        </div>
      </div>
    </div>

    <div class="composer">
      <el-input
        v-model="question"
        type="textarea"
        :rows="2"
        resize="none"
        placeholder="输入问题，Enter 发送，Shift+Enter 换行"
        :disabled="streaming"
        @keydown.enter.exact.prevent="send" />
      <el-button
        type="primary"
        :loading="streaming"
        :disabled="!question.trim() || streaming"
        @click="send">发送</el-button>
    </div>
  </div>
  `
};
