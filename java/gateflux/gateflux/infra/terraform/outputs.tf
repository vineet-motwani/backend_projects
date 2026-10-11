output "public_ip" {
  description = "Public IP address of the deployed GateFlux instance"
  value       = aws_instance.gateflux_node.public_ip
}

output "gateway_health_url" {
  description = "Health check URL for the GateFlux gateway"
  value       = "http://${aws_instance.gateflux_node.public_ip}:8080/actuator/health"
}

output "gateway_proxy_url" {
  description = "API route URL for the GateFlux gateway"
  value       = "http://${aws_instance.gateflux_node.public_ip}:8080/get"
}

output "ssh_command" {
  description = "SSH command to connect to the benchmark server"
  value       = var.ssh_public_key == "" ? "ssh -i ${path.module}/gateflux-key.pem ubuntu@${aws_instance.gateflux_node.public_ip}" : "ssh ubuntu@${aws_instance.gateflux_node.public_ip}"
}

output "deploy_command" {
  description = "Command to sync files and launch the stack on the remote server"
  value       = "rsync -avz -e '${var.ssh_public_key == "" ? "ssh -i infra/terraform/gateflux-key.pem" : "ssh"}' --exclude 'target' --exclude '.git' ./ ubuntu@${aws_instance.gateflux_node.public_ip}:/opt/gateflux/ && ssh ${var.ssh_public_key == "" ? "-i infra/terraform/gateflux-key.pem" : ""} ubuntu@${aws_instance.gateflux_node.public_ip} 'cd /opt/gateflux && docker compose up -d --build'"
}
