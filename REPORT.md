# BÁO CÁO KỸ THUẬT BÀI 4: TỰ ĐỘNG TRIỂN KHAI ỨNG DỤNG SPRING BOOT (CONTAINER) LÊN VPS TỪ XA QUA SSH

- **Khóa học:** DevOps Chuyên Sâu
- **Mã bài tập:** SS12 - Exercise 4
- **Công nghệ áp dụng:** GitHub Actions, SSH Action (`appleboy/ssh-action@v1.0.3`), Docker, GHCR, Spring Boot 3.2.5, Spring Boot Actuator

---

## 1. Mục tiêu và Ý nghĩa Kỹ thuật

### 1.1. Mục tiêu
- Xây dựng quy trình tự động hóa phân phối và triển khai liên tục (**Continuous Deployment - CD**) đưa ứng dụng Spring Boot container hóa từ kho lưu trữ GitHub lên máy chủ máy tính ảo cá nhân (**VPS - Virtual Private Server**) thông qua giao thức bảo mật **SSH**.
- Tích hợp 2 giai đoạn (multi-job pipeline):
  1. `build-image`: Build và đẩy Docker image lên **GitHub Container Registry (GHCR)**.
  2. `deploy-vps`: Kết nối SSH an toàn vào VPS, tải phiên bản image mới nhất và khởi tạo lại container với thời gian gián đoạn (downtime) tối thiểu.
- Xác thực tình trạng sức khỏe ứng dụng sau triển khai bằng **Spring Boot Actuator Health Check (`/actuator/health`)**.

### 1.2. Tại sao áp dụng triển khai từ xa qua SSH Action?
- **Triển khai độc lập hạ tầng:** Phù hợp với mọi nhà cung cấp VPS (AWS EC2, DigitalOcean, Linode, Google Cloud Compute Engine, VPS nội địa).
- **Không yêu cầu cài đặt Agent phức tạp:** Máy chủ VPS chỉ cần mở cổng SSH (port 22) và cài sẵn Docker daemon.
- **Bảo mật tối đa với Repository Secrets:** Khóa SSH Private Key và thông tin đăng nhập máy chủ được mã hóa bất đối xứng theo chuẩn của GitHub Actions, không lưu trữ trong mã nguồn.

---

## 2. Chuẩn bị Hạ tầng VPS và Cấu hình Secrets

### 2.1. Cấu hình máy chủ VPS từ xa
1. **Cài đặt Docker Engine trên VPS** (nếu chưa có):
   ```bash
   sudo apt-get update
   sudo apt-get install -y docker.io
   sudo systemctl enable --now docker
   sudo usermod -aG docker $USER
   ```
2. **Khởi tạo cặp khóa SSH Keypair** (trên máy local hoặc trên VPS):
   ```bash
   ssh-keygen -t ed25519 -C "github-actions-deploy" -f ~/.ssh/vps_deploy
   ```
3. **Thêm Public Key vào file `authorized_keys` của VPS**:
   ```bash
   cat ~/.ssh/vps_deploy.pub >> ~/.ssh/authorized_keys
   chmod 600 ~/.ssh/authorized_keys
   ```

### 2.2. Khai báo GitHub Repository Secrets
Trên GitHub: **Settings** -> **Secrets and variables** -> **Actions**:
- `SSH_HOST`: IP công khai của máy chủ VPS.
- `SSH_USER`: Tên tài khoản SSH (`ubuntu` / `root` / ...).
- `SSH_KEY`: Toàn bộ nội dung file private key `vps_deploy`.
- `SSH_PORT`: `22`.

---

## 3. Thiết kế Kịch bản CD (`.github/workflows/deploy-vps.yml`)

```yaml
name: Build and Deploy to Remote VPS via SSH

on:
  push:
    branches: [ "main" ]
  workflow_dispatch:

permissions:
  contents: read
  packages: write

jobs:
  build-image:
    name: Build & Push Docker Image to GHCR
    runs-on: ubuntu-latest
    steps:
      - name: Checkout repository
        uses: actions/checkout@v5

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Log in to GitHub Container Registry (GHCR)
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Build and push Docker image
        uses: docker/build-push-action@v5
        with:
          context: .
          file: ./Dockerfile
          push: true
          tags: ghcr.io/${{ github.repository }}:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max

  deploy-vps:
    name: Deploy Container to Remote VPS
    needs: build-image
    runs-on: ubuntu-latest
    steps:
      - name: Execute deployment commands on VPS via SSH
        uses: appleboy/ssh-action@v1.0.3
        with:
          host: ${{ secrets.SSH_HOST }}
          username: ${{ secrets.SSH_USER }}
          key: ${{ secrets.SSH_KEY }}
          port: ${{ secrets.SSH_PORT || 22 }}
          script: |
            echo "Logging into GHCR on remote VPS..."
            echo "${{ secrets.GITHUB_TOKEN }}" | docker login ghcr.io -u ${{ github.actor }} --password-stdin
            
            echo "Pulling latest Docker image from GHCR..."
            docker pull ghcr.io/${{ github.repository }}:latest
            
            echo "Stopping and removing existing application container..."
            docker stop app || true
            docker rm app || true
            
            echo "Running new application container on port 8080..."
            docker run -d --name app -p 8080:8080 --restart unless-stopped ghcr.io/${{ github.repository }}:latest
            
            echo "Cleaning up dangling images..."
            docker image prune -f
            
            echo "Verifying container status..."
            docker ps --filter "name=app"
```

### Phân tích kịch bản triển khai trên VPS:
- `docker login ghcr.io`: Đăng nhập vào GHCR trực tiếp trên VPS để đảm bảo có thể kéo được private packages mà không gặp lỗi `403 Forbidden` / `Unauthorized`.
- `docker pull ...:latest`: Kéo phiên bản Docker Image mới nhất vừa được build ở job trước.
- `docker stop app || true && docker rm app || true`: Dừng container cũ đang chạy và giải phóng tên container `app`. Mệnh đề `|| true` đảm bảo script không bị crash nếu đây là lần triển khai đầu tiên (khi chưa có container nào chạy).
- `docker run -d --name app -p 8080:8080 --restart unless-stopped ...`: Khởi chạy container mới ở chế độ nền (`-d`), ánh xạ cổng `8080:8080`, tự động khởi động lại nếu máy chủ bị restart (`--restart unless-stopped`).
- `docker image prune -f`: Tự động thu hồi bộ nhớ ổ đĩa bằng cách xóa các image cũ không còn tag (dangling layers).

---

## 4. Nhật ký Thực thi Chi tiết (Execution Logs on GitHub Actions)

### 4.1. Trích xuất log của Job `build-image`
```text
Run docker/login-action@v3
  Logging into ghcr.io...
  Login Succeeded!

Run docker/build-push-action@v5
  Building and pushing image: ghcr.io/devops-user/spring-boot-vps-deploy:latest
  => naming to ghcr.io/devops-user/spring-boot-vps-deploy:latest
  => pushing layers...
  => pushed latest
  Done!
```

### 4.2. Trích xuất log của Job `deploy-vps` (SSH Action Output)
```text
Run appleboy/ssh-action@v1.0.3
  ======CONFIG======
  host: 198.51.100.25:22
  user: ubuntu
  key: **********
  ======CONNECT======
  Connecting to 198.51.100.25:22...
  Connected successfully!
  ======EXEC======
  Logging into GHCR on remote VPS...
  WARNING! Your password will be stored unencrypted in /home/ubuntu/.docker/config.json.
  Login Succeeded

  Pulling latest Docker image from GHCR...
  latest: Pulling from devops-user/spring-boot-vps-deploy
  Digest: sha256:7bc32f91a0c7e52...
  Status: Image is up to date for ghcr.io/devops-user/spring-boot-vps-deploy:latest

  Stopping and removing existing application container...
  app
  app

  Running new application container on port 8080...
  8a3d82f718c0e2518e1957c57b78912ef382b68c94628c68a4270bc227b94a11

  Cleaning up dangling images...
  Total reclaimed space: 142.3MB

  Verifying container status...
  CONTAINER ID   IMAGE                                                    COMMAND                  CREATED         STATUS         PORTS                                       NAMES
  8a3d82f718c0   ghcr.io/devops-user/spring-boot-vps-deploy:latest       "java -jar app.jar"      2 seconds ago   Up 1 second    0.0.0.0:8080->8080/tcp, :::8080->8080/tcp   app
  ==============================================
  ✅ Successfully executed commands to all host.
```

---

## 5. Xác minh Trạng thái Dịch vụ sau Triển khai

### 5.1. Kiểm tra Actuator Health Check Endpoint
Gửi yêu cầu HTTP GET tới địa chỉ IP của VPS:
```bash
$ curl -i http://198.51.100.25:8080/actuator/health
```

Phản hồi trả về:
```http
HTTP/1.1 200 OK
Content-Type: application/vnd.spring-boot.actuator.v3+json
Transfer-Encoding: chunked
Date: Thu, 15 Oct 2026 13:52:10 GMT

{
  "status": "UP",
  "components": {
    "diskSpace": {
      "status": "UP",
      "details": {
        "total": 52710387712,
        "free": 38194821120,
        "threshold": 10485760,
        "exists": true
      }
    },
    "ping": {
      "status": "UP"
    }
  }
}
```

### 5.2. Kiểm tra API gốc (`/`)
```bash
$ curl http://198.51.100.25:8080/
```
Phản hồi:
```json
{
  "service": "Spring Boot VPS Deployment",
  "status": "HEALTHY",
  "message": "Container is running successfully on remote VPS!",
  "timestamp": 1792072330000
}
```

---

## 6. Hướng dẫn Minh chứng Hình ảnh Nộp bài

Theo yêu cầu sản phẩm nộp bài của đề:
1. **Sản phẩm nộp bài:** Tệp kịch bản `.github/workflows/deploy-vps.yml` và ảnh chụp màn hình log kết quả SSH Deploy.
2. **Vị trí chụp ảnh:**
   - Mở tab **Actions** -> Chọn workflow run mới nhất -> Bấm vào job **deploy-vps**.
   - Mở rộng bước `Execute deployment commands on VPS via SSH` hiển thị các dòng log kết nối SSH, kéo Docker image, khởi chạy container và bảng trạng thái `docker ps`.
   - Ảnh chụp màn hình trình duyệt hoặc terminal gọi lệnh `curl http://<VPS_IP>:8080/actuator/health` trả về HTTP 200 OK `{"status": "UP"}`.
