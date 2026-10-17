/**
 * 科目树组件
 *
 * 数据是三级结构（公共课 → 英语/数学/政治 → 具体科目）。
 * 交互规则：
 *   · 分组节点（有 children）不能提问，点了只展开/收起
 *   · 叶子节点且 docCount > 0 才能提问 —— 没有资料的科目点了也没意义
 */
window.AIKB = window.AIKB || {};

window.AIKB.SubjectTree = {
  name: 'SubjectTree',
  props: {
    tree: { type: Array, default: () => [] },
    current: { type: Object, default: null }
  },
  emits: ['select'],
  computed: {
    expandedKeys() {
      // 全部展开：一共就 14 个节点，一屏能看全，
      // 比让用户一层层点开更方便（科目树就是用来快速跳转的）
      return [];
    }
  },
  methods: {
    onClick(data) {
      if (data.children && data.children.length) {
        return; // 分组节点不算选择
      }
      if (!data.docCount) {
        this.$emit('select', null);
        return;
      }
      this.$emit('select', data);
    },
    isLeaf(data) {
      return !(data.children && data.children.length);
    }
  },
  template: `
  <el-tree
    :data="tree"
    node-key="code"
    :props="{ label: 'name', children: 'children' }"
    :expand-on-click-node="false"
    default-expand-all
    highlight-current
    class="subject-tree"
    @node-click="onClick">
    <template #default="{ data }">
      <span class="subject-node">
        <span class="subject-node-name" :class="{ 'is-group': !isLeaf(data), 'is-ready': isLeaf(data) && data.docCount }">
          {{ data.name }}
        </span>
        <span v-if="data.docCount" class="subject-count">
          {{ data.docCount }}
        </span>
      </span>
    </template>
  </el-tree>
  `
};
