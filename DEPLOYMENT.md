# SoundBridge Platform - Production Deployment Guide

## Overview
This guide covers deploying SoundBridge to production using Azure Container Apps and Supabase.

## Prerequisites
- Azure subscription
- Docker installed locally
- GitHub account (for CI/CD)
- Supabase project with production database
- Spotify & YouTube API credentials

## Deployment Options

### Option 1: Local Docker Testing
```bash
# Build Docker image locally
docker build -t soundbridge-platform:latest .

# Run with docker-compose
docker-compose up -d
```

### Option 2: Azure Container Apps (Recommended)

#### Setup Azure Resources
```bash
# Create resource group
az group create --name soundbridge-rg --location eastus

# Deploy using Bicep template
az deployment group create \
  --resource-group soundbridge-rg \
  --template-file azure/deploy.bicep \
  --parameters \
    containerImage=ghcr.io/yourusername/soundbridge-platform:latest \
    supabaseDbUrl="jdbc:postgresql://..." \
    supabaseDbUser="postgres" \
    supabaseDbPassword="YOUR_PASSWORD" \
    spotifyClientId="YOUR_SPOTIFY_ID" \
    spotifyClientSecret="YOUR_SPOTIFY_SECRET" \
    youtubeApiKey="YOUR_YOUTUBE_KEY"
```

#### GitHub Actions CI/CD
1. Set GitHub secrets:
   - `AZURE_CREDENTIALS` - Azure service principal JSON
   - `AZURE_SUBSCRIPTION_ID` - Your Azure subscription ID
   - `SUPABASE_DB_URL` - Database connection string
   - `SUPABASE_DB_USER` - Database user
   - `SUPABASE_DB_PASSWORD` - Database password
   - `SPOTIFY_CLIENT_ID` - Spotify API ID
   - `SPOTIFY_CLIENT_SECRET` - Spotify API secret
   - `YOUTUBE_API_KEY` - YouTube API key

2. Push to `main` branch to trigger automatic deployment

### Option 3: Vercel (Frontend) + Azure/Supabase Backend

Vercel should host only the React frontend. The Spring Boot API should run on Azure (or another backend host).

#### Deploy Frontend to Vercel
1. Import the GitHub repo in Vercel.
2. Configure project:
  - Root Directory: `frontend`
  - Build Command: `npm run build`
  - Output Directory: `dist`
3. Add Vercel environment variable:
  - `VITE_API_URL=https://<backend-domain>`
4. Deploy.

#### Backend CORS for Vercel
Set backend env var to allow the Vercel frontend domain:

```env
CORS_ALLOWED_ORIGINS=https://<your-vercel-app>.vercel.app
```

For preview deployments, include multiple comma-separated origins.

### Option 4: Manual Deployment

#### Build JAR
```bash
mvn clean package
```

#### Deploy to App Service
```bash
az webapp up \
  --resource-group soundbridge-rg \
  --name soundbridge-platform \
  --runtime java:21-java21 \
  --plan soundbridge-plan
```

## Production Configuration

### Environment Variables
Ensure these are set in your production environment:

```env
# Server
SERVER_PORT=9000

# Database (Supabase)
SUPABASE_DB_URL=jdbc:postgresql://db.YOUR_REGION.supabase.co:5432/postgres?sslmode=require
SUPABASE_DB_USER=postgres
SUPABASE_DB_PASSWORD=YOUR_PASSWORD

# APIs
SPOTIFY_CLIENT_ID=YOUR_ID
SPOTIFY_CLIENT_SECRET=YOUR_SECRET
YOUTUBE_API_KEY=YOUR_KEY

# App Startup
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
SPRING_JPA_DATABASE=POSTGRESQL
```

### Database Setup
1. Create Supabase project
2. Run schema migrations
3. Enable Row Level Security (RLS) for data isolation
4. Configure backups and replication

### Monitoring
- Application Insights integrated
- Health check endpoint: `GET /health`
- Diagnostic endpoint: `GET /diagnose`
- Logs available in Azure Monitor

## Scaling Considerations

### Load Balancing
- Azure Container Apps auto-scaling (1-3 replicas)
- Database connection pooling via HikariCP

### Performance Tuning
- YouTube API search caching
- Job processing with async tasks
- Database query optimization

## Security
- Secrets stored in Azure Key Vault
- SSL/TLS encryption enforced
- Supabase Row Level Security enabled
- CORS configured for frontend domains
- API rate limiting recommended

## Monitoring & Alerts
```bash
# View logs
az containerapp logs show --name soundbridge-api --resource-group soundbridge-rg

# Set up alerts in Azure Monitor
az monitor metrics alert create \
  --resource-group soundbridge-rg \
  --name soundbridge-health \
  --scopes /subscriptions/YOUR_ID/resourceGroups/soundbridge-rg
```

## Troubleshooting

**Database Connection Failed**
- Verify SUPABASE_DB_URL format
- Check firewall rules in Supabase
- Ensure SSH connection enabled

**API Returns 500**
- Check Application Insights logs
- Verify environment variables set
- Review Supabase database connectivity

**Jobs Not Processing**
- Verify async task executor configured
- Check Spring Cloud Task setup
- Monitor job status via health endpoint

## Rollback
```bash
# Deploy previous version
az deployment group create \
  --resource-group soundbridge-rg \
  --template-file azure/deploy.bicep \
  --parameters containerImage=ghcr.io/yourusername/soundbridge-platform:PREVIOUS_TAG
```

## Cost Optimization
- Azure Container Apps: ~$17/month (1 vCPU)
- Supabase: ~$25/month (prod tier)
- Total: ~$42/month for production

## Support & Documentation
- Supabase Docs: https://supabase.com/docs
- Azure Container Apps: https://learn.microsoft.com/azure/container-apps
- Spring Boot: https://spring.io/projects/spring-boot
