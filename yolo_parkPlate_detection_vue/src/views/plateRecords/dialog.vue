<template>
	<div class="system-role-dialog-container">
		<el-dialog :title="state.dialog.title" v-model="state.dialog.isShowDialog" width="900px" class="dia">
			<el-form ref="greenhouseDialogFormRef" :model="state.form" size="default" label-width="100px" :rules="state.rules">
				<el-row :gutter="20">
					<el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb15">
						<el-form-item label="车牌号" prop="plateNumber">
							<el-input v-model="state.form.plateNumber" placeholder="请输入车牌号" clearable />
						</el-form-item>
					</el-col>
					<!-- 修改为下拉框 -->
					<el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb15">
						<el-form-item label="车位区域" prop="parkArea">
							<el-select
								v-model="state.form.parkArea"
								placeholder="请选择车位区域"
								clearable
								filterable
								:loading="state.parkAreaLoading"
								style="width: 100%"
							>
								<el-option
									v-for="item in state.parkAreaOptions"
									:key="item.value"
									:label="item.label"
									:value="item.value"
								/>
							</el-select>
						</el-form-item>
					</el-col>
					<el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb15">
						<el-form-item label="费用" prop="price">
							<el-input v-model="state.form.price" placeholder="请输入费用" clearable />
						</el-form-item>
					</el-col>
					<el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb15">
						<el-form-item label="入场时间" prop="recordTime">
							<el-date-picker
								v-model="state.form.startTime"
								type="datetime"
								placeholder="请选择入场时间"
								style="width: 100%"
								value-format="YYYY-MM-DD HH:mm:ss"
							/>
						</el-form-item>
					</el-col>

					<el-col :xs="24" :sm="12" :md="12" :lg="12" :xl="12" class="mb15">
						<el-form-item label="出场时间" prop="recordTime">
							<el-date-picker
								v-model="state.form.endTime"
								type="datetime"
								placeholder="请选择出场时间"
								style="width: 100%"
								value-format="YYYY-MM-DD HH:mm:ss"
							/>
						</el-form-item>
					</el-col>
				</el-row>
			</el-form>
			<template #footer>
				<span class="dialog-footer">
					<el-button @click="onCancel" size="default">取 消</el-button>
					<el-button type="primary" @click="onSubmit" size="default" :loading="state.submitLoading">{{ state.dialog.submitTxt }}</el-button>
				</span>
			</template>
		</el-dialog>
	</div>
</template>

<script setup lang="ts" name="systemRoleDialog">
import { nextTick, reactive, ref, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import type { FormInstance } from 'element-plus';
import request from '/@/utils/request';

// 定义子组件向父组件传值/事件
const emit = defineEmits(['refresh']);

// 定义选项项类型
interface OptionItem {
	value: string | number;
	label: string;
}

// 定义变量内容
const greenhouseDialogFormRef = ref<FormInstance>();
const state = reactive({
	form: {
		id: null,
		plateNumber: '',
		parkArea: '',
		price: '',
		startTime: '',
		endTime: ''
	},
	parkAreaOptions: [] as OptionItem[], // 车位区域选项
	parkAreaLoading: false, // 选项加载状态
	submitLoading: false, // 提交加载状态
	rules: {
		plateNumber: [{ required: true, message: '请输入车牌号', trigger: 'blur' }],
		parkArea: [{ required: true, message: '请选择车位区域', trigger: 'change' }],
		price: [{ required: true, message: '请输入费用', trigger: 'blur' }],
		startTime: [{ required: true, message: '请选择入场时间', trigger: 'change' }]
	},
	dialog: {
		isShowDialog: false,
		type: '',
		title: '',
		submitTxt: '',
	},
});

// 组件挂载时加载一次车位区域选项
onMounted(() => {
	getParkAreaOptions();
});

// 获取车位区域选项
const getParkAreaOptions = async () => {
	state.parkAreaLoading = true;
	try {
		const res = await request.get('/api/area'); // 调整为实际API
		console.log(res)
		if (res.code == 0) {
		state.parkAreaOptions = res.data.records.map((item: any) => ({
				value: item.parkArea,   // 使用区域名称作为值
				label: item.parkArea    // 也使用区域名称作为显示文本
			}));
		} else if (res.msg) {
			ElMessage.warning(res.msg);
		}
	} catch (error) {
		console.error('获取车位区域失败:', error);
		ElMessage.error('获取车位区域数据异常');
	} finally {
		state.parkAreaLoading = false;
	}
};

// 打开弹窗
const openDialog = (type: string, row: any = {}) => {
	state.dialog.type = type;
	
	if (type === 'edit') {
		state.form = { ...row };
		state.dialog.title = '修改停车记录信息';
		state.dialog.submitTxt = '修 改';
	} else {
		state.dialog.title = '新增停车记录信息';
		state.dialog.submitTxt = '新 增';
		// 清空表单
		state.form = {
			id: null,
			plateNumber: '',
			parkArea: '',
			price: '',
			startTime: '',
			endTime: ''
		};
	}
	state.dialog.isShowDialog = true;
	
	// 如果选项为空则重新加载
	if (!state.parkAreaOptions.length) {
		getParkAreaOptions();
	}
};

// 关闭弹窗
const closeDialog = () => {
	state.dialog.isShowDialog = false;
	state.submitLoading = false;
};

// 取消
const onCancel = () => {
	closeDialog();
};

// 提交
const onSubmit = async () => {
	if (!greenhouseDialogFormRef.value) return;
	
	try {
		// 表单验证
		const valid = await greenhouseDialogFormRef.value.validate();
		if (!valid) return;
		
		state.submitLoading = true;
		
		const isEdit = state.dialog.type === 'edit';
		const apiUrl = isEdit ? '/api/plate/update' : '/api/plate';
		const successMsg = isEdit ? '修改成功' : '添加成功';
		
		// 提交数据
		const res = await request.post(apiUrl, state.form);
		
		if (res.code === 0) {
			ElMessage.success(successMsg);
			closeDialog();
			emit('refresh');
		} else {
			ElMessage.error(res.msg || '操作失败');
		}
	} catch (error) {
		console.error('提交失败:', error);
	} finally {
		state.submitLoading = false;
	}
};

// 暴露变量
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
</style>