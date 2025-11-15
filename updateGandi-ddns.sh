#!/bin/bash
set -x
SERVICE_DIR="/opt/gandi-ddns"

# this script assumes that the service user 'gandi-ddns' already exists
# and has ownership of the SERVICE_DIR

sudo systemctl stop gandi-ddns

sudo -u gandi-ddns /bin/sh <<EOF

    cd "$SERVICE_DIR" || exit 1
    git pull
    ./mvnw clean package

EOF

sudo systemctl enable --now gandi-ddns
