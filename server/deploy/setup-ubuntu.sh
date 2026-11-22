#!/usr/bin/env bash
# 새로 산 Ubuntu 서버에서 한 번만 실행한다:  sudo bash deploy/setup-ubuntu.sh
# Docker 설치 + 방화벽(22, 80, 443만 열기)
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "sudo로 실행해 주세요: sudo bash deploy/setup-ubuntu.sh"
  exit 1
fi

apt-get update -y
apt-get install -y ca-certificates curl git ufw

if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
fi
systemctl enable --now docker

# 메모리 1GB 서버에서 빌드가 멈추지 않도록 스왑 2GB
if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

if [ -n "${SUDO_USER:-}" ]; then
  usermod -aG docker "$SUDO_USER"
  echo "다시 접속하면 sudo 없이 docker를 쓸 수 있습니다."
fi
echo "설치 완료. 다음: server/README.md 3단계"
