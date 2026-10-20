/**
 * 应用入口
 *
 * 职责：串起「科目树 → 会话 → 对话 → 资料管理」四个组件，
 * 管住全局状态（当前科目、当前会话、消息、流式状态）。
 *
 * 状态放在这里而不是散在组件里，是因为：
 *   · 切换科目要同时重置会话与消息（跨组件协作）
 *   · 会话列表刷新依赖对话结束的事件
 *   这些都需要一个共同的上层。
 */
window.AIKB = window.AIKB || {};

(function (ns) {
  const { createApp, ref, onMounted, computed } = Vue;

  const App = {
    components: {
      SubjectTree: ns.SubjectTree,
      SessionList: ns.SessionList,
      ChatPanel: ns.ChatPanel,
      KbManager: ns.KbManager
    },
    setup() {
      const status = ref({ mock: true, model: '', hint: '' });
      const tree = ref([]);
      const current = ref(null);          // 当前叶子科目对象
      const sessions = ref([]);
      const sessionId = ref(null);
      const messages = ref([]);
      const draft = ref('');
      const citations = ref([]);
      const keywords = ref([]);
      const streaming = ref(false);
      const kbDialog = ref(false);
      const loadingTree = ref(false);

      const examples = computed(() => {
        const map = {
          english1: ['英语一各题型多少分？', '阅读理解怎么提高？', '大作文怎么写？'],
          english2: ['英语二和英语一有什么区别？', '翻译怎么练？'],
          math1: ['中值定理证明题怎么想？', '等价无穷小什么时候不能替换？'],
          math2: ['数学二不考哪些内容？', '二重积分怎么定限？'],
          linear: ['矩阵可对角化的条件是什么？', '基础解系怎么求？'],
          prob: ['独立和不相关有什么区别？', '最大似然估计怎么做？'],
          politics: ['多选题怎么提高正确率？', '马原材料题怎么答？'],
          ds: ['快排和归并怎么选？', 'Dijkstra 和 Floyd 的区别？'],
          os: ['死锁的四个必要条件是什么？', 'LRU 和 FIFO 的区别？']
        };
        return (current.value && map[current.value.code]) || ['这个科目有哪些重点？'];
      });

      const subjectName = computed(() => (current.value ? current.value.name : ''));

      /** 从树里摘出所有"有资料、可提问"的叶子，用于默认选中 */
      function firstLeaf(nodes) {
        for (const n of nodes || []) {
          if (n.children && n.children.length) {
            const found = firstLeaf(n.children);
            if (found) return found;
          } else if (n.docCount > 0) {
            return n;
          }
        }
        return null;
      }

      async function loadTree() {
        loadingTree.value = true;
        try {
          tree.value = await ns.api.subjectTree();
        } catch (e) {
          ElementPlus.ElMessage.error('加载科目失败：' + e.message);
        } finally {
          loadingTree.value = false;
        }
      }

      async function loadSessions() {
        if (!current.value) {
          sessions.value = [];
          return;
        }
        try {
          sessions.value = await ns.api.sessions(current.value.code);
        } catch (e) {
          sessions.value = [];
        }
      }

      function pickSubject(node) {
        if (!node) return;
        current.value = node;
        sessionId.value = null;
        messages.value = [];
        draft.value = '';
        citations.value = [];
        loadSessions();
      }

      function newChat() {
        sessionId.value = null;
        messages.value = [];
        draft.value = '';
        citations.value = [];
      }

      async function openSession(id) {
        sessionId.value = id;
        draft.value = '';
        citations.value = [];
        try {
          const list = await ns.api.messages(id);
          messages.value = list.map((m) => ({
            role: m.role,
            content: m.content,
            citations: ns.parseCitations(m.citations)
          }));
        } catch (e) {
          ElementPlus.ElMessage.error('加载会话失败：' + e.message);
        }
      }

      function send(question) {
        if (!current.value || streaming.value) return;

        messages.value.push({ role: 'user', content: question, citations: [] });
        draft.value = '';
        citations.value = [];
        keywords.value = [];
        streaming.value = true;

        ns.openChatStream({
          subjectCode: current.value.code,
          question: question,
          sessionId: sessionId.value,
          onMeta: (ev) => {
            // 第一轮由后端建会话，接住 id 后续追问才在同一会话里
            if (ev.sessionId) sessionId.value = ev.sessionId;
            // 后端分析出的关键词：展示给用户，让他知道系统怎么理解这个问题
            keywords.value = ev.keywords || [];
          },
          onDelta: (text) => {
            draft.value += text;
          },
          onCitations: (items) => {
            citations.value = items;
          },
          onDone: () => {
            messages.value.push({
              role: 'assistant',
              content: draft.value,
              citations: citations.value,
              keywords: keywords.value
            });
            draft.value = '';
            keywords.value = [];
            streaming.value = false;
            loadSessions();
          },
          onError: (msg) => {
            if (draft.value) {
              messages.value.push({
                role: 'assistant',
                content: draft.value + '\n\n（生成中断：' + msg + '）',
                citations: citations.value
              });
            } else {
              ElementPlus.ElMessage.error(msg);
            }
            draft.value = '';
            streaming.value = false;
          }
        });
      }

      async function removeSession(id) {
        try {
          await ns.api.deleteSession(id);
        } catch (e) {
          ElementPlus.ElMessage.error(e.message);
          return;
        }
        if (sessionId.value === id) newChat();
        loadSessions();
      }

      async function renameSession(s) {
        let title = null;
        try {
          title = window.prompt('重命名会话', s.title);
        } catch (e) {
          return;
        }
        if (!title || !title.trim()) return;
        try {
          await ns.api.renameSession(s.id, title.trim());
          loadSessions();
        } catch (e) {
          ElementPlus.ElMessage.error(e.message);
        }
      }

      onMounted(async () => {
        try {
          status.value = await ns.api.status();
        } catch (e) {
          status.value = { mock: true, hint: '无法连接后端' };
        }
        await loadTree();
        const first = firstLeaf(tree.value);
        if (first) {
          pickSubject(first);
        }
      });

      return {
        status, tree, current, sessions, sessionId, messages, draft, citations, keywords,
        streaming, kbDialog, loadingTree, examples, subjectName,
        pickSubject, newChat, openSession, send, removeSession, renameSession,
        loadTree, loadSessions
      };
    },

    template: `
    <div class="layout">
      <!-- ================= 左侧 ================= -->
      <aside class="aside">
        <div class="brand">
          <div class="brand-mark">知</div>
          <div>
            <div class="brand-title">考研复习助手</div>
            <div class="brand-sub">RAG · 按科目检索复习资料</div>
          </div>
        </div>

        <div class="aside-section">
          <el-button type="primary" size="small" style="width:100%" @click="newChat">+ 新对话</el-button>
        </div>

        <div class="section-label subject-section-label">
          <span>科目</span>
          <span class="muted">{{ tree.length ? '' : '加载中…' }}</span>
        </div>
        <div class="tree-wrap">
          <subject-tree :tree="tree" :current="current" @select="pickSubject" />
        </div>

        <div class="session-wrap">
          <div class="section-label">
            <span>历史对话</span>
            <el-button link size="small" @click="loadSessions">刷新</el-button>
          </div>
          <session-list
            :sessions="sessions"
            :current-id="sessionId"
            @select="openSession"
            @remove="removeSession"
            @rename="renameSession" />
        </div>
      </aside>

      <!-- ================= 右侧 ================= -->
      <main class="main">
        <div class="topbar">
          <div class="topbar-left">
            <el-tag :type="status.mock ? 'warning' : 'success'" size="small" effect="light">
              {{ status.mock ? '模拟模式' : 'DeepSeek' }}
            </el-tag>
            <span class="current-subject">{{ subjectName || '未选择科目' }}</span>
            <span class="muted">{{ status.hint }}</span>
          </div>
          <div style="display:flex;gap:8px;align-items:center">
            <el-button size="small" :disabled="!current" @click="kbDialog = true">资料管理</el-button>
          </div>
        </div>

        <chat-panel
          :subject-name="subjectName"
          :messages="messages"
          :draft="draft"
          :citations="citations"
          :streaming="streaming"
          :examples="examples"
          :notice="status.notice"
          :keywords="keywords"
          @send="send" />
      </main>

      <kb-manager
        v-model="kbDialog"
        :subject-code="current ? current.code : ''"
        :subject-name="subjectName"
        @changed="loadTree(); loadSessions();" />
    </div>
    `
  };

  const app = createApp(App);
  app.use(ElementPlus, { locale: window.ElementPlusLocaleZhCn });
  app.component('Close', window.ElementPlusIconsVue.Close);
  app.mount('#app');
})(window.AIKB);
