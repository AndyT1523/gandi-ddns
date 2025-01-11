# Gandi DDNS Client 

A simple DDNS client designed for Gandi's v5 API written in the Spring Boot framework.

## Prerequisites

* OpenJDK 21
* Apache Maven

## Compiling

```bash
mvn clean package
```

## About

This program will compare your WAN IP to the resolved IP of the domain name and update if non-matching.

The IPs are compared on a schedule set to the TTL of the domain.

The domain IP is cached and used for two checks then re-resolved to prevent an infinite update loop.

### Limitations

Only works for IPv4.

## Usage & Configuration

### Application Properties

The application properties path is sourced from the environment variable `APP_ENV_PATH`, if this env is not set it will default to `/etc/gandi-ddns.properties`

The following properties are required:
> gandi.apikey=API key  
> target.hostname=full hostname

The configuration was externalized so the API key can be updated without restarting the .jar via `systemctl reload gandi-ddns`

### Domain TTL

In `DynamicDNSservice.java` adjust the following annotation `@Scheduled(fixedDelay = 180000)` to match the TTL in milliseconds.  
Adjust the class variable `DOMAIN_TTL` to match TTL in seconds.

### Notes

Currently only supports updating of subdomains. To work with a TLD you'll need to adjust how the `API_URL` String is built in the `updateProperties()` method.  
See https://api.gandi.net/docs/livedns/#put-v5-livedns-domains-fqdn-records-rrset_name-rrset_type for details.

## Installing

1. Create service user account

```bash
useradd gandi-ddns -r -s /sbin/nologin
```

2. Add systemd service file

```bash
cp gandi-ddns.service /etc/systemd/system
```

1. Reload systemd

```bash
systemctl daemon-reload
```

3. Add executable to /opt

```bash
mkdir -p /opt/gandi-ddns && cp gandi-ddns-0.0.1-SNAPSHOT.jar /opt/gandi-ddns
```

4. Enable and start service

```bash
systemctl enable --now gandi-ddns.service
```

## License

[![AGPLv3](https://www.gnu.org/graphics/agplv3-155x51.png)](https://www.gnu.org/licenses/agpl-3.0.html)
