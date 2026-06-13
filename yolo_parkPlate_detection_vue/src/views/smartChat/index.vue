<template>
	<div class="chat-container">
		<div class="chat-header">
			<h3 class="chat-title">停车场运营智能助手</h3>
			<p>可回答空余车位、推荐区域、动态计费、高峰预测和异常预警</p>
		</div>

		<div class="chat-messages" ref="messageContainer">
			<div v-for="(message, index) in messages" :key="index" :class="['message', message.role === 'user' ? 'user-message' : 'assistant-message']">
				<div class="message-content">
					<div class="avatar">{{ message.role === 'user' ? '👤' : '🚗' }}</div>
					<div class="text">{{ message.content }}</div>
				</div>
			</div>
			<div v-if="loading" class="message assistant-message">
				<div class="message-content">
					<div class="avatar">🚗</div>
					<div class="text"><span class="loading-dots">查询停车业务数据中</span></div>
				</div>
			</div>
		</div>

		<div class="suggested-questions">
			<div class="suggested-title">常用问题</div>
			<div class="suggested-list">
				<div v-for="(question, index) in suggestedQuestions" :key="index" class="suggested-item" @click="selectQuestion(question)">
					{{ question }}
				</div>
			</div>
		</div>

		<div class="chat-input">
			<el-input v-model="userInput" type="textarea" :rows="3" placeholder="例如：现在推荐停哪里？未来3小时会不会拥堵？" @keyup.enter.ctrl="sendMessage" />
			<el-button type="primary" :loading="loading" @click="sendMessage" :disabled="!userInput.trim()">发送</el-button>
		</div>
	</div>
</template>

<script>
import request from '/@/utils/request';

export default {
	name: 'SmartChat',
	data() {
		return {
			messages: [
				{
					role: 'assistant',
					content: '你好，我是停车场运营助手。我不依赖外部大模型，只读取系统车位、停车记录和预警规则，不编造车辆数据。',
				},
			],
			userInput: '',
			loading: false,
			suggestedQuestions: [
				'现在推荐停哪里？',
				'全场还有多少空余车位？',
				'未来3小时会不会拥堵？',
				'当前有哪些异常预警？',
				'动态计费规则是什么？',
				'今天收入多少？',
			],
		};
	},
	methods: {
		selectQuestion(question) {
			this.userInput = question;
			this.sendMessage();
		},
		async sendMessage() {
			if (!this.userInput.trim() || this.loading) return;

			const userMessage = this.userInput.trim();
			this.messages.push({ role: 'user', content: userMessage });
			this.userInput = '';
			this.loading = true;

			try {
				const res = await request.post('/api/smart/chat', { question: userMessage });
				if (res.code == 0) {
					this.messages.push({ role: 'assistant', content: res.data.answer });
				} else {
					this.messages.push({ role: 'assistant', content: res.msg || '停车业务助手暂时无法回答，请稍后再试。' });
				}
			} catch (error) {
				this.$message.error('发送消息失败，请确认后端服务已启动');
			} finally {
				this.loading = false;
				this.$nextTick(() => this.scrollToBottom());
			}
		},
		scrollToBottom() {
			const container = this.$refs.messageContainer;
			if (container) container.scrollTop = container.scrollHeight;
		},
	},
};
</script>

<style scoped>
.chat-container {
	height: calc(100vh - 60px);
	display: flex;
	flex-direction: column;
	background: linear-gradient(135deg, #eef7ff 0%, #f6ffed 100%);
}
.chat-header {
	padding: 20px;
	background: rgba(255, 255, 255, 0.92);
	box-shadow: 0 4px 10px rgba(0, 0, 0, 0.05);
	text-align: center;
}
.chat-title {
	margin: 0;
	color: #1f2d3d;
	font-size: 1.5rem;
	font-weight: 600;
}
.chat-header p {
	margin: 8px 0 0;
	color: #606266;
}
.chat-messages {
	flex: 1;
	overflow-y: auto;
	padding: 20px;
	scroll-behavior: smooth;
}
.message {
	margin-bottom: 22px;
	display: flex;
	justify-content: flex-start;
}
.message-content {
	display: flex;
	align-items: flex-start;
	max-width: 76%;
	gap: 12px;
}
.user-message .message-content {
	flex-direction: row-reverse;
	margin-left: auto;
}
.avatar {
	width: 38px;
	height: 38px;
	min-width: 38px;
	border-radius: 50%;
	display: flex;
	align-items: center;
	justify-content: center;
	background: #fff;
	box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}
.text {
	padding: 13px 16px;
	border-radius: 12px;
	background: #fff;
	box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
	line-height: 1.7;
	white-space: pre-wrap;
	word-break: break-word;
}
.user-message .text {
	background: linear-gradient(135deg, #409eff 0%, #67c23a 100%);
	color: #fff;
}
.chat-input {
	padding: 18px 20px;
	background: rgba(255, 255, 255, 0.95);
	box-shadow: 0 -4px 10px rgba(0, 0, 0, 0.05);
	display: flex;
	gap: 12px;
}
.chat-input :deep(.el-button) {
	border-radius: 10px;
	padding: 0 28px;
}
.suggested-questions {
	padding: 16px 20px;
	background: rgba(255, 255, 255, 0.88);
	border-top: 1px solid rgba(0, 0, 0, 0.05);
}
.suggested-title {
	font-size: 14px;
	color: #606266;
	margin-bottom: 12px;
	font-weight: 600;
}
.suggested-list {
	display: flex;
	flex-wrap: wrap;
	gap: 8px;
}
.suggested-item {
	padding: 8px 16px;
	background: rgba(64, 158, 255, 0.1);
	border-radius: 18px;
	font-size: 13px;
	color: #409eff;
	cursor: pointer;
	transition: all 0.2s ease;
}
.suggested-item:hover {
	background: rgba(64, 158, 255, 0.2);
	transform: translateY(-1px);
}
.loading-dots::after {
	content: '...';
	animation: loading 1.5s infinite;
}
@keyframes loading {
	0% { content: '.'; }
	33% { content: '..'; }
	66% { content: '...'; }
}
</style>