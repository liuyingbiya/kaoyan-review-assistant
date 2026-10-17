/**
 * 会话列表组件
 *
 * 复习时会按科目反复追问，所以历史会话要能快速切回来。
 * 双击标题可以重命名（前端 prompt，后端 PUT）。
 */
window.AIKB = window.AIKB || {};

window.AIKB.SessionList = {
  name: 'SessionList',
  props: {
    sessions: { type: Array, default: () => [] },
    currentId: { type: [Number, String], default: null }
  },
  emits: ['select', 'remove', 'rename'],
  template: `
  <div class="session-list">
    <div v-if="!sessions.length" class="muted" style="padding:6px 10px">
      还没有对话，提问后会出现在这里
    </div>
    <div
      v-for="s in sessions"
      :key="s.id"
      class="session-item"
      :class="{ active: s.id === currentId }"
      @click="$emit('select', s.id)"
      @dblclick="$emit('rename', s)">
      <span class="session-title" :title="s.title">{{ s.title }}</span>
      <span class="session-del" @click.stop="$emit('remove', s.id)">✕</span>
    </div>
  </div>
  `
};
