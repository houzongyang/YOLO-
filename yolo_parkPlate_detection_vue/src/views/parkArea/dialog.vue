<template>
	<div class="system-role-dialog-container">
		<el-dialog :title="state.dialog.title" v-model="state.dialog.isShowDialog" width="760px" class="dia">
			<el-alert title="区域状态会根据空余车位自动计算：空余为 0 时显示已满，否则显示可用。" type="info" show-icon :closable="false" class="mb15" />
			<el-form ref="greenhouseDialogFormRef" :model="state.form" size="default" label-width="120px" :rules="state.rules">
				<el-row :gutter="20">
					<el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb15">
						<el-form-item label="区域名称" prop="parkArea">
							<el-input v-model="state.form.parkArea" placeholder="请输入车位区域名称" clearable />
						</el-form-item>
					</el-col>
					<el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb15">
						<el-form-item label="空余车位" prop="unused">
							<el-input-number v-model="state.form.unused" :min="0" :precision="0" controls-position="right" style="width: 100%" />
						</el-form-item>
					</el-col>
					<el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb15">
						<el-form-item label="已用车位" prop="used">
							<el-input-number v-model="state.form.used" :min="0" :precision="0" controls-position="right" style="width: 100%" />
						</el-form-item>
					</el-col>
					<el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb15">
						<el-form-item label="单价（元/小时）" prop="price">
							<el-input-number v-model="state.form.price" :min="0" :precision="2" controls-position="right" style="width: 100%" />
						</el-form-item>
					</el-col>
					<el-col :span="24" class="mb15">
						<el-form-item label="当前状态">
							<el-tag :type="state.form.unused === 0 ? 'danger' : 'success'">
								{{ state.form.unused === 0 ? '已满' : '可用' }}
							</el-tag>
							<span class="status-tip">总车位：{{ totalSpaces }} 个</span>
						</el-form-item>
					</el-col>
				</el-row>
			</el-form>
			<template #footer>
				<span class="dialog-footer">
					<el-button @click="onCancel" size="default">取 消</el-button>
					<el-button type="primary" @click="onSubmit" size="default">{{ state.dialog.submitTxt }}</el-button>
				</span>
			</template>
		</el-dialog>
	</div>
</template>

<script setup lang="ts" name="systemRoleDialog">
import { computed, nextTick, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { FormInstance } from 'element-plus';
import request from '/@/utils/request';

interface AreaForm {
	id: number | null;
	parkArea: string;
	unused: number;
	used: number;
	price: number;
	areaStatus: number;
}

const emit = defineEmits(['refresh']);
const greenhouseDialogFormRef = ref<FormInstance>();

const createDefaultForm = (): AreaForm => ({
	id: null,
	parkArea: '',
	unused: 0,
	used: 0,
	price: 0,
	areaStatus: 0,
});

const validateNonNegativeInteger = (_rule: any, value: number, callback: any) => {
	if (value === null || value === undefined) {
		callback(new Error('请输入车位数量'));
		return;
	}
	if (!Number.isInteger(Number(value)) || Number(value) < 0) {
		callback(new Error('车位数量必须为非负整数'));
		return;
	}
	callback();
};

const validatePrice = (_rule: any, value: number, callback: any) => {
	if (value === null || value === undefined) {
		callback(new Error('请输入单价'));
		return;
	}
	if (Number(value) < 0) {
		callback(new Error('单价不能小于0'));
		return;
	}
	callback();
};

const state = reactive({
	form: createDefaultForm(),
	rules: {
		parkArea: [{ required: true, message: '请输入区域名称', trigger: 'blur' }],
		unused: [{ required: true, validator: validateNonNegativeInteger, trigger: 'change' }],
		used: [{ required: true, validator: validateNonNegativeInteger, trigger: 'change' }],
		price: [{ required: true, validator: validatePrice, trigger: 'change' }],
	},
	dialog: {
		isShowDialog: false,
		type: '',
		title: '',
		submitTxt: '',
	},
});

const totalSpaces = computed(() => Number(state.form.unused || 0) + Number(state.form.used || 0));

const openDialog = (type: string, row?: AreaForm) => {
	state.dialog.type = type;
	if (type === 'edit' && row) {
		state.form = {
			...row,
			unused: Number(row.unused || 0),
			used: Number(row.used || 0),
			price: Number(row.price || 0),
			areaStatus: Number(row.unused || 0) === 0 ? 1 : 0,
		};
		state.dialog.title = '修改区域信息';
		state.dialog.submitTxt = '修 改';
	} else {
		state.dialog.title = '新增区域信息';
		state.dialog.submitTxt = '新 增';
		nextTick(() => {
			state.form = createDefaultForm();
			greenhouseDialogFormRef.value?.clearValidate();
		});
	}
	state.dialog.isShowDialog = true;
};

const closeDialog = () => {
	state.dialog.isShowDialog = false;
};

const onCancel = () => {
	closeDialog();
};

const onSubmit = () => {
	if (!greenhouseDialogFormRef.value) return;
	greenhouseDialogFormRef.value.validate((valid: boolean) => {
		if (!valid) return false;

		const payload = {
			...state.form,
			parkArea: state.form.parkArea.trim(),
			unused: Number(state.form.unused || 0),
			used: Number(state.form.used || 0),
			price: Number(state.form.price || 0),
			areaStatus: Number(state.form.unused || 0) === 0 ? 1 : 0,
		};
		const requestUrl = state.dialog.type === 'edit' ? '/api/area/update' : '/api/area';
		request.post(requestUrl, payload).then((res) => {
			if (res.code == 0) {
				ElMessage.success(state.dialog.type === 'edit' ? '修改成功！' : '添加成功！');
				closeDialog();
				emit('refresh');
			} else {
				ElMessage({
					type: 'error',
					message: res.msg,
				});
			}
		});
	});
};

defineExpose({
	openDialog,
});
</script>

<style scoped lang="scss">
:deep(.dia) {
	.el-dialog {
		margin-top: 8vh !important;
		.el-dialog__body {
			padding: 15px 20px;
		}
		.el-dialog__header {
			padding: 15px 20px;
			margin-right: 0;
		}
		.el-dialog__footer {
			padding: 15px 20px;
		}
	}
}

.el-form {
	width: 100%;
	margin: 0;
}

.mb15 {
	margin-bottom: 15px;
}

.status-tip {
	margin-left: 12px;
	color: var(--el-text-color-secondary);
}
</style>
