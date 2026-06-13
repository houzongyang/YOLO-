# 智慧停车创新点设计方案

## 现有基础

项目已具备智慧停车创新的核心数据闭环：

- 前端：Vue3 + Element Plus + ECharts，已有首页、数据大屏、停车记录、车位管理、图片/视频/摄像头识别记录、智能问答等页面。
- 后端：Spring Boot + MyBatis-Plus，已有 `area`、`plate`、`imgRecords`、`videoRecords`、`cameraRecords`、`user` 等接口。
- 算法：Flask 服务承接 YOLO 车牌/车辆识别，Spring Boot 通过 `/flask/predict` 调用算法并落库识别结果。
- 数据：车位区域包含 `used`、`unused`、`areaStatus`、`price`，停车记录包含 `plateNumber`、`parkArea`、`startTime`、`endTime`、`duration`、`price`。

## 核心创新方向

### 1. 车位智能推荐

基于当前车位空闲数、停车区域单价、历史高峰时段和车辆入场位置，为新入场车辆推荐最合适停车区域。

- 推荐规则：优先空闲充足、价格较低、历史拥堵风险低的区域。
- 推荐结果：返回推荐区域、预计等待风险、当前空闲数、预估费用。
- 落地页面：在 `imgPredict`、`cameraPredict` 和 `parkArea` 页面展示“推荐停入区域”。
- 后端接口：新增 `GET /api/smart/recommend?plateNumber=xxx`。

建议评分公式：

```text
score = unusedRatio * 0.45 + priceScore * 0.25 + lowPeakRisk * 0.20 + recentTurnover * 0.10
```

### 2. 停车高峰预测

利用 `platerecords` 的入场/出场时间，按小时、星期、区域统计历史车流，预测未来 1-3 小时停车压力。

- 预测粒度：全场、单区域、小时级。
- 预测指标：预计入场数、预计出场数、预计空闲车位、拥堵等级。
- 落地页面：增强 `dataView` 大屏，新增“未来停车压力预测”折线/热力图。
- 后端接口：新增 `GET /api/smart/forecast?hours=3&parkArea=xxx`。

轻量实现可先采用移动平均：最近 7 天同星期、同小时记录加权平均，不引入额外机器学习依赖。

### 3. 异常停车与套牌预警

结合车牌识别记录和停车记录，自动发现异常行为并提醒管理员。

- 长时停车：超过阈值未出场，如超过 24 小时。
- 重复入场：同一车牌存在未结算记录时再次入场。
- 疑似套牌：同一车牌短时间内出现在不同区域或摄像头。
- 识别低置信度：算法返回低于阈值的车牌结果进入人工复核队列。
- 落地页面：新增“风险预警”卡片到 `dataView`，或新增 `smartAlert` 页面。
- 后端接口：新增 `GET /api/smart/alerts`、`POST /api/smart/alerts/{id}/resolve`。

### 4. 动态计费策略

在已有区域单价 `price` 基础上，引入高峰、低峰、会员、封顶价等规则，提升系统业务完整度。

- 高峰调价：车位利用率高于 85% 时加价，低于 40% 时优惠。
- 区域差异：热门区域按区域系数计费。
- 免费时长：支持前 15 分钟免费。
- 封顶价：设置日封顶费用，避免超长停车费用异常。
- 落地页面：在 `parkArea` 编辑弹窗增加计费规则配置；在 `plateRecords` 展示费用明细。
- 后端接口：新增 `POST /api/smart/billing/estimate`，保存时复用计费服务计算 `price`。

### 5. 可解释数据大屏

将已有 ECharts 数据大屏升级为可解释运营驾驶舱，帮助管理员快速决策。

- 今日关键指标：当前在场车辆、空闲车位、今日收入、平均停车时长、周转率。
- 趋势解释：自动生成“今日高峰出现在 18:00，A 区利用率持续偏高”等文本摘要。
- 热点区域：展示利用率 Top 区域与长期空闲区域。
- 数据联动：点击图表区域跳转到对应 `parkArea` 或 `plateRecords` 筛选结果。
- 落地页面：优先增强 `dataView`，避免新增复杂菜单。

### 6. 智能问答增强

利用现有 `smartChat` 页面，将停车运营数据接入问答场景，形成“停车场运营助手”。

- 示例问题：“今天收入多少？”、“A 区还有多少车位？”、“哪些车超过 24 小时未出场？”
- 实现方式：前端预置问题模板，后端按意图调用统计、推荐、预警接口。
- 首期无需接入大模型，可用关键词意图识别完成演示。
- 后续可扩展为大模型 + 工具调用模式。

## 推荐一期落地范围

优先选择“投入低、展示强、与现有代码耦合小”的功能：

1. 车位智能推荐
2. 停车高峰预测
3. 异常停车预警
4. 数据大屏可解释摘要

一期不建议立即实现完整动态计费和复杂大模型问答，因为涉及更多表结构、权限和规则维护。

## 数据库扩展建议

### smart_alerts

用于保存异常停车、低置信度识别、重复入场等告警。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | int | 主键 |
| alert_type | varchar(32) | 告警类型 |
| plate_number | varchar(32) | 车牌号 |
| park_area | varchar(64) | 区域 |
| level | varchar(16) | 风险等级 |
| message | varchar(255) | 告警描述 |
| status | tinyint | 0 未处理，1 已处理 |
| created_time | datetime | 创建时间 |
| resolved_time | datetime | 处理时间 |

### smart_forecast_cache

用于缓存小时级预测结果，避免大屏频繁计算。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | int | 主键 |
| park_area | varchar(64) | 区域 |
| forecast_time | datetime | 预测时间点 |
| expected_in | int | 预计入场 |
| expected_out | int | 预计出场 |
| expected_unused | int | 预计空闲 |
| pressure_level | varchar(16) | 压力等级 |
| created_time | datetime | 生成时间 |

## 后端实现建议

新增 `SmartParkingController` 聚合智慧停车能力：

- `GET /smart/summary`：返回运营摘要和可解释文本。
- `GET /smart/recommend`：返回车位推荐。
- `GET /smart/forecast`：返回未来小时级预测。
- `GET /smart/alerts`：返回异常告警列表。
- `POST /smart/alerts/{id}/resolve`：处理告警。

新增服务类：

- `SmartRecommendService`：计算区域推荐分。
- `ParkingForecastService`：按历史记录预测入场/出场。
- `ParkingAlertService`：扫描未完成停车记录和识别记录生成告警。
- `ParkingSummaryService`：生成大屏统计和运营解释文本。

## 前端实现建议

新增或改造页面：

- `src/views/dataView/index.vue`：增加预测折线图、风险预警卡片、运营摘要。
- `src/views/parkArea/index.vue`：增加推荐分、利用率、压力等级列。
- `src/views/imgPredict/index.vue`：识别成功后展示推荐区域。
- `src/views/cameraPredict/index.vue`：实时识别后展示推荐区域。
- `src/views/smartAlert/index.vue`：如需要独立页面，可用于告警处理闭环。

## 演示亮点话术

- “系统不只识别车牌，还能根据实时车位、价格和历史高峰为车辆推荐停车区域。”
- “数据大屏从静态统计升级为预测驾驶舱，可提前发现未来 1-3 小时拥堵风险。”
- “异常预警能发现长时停车、重复入场、疑似套牌和低置信度识别，减少人工巡检成本。”
- “所有创新点基于现有车位、停车记录和 YOLO 识别结果实现，不依赖昂贵硬件扩展。”

## 实施优先级

| 优先级 | 功能 | 价值 | 复杂度 | 建议周期 |
| --- | --- | --- | --- | --- |
| P0 | 车位智能推荐 | 高 | 中 | 1 天 |
| P0 | 数据大屏摘要与预测图 | 高 | 中 | 1 天 |
| P1 | 异常停车预警 | 高 | 中 | 1-2 天 |
| P1 | 智能问答模板增强 | 中 | 低 | 0.5 天 |
| P2 | 动态计费策略 | 中 | 高 | 2 天 |

