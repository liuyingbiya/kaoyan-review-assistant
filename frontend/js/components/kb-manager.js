/**
 * 资料管理弹窗
 *
 * 能力：列出当前科目的资料、粘贴文本导入、删除资料。
 * 导入后端会自动切分（400 字 / 80 字重叠），这里不用管切分逻辑。
 */
window.AIKB = window.AIKB || {};

window.AIKB.KbManager = {
  name: 'KbManager',
  props: {
    modelValue: { type: Boolean, default: false },
    subjectCode: { type: String, default: '' },
    subjectName: { type: String, default: '' }
  },
  emits: ['update:modelValue', 'changed'],
  data() {
    return {
      docs: [],
      loading: false,
      showImport: false,
      importTitle: '',
      importContent: ''
    };
  },
  watch: {
    modelValue(open) {
      if (open) {
        this.load();
      }
    }
  },
  methods: {
    async load() {
      if (!this.subjectCode) {
        return;
      }
      this.loading = true;
      try {
        this.docs = await window.AIKB.api.docs(this.subjectCode);
      } catch (e) {
        ElementPlus.ElMessage.error(e.message);
      } finally {
        this.loading = false;
      }
    },
    async doImport() {
      if (!this.importTitle.trim() || !this.importContent.trim()) {
        ElementPlus.ElMessage.warning('标题和内容都要填');
        return;
      }
      try {
        await window.AIKB.api.importText({
          subjectCode: this.subjectCode,
          title: this.importTitle.trim(),
          content: this.importContent
        });
        ElementPlus.ElMessage.success('导入成功，已自动切分入库');
        this.importTitle = '';
        this.importContent = '';
        this.showImport = false;
        await this.load();
        this.$emit('changed');
      } catch (e) {
        ElementPlus.ElMessage.error(e.message);
      }
    },
    async remove(id) {
      try {
        await ElementPlus.ElMessageBox.confirm(
          '删除后这篇资料的所有片段都会消失，检索就查不到了。确定吗？',
          '提示',
          { type: 'warning' }
        );
      } catch (e) {
        return; // 用户取消
      }
      try {
        await window.AIKB.api.deleteDoc(id);
        ElementPlus.ElMessage.success('已删除');
        await this.load();
        this.$emit('changed');
      } catch (e) {
        ElementPlus.ElMessage.error(e.message);
      }
    },
    close() {
      this.$emit('update:modelValue', false);
    },
    /** 选文件上传（.txt / .md）。上传后后端会自动切分入库。 */
    async onFile(e) {
      const f = e.target.files && e.target.files[0];
      if (!f) {
        return;
      }
      if (!/\.(txt|md)$/i.test(f.name)) {
        ElementPlus.ElMessage.warning('只支持 .txt / .md 文件');
        e.target.value = '';
        return;
      }
      try {
        await window.AIKB.api.uploadFile(this.subjectCode, f, this.importTitle.trim() || '');
        ElementPlus.ElMessage.success('已上传并切分入库');
        e.target.value = '';
        await this.load();
        this.$emit('changed');
      } catch (err) {
        ElementPlus.ElMessage.error(err.message);
        e.target.value = '';
      }
    }
  },
  template: `
  <el-dialog
    :model-value="modelValue"
    @update:model-value="close"
    title="资料管理"
    width="760px">
    <div class="dialog-toolbar">
      <div>
        <el-tag size="small">{{ subjectName }}</el-tag>
        <span class="muted" style="margin-left:8px">共 {{ docs.length }} 篇</span>
      </div>
      <div style="display:flex;gap:8px">
        <input ref="file" type="file" accept=".txt,.md" style="display:none" @change="onFile" />
        <el-button size="small" @click="$refs.file.click()">上传文件</el-button>
        <el-button size="small" @click="showImport = !showImport">
          {{ showImport ? '收起' : '粘贴文本导入' }}
        </el-button>
      </div>
    </div>

    <div v-if="showImport" class="import-box">
      <el-input v-model="importTitle" size="default" placeholder="资料标题，例如：我的操作系统笔记"
                style="margin-bottom:8px" />
      <el-input v-model="importContent" type="textarea" :rows="6"
                placeholder="粘贴正文，导入后会自动切分成片段（400 字一片，80 字重叠）" />
      <el-button type="primary" size="small" style="margin-top:8px" @click="doImport">导入</el-button>
    </div>

    <el-table :data="docs" size="small" v-loading="loading" style="margin-top:12px"
              empty-text="该科目还没有资料，先导入一篇">
      <el-table-column prop="title" label="标题" min-width="240" />
      <el-table-column prop="chunkCount" label="片段数" width="90" align="center" />
      <el-table-column prop="charCount" label="字数" width="90" align="center" />
      <el-table-column label="操作" width="80" align="center">
        <template #default="scope">
          <el-button link type="danger" size="small" @click="remove(scope.row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>
  `
};
