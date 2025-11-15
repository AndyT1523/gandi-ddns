# Gandi DDNS Client 

A simple DDNS client designed for Gandi's v5 API written in the Spring Boot framework.

## Prerequisites

* OpenJDK 21

## Compiling

```bash
./mvnw clean package
```

## About

This program will compare your WAN IP to the resolved IP of the domain name and update if non-matching.

The IPs are compared on a schedule set to the TTL of the domain.

The domain IP is cached and used for two checks then re-resolved to prevent an infinite update loop.

Built with native support for systemd using sd_notify() on Linux.  
If your Linux system does not use systemd, a PID file will be written to `/run/gandi-ddns.pid` by default.

### Limitations

Only works for IPv4.

No included OpenRC service script.

## Usage & Configuration

### Application Properties

The application properties path is sourced from the environment variable `APP_ENV_PATH`, if this env is not set it will default to `/etc/gandi-ddns.properties`

The following properties are required:
> gandi.apikey=API key  
> target.hostname=full hostname

The configuration was externalized so the API key can be updated without restarting the .jar with a `SIGHUP` via `systemctl reload gandi-ddns`

### Domain TTL

In `DynamicDNSservice.java` adjust the following annotation `@Scheduled(fixedDelay = 300000)` to match the TTL in milliseconds.  
Adjust the class variable `DOMAIN_TTL` to match TTL in seconds.

### Notes

Currently only supports updating of subdomains. To work with a TLD you'll need to adjust how the `API_URL` String is built in the `updateProperties()` method.  
See https://api.gandi.net/docs/livedns/#put-v5-livedns-domains-fqdn-records-rrset_name-rrset_type for details.

## Installing

- Create the service user account
- Change working directory to /opt
- Clone the Git repository
    - Set ownership of the repository to the service user
- Copy the systemd service file (`gandi-ddns.service`)
    - Set ownership of the systemd service file to root
    - Reload systemd
- Copy the update script to /opt (`updateGandi-ddns.sh`)
   - Set ownership to the current user
- Run the update script

```bash
sudo useradd gandi-ddns -r -m -s /sbin/nologin
cd /opt
sudo git clone https://github.com/AndyT1523/gandi-ddns
sudo chown -R gandi-ddns:gandi-ddns gandi-ddns
sudo cp gandi-ddns/gandi-ddns.service /etc/systemd/system
sudo chown root:root /etc/systemd/system/gandi-ddns.service
sudo systemctl daemon-reload
sudo cp gandi-ddns/updateGandi-ddns.sh /opt
sudo chown $USER:$USER /opt/updateGandi-ddns.sh
./updateGandi-ddns.sh
```

## Updating

See `updateGandi-ddns.sh` for details.

## License

[![AGPLv3](https://www.gnu.org/graphics/agplv3-155x51.png)](https://www.gnu.org/licenses/agpl-3.0.html)
