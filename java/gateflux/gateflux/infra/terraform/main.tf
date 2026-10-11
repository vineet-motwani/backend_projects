terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
    local = {
      source  = "hashicorp/local"
      version = "~> 2.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

# Use the Default VPC to avoid NAT Gateway charges ($32/month saved)
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# Fetch the official Ubuntu 24.04 LTS AMI
data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

# Generate an SSH keypair automatically if user did not provide one
resource "tls_private_key" "auto_generated_key" {
  count     = var.ssh_public_key == "" ? 1 : 0
  algorithm = "ED25519"
}

resource "local_sensitive_file" "private_key_pem" {
  count           = var.ssh_public_key == "" ? 1 : 0
  content         = tls_private_key.auto_generated_key[0].private_key_openssh
  filename        = "${path.module}/gateflux-key.pem"
  file_permission = "0600"
}

resource "aws_key_pair" "gateflux_key" {
  key_name   = "gateflux-benchmark-key"
  public_key = var.ssh_public_key != "" ? var.ssh_public_key : tls_private_key.auto_generated_key[0].public_key_openssh
}

# Security group allowing SSH, Gateway (8080), and outbound
resource "aws_security_group" "gateflux_sg" {
  name        = "gateflux-sg"
  description = "Security group for GateFlux gateway benchmark instance"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "SSH access"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = var.allowed_cidr_blocks
  }

  ingress {
    description = "GateFlux Gateway Port"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = var.allowed_cidr_blocks
  }

  egress {
    description = "Allow all outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "gateflux-sg"
  }
}

# EC2 Instance (uses Spot instance for ~$0.008/hr if enabled)
resource "aws_instance" "gateflux_node" {
  ami                         = data.aws_ami.ubuntu.id
  instance_type               = var.instance_type
  subnet_id                   = data.aws_subnets.default.ids[0]
  vpc_security_group_ids      = [aws_security_group.gateflux_sg.id]
  key_name                    = aws_key_pair.gateflux_key.key_name
  associate_public_ip_address = true

  user_data                   = file("${path.module}/cloud-init.sh")

  dynamic "instance_market_options" {
    for_each = var.use_spot_instance ? [1] : []
    content {
      market_type = "spot"
    }
  }

  root_block_device {
    volume_size           = 20
    volume_type           = "gp3"
    delete_on_termination = true
  }

  tags = {
    Name    = "gateflux-benchmark-node"
    Project = "gateflux"
  }
}
