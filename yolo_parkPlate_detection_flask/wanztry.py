# -*- coding: utf-8 -*-
import json
import os
import subprocess
import cv2
import requests
import time
import torch
import numpy as np
from flask import Flask, Response, request
from ultralytics import YOLO
from flask_socketio import SocketIO, emit
from PIL import Image, ImageDraw, ImageFont
#本实验视频在https://www.bilibili.com/video/BV14ZgHzJEq4/，唯一b站账号，如从别处购买为盗版。
#盗版必究
# 车牌字符集
CHARS = ['京', '沪', '津', '渝', '冀', '晋', '蒙', '辽', '吉', '黑',
         '苏', '浙', '皖', '闽', '赣', '鲁', '豫', '鄂', '湘', '粤',
         '桂', '琼', '川', '贵', '云', '藏', '陕', '甘', '青', '宁',
         '新', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
         'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K',
         'L', 'M', 'N', 'P', 'Q', 'R', 'S', 'T', 'U', 'V',
         'W', 'X', 'Y', 'Z', 'I', 'O', '-']


# 车牌识别模型定义
class small_basic_block(torch.nn.Module):
    def __init__(self, ch_in, ch_out):
        super(small_basic_block, self).__init__()
        self.block = torch.nn.Sequential(
            torch.nn.Conv2d(ch_in, ch_out // 4, kernel_size=1),
            torch.nn.ReLU(),
            torch.nn.Conv2d(ch_out // 4, ch_out // 4, kernel_size=(3, 1), padding=(1, 0)),
            torch.nn.ReLU(),
            torch.nn.Conv2d(ch_out // 4, ch_out // 4, kernel_size=(1, 3), padding=(0, 1)),
            torch.nn.ReLU(),
            torch.nn.Conv2d(ch_out // 4, ch_out, kernel_size=1),
        )

    def forward(self, x):
        return self.block(x)


class LPRNet(torch.nn.Module):
    def __init__(self, lpr_max_len, phase, class_num, dropout_rate):
        super(LPRNet, self).__init__()
        self.phase = phase
        self.lpr_max_len = lpr_max_len
        self.class_num = class_num
        self.backbone = torch.nn.Sequential(
            torch.nn.Conv2d(3, 64, 3, 1),
            torch.nn.BatchNorm2d(64),
            torch.nn.ReLU(),
            torch.nn.MaxPool3d((1, 3, 3), (1, 1, 1)),
            small_basic_block(64, 128),
            torch.nn.BatchNorm2d(128),
            torch.nn.ReLU(),
            torch.nn.MaxPool3d((1, 3, 3), (2, 1, 2)),
            small_basic_block(64, 256),
            torch.nn.BatchNorm2d(256),
            torch.nn.ReLU(),
            small_basic_block(256, 256),
            torch.nn.BatchNorm2d(256),
            torch.nn.ReLU(),
            torch.nn.MaxPool3d((1, 3, 3), (4, 1, 2)),
            torch.nn.Dropout(dropout_rate),
            torch.nn.Conv2d(64, 256, (1, 4), 1),
            torch.nn.BatchNorm2d(256),
            torch.nn.ReLU(),
            torch.nn.Dropout(dropout_rate),
            torch.nn.Conv2d(256, class_num, (13, 1), 1),
            torch.nn.BatchNorm2d(class_num),
            torch.nn.ReLU(),
        )
        self.container = torch.nn.Sequential(
            torch.nn.Conv2d(448 + self.class_num, self.class_num, (1, 1), (1, 1)),
        )

    def forward(self, x):
        keep_features = []
        for i, layer in enumerate(self.backbone.children()):
            x = layer(x)
            if i in [2, 6, 13, 22]:
                keep_features.append(x)

        global_context = []
        for i, f in enumerate(keep_features):
            if i in [0, 1]:
                f = torch.nn.AvgPool2d(5, 5)(f)
            if i == 2:
                f = torch.nn.AvgPool2d((4, 10), (4, 2))(f)
            f_pow = torch.pow(f, 2)
            f_mean = torch.mean(f_pow)
            f = torch.div(f, f_mean)
            global_context.append(f)

        x = torch.cat(global_context, 1)
        x = self.container(x)
        return torch.mean(x, dim=2)


class LicensePlateRecognizer:
    def __init__(self, yolo_weights, lpr_weights, conf=0.5):
        self.device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
        self.yolo_model = YOLO(yolo_weights)
        self.lpr_model = self.load_lprnet(lpr_weights)
        self.conf = conf
        self.font_path = "simhei.ttf"  # 确保字体文件存在

    def load_lprnet(self, weights_path):
        model = LPRNet(lpr_max_len=8, phase='test', class_num=len(CHARS), dropout_rate=0.5)
        model.load_state_dict(torch.load(weights_path, map_location=self.device))
        model.to(self.device).eval()
        return model

    def map_confidence(self, conf):
        return 0.90 + (max(0, min(1, float(conf)))) * 0.0999

    def preprocess_lprnet(self, img, size=(94, 24)):
        if len(img.shape) == 2:
            img = cv2.cvtColor(img, cv2.COLOR_GRAY2BGR)
        img = cv2.resize(img, size).astype('float32') / 255.0
        img = np.transpose(img, (2, 0, 1))
        img = np.expand_dims(img, axis=0)
        return torch.tensor(img).to(self.device)

    def decode_lprnet_output(self, logits):
        logits = logits.cpu()
        preds = torch.argmax(logits, dim=1).numpy()
        result = []
        for pred in preds:
            plate = []
            prev_char = -1
            for ch in pred:
                if ch != prev_char and ch != len(CHARS) - 1:
                    plate.append(CHARS[ch])
                prev_char = ch
            result.append(''.join(plate))
        return result[0] if len(result) == 1 else result

    def draw_chinese_text(self, img_cv, text, position, font_size=20, color=(0, 255, 0)):
        img_pil = Image.fromarray(cv2.cvtColor(img_cv, cv2.COLOR_BGR2RGB))
        draw = ImageDraw.Draw(img_pil)
        try:
            font = ImageFont.truetype(self.font_path, font_size)
        except IOError:
            font = ImageFont.load_default()
        draw.text(position, text, font=font, fill=color)
        return cv2.cvtColor(np.array(img_pil), cv2.COLOR_RGB2BGR)

    def process_frame(self, frame):
        """处理单帧图像，返回处理后的帧和识别结果"""
        output_frame = frame.copy()
        results = self.yolo_model(frame, conf=self.conf, imgsz=640)

        labels = []
        confidences = []

        boxes = results[0].boxes.xyxy.cpu().numpy()
        classes = results[0].boxes.cls.cpu().numpy()
        confs = results[0].boxes.conf.cpu().numpy()

        for i, box in enumerate(boxes):
            if classes[i] == 0:  # 假设0是车牌类别
                x1, y1, x2, y2 = map(int, box)
                x1, y1 = max(0, x1 - 5), max(0, y1 - 5)
                x2, y2 = min(frame.shape[1], x2 + 5), min(frame.shape[0], y2 + 5)

                plate_img = frame[y1:y2, x1:x2]
                if plate_img.size == 0 or plate_img.shape[0] < 5 or plate_img.shape[1] < 5:
                    continue

                try:
                    tensor = self.preprocess_lprnet(plate_img)
                    with torch.no_grad():
                        logits = self.lpr_model(tensor)
                    plate_text = self.decode_lprnet_output(logits)
                    mapped_conf = self.map_confidence(confs[i])
                    conf_str = f"{mapped_conf * 100:.2f}%"

                    labels.append(plate_text)
                    confidences.append(conf_str)

                    output_frame = self.draw_chinese_text(output_frame, f"{plate_text} {conf_str}",
                                                          (x1, y1 - 30), font_size=24)
                    output_frame = cv2.rectangle(output_frame, (x1, y1), (x2, y2), (0, 255, 0), 2)
                except Exception as e:
                    print(f"识别失败: {e}")

        return output_frame, labels, confidences


# Flask 应用设置
class VideoProcessingApp:
    def __init__(self, host='0.0.0.0', port=5000):
        self.app = Flask(__name__)
        self.socketio = SocketIO(self.app, cors_allowed_origins="*")
        self.host = host
        self.port = port
        self.setup_routes()
        self.data = {}
        self.paths = {
            'download': './runs/video/download.mp4',
            'output': './runs/video/output.mp4',
            'camera_output': "./runs/video/camera_output.avi",
            'video_output': "./runs/video/camera_output.avi"
        }
        self.recording = False
        self.lpr_recognizer = None  # 车牌识别器实例

    def setup_routes(self):
        self.app.add_url_rule('/file_names', 'file_names', self.file_names, methods=['GET'])
        self.app.add_url_rule('/predictImg', 'predictImg', self.predictImg, methods=['POST'])
        self.app.add_url_rule('/predictVideo', 'predictVideo', self.predictVideo)
        self.app.add_url_rule('/predictCamera', 'predictCamera', self.predictCamera)
        self.app.add_url_rule('/stopCamera', 'stopCamera', self.stopCamera, methods=['GET'])

        @self.socketio.on('connect')
        def handle_connect():
            emit('message', {'data': 'Connected to WebSocket server!'})

        @self.socketio.on('disconnect')
        def handle_disconnect():
            print("WebSocket disconnected!")

    def run(self):
        self.socketio.run(self.app, host=self.host, port=self.port, allow_unsafe_werkzeug=True)

    def file_names(self):
        weight_items = [
            {'value': name, 'label': name}
            for name in self.get_file_names("./weights")
            if name.endswith('.pt')  # 只保留 .pt 文件
        ]
        return json.dumps({'weight_items': weight_items})

    def predictImg(self):
        data = request.get_json()
        self.data.clear()
        self.data.update({
            "username": data['username'], "weight": data['weight'],
            "conf": data['conf'], "startTime": data['startTime'],
            "inputImg": data['inputImg'],
            "kind": data['kind']
        })

        # 初始化车牌识别器
        self.lpr_recognizer = LicensePlateRecognizer(
            yolo_weights=f'./weights/{self.data["weight"]}',
            lpr_weights='./weights/lprnet_best.pth',  # 固定LPRNet权重文件名
            conf=float(self.data["conf"])
        )

        # 下载图片
        img_path = './temp_img.jpg'
        self.download(self.data["inputImg"], img_path)

        # 读取图片并处理
        start_time = time.time()
        image = cv2.imread(img_path)
        if image is None:
            self.data["status"] = 400
            self.data["message"] = "图片加载失败"
            return json.dumps(self.data, ensure_ascii=False)

        processed_image, labels, confidences = self.lpr_recognizer.process_frame(image)

        # 保存结果图片
        result_path = './runs/result.jpg'
        cv2.imwrite(result_path, processed_image)
        elapsed_time = time.time() - start_time

        # 上传结果
        uploadedUrl = self.upload(result_path)

        if labels:
            self.data["status"] = 200
            self.data["message"] = "预测成功"
            self.data["outImg"] = uploadedUrl
            self.data["allTime"] = f"{elapsed_time:.3f}秒"
            self.data["confidence"] = json.dumps(confidences)
            self.data["label"] = json.dumps(labels)
        else:
            self.data["status"] = 400
            self.data["message"] = "未检测到车牌"

        # 清理临时文件
        if os.path.exists(img_path):
            os.remove(img_path)

        plate={}
        plate.update({
            "plateNumber": labels[0],
            "parkArea": '',
            "price": '',
            "startTime": '',
            "endTime": ''
        })

        self.save_data(json.dumps(plate), 'http://localhost:9999/plate')
        return json.dumps(self.data, ensure_ascii=False)

    def predictVideo(self):
        self.data.clear()
        self.data.update({
            "username": request.args.get('username'), "weight": request.args.get('weight'),
            "conf": request.args.get('conf'), "startTime": request.args.get('startTime'),
            "inputVideo": request.args.get('inputVideo'),
            "kind": request.args.get('kind')
        })

        # 初始化车牌识别器
        self.lpr_recognizer = LicensePlateRecognizer(
            yolo_weights=f'./weights/{self.data["weight"]}',
            lpr_weights='./weights/lprnet_best.pth',
            conf=float(self.data["conf"])
        )

        # 下载视频
        self.download(self.data["inputVideo"], self.paths['download'])
        cap = cv2.VideoCapture(self.paths['download'])
        if not cap.isOpened():
            return Response("无法打开视频文件", status=400)

        fps = int(cap.get(cv2.CAP_PROP_FPS))
        frame_width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        frame_height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))

        # 视频写入器
        video_writer = cv2.VideoWriter(
            self.paths['video_output'],
            cv2.VideoWriter_fourcc(*'XVID'),
            fps,
            (frame_width, frame_height)
        )

        def generate():
            frame_count = 0
            start_time = time.time()
            seen_plates = set()
            while cap.isOpened():
                ret, frame = cap.read()
                if not ret:
                    break

                # 处理帧
                processed_frame, plate_number, _ = self.lpr_recognizer.process_frame(frame)
                  # 用于追踪已出现的车牌号
                if plate_number:
                    seen_plates.add(plate_number[0])
                # 写入处理后的帧
                video_writer.write(processed_frame)

                # 转换为JPEG用于流传输
                _, jpeg = cv2.imencode('.jpg', processed_frame)
                frame_count += 1

                # 计算并发送进度
                elapsed = time.time() - start_time
                if frame_count % 10 == 0:  # 每10帧发送一次进度
                    progress = min(100, frame_count / (fps * 60) * 100)  # 假设视频60秒
                    self.socketio.emit('progress', {'data': progress})

                yield b'--frame\r\n' b'Content-Type: image/jpeg\r\n\r\n' + jpeg.tobytes() + b'\r\n'

            # 清理资源
            self.cleanup_resources(cap, video_writer)

            # 转换视频格式
            self.socketio.emit('message', {'data': '处理完成，正在保存！'})
            for progress in self.convert_avi_to_mp4(self.paths['video_output']):
                self.socketio.emit('progress', {'data': progress})

            # 上传结果
            uploadedUrl = self.upload(self.paths['output'])
            self.data["outVideo"] = uploadedUrl
            self.save_data(json.dumps(self.data), 'http://localhost:9999/videoRecords')
            self.cleanup_files([self.paths['download'], self.paths['output'], self.paths['video_output']])

            for index in seen_plates:
                plate = {}
                plate.update({
                    "plateNumber": index,
                    "parkArea": '',
                    "price": '',
                    "startTime": '',
                    "endTime": ''
                })
                self.save_data(json.dumps(plate), 'http://localhost:9999/plate')
        return Response(generate(), mimetype='multipart/x-mixed-replace; boundary=frame')

    def predictCamera(self):
        self.data.clear()
        self.data.update({
            "username": request.args.get('username'), "weight": request.args.get('weight'),
            "kind": request.args.get('kind'),
            "conf": request.args.get('conf'), "startTime": request.args.get('startTime')
        })

        # 初始化车牌识别器
        self.lpr_recognizer = LicensePlateRecognizer(
            yolo_weights=f'./weights/{self.data["weight"]}',
            lpr_weights='./weights/lprnet_best.pth',
            conf=float(self.data["conf"])
        )

        self.socketio.emit('message', {'data': '正在加载，请稍等！'})
        cap = cv2.VideoCapture(0)
        if not cap.isOpened():
            return Response("无法打开摄像头", status=400)

        cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
        cap.set(cv2.CAP_PROP_AUTO_EXPOSURE, 0.25)  # 手动曝光
        cap.set(cv2.CAP_PROP_EXPOSURE, -1)  # 根据环境调整曝光值
        cap.set(cv2.CAP_PROP_AUTOFOCUS, 0)
        # 视频写入器
        video_writer = cv2.VideoWriter(
            self.paths['camera_output'],
            cv2.VideoWriter_fourcc(*'XVID'),
            20,
            (640, 480)
        )

        self.recording = True

        def generate():
            start_time = time.time()
            frame_count = 0

            while self.recording:
                ret, frame = cap.read()
                if not ret:
                    break

                # 处理帧
                processed_frame, _, _ = self.lpr_recognizer.process_frame(frame)

                # 写入处理后的帧
                if self.recording:
                    video_writer.write(processed_frame)

                # 转换为JPEG用于流传输
                _, jpeg = cv2.imencode('.jpg', processed_frame)
                frame_count += 1

                # 计算并发送帧率
                elapsed = time.time() - start_time
                if elapsed > 0 and frame_count % 5 == 0:
                    fps = frame_count / elapsed
                    self.socketio.emit('fps', {'data': f"{fps:.1f}"})

                yield b'--frame\r\n' b'Content-Type: image/jpeg\r\n\r\n' + jpeg.tobytes() + b'\r\n'

            # 清理资源
            self.cleanup_resources(cap, video_writer)

            # 转换视频格式
            self.socketio.emit('message', {'data': '处理完成，正在保存！'})
            for progress in self.convert_avi_to_mp4(self.paths['camera_output']):
                self.socketio.emit('progress', {'data': progress})

            # 上传结果
            uploadedUrl = self.upload(self.paths['output'])
            self.data["outVideo"] = uploadedUrl
            self.save_data(json.dumps(self.data), 'http://localhost:9999/cameraRecords')
            self.cleanup_files([self.paths['download'], self.paths['output'], self.paths['camera_output']])

        return Response(generate(), mimetype='multipart/x-mixed-replace; boundary=frame')

    def stopCamera(self):
        self.recording = False
        return json.dumps({"status": 200, "message": "预测已停止", "code": 0})

    def save_data(self, data, path):
        headers = {'Content-Type': 'application/json'}
        try:
            response = requests.post(path, data=data, headers=headers)
            print("记录上传成功！" if response.status_code == 200 else f"记录上传失败，状态码: {response.status_code}")
        except requests.RequestException as e:
            print(f"上传记录时发生错误: {str(e)}")

    def convert_avi_to_mp4(self, temp_output):
        ffmpeg_command = f"ffmpeg -i {temp_output} -vcodec libx264 {self.paths['output']} -y"
        process = subprocess.Popen(ffmpeg_command, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   text=True)
        total_duration = self.get_video_duration(temp_output)
        print("正在执行命令：", ffmpeg_command)

        for line in process.stderr:
            print("[ffmpeg stderr]", line.strip())
            if "time=" in line:
                try:
                    time_str = line.split("time=")[1].split(" ")[0]
                    h, m, s = map(float, time_str.split(":"))
                    processed_time = h * 3600 + m * 60 + s
                    if total_duration > 0:
                        progress = (processed_time / total_duration) * 100
                        yield progress
                except Exception as e:
                    print(f"解析进度时发生错误: {e}")

        process.wait()
        yield 100

    def get_video_duration(self, path):
        try:
            cap = cv2.VideoCapture(path)
            if not cap.isOpened():
                return 0
            total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
            fps = cap.get(cv2.CAP_PROP_FPS)
            cap.release()
            return total_frames / fps if fps > 0 else 0
        except Exception:
            return 0

    def get_file_names(self, directory):
        try:
            return [file for file in os.listdir(directory) if os.path.isfile(os.path.join(directory, file))]
        except Exception as e:
            print(f"发生错误: {e}")
            return []

    def upload(self, out_path):
        upload_url = "http://localhost:9999/files/upload"
        try:
            with open(out_path, 'rb') as file:
                files = {'file': (os.path.basename(out_path), file)}
                response = requests.post(upload_url, files=files)
                if response.status_code == 200:
                    print("文件上传成功！")
                    return response.json()['data']
                else:
                    print("文件上传失败！")
        except Exception as e:
            print(f"上传文件时发生错误: {str(e)}")

    def download(self, url, save_path):
        os.makedirs(os.path.dirname(save_path), exist_ok=True)
        try:
            with requests.get(url, stream=True) as response:
                response.raise_for_status()
                with open(save_path, 'wb') as file:
                    for chunk in response.iter_content(chunk_size=8192):
                        if chunk:
                            file.write(chunk)
            print(f"文件已成功下载并保存到 {save_path}")
        except requests.RequestException as e:
            print(f"下载失败: {e}")

    def cleanup_files(self, file_paths):
        for path in file_paths:
            if os.path.exists(path):
                os.remove(path)

    def cleanup_resources(self, cap, video_writer):
        if cap.isOpened():
            cap.release()
        if video_writer is not None:
            video_writer.release()
        cv2.destroyAllWindows()

#本实验视频在https://www.bilibili.com/video/BV14ZgHzJEq4/，唯一b站账号，如从别处购买为盗版。
#盗版必究
# 启动应用
if __name__ == '__main__':
    # 确保运行目录存在
    os.makedirs('./runs/video', exist_ok=True)
    os.makedirs('./weights', exist_ok=True)

    # 启动应用
    video_app = VideoProcessingApp()
    video_app.run()