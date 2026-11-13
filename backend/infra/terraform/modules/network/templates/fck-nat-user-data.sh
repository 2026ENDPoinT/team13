#!/bin/sh
# fck-nat high-availability mode: attach the static ENI and the fixed EIP at boot.
: > /etc/fck-nat.conf
echo "eni_id=${eni_id}" >> /etc/fck-nat.conf
echo "eip_id=${eip_id}" >> /etc/fck-nat.conf

systemctl restart fck-nat.service
