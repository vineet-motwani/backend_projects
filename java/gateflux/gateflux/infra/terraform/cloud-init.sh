#!/usr/bin/env bash
set -e

# Optimize OS limits for 10k+ concurrent connection benchmarking
sysctl -w net.core.somaxconn=65535
sysctl -w net.ipv4.tcp_max_syn_backlog=65535
sysctl -w fs.file-max=2097152

cat <<'EOF' >> /etc/security/limits.conf
* soft nofile 65535
* hard nofile 65535
EOF

# Install Docker and Docker Compose plugin on Ubuntu 24.04
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y ca-certificates curl gnupg git unzip

install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  tee /etc/apt/sources.list.d/docker.list > /dev/null

apt-get update -y
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

systemctl enable docker
systemctl start docker
usermod -aG docker ubuntu

# Setup application directory
mkdir -p /opt/gateflux
chown -R ubuntu:ubuntu /opt/gateflux

echo "GateFlux server initialization complete." > /opt/gateflux/init.log
