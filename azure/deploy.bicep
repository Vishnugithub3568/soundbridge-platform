param location string = resourceGroup().location
param containerImage string
param supabaseDbUrl string
param supabaseDbUser string
@secure()
param supabaseDbPassword string
param spotifyClientId string
@secure()
param spotifyClientSecret string
@secure()
param youtubeApiKey string

var containerAppName = 'soundbridge-api'
var envName = 'soundbridge-env'
var appInsightsName = 'soundbridge-insights'

resource appInsights 'Microsoft.Insights/components@2020-02-02' = {
  name: appInsightsName
  location: location
  kind: 'web'
  properties: {
    Application_Type: 'web'
    publicNetworkAccessForIngestion: 'Enabled'
    publicNetworkAccessForQuery: 'Enabled'
  }
}

resource containerEnvironment 'Microsoft.App/managedEnvironments@2023-05-01' = {
  name: envName
  location: location
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: appInsights.properties.customerId
        sharedKey: appInsights.listKeys().primarySharedKey
      }
    }
  }
}

resource containerApp 'Microsoft.App/containerApps@2023-05-01' = {
  name: containerAppName
  location: location
  properties: {
    managedEnvironmentId: containerEnvironment.id
    template: {
      containers: [
        {
          name: 'soundbridge-api'
          image: containerImage
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
          env: [
            {
              name: 'SERVER_PORT'
              value: '9000'
            }
            {
              name: 'SUPABASE_DB_URL'
              value: supabaseDbUrl
            }
            {
              name: 'SUPABASE_DB_USER'
              value: supabaseDbUser
            }
            {
              name: 'SUPABASE_DB_PASSWORD'
              secureValue: supabaseDbPassword
            }
            {
              name: 'SPOTIFY_CLIENT_ID'
              value: spotifyClientId
            }
            {
              name: 'SPOTIFY_CLIENT_SECRET'
              secureValue: spotifyClientSecret
            }
            {
              name: 'YOUTUBE_API_KEY'
              secureValue: youtubeApiKey
            }
          ]
          probes: [
            {
              type: 'liveness'
              httpGet: {
                path: '/health'
                port: 9000
              }
              initialDelaySeconds: 30
              periodSeconds: 10
            }
          ]
        }
      ]
      scale: {
        minReplicas: 1
        maxReplicas: 3
      }
    }
    configuration: {
      ingress: {
        external: true
        targetPort: 9000
        trafficWeight: 100
      }
    }
  }
}

output containerAppUrl string = containerApp.properties.configuration.ingress.fqdn
