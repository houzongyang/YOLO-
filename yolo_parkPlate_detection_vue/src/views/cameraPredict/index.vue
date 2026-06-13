<template>
	<div class="system-predict-container layout-padding">
		<div class="system-predict-padding layout-padding-auto layout-padding-view">
			<div class="header">
				<div class="kind">
					<el-select v-model="kind" placeholder="请选择种类" size="large" style="width: 180px" @change="getData">
						<el-option v-for="item in state.kind_items" :key="item.value" :label="item.label"
							:value="item.value" />
					</el-select>
				</div>
				<div class="weight">
					<el-select v-model="weight" placeholder="请选择模型" size="large" style="margin-left: 20px;width: 180px">
						<el-option v-for="item in state.weight_items" :key="item.value" :label="item.label"
							:value="item.value" />
					</el-select>
				</div>
				<div class="conf" style="margin-left: 20px;display: flex; flex-direction: row;">
					<div
						style="font-size: 14px;margin-right: 20px;display: flex;justify-content: start;align-items: center;color: #909399;">
						设置最小置信度阈值</div>
					<el-slider v-model="conf" :format-tooltip="formatTooltip" style="width: 280px;" />
				</div>
				<div class="button-section" style="margin-left: 20px">
					<el-button type="primary" @click="start" class="predict-button">开始录制</el-button>
				</div>
                <div class="button-section" style="margin-left: 20px">
					<el-button type="primary" @click="stop" class="predict-button">结束录制</el-button>
				</div>
				<div class="progress-section" v-if="state.isShow">
					<el-progress :text-inside="true" :stroke-width="20" :percentage=state.percentage style="width: 380px;">
						<span>{{ state.type_text }} {{ state.percentage }}%</span>
					</el-progress>
				</div>
			</div>
			<el-card v-if="state.recommendation.parkArea" shadow="hover" class="camera-recommend-card">
				<div class="camera-recommend">
					<span>实时推荐停入：</span>
					<strong>{{ state.recommendation.parkArea }}</strong>
					<el-tag type="success">空余 {{ state.recommendation.unused }} 个</el-tag>
					<el-tag type="warning">风险 {{ state.recommendation.waitRisk }}</el-tag>
					<span class="reason">{{ state.recommendation.reason }}</span>
				</div>
			</el-card>
			<div class="cards" ref="cardsContainer">
				<img v-if="state.cameraisShow" class="video" :src="state.video_path">
			</div>
		</div>
	</div>
</template>


<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import request from '/@/utils/request';
import { useUserInfo } from '/@/stores/userInfo';
import { storeToRefs } from 'pinia';
import { SocketService } from '/@/utils/socket';
import { formatDate } from '/@/utils/formatTime';

const stores = useUserInfo();
const conf = ref(50);
const kind = ref('plate');
const weight = ref('');
const { userInfos } = storeToRefs(stores);

const state = reactive({
	weight_items: [] as any,
	kind_items: [
    {
      value: 'plate',
      label: '车牌',
    }
	],
	data: {} as any,
	video_path: '',
	type_text: "正在保存",
	percentage: 50,
	isShow: false,
	cameraisShow: false,
	form: {
		username: '',
		weight: '',
		conf: null as any,
		kind: '',
		startTime: ''
	},
	recommendation: {} as any,
});

const socketService = new SocketService();

socketService.on('message', (data) => {
	console.log('Received message:', data);
	ElMessage.success(data);
});

const formatTooltip = (val: number) => {
	return val / 100
}

socketService.on('progress', (data) => {
	state.percentage = parseInt(data);
	if (parseInt(data) < 100) {
		state.isShow = true;
	} else {
		//两秒后隐藏进度条
		ElMessage.success("保存成功！");
		setTimeout(() => {
			state.isShow = false;
			state.percentage = 0;
		}, 2000);
	}
	console.log('Received message:', data);
});

const getData = () => {
	request.get('/api/flask/file_names').then((res) => {
		if (res.code == 0) {
			res.data = JSON.parse(res.data);
			state.weight_items = res.data.weight_items.filter(item => item.value.includes(kind.value));
			if (state.weight_items.length > 0) {
				weight.value = state.weight_items[0].value;
			}			
		} else {
			ElMessage.error(res.msg);
		}
	});
};


const getRecommendation = () => {
	request.get('/api/smart/recommend').then((res) => {
		if (res.code == 0) state.recommendation = res.data;
	});
};

const start = () => {
	getRecommendation();
	state.form.weight = weight.value;
	state.form.kind = kind.value;
	state.form.conf = (parseFloat(conf.value)/100);
	state.form.username = userInfos.value.userName;
	state.form.startTime = formatDate(new Date(), 'YYYY-mm-dd HH:MM:SS');
	console.log(state.form);
	const queryParams = new URLSearchParams(state.form).toString();
	state.cameraisShow = true
	state.video_path = `http://127.0.0.1:5000/predictCamera?${queryParams}`;
};

const stop = () => {
	request.get('/flask/stopCamera').then((res) => {
		if (res.code == 0) {
			res.data = JSON.parse(res.data);
			console.log(res.data);
			state.weight_items = res.data.weight_items;
		} else {
			ElMessage.error(res.msg);
		}
	});
	state.cameraisShow = false
};

onMounted(() => {
	getData();
	getRecommendation();
});
</script>

<style scoped lang="scss">
.camera-recommend-card {
	margin-top: 15px;
}
.camera-recommend {
	display: flex;
	align-items: center;
	gap: 10px;
	flex-wrap: wrap;
}
.camera-recommend strong {
	font-size: 22px;
	color: var(--el-color-primary);
}
.camera-recommend .reason {
	color: var(--el-text-color-secondary);
}
.system-predict-container {
	width: 100%;
	height: 100%;
	display: flex;
	flex-direction: column;

	.system-predict-padding {
		padding: 15px;

		.el-table {
			flex: 1;
		}
	}
}

.header {
	width: 100%;
	height: 5%;
	display: flex;
	justify-content: start;
	align-items: center;
	font-size: 20px;
}

.cards {
	width: 100%;
	height: 95%;
	border-radius: 5px;
	margin-top: 15px;
	padding: 0px;
	overflow: hidden;
	display: flex;
	justify-content: center;
	align-items: center;
	/* 防止视频溢出 */
}

.video {
	width: 100%;
	max-height: 100%;
	/* 限制视频最大高度不超过父元素高度 */
	height: auto;
	object-fit: contain;
}

.button-section {
	display: flex;
	justify-content: center;
}

.predict-button {
	width: 100%;
	/* 按钮宽度填满 */
}

.progress-section .el-progress--line {
	margin-left: 20px;
	width: 600px;
}
</style>
