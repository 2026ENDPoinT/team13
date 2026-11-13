#!/usr/bin/env bash
# Hermes 운영 스크립트·systemd 유닛 설치. Hermes 에서 이 디렉터리(deploy/hermes/ops)를 받아 실행한다.
#   tar -C deploy/hermes -cf - ops | ssh hermes 'rm -rf /tmp/ops && tar -C /tmp -xf - && bash /tmp/ops/install.sh && rm -rf /tmp/ops'
# 스크립트는 ~/bin, 유닛은 /etc/systemd/system. 타이머를 켜고 설치만 한다(백업을 바로 돌리지는 않는다).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
scripts=(hermes-ops-watch hermes-db-backup hermes-file-backup docker-cache-prune)
timers=(hermes-ops-watch.timer hermes-db-backup.timer hermes-file-backup.timer docker-cache-prune.timer)

for s in "${scripts[@]}"; do
  case $s in hermes-ops-watch) python3 -m py_compile "$here/$s" ;; *) bash -n "$here/$s" ;; esac
  install -m 755 "$here/$s" ~/bin/"$s"
done
sudo -n install -m 644 "$here"/systemd/*.service "$here"/systemd/*.timer /etc/systemd/system/
sudo -n systemctl daemon-reload
sudo -n systemctl enable --now "${timers[@]}"
systemctl list-timers "${timers[@]}" --no-pager
