# Deployment Guide for Astra Gateway

## Prerequisites

- Kubernetes 1.24+
- Helm 3.0+
- Docker
- Terraform (for cloud infrastructure)
- kubectl configured

## Architecture Overview

Astra Gateway is deployed as microservices on Kubernetes with the following components:

```
┌─────────────────────────────────────────────┐
│     Cloud Load Balancer (AWS/GCP/Azure)     │
└─────────────────────────────────────────────┘
                    │
┌─────────────────────────────────────────────┐
│  NGINX Ingress Controller (SSL/TLS)         │
└─────────────────────────────────────────────┘
                    │
┌─────────────────────────────────────────────┐
│  Istio Service Mesh (optional, for advanced routing)
└─────────────────────────────────────────────┘
                    │
┌─────────────────────────────────────────────┐
│  Gateway Service (3+ replicas)              │
│  - Request validation                       │
│  - Authentication                           │
│  - Load balancing                           │
└─────────────────────────────────────────────┘
                    │
        ┌───────────┼───────────┐
        │           │           │
   ┌────▼──┐  ┌────▼──┐  ┌────▼──┐
   │ Auth  │  │Routing│  │ Govt  │
   │Service│  │Engine │  │Engine │
   └───────┘  └───────┘  └───────┘
                    │
   ┌────────────────┼────────────────┐
   │                │                │
┌──▼──┐         ┌──▼──┐          ┌──▼──┐
│PostgreSQL│  │Redis  │          │ClickHouse
└───────┘     └───────┘          └─────────┘
```

## Deployment Steps

### 1. Prepare Infrastructure

```bash
# Create AWS/GCP/Azure infrastructure with Terraform
cd infrastructure/terraform
terraform init
terraform apply

# Output should include:
# - VPC and subnets
# - EKS/GKE/AKS cluster
# - RDS PostgreSQL instance
# - ElastiCache Redis
# - Security groups/network policies
```

### 2. Create Kubernetes Cluster

```bash
# AWS EKS
aws eks create-cluster \
  --name astra-gateway \
  --version 1.28 \
  --role-arn arn:aws:iam::ACCOUNT:role/eks-service-role \
  --resources-vpc-config subnetIds=subnet-123,subnet-456

# Get kubeconfig
aws eks update-kubeconfig --name astra-gateway

# GCP GKE
gcloud container clusters create astra-gateway \
  --zone us-central1-a \
  --num-nodes 3 \
  --machine-type n1-standard-2

# Azure AKS
az aks create \
  --resource-group astra-rg \
  --name astra-gateway \
  --node-count 3 \
  --vm-set-type VirtualMachineScaleSets
```

### 3. Install Kubernetes Add-ons

```bash
# NGINX Ingress Controller
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm install ingress-nginx ingress-nginx/ingress-nginx \
  --namespace ingress-nginx --create-namespace

# Cert Manager (for SSL certificates)
helm repo add jetstack https://charts.jetstack.io
helm install cert-manager jetstack/cert-manager \
  --namespace cert-manager --create-namespace \
  --set installCRDs=true

# Prometheus Operator (monitoring)
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm install prometheus prometheus-community/kube-prometheus-stack \
  --namespace monitoring --create-namespace

# Istio (service mesh, optional)
istioctl install --set profile=production -y

# ELK Stack (logging)
helm repo add elastic https://helm.elastic.co
helm install elasticsearch elastic/elasticsearch \
  --namespace logging --create-namespace
```

### 4. Configure Secrets and ConfigMaps

```bash
# Create namespace
kubectl create namespace astra

# Create secrets for sensitive data
kubectl create secret generic astra-secrets \
  -n astra \
  --from-literal=JWT_SECRET_KEY=$(openssl rand -base64 32) \
  --from-literal=DATABASE_URL="postgresql://..." \
  --from-literal=REDIS_URL="redis://..." \
  --from-literal=OPENAI_API_KEY="sk-..." \
  --from-literal=ANTHROPIC_API_KEY="sk-ant-..."

# Create ConfigMap for environment
kubectl create configmap astra-config \
  -n astra \
  --from-literal=ENVIRONMENT=production \
  --from-literal=LOG_LEVEL=info

# Verify
kubectl get secrets -n astra
kubectl get configmaps -n astra
```

### 5. Deploy Database Migrations

```bash
# Create migration job
kubectl apply -f infrastructure/k8s/db-migration-job.yaml

# Wait for completion
kubectl wait --for=condition=complete job/astra-db-migration -n astra --timeout=300s

# Verify database
kubectl exec -it postgres-0 -n astra -- psql -U astra -d astra -c "SELECT version();"
```

### 6. Deploy Astra Gateway Services

```bash
# Deploy core infrastructure (PostgreSQL, Redis)
kubectl apply -f infrastructure/k8s/astra-core.yaml

# Verify services are running
kubectl get pods -n astra
kubectl get svc -n astra

# Deploy remaining microservices
kubectl apply -f infrastructure/k8s/auth-service.yaml
kubectl apply -f infrastructure/k8s/routing-engine.yaml
kubectl apply -f infrastructure/k8s/governance-engine.yaml
kubectl apply -f infrastructure/k8s/observability-service.yaml
kubectl apply -f infrastructure/k8s/billing-service.yaml
```

### 7. Configure Ingress and DNS

```bash
# Create ClusterIssuer for Let's Encrypt
kubectl apply -f infrastructure/k8s/cert-issuer.yaml

# Apply Ingress configuration
kubectl apply -f infrastructure/k8s/ingress.yaml

# Get load balancer IP
kubectl get ingress -n astra

# Update DNS records
# api.astragateway.io -> <LOAD_BALANCER_IP>
```

### 8. Verify Deployment

```bash
# Check all pods are running
kubectl get pods -n astra --watch

# Check services
kubectl get svc -n astra

# Test API endpoint
curl -X GET http://localhost:8080/health \
  -H "Authorization: Bearer test-token"

# Check logs
kubectl logs -n astra -l app=gateway-service -f

# Port forward for local testing
kubectl port-forward -n astra svc/gateway-service 8080:80
```

## Helm Deployment (Recommended)

```bash
# Add Astra Helm repository
helm repo add astra https://helm.astragateway.io
helm repo update

# Install Astra Gateway
helm install astra-gateway astra/astra-gateway \
  --namespace astra \
  --create-namespace \
  --values infrastructure/helm/values.yaml

# Verify installation
helm status astra-gateway -n astra

# Upgrade
helm upgrade astra-gateway astra/astra-gateway \
  --namespace astra \
  --values infrastructure/helm/values-prod.yaml

# Rollback
helm rollback astra-gateway 1 -n astra
```

## Production Configuration

### High Availability

```yaml
# Enable multi-region replication
postgresql:
  replication:
    enabled: true
    slots: 3
  backup:
    schedule: "0 2 * * *"  # Daily at 2 AM UTC

redis:
  sentinel:
    enabled: true
    quorum: 2

gateway:
  replicas: 5
  pod_disruption_budget:
    min_available: 3
```

### Monitoring Setup

```bash
# Port forward Prometheus
kubectl port-forward -n monitoring svc/prometheus-kube-prom-prometheus 9090:9090

# Port forward Grafana
kubectl port-forward -n monitoring svc/prometheus-grafana 3000:80
# Login with admin/prom-operator

# Port forward Jaeger
kubectl port-forward -n monitoring svc/jaeger 16686:16686
```

### Auto-Scaling Configuration

```yaml
# HorizontalPodAutoscaler
autoscaling:
  enabled: true
  min_replicas: 3
  max_replicas: 50
  target_cpu_utilization: 70
  target_memory_utilization: 80
  scale_down_behavior:
    stabilization_window: 300s
```

## Multi-Region Deployment

### Primary Region (US-EAST-1)
```bash
# Create primary cluster
eksctl create cluster --name astra-gateway-us-east-1

# Deploy Astra Gateway
helm install astra-gateway astra/astra-gateway \
  --namespace astra \
  --values infrastructure/helm/values-us-east.yaml
```

### Secondary Region (EU-WEST-1)
```bash
# Create secondary cluster
eksctl create cluster --name astra-gateway-eu-west-1

# Deploy Astra Gateway
helm install astra-gateway astra/astra-gateway \
  --namespace astra \
  --values infrastructure/helm/values-eu-west.yaml

# Enable cross-region replication
kubectl apply -f infrastructure/k8s/cross-region-replication.yaml
```

### Global Load Balancing
```bash
# Configure Route 53 health checks (AWS)
aws route53 create-health-check \
  --health-check-config IPAddress=us-east-1-lb-ip

# Create weighted routing policy
# api.astragateway.io -> 50% us-east, 50% eu-west
```

## Disaster Recovery

### Backup Strategy

```bash
# Automated database backups
# PostgreSQL: WAL archiving to S3 every 5 minutes
# Retention: 30 days

# Redis snapshot: Daily backup to S3
# ClickHouse: Replicated tables across regions

# Backup verification job runs hourly
kubectl apply -f infrastructure/k8s/backup-verification-job.yaml
```

### Recovery Procedure

```bash
# 1. Restore database from backup
BACKUP_ID=latest
aws rds restore-db-instance-from-db-snapshot \
  --db-instance-identifier astra-gateway-restored \
  --db-snapshot-identifier arn:aws:rds:$REGION:$ACCOUNT:snapshot:$BACKUP_ID

# 2. Update connection string
kubectl patch secret astra-secrets \
  -p '{"data":{"DATABASE_URL":"postgresql://restored-db:5432/astra"}}'

# 3. Restart services
kubectl rollout restart deployment/gateway-service -n astra

# 4. Verify health
kubectl run -it --image=curlimages/curl curl-test \
  -- curl http://gateway-service:8080/health
```

## Troubleshooting

### Pod not starting

```bash
# Check pod status
kubectl describe pod gateway-service-xxxx -n astra

# Check logs
kubectl logs gateway-service-xxxx -n astra
kubectl logs gateway-service-xxxx -n astra --previous  # For crashed pods

# Check resource availability
kubectl top nodes
kubectl describe node <node-name>
```

### Database connection issues

```bash
# Test PostgreSQL connectivity
kubectl exec -it postgres-0 -n astra -- psql -U astra -d astra

# Check connection pool
SELECT datname, count(*) FROM pg_stat_activity GROUP BY datname;

# Kill idle connections if needed
SELECT pg_terminate_backend(pid) FROM pg_stat_activity 
  WHERE usename = 'astra' AND state = 'idle';
```

### Memory leaks

```bash
# Monitor memory usage
kubectl top pods -n astra --containers

# Get memory profile
kubectl exec -it gateway-service-xxxx -n astra -- \
  curl localhost:6060/debug/pprof/heap > heap.prof

# Analyze with pprof
go tool pprof heap.prof
```

## Cleanup

```bash
# Delete Astra Gateway
helm uninstall astra-gateway -n astra

# Delete infrastructure
kubectl delete namespace astra

# Delete cloud infrastructure
terraform destroy -auto-approve

# Confirm deletion
kubectl get namespaces
```

## References

- [Kubernetes Documentation](https://kubernetes.io/docs/)
- [Helm Documentation](https://helm.sh/docs/)
- [AWS EKS](https://docs.aws.amazon.com/eks/)
- [Google GKE](https://cloud.google.com/kubernetes-engine/docs)
- [Azure AKS](https://docs.microsoft.com/en-us/azure/aks/)
