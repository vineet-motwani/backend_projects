variable "aws_region" {
  description = "AWS region to deploy the instance into"
  type        = string
  default     = "ap-south-1" # Mumbai (low latency for India), change to us-east-1 if preferred
}

variable "instance_type" {
  description = "EC2 instance type (t3.small provides 2 vCPU and 2GB RAM for ~0.02/hr)"
  type        = string
  default     = "t3.small"
}

variable "use_spot_instance" {
  description = "Set to true to request a Spot instance for maximum cost savings (~$0.007/hr)"
  type        = bool
  default     = true
}

variable "ssh_public_key" {
  description = "Public SSH key content to log into the EC2 instance (e.g. contents of ~/.ssh/id_rsa.pub or ~/.ssh/id_ed25519.pub)"
  type        = string
  default     = ""
}

variable "allowed_cidr_blocks" {
  description = "CIDR blocks allowed to access port 8080 and SSH (default open for easy testing)"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}
