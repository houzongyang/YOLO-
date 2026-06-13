<template>
	<div class="smart-dashboard layout-padding">
		<div class="layout-padding-auto layout-padding-view dashboard-body">
			<el-row :gutter="16" class="metric-row">
				<el-col :xs="12" :sm="8" :md="4" v-for="item in metricCards" :key="item.label">
					<div :class="['metric-card', item.type]">
						<div class="metric-label">{{ item.label }}</div>
						<div class="metric-value">{{ item.value }}</div>
						<div class="metric-desc">{{ item.desc }}</div>
					</div>
				</el-col>
			</el-row>

			<el-row :gutter="16" class="mt16">
				<el-col :xs="24" :md="8">
					<el-card class="module-card" shadow="hover">
						<template #header>
							<div class="card-header">
								<span>车位智能推荐</span>
								<el-tag type="success">可解释</el-tag>
							</div>
						</template>
						<div v-if="smartData.recommendation.available" class="recommend-main">
							<div class="recommend-area">{{ smartData.recommendation.parkArea }}</div>
							<div class="recommend-score">推荐分 {{ smartData.recommendation.score || 0 }}</div>
							<p>{{ smartData.recommendation.reason }}</p>
							<el-descriptions :column="2" border size="small">
								<el-descriptions-item label="空余车位">{{ smartData.recommendation.unused }}</el-descriptions-item>
								<el-descriptions-item label="占用率">{{ smartData.recommendation.occupancyRate }}%</el-descriptions-item>
								<el-descriptions-item label="等待风险">{{ smartData.recommendation.waitRisk }}</el-descriptions-item>
								<el-descriptions-item label="2小时预估">{{ smartData.recommendation.estimatedFee }} 元</el-descriptions-item>
							</el-descriptions>
						</div>
						<el-empty v-else description="暂无可推荐区域" />
					</el-card>
				</el-col>

				<el-col :xs="24" :md="16">
					<el-card class="module-card" shadow="hover">
						<template #header>
							<div class="card-header">
								<span>未来停车压力预测</span>
								<el-tag type="warning">未来 3 小时</el-tag>
							</div>
						</template>
						<el-empty v-if="smartData.forecast.length === 0" description="暂无真实历史停车记录，数据不足，暂不预测" />
						<div v-else ref="forecastChart" class="chart-container"></div>
						<el-table v-if="smartData.forecast.length > 0" :data="smartData.forecast" size="small" class="mt12">
							<el-table-column prop="time" label="时间" width="90" />
							<el-table-column prop="expectedIn" label="预计入场" width="100" />
							<el-table-column prop="expectedOut" label="预计出场" width="100" />
							<el-table-column prop="expectedUnused" label="预计空余" width="100" />
							<el-table-column prop="occupancyRate" label="预计占用率">
								<template #default="scope">
									<el-progress :percentage="scope.row.occupancyRate" :status="progressStatus(scope.row.occupancyRate)" />
								</template>
							</el-table-column>
							<el-table-column prop="pressureLevel" label="压力" width="90">
								<template #default="scope">
									<el-tag :type="pressureTag(scope.row.pressureLevel)">{{ scope.row.pressureLevel }}</el-tag>
								</template>
							</el-table-column>
						</el-table>
					</el-card>
				</el-col>
			</el-row>

			<el-row :gutter="16" class="mt16">
				<el-col :xs="24" :md="10">
					<el-card class="module-card" shadow="hover">
						<template #header>
							<div class="card-header">
								<span>异常停车 / 高占用预警</span>
								<el-tag :type="hasRisk ? 'danger' : 'success'">{{ hasRisk ? '需处理' : '正常' }}</el-tag>
							</div>
						</template>
						<el-empty v-if="smartData.alerts.length === 0" description="暂无真实预警" />
						<el-timeline v-else>
							<el-timeline-item v-for="alert in smartData.alerts" :key="alert.id + alert.type" :type="alert.level === 'high' ? 'danger' : alert.level === 'warning' ? 'warning' : 'success'" :timestamp="alert.createdTime">
								<div class="alert-message">{{ alert.message }}</div>
								<div class="alert-meta" v-if="alert.parkArea || alert.plateNumber">
									{{ alert.parkArea || '' }} {{ alert.plateNumber || '' }}
								</div>
							</el-timeline-item>
						</el-timeline>
					</el-card>
				</el-col>

				<el-col :xs="24" :md="14">
					<el-card class="module-card" shadow="hover">
						<template #header>
							<div class="card-header">
								<span>可解释运营摘要</span>
								<el-button type="primary" size="small" @click="refreshSmartData">刷新</el-button>
							</div>
						</template>
						<el-alert v-for="(text, index) in smartData.explanations" :key="index" :title="text" type="info" show-icon :closable="false" class="mb10" />
						<el-table :data="smartData.areaStats" size="small" class="mt12">
							<el-table-column prop="parkArea" label="区域" width="100" />
							<el-table-column prop="unused" label="空余" width="80" />
							<el-table-column prop="used" label="占用" width="80" />
							<el-table-column prop="recommendScore" label="推荐分" width="100" />
							<el-table-column prop="occupancyRate" label="占用率">
								<template #default="scope">
									<el-progress :percentage="scope.row.occupancyRate" :status="progressStatus(scope.row.occupancyRate)" />
								</template>
							</el-table-column>
							<el-table-column prop="pressureLevel" label="压力" width="90">
								<template #default="scope">
									<el-tag :type="pressureTag(scope.row.pressureLevel)">{{ scope.row.pressureLevel }}</el-tag>
								</template>
							</el-table-column>
						</el-table>
					</el-card>
				</el-col>
			</el-row>
		</div>
	</div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue';
import * as echarts from 'echarts/core';
import { LineChart, BarChart } from 'echarts/charts';
import { TitleComponent, TooltipComponent, GridComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import request from '/@/utils/request';
import { ElMessage } from 'element-plus';

echarts.use([TitleComponent, TooltipComponent, LegendComponent, GridComponent, LineChart, BarChart, CanvasRenderer]);

const forecastChart = ref<HTMLElement | null>(null);
let forecastChartInstance: any = null;
let refreshTimer: any = null;

const smartData = reactive({
	totalSpaces: 0,
	usedSpaces: 0,
	unusedSpaces: 0,
	currentVehicles: 0,
	occupancyRate: 0,
	todayIncome: 0,
	averageDuration: 0,
	turnoverRate: 0,
	todayFinishedCount: 0,
	recommendation: {} as any,
	forecast: [] as any[],
	alerts: [] as any[],
	areaStats: [] as any[],
	explanations: [] as string[],
});

const metricCards = computed(() => [
	{ label: '总车位', value: smartData.totalSpaces, desc: '所有区域容量', type: 'primary' },
	{ label: '空余车位', value: smartData.unusedSpaces, desc: '可直接引导', type: 'success' },
	{ label: '占用车位', value: smartData.usedSpaces, desc: '当前占用', type: 'warning' },
	{ label: '占用率', value: `${smartData.occupancyRate}%`, desc: '实时压力', type: 'danger' },
	{ label: '今日收入', value: `¥${smartData.todayIncome}`, desc: `${smartData.todayFinishedCount} 次结算`, type: 'income' },
	{ label: '平均时长', value: `${smartData.averageDuration}h`, desc: `周转率 ${smartData.turnoverRate}%`, type: 'info' },
]);

const hasRisk = computed(() => smartData.alerts.some((item) => item.type !== 'NORMAL'));

const progressStatus = (rate: number) => {
	if (rate >= 90) return 'exception';
	if (rate >= 75) return 'warning';
	return 'success';
};

const pressureTag = (level: string) => {
	if (level === '严重') return 'danger';
	if (level === '高') return 'warning';
	if (level === '中') return '';
	return 'success';
};

const refreshSmartData = async () => {
	try {
		const res = await request.get('/api/smart/summary');
		if (res.code == 0) {
			Object.assign(smartData, res.data);
			nextTick(updateForecastChart);
		} else {
			ElMessage.error(res.msg || '智慧停车数据获取失败');
		}
	} catch (error) {
		ElMessage.error('网络错误，无法获取智慧停车数据');
	}
};

const updateForecastChart = () => {
	if (!forecastChart.value) return;
	if (!forecastChartInstance) {
		forecastChartInstance = echarts.init(forecastChart.value);
	}
	forecastChartInstance.setOption({
		title: { text: '未来占用率与空余车位', left: 'center', textStyle: { fontSize: 15 } },
		tooltip: { trigger: 'axis' },
		legend: { data: ['预计占用率', '预计空余'], top: 28 },
		grid: { left: 40, right: 40, bottom: 30, top: 70 },
		xAxis: { type: 'category', data: smartData.forecast.map((item) => item.time) },
		yAxis: [
			{ type: 'value', name: '占用率%', min: 0, max: 100 },
			{ type: 'value', name: '空余' },
		],
		series: [
			{ name: '预计占用率', type: 'line', smooth: true, data: smartData.forecast.map((item) => item.occupancyRate), itemStyle: { color: '#f56c6c' } },
			{ name: '预计空余', type: 'bar', yAxisIndex: 1, data: smartData.forecast.map((item) => item.expectedUnused), itemStyle: { color: '#67c23a' } },
		],
	});
};

onMounted(() => {
	refreshSmartData();
	refreshTimer = setInterval(refreshSmartData, 5 * 60 * 1000);
	window.addEventListener('resize', updateForecastChart);
});

onUnmounted(() => {
	if (refreshTimer) clearInterval(refreshTimer);
	window.removeEventListener('resize', updateForecastChart);
	if (forecastChartInstance) forecastChartInstance.dispose();
});
</script>

<style scoped>
.smart-dashboard.layout-padding {
	background: #f5f7fb;
	min-height: 100%;
	overflow-y: auto;
	overflow-x: hidden;
	display: block;
	box-sizing: border-box;
}
.dashboard-body.layout-padding-auto.layout-padding-view {
	height: auto;
	min-height: 100%;
	overflow: visible;
	display: block;
	box-sizing: border-box;
	padding: 16px 16px 36px;
}
.metric-row .el-col {
	margin-bottom: 12px;
}
.metric-card {
	padding: 16px;
	border-radius: 10px;
	background: #fff;
	box-shadow: 0 4px 12px rgba(0, 0, 0, 0.06);
	border-top: 4px solid #409eff;
}
.metric-card.success { border-top-color: #67c23a; }
.metric-card.warning { border-top-color: #e6a23c; }
.metric-card.danger { border-top-color: #f56c6c; }
.metric-card.income { border-top-color: #9254de; }
.metric-card.info { border-top-color: #909399; }
.metric-label {
	font-size: 13px;
	color: #606266;
}
.metric-value {
	margin-top: 8px;
	font-size: 26px;
	font-weight: 700;
	color: #303133;
}
.metric-desc {
	margin-top: 6px;
	font-size: 12px;
	color: #909399;
}
.mt16 { margin-top: 16px; }
.mt12 { margin-top: 12px; }
.mb10 { margin-bottom: 10px; }
.module-card {
	border-radius: 10px;
}
.card-header {
	display: flex;
	align-items: center;
	justify-content: space-between;
	font-weight: 600;
}
.chart-container {
	width: 100%;
	height: 300px;
}
.recommend-main {
	text-align: center;
}
.recommend-area {
	font-size: 34px;
	font-weight: 700;
	color: #409eff;
}
.recommend-score {
	margin: 8px 0;
	font-size: 16px;
	color: #67c23a;
}
.recommend-main p {
	line-height: 1.7;
	color: #606266;
}
.alert-message {
	font-weight: 600;
	color: #303133;
}
.alert-meta {
	margin-top: 4px;
	font-size: 12px;
	color: #909399;
}
</style>