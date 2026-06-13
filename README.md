# YOLO 智慧停车车牌检测系统

基于 Vue 3、Spring Boot、Flask、YOLO 和 LPRNet 的智慧停车车牌检测 Web 系统。项目支持图片识别、视频识别、摄像头实时识别、车牌进出场记录、停车区域管理、数据看板和智能推荐等功能。

## 技术栈

- 前端：Vue 3、Vite、TypeScript、Element Plus、Pinia、Vue Router、ECharts
- 后端：Spring Boot 2.3.7、MyBatis-Plus、MySQL
- 推理服务：Flask、Flask-SocketIO、PyTorch、Ultralytics YOLO、OpenCV、LPRNet
- 视频处理：FFmpeg

## 项目结构

```text
.
├── docs/                                  # 项目文档
├── ffmpeg-7.1-full_build/                 # FFmpeg 相关文件
├── yolo_parkPlate_detection_flask/        # Python/Flask 推理服务
│   ├── wanztry.py                         # Flask 服务入口
│   ├── weights/                           # YOLO 和 LPRNet 权重
│   └── runs/                              # 推理结果输出目录
├── yolo_parkPlate_detection_springboot/   # Spring Boot 后端服务
│   ├── src/main/java/com/example/Ece/
│   ├── src/main/resources/application.properties
│   └── pom.xml
└── yolo_parkPlate_detection_vue/          # Vue 前端项目
    ├── src/
    ├── vite.config.ts
    └── package.json
```

## 服务关系

```text
浏览器
  ↓ http://localhost:8100
Vue 前端
  ↓ /api 代理到 http://localhost:9999
Spring Boot 后端
  ↓ 调用 http://localhost:5000
Flask 推理服务
  ↓ 回写识别结果和文件地址
Spring Boot 后端 + MySQL
```

默认端口：

| 服务 | 默认端口 | 说明 |
| --- | --- | --- |
| Vue | 8100 | 前端页面 |
| Spring Boot | 9999 | 业务接口、文件上传、数据库操作 |
| Flask | 5000 | YOLO/LPRNet 推理、视频流处理、Socket.IO 进度推送 |

## 环境要求

- Node.js >= 16，npm >= 7
- JDK 8
- Maven 3.6+，或使用项目自带 `mvnw.cmd`
- Python 3.8+
- MySQL 5.7/8.0
- FFmpeg，并确保 `ffmpeg` 命令可在终端直接执行
- 摄像头功能需要本机摄像头权限

## 配置说明

### 1. 后端数据库配置

编辑：

```text
yolo_parkPlate_detection_springboot/src/main/resources/application.properties
```

示例：

```properties
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
spring.datasource.url=jdbc:mysql://localhost:3306/db_xys_tingchechang?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8
spring.datasource.username=root
spring.datasource.password=your_password
server.port=9999
file.ip=localhost
file.upload-dir=files
spring.servlet.multipart.max-file-size=500MB
spring.servlet.multipart.max-request-size=500MB
```

注意：请使用自己的数据库账号密码，不要把真实生产库凭据提交到仓库。

后端实体对应的主要数据表：

- `user`
- `area`
- `platerecords`
- `imgrecords`
- `videorecords`
- `camerarecords`

### 2. Flask 权重文件

推理服务默认读取：

```text
yolo_parkPlate_detection_flask/weights/
```

需要至少包含：

- YOLO 检测权重，例如 `plate_best.pt`
- LPRNet 车牌字符识别权重：`lprnet_best.pth`

前端权重下拉列表来自 Flask 的 `/file_names` 接口，只会读取 `weights` 目录下的 `.pt` 文件。

### 3. 前端配置

前端端口在：

```text
yolo_parkPlate_detection_vue/.env
```

默认：

```env
VITE_PORT = 8100
VITE_OPEN = true
```

开发代理在 `vite.config.ts` 中配置：

- `/api` -> `http://localhost:9999/`
- `/flask` -> `http://localhost:5000/`

部分上传和视频流地址在前端源码中写死为 `localhost:9999` 或 `127.0.0.1:5000`，部署到其他机器时需要同步修改。

## 启动项目

建议按下面顺序启动。

### 1. 启动 Spring Boot 后端

```powershell
cd yolo_parkPlate_detection_springboot
.\mvnw.cmd spring-boot:run
```

启动成功后，后端接口地址为：

```text
http://localhost:9999
```

### 2. 启动 Flask 推理服务

```powershell
cd yolo_parkPlate_detection_flask
python -m venv .venv
.\.venv\Scripts\activate
python -m pip install -U pip
python -m pip install flask flask-socketio ultralytics opencv-python pillow numpy requests torch torchvision
python wanztry.py
```

启动成功后，推理服务地址为：

```text
http://localhost:5000
```

如果需要 GPU 推理，请按自己的 CUDA 版本安装对应的 PyTorch。

### 3. 启动 Vue 前端

```powershell
cd yolo_parkPlate_detection_vue
npm install
npm run dev
```

浏览器访问：

```text
http://localhost:8100
```

## 常用命令

前端：

```powershell
cd yolo_parkPlate_detection_vue
npm run dev
npm run build
```

后端：

```powershell
cd yolo_parkPlate_detection_springboot
.\mvnw.cmd spring-boot:run
.\mvnw.cmd clean package
```

Flask：

```powershell
cd yolo_parkPlate_detection_flask
.\.venv\Scripts\activate
python wanztry.py
```

## 主要功能

- 用户登录、注册和用户管理
- 图片车牌检测与识别
- 视频车牌检测与识别
- 摄像头实时车牌检测与识别
- 图片、视频、摄像头识别记录管理
- 停车区域管理、余位统计和计费信息
- 车牌进出场记录管理
- 数据看板和统计分析
- 智能停车推荐、告警、费用估算和问答接口

## 接口概览

Spring Boot 主要接口：

- `/user`：用户管理、登录、注册
- `/files`：文件上传和访问
- `/flask`：转发图片识别和权重列表请求到 Flask
- `/area`：停车区域管理和统计
- `/plate`：车牌进出场记录
- `/imgRecords`：图片识别记录
- `/videoRecords`：视频识别记录
- `/cameraRecords`：摄像头识别记录
- `/smart`：智能推荐、统计、告警、费用估算和问答

Flask 主要接口：

- `GET /file_names`：获取可用 YOLO 权重
- `POST /predictImg`：图片识别
- `GET /predictVideo`：视频识别流
- `GET /predictCamera`：摄像头识别流
- `GET /stopCamera`：停止摄像头识别

## 常见问题

### 前端请求失败

确认 Spring Boot 已启动在 `9999` 端口，Flask 已启动在 `5000` 端口，并检查 `vite.config.ts` 中的代理配置。

### 图片识别没有权重可选

确认 `.pt` 权重文件放在：

```text
yolo_parkPlate_detection_flask/weights/
```

### 视频识别失败或无法生成结果

确认终端可以直接执行：

```powershell
ffmpeg -version
```

同时确认 Flask 项目下存在输出目录：

```text
yolo_parkPlate_detection_flask/runs/video/
```

`wanztry.py` 启动时会自动创建该目录。

### 摄像头无画面

检查本机摄像头权限，或者修改 `wanztry.py` 中的摄像头索引：

```python
cv2.VideoCapture(0)
```

### 数据库连接失败

检查 `application.properties` 中的 MySQL 地址、端口、库名、账号密码，并确认相关数据表已经创建。

## 打包部署

前端打包：

```powershell
cd yolo_parkPlate_detection_vue
npm run build
```

后端打包：

```powershell
cd yolo_parkPlate_detection_springboot
.\mvnw.cmd clean package
```

生产部署时需要统一修改前端硬编码接口地址、Vite 代理、后端 `file.ip`、Flask 回写后端地址和数据库配置。
