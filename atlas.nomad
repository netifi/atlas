job "metrics" {

  region = "us-west"
  datacenters = ["westus2-1"]
  type = "service"

  group "atlas" {
    count = 1

    task "atlas" {
      driver = "docker"

      logs {
        max_files     = 10
        max_file_size = 15
      }

      resources {
          cpu    = 1000
          memory = 2000
          network {
            mbits = 100
            port "http" {
              static = 7101
            }
          }
        }

      config {
        image = "netifi.azurecr.io/atlas/atlas-server"
        network_mode = "host"
        auth {
          username = "netifi"
          password = "xv06MJfkZA17wCmy1v9e7kanMtvTsg5+"
        }
      }

      env {
        ATLAS_SERVER_OPTS=<<EOF
          -DROUTER_HOST=edge.prd.netifi.io
          EOF
      }
    }
  }

}