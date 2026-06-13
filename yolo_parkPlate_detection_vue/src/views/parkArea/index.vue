<template>
	<div class="system-role-container layout-padding">
		<div class="system-role-padding layout-padding-auto layout-padding-view">
			<el-row :gutter="15" class="area-summary mb15">
				<el-col :xs="12" :sm="8" :md="4">
					<div class="summary-card">
						<div class="summary-label">区域数量</div>
						<div class="summary-value">{{ state.summary.totalAreas }}</div>
					</div>
				</el-col>
				<el-col :xs="12" :sm="8" :md="5">
					<div class="summary-card">
						<div class="summary-label">车位总数</div>
						<div class="summary-value">{{ state.summary.totalSpaces }}</div>
					</div>
				</el-col>
				<el-col :xs="12" :sm="8" :md="5">
					<div class="summary-card success">
						<div class="summary-label">空余车位</div>
						<div class="summary-value">{{ state.summary.unusedSpaces }}</div>
					</div>
				</el-col>
				<el-col :xs="12" :sm="8" :md="5">
					<div class="summary-card warning">
						<div class="summary-label">已用车位</div>
						<div class="summary-value">{{ state.summary.usedSpaces }}</div>
					</div>
				</el-col>
				<el-col :xs="12" :sm="8" :md="5">
					<div class="summary-card danger">
						<div class="summary-label">占用率</div>
						<div class="summary-value">{{ state.summary.occupancyRate }}%</div>
					</div>
				</el-col>
			</el-row>

			<el-row :gutter="15" class="mb15">
				<el-col :xs="24" :md="9">
					<el-card shadow="hover" class="smart-card">
						<template #header>
							<div class="card-header">
								<span>推荐停入区域</span>
								<el-tag type="success">智能推荐</el-tag>
							</div>
						</template>
						<div v-if="state.smart.recommendation.available" class="recommend-box">
							<div class="recommend-area">{{ state.smart.recommendation.parkArea }}</div>
							<div class="recommend-reason">{{ state.smart.recommendation.reason }}</div>
							<div class="recommend-meta">
								<el-tag>推荐分 {{ state.smart.recommendation.score }}</el-tag>
								<el-tag type="warning">等待风险 {{ state.smart.recommendation.waitRisk }}</el-tag>
								<el-tag type="success">2小时约 {{ state.smart.recommendation.estimatedFee }} 元</el-tag>
							</div>
						</div>
						<el-empty v-else description="暂无可用区域" />
					</el-card>
				</el-col>
				<el-col :xs="24" :md="15">
					<el-card shadow="hover" class="smart-card">
						<template #header>
							<div class="card-header">
								<span>实时预警与解释</span>
								<el-tag :type="hasRisk ? 'danger' : 'success'">{{ hasRisk ? '有风险' : '正常' }}</el-tag>
							</div>
						</template>
						<el-alert v-for="(text, index) in state.smart.explanations" :key="index" :title="text" type="info" :closable="false" show-icon class="mb8" />
						<el-empty v-if="state.smart.alerts.length === 0" description="暂无真实预警" />
						<template v-else>
							<el-alert v-for="alert in state.smart.alerts" :key="alert.id + alert.type" :title="alert.message" :type="alert.level === 'high' ? 'error' : alert.level === 'warning' ? 'warning' : 'success'" :closable="false" show-icon class="mb8" />
						</template>
					</el-card>
				</el-col>
			</el-row>

			<div class="system-user-search mb15">
				<el-input v-model="state.tableData.param.parkArea" size="default" placeholder="请输入区域名称" clearable style="max-width: 180px" @keyup.enter="getTableData" />
				<el-select v-model="state.tableData.param.areaStatus" placeholder="请选择状态" size="default" style="max-width: 180px" class="ml10" @change="getTableData">
					<el-option v-for="item in state.statusItems" :key="item.value" :label="item.label" :value="item.value" />
				</el-select>
				<el-button size="default" type="primary" class="ml10" @click="getTableData()">
					<el-icon><ele-Search /></el-icon>
					查询
				</el-button>
				<el-button size="default" class="ml10" @click="onResetSearch">
					<el-icon><ele-Refresh /></el-icon>
					重置
				</el-button>
				<el-button size="default" type="success" class="ml10" @click="onOpenAddGreenhouse('add')">
					<el-icon><ele-FolderAdd /></el-icon>
					添加
				</el-button>
			</div>

			<el-table :data="state.tableData.data" v-loading="state.tableData.loading" style="width: 100%">
				<el-table-column prop="num" label="序号" width="70" align="center" />
				<el-table-column prop="parkArea" label="车位区域名称" min-width="140" show-overflow-tooltip align="center" />
				<el-table-column prop="total" label="总车位" width="90" align="center" />
				<el-table-column prop="unused" label="空余" width="90" align="center" />
				<el-table-column prop="used" label="占用" width="90" align="center" />
				<el-table-column prop="price" label="单价（元/小时）" width="130" align="center" />
				<el-table-column prop="recommendScore" label="推荐分" width="100" align="center" />
				<el-table-column label="压力" width="90" align="center">
					<template #default="scope">
						<el-tag :type="pressureTag(scope.row.pressureLevel)">{{ scope.row.pressureLevel }}</el-tag>
					</template>
				</el-table-column>
				<el-table-column label="占用率" width="170" align="center">
					<template #default="scope">
						<el-progress :percentage="scope.row.occupancyRate" :status="progressStatus(scope.row.occupancyRate)" />
					</template>
				</el-table-column>
				<el-table-column prop="areaStatus" label="状态" width="90" align="center">
					<template #default="scope">
						<el-tag :type="scope.row.areaStatus === 0 ? 'success' : 'danger'">{{ scope.row.areaStatus === 0 ? '可用' : '已满' }}</el-tag>
					</template>
				</el-table-column>
				<el-table-column label="操作" width="150" fixed="right" align="center">
					<template #default="scope">
						<el-button size="small" text type="primary" @click="onOpenEditGreenhouse('edit', scope.row)">修改</el-button>
						<el-button size="small" text type="danger" @click="onRowDel(scope.row)">删除</el-button>
					</template>
				</el-table-column>
			</el-table>
			<el-pagination
				@size-change="onHandleSizeChange"
				@current-change="onHandleCurrentChange"
				class="mt15"
				:pager-count="5"
				:page-sizes="[10, 20, 30]"
				v-model:current-page="state.tableData.param.pageNum"
				background
				v-model:page-size="state.tableData.param.pageSize"
				layout="total, sizes, prev, pager, next, jumper"
				:total="state.tableData.total"
			/>
		</div>
		<GreenhouseDialog ref="greenhouseDialogRef" @refresh="refreshData" />
	</div>
</template>

<script setup lang="ts" name="systemRole">
import { computed, defineAsyncComponent, reactive, onMounted, ref } from 'vue';
import { ElMessageBox, ElMessage } from 'element-plus';
import request from '/@/utils/request';

interface AreaRow {
	id: number;
	parkArea: string;
	used: number;
	unused: number;
	price: number;
	areaStatus: number;
	num?: number;
	total?: number;
	occupancyRate?: number;
	recommendScore?: number;
	pressureLevel?: string;
}

interface SummaryData {
	totalAreas: number;
	totalSpaces: number;
	usedSpaces: number;
	unusedSpaces: number;
	fullAreas: number;
	availableAreas: number;
	occupancyRate: number;
}

const GreenhouseDialog = defineAsyncComponent(() => import('./dialog.vue'));
const greenhouseDialogRef = ref();
const state = reactive({
	tableData: {
		data: [] as AreaRow[],
		total: 0,
		loading: false,
		param: {
			parkArea: '',
			areaStatus: -1,
			pageNum: 1,
			pageSize: 10,
		},
	},
	summary: {
		totalAreas: 0,
		totalSpaces: 0,
		usedSpaces: 0,
		unusedSpaces: 0,
		fullAreas: 0,
		availableAreas: 0,
		occupancyRate: 0,
	} as SummaryData,
	smart: {
		recommendation: {} as any,
		alerts: [] as any[],
		areaStats: [] as any[],
		explanations: [] as string[],
	},
	statusItems: [
		{ value: -1, label: '全部' },
		{ value: 0, label: '可用' },
		{ value: 1, label: '已满' },
	],
});

const hasRisk = computed(() => state.smart.alerts.some((item) => item.type !== 'NORMAL'));

const getOccupancyRate = (used: number, unused: number) => {
	const total = Number(used || 0) + Number(unused || 0);
	if (total === 0) return 0;
	return Math.round((Number(used || 0) * 10000) / total) / 100;
};

const progressStatus = (rate: number) => {
	if (rate >= 90) return 'exception';
	if (rate >= 75) return 'warning';
	return 'success';
};

const pressureTag = (level: string) => {
	if (level === '严重') return 'danger';
	if (level === '高') return 'warning';
	if (level === '低') return 'success';
	return '';
};

const getSummaryData = async () => {
	const res = await request.get('/api/area/summary');
	if (res.code == 0) {
		state.summary = res.data;
	}
};

const getSmartData = async () => {
	const res = await request.get('/api/smart/summary');
	if (res.code == 0) {
		state.smart.recommendation = res.data.recommendation || {};
		state.smart.alerts = res.data.alerts || [];
		state.smart.areaStats = res.data.areaStats || [];
		state.smart.explanations = res.data.explanations || [];
	}
};

const getTableData = () => {
	state.tableData.loading = true;
	request
		.get('/api/area', { params: state.tableData.param })
		.then((res) => {
			if (res.code == 0) {
				state.tableData.data = res.data.records.map((item: AreaRow, index: number) => {
					const used = Number(item.used || 0);
					const unused = Number(item.unused || 0);
					const smartArea = state.smart.areaStats.find((area) => area.parkArea === item.parkArea) || {};
					return {
						...item,
						used,
						unused,
						total: used + unused,
						occupancyRate: getOccupancyRate(used, unused),
						recommendScore: smartArea.recommendScore || 0,
						pressureLevel: smartArea.pressureLevel || '低',
						num: (state.tableData.param.pageNum - 1) * state.tableData.param.pageSize + index + 1,
					};
				});
				state.tableData.total = res.data.total;
			} else {
				ElMessage({ type: 'error', message: res.msg });
			}
		})
		.finally(() => {
			state.tableData.loading = false;
		});
};

const refreshData = async () => {
	await Promise.all([getSummaryData(), getSmartData()]);
	getTableData();
};

const onResetSearch = () => {
	state.tableData.param.parkArea = '';
	state.tableData.param.areaStatus = -1;
	state.tableData.param.pageNum = 1;
	getTableData();
};

const onOpenAddGreenhouse = (type: string) => {
	greenhouseDialogRef.value.openDialog(type);
};

const onOpenEditGreenhouse = (type: string, row: AreaRow) => {
	greenhouseDialogRef.value.openDialog(type, row);
};

const onRowDel = (row: AreaRow) => {
	ElMessageBox.confirm(`删除区域「${row.parkArea}」后不可恢复，确认继续？`, '提示', {
		confirmButtonText: '确认',
		cancelButtonText: '取消',
		type: 'warning',
	})
		.then(() => {
			request.delete('/api/area/' + row.id).then((res) => {
				if (res.code == 0) {
					ElMessage({ type: 'success', message: '删除成功！' });
					refreshData();
				} else {
					ElMessage({ type: 'error', message: res.msg });
				}
			});
		})
		.catch(() => {});
};

const onHandleSizeChange = (val: number) => {
	state.tableData.param.pageSize = val;
	state.tableData.param.pageNum = 1;
	getTableData();
};

const onHandleCurrentChange = (val: number) => {
	state.tableData.param.pageNum = val;
	getTableData();
};

onMounted(() => {
	refreshData();
});
</script>

<style scoped lang="scss">
.system-role-container.layout-padding {
	overflow-y: auto;
	overflow-x: hidden;
	display: block;
	box-sizing: border-box;
}
.system-role-padding.layout-padding-auto.layout-padding-view {
	height: auto;
	min-height: 100%;
	overflow: visible;
	display: block;
	box-sizing: border-box;
	padding: 15px 15px 36px;
	.el-table {
		width: 100%;
	}
}
.area-summary {
	.summary-card {
		padding: 16px;
		border-radius: 8px;
		background: var(--el-fill-color-light);
		border-left: 4px solid var(--el-color-primary);
	}
	.summary-card.success { border-left-color: var(--el-color-success); }
	.summary-card.warning { border-left-color: var(--el-color-warning); }
	.summary-card.danger { border-left-color: var(--el-color-danger); }
	.summary-label {
		font-size: 13px;
		color: var(--el-text-color-secondary);
	}
	.summary-value {
		margin-top: 8px;
		font-size: 24px;
		font-weight: 600;
		color: var(--el-text-color-primary);
	}
}
.smart-card {
	min-height: 220px;
}
.card-header {
	display: flex;
	align-items: center;
	justify-content: space-between;
	font-weight: 600;
}
.recommend-box {
	text-align: center;
}
.recommend-area {
	font-size: 32px;
	font-weight: 700;
	color: var(--el-color-primary);
}
.recommend-reason {
	margin: 10px 0;
	line-height: 1.7;
	color: var(--el-text-color-regular);
}
.recommend-meta {
	display: flex;
	gap: 8px;
	justify-content: center;
	flex-wrap: wrap;
}
.mb8 {
	margin-bottom: 8px;
}
</style>