#!/system/bin/sh
# Late verification and fail-safe reconciliation for a committed UPM mount.
# Match Manager's FLAG_MOUNT_MASTER shell: a private root-manager namespace
# can disappear with this service and leave the global APK target unmounted.
if [ "$(readlink /proc/self/ns/mnt)" != "$(readlink /proc/1/ns/mnt)" ]; then
  exec nsenter --mount=/proc/1/ns/mnt -- /system/bin/sh "$0" "$@"
fi
MODDIR=${0%/*}
state_file="$MODDIR/state.env"
log="$MODDIR/log.txt"
exec >>"$log" 2>&1

log_status() {
  echo "$(date +%s 2>/dev/null || echo 0) [service] $*"
}

run_boot_recovery_attempt() {
  /system/bin/sh "$MODDIR/service.sh" --urv-recovery-attempt "$recovery_deadline"
}

# Boot Binder services can fail transiently even after a readiness probe succeeds.
# Retry the complete, locked verification pass without launching Manager. Terminal
# incompatibility and active transactions stay with the existing recovery policy.
run_boot_recovery() {
  recovery_deadline=$(($(awk '{print int($1)}' /proc/uptime) + 600))
  while :; do
    [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] || return 0
    recovery_now="$(awk '{print int($1)}' /proc/uptime)" || return 1
    [ "$recovery_now" -lt "$recovery_deadline" ] || {
      log_status "Boot recovery retry deadline reached; leaving recovery for Manager"
      return 0
    }
    rm -f "$MODDIR/boot-result"
    run_boot_recovery_attempt
    [ -f "$MODDIR/boot-result" ] || {
      log_status "Boot recovery stopped before a safe state could be loaded"
      return 1
    }
    recovery_status="$(head -n 1 "$MODDIR/boot-result" 2>/dev/null)"
    case "$recovery_status" in
      VERIFIED|REPATCH_REQUIRED|VERIFY_FAILED|INCOMPLETE_TRANSACTION) return 0 ;;
    esac
    [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] || return 0
    recovery_now="$(awk '{print int($1)}' /proc/uptime)" || return 1
    [ "$recovery_now" -lt "$recovery_deadline" ] || {
      log_status "Boot recovery retry deadline reached; leaving recovery for Manager"
      return 0
    }
    log_status "Temporary boot recovery failure; retrying independently of Manager"
    sleep 5
  done
}

if [ "${1:-}" != --urv-recovery-attempt ]; then
  run_boot_recovery
  exit $?
fi
boot_recovery_deadline="${2:-0}"
case "$boot_recovery_deadline" in ''|*[!0-9]*) exit 1 ;; esac
rm -f "$MODDIR/boot-result"

disable_module() {
  : >"$MODDIR/disable" || return 1
  sync
}

mountinfo_root_alias() {
  case "$1" in
    /data/*) printf '%s\n' "${1#/data}" ;;
    *) printf '%s\n' "$1" ;;
  esac
}

known_mount_sources() {
  rollback_path="${MODDIR%/*}/.$URV_PACKAGE-revanced.urv-rollback/$URV_PACKAGE.apk"
  backup_path="/data/adb/urv/transactions/$URV_PACKAGE/backup/module/$URV_PACKAGE.apk"
  rollback_shadow_path="${MODDIR%/*}/.$URV_PACKAGE-revanced.urv-rollback/$URV_PACKAGE-stock.apk"
  backup_shadow_path="/data/adb/urv/transactions/$URV_PACKAGE/backup/module/$URV_PACKAGE-stock.apk"
  legacy_path="/data/adb/revanced/$URV_PACKAGE/$URV_PACKAGE.apk"
  for source in "$URV_PATCHED_PATH" "$URV_STOCK_SHADOW_PATH" "$rollback_path" "$backup_path" \
      "$rollback_shadow_path" "$backup_shadow_path" "$legacy_path"; do
    [ -n "$source" ] || continue
    printf '%s\n' "$source"
    root_alias="$(mountinfo_root_alias "$source")" || return 1
    [ "$root_alias" = "$source" ] || printf '%s\n' "$root_alias"
  done
}

installed_user_ids() {
  user_dump="$(timeout 10 pm list users 2>/dev/null)" || return 1
  users="$(printf '%s\n' "$user_dump" |
    sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p')" || return 1
  [ -n "$users" ] || return 1
  for user in $users; do
    packages="$(timeout 10 pm list packages --user "$user" "$URV_PACKAGE" 2>/dev/null)" || return 1
    if printf '%s\n' "$packages" | grep -Fx "package:$URV_PACKAGE" >/dev/null; then
      echo "$user"
    fi
  done
}

target_is_mounted() {
  awk -v target="$URV_STOCK_PATH" '$5 == target { found=1 } END { exit !found }' /proc/self/mountinfo
}

target_mount_counts() {
  # Toybox awk rejects literal newlines in -v values.
  allowed_sources="$(known_mount_sources)" || return 1
  URV_ALLOWED_SOURCES="$allowed_sources" awk -v target="$URV_STOCK_PATH" '
    BEGIN { count=split(ENVIRON["URV_ALLOWED_SOURCES"], candidates, "\n") }
    $5 == target {
      total++
      separator=0
      for (i=6; i<=NF; i++) if ($i == "-") { separator=i; break }
      root=$4
      source=(separator ? $(separator+2) : "")
      sub(/\\040\(deleted\)$/, "", root)
      sub(/\\040\(deleted\)$/, "", source)
      sub(/ \(deleted\)$/, "", root)
      sub(/ \(deleted\)$/, "", source)
      for (candidate=1; candidate<=count; candidate++) {
        if (root == candidates[candidate] || source == candidates[candidate]) owned=1
      }
      if (owned) urv++
      owned=0
    }
    END { print total+0 ":" urv+0 }
  ' /proc/self/mountinfo
}

target_matches_urv_inode() {
  target_inode="$(stat -c '%d:%i' "$URV_STOCK_PATH" 2>/dev/null)"
  [ -n "$target_inode" ] || return 1
  rollback_path="${MODDIR%/*}/.$URV_PACKAGE-revanced.urv-rollback/$URV_PACKAGE.apk"
  backup_path="/data/adb/urv/transactions/$URV_PACKAGE/backup/module/$URV_PACKAGE.apk"
  rollback_shadow_path="${MODDIR%/*}/.$URV_PACKAGE-revanced.urv-rollback/$URV_PACKAGE-stock.apk"
  backup_shadow_path="/data/adb/urv/transactions/$URV_PACKAGE/backup/module/$URV_PACKAGE-stock.apk"
  legacy_path="/data/adb/revanced/$URV_PACKAGE/$URV_PACKAGE.apk"
  for source in "$URV_PATCHED_PATH" "$URV_STOCK_SHADOW_PATH" "$rollback_path" "$backup_path" \
      "$rollback_shadow_path" "$backup_shadow_path" "$legacy_path"; do
    [ -f "$source" ] || continue
    [ "$(stat -c '%d:%i' "$source" 2>/dev/null)" = "$target_inode" ] && return 0
  done
  return 1
}

target_matches_patched_inode() {
  patched_inode="$(stat -c '%d:%i' "$URV_PATCHED_PATH" 2>/dev/null)" || return 1
  target_inode="$(stat -c '%d:%i' "$URV_STOCK_PATH" 2>/dev/null)" || return 1
  [ -n "$patched_inode" ] && [ "$patched_inode" = "$target_inode" ]
}

target_payload_layer_counts() {
  patched_root="$(mountinfo_root_alias "$URV_PATCHED_PATH")" || return 1
  shadow_root="$(mountinfo_root_alias "$URV_STOCK_SHADOW_PATH")" || return 1
  awk -v target="$URV_STOCK_PATH" \
    -v patched="$URV_PATCHED_PATH" -v patched_root="$patched_root" \
    -v shadow="$URV_STOCK_SHADOW_PATH" -v shadow_root="$shadow_root" '
      $5 == target {
        separator=0
        for (i=6; i<=NF; i++) if ($i == "-") { separator=i; break }
        root=$4
        source=(separator ? $(separator+2) : "")
        sub(/\\040\(deleted\)$/, "", root)
        sub(/\\040\(deleted\)$/, "", source)
        sub(/ \(deleted\)$/, "", root)
        sub(/ \(deleted\)$/, "", source)
        if (root == patched || root == patched_root || source == patched || source == patched_root) patched_count++
        if (shadow != "" && (root == shadow || root == shadow_root || source == shadow || source == shadow_root)) shadow_count++
      }
      END { print patched_count+0 ":" shadow_count+0 }
    ' /proc/self/mountinfo
}

root_mount_layout_valid() {
  ownership="$(target_mount_counts)" || return 1
  total_mounts="${ownership%%:*}"
  urv_mounts="${ownership##*:}"
  required_mounts=1
  [ "$URV_PRESERVE_STOCK" = 1 ] && required_mounts=2
  [ "$total_mounts" -ge "$required_mounts" ] || return 1
  [ "$total_mounts" -le 8 ] || return 1
  [ "$total_mounts" = "$urv_mounts" ] || return 1
  payload_counts="$(target_payload_layer_counts)" || return 1
  patched_layers="${payload_counts%%:*}"
  shadow_layers="${payload_counts##*:}"
  [ "$patched_layers" -ge 1 ] || return 1
  if [ "$URV_PRESERVE_STOCK" = 1 ]; then
    [ "$shadow_layers" -ge 1 ] || return 1
  fi
  target_matches_patched_inode
}

remove_target_mounts() {
  remove_zygote_payload_mounts || return 1
  attempts=0
  while target_is_mounted; do
    ownership="$(target_mount_counts)" || return 1
    total_mounts="${ownership%%:*}"
    urv_mounts="${ownership##*:}"
    [ "$total_mounts" = 1 ] && target_matches_urv_inode && urv_mounts=1
    [ "$total_mounts" = "$urv_mounts" ] || return 1
    [ "$attempts" -lt 16 ] || return 1
    if ! umount "$URV_STOCK_PATH"; then
      # Re-check the visible stack before lazy detach. Another root tool can race
      # UPM even while UPM's own package lock is held.
      ownership="$(target_mount_counts)" || return 1
      total_mounts="${ownership%%:*}"
      urv_mounts="${ownership##*:}"
      [ "$total_mounts" = 1 ] && target_matches_urv_inode && urv_mounts=1
      if [ "$total_mounts" -gt 0 ]; then
        [ "$total_mounts" = "$urv_mounts" ] || return 1
        umount -l "$URV_STOCK_PATH" || return 1
      fi
    fi
    attempts=$((attempts + 1))
  done
}

remove_failed_urv_mounts() {
  remove_zygote_payload_mounts || return 1
  ownership="$(target_mount_counts)" || return 1
  total_mounts="${ownership%%:*}"
  urv_mounts="${ownership##*:}"
  [ "$total_mounts" -gt 0 ] || return 0
  if [ "$total_mounts" = "$urv_mounts" ]; then
    remove_target_mounts || return 1
  elif target_matches_urv_inode; then
    # The UPM layer is currently on top, so peeling exactly one layer cannot remove a foreign mount.
    if ! umount "$URV_STOCK_PATH" && target_is_mounted; then
      target_matches_urv_inode || return 1
      umount -l "$URV_STOCK_PATH" || return 1
    fi
  fi
  ownership="$(target_mount_counts)" || return 1
  [ "${ownership##*:}" = 0 ]
}

stop_and_wait() {
  installed_users="$(installed_user_ids)" || return 1
  stop_package_uids=''
  for stop_user in $installed_users; do
    stop_uid_dump="$(timeout 10 pm list packages --user "$stop_user" -U "$URV_PACKAGE" 2>/dev/null)" || return 1
    stop_user_uid="$(printf '%s\n' "$stop_uid_dump" | awk -v pkg="package:$URV_PACKAGE" '
      $1 == pkg { for (i=2; i<=NF; i++) if ($i ~ /^uid:[0-9]+$/) { sub(/^uid:/, "", $i); print $i } }
    ')" || return 1
    case "$stop_user_uid" in ''|*[!0-9]*) return 1 ;; esac
    stop_package_uids="$stop_package_uids $stop_user_uid"
  done
  stop_started="$(awk '{print int($1)}' /proc/uptime)" || return 1
  stop_attempted=0
  while :; do
    process_list="$(timeout 2 /system/bin/ps -A -o UID,ARGS 2>/dev/null)" || return 1
    # Custom manifest process names need UID matching; retain name matching for isolated children.
    package_processes="$(printf '%s\n' "$process_list" | awk -v pkg="$URV_PACKAGE" -v uids="$stop_package_uids" '
      BEGIN { count=split(uids, ids, " "); for (i=1; i<=count; i++) owned[ids[i]]=1 }
      $1 in owned || $2 == pkg || index($2, pkg ":") == 1 { print }
    ')" || return 1
    [ -n "$package_processes" ] || [ "$stop_attempted" = 0 ] || return 0
    if [ -z "$installed_users" ]; then
      [ -z "$package_processes" ]
      return $?
    fi
    stop_now="$(awk '{print int($1)}' /proc/uptime)" || return 1
    stop_remaining=$((15 - stop_now + stop_started))
    [ "$stop_remaining" -gt 0 ] || return 1
    # Attempt force-stop even when idle to suppress component restarts during mounting.
    stop_attempted=1
    for installed_user in $installed_users; do
      # HyperOS can reject ActivityManager Binder calls while boot is settling.
      # Check the actual processes and retry within one deadline, even if am fails.
      timeout "$stop_remaining" am force-stop --user "$installed_user" "$URV_PACKAGE" ||
        log_status "ActivityManager stop failed; checking package processes before retry"
      stop_now="$(awk '{print int($1)}' /proc/uptime)" || return 1
      stop_remaining=$((15 - stop_now + stop_started))
      [ "$stop_remaining" -gt 0 ] || break
    done
    sleep 0.2
  done
}

zygote_pids() {
  { pidof zygote64 2>/dev/null || true; pidof zygote 2>/dev/null || true; } |
    tr ' ' '\n' | awk '/^[0-9]+$/ && $0 > 1' | sort -n -u
}

validate_zygote() {
  pid="$1"
  cmdline="$(cat "/proc/$pid/cmdline" 2>/dev/null)" || return 1
  case "$cmdline" in
    zygote|zygote64|zygote\ *|zygote64\ *|*--zygote*|*--nice-name=zygote*) return 0 ;;
  esac
  return 1
}

live_zygote_pids() {
  for pid in $(zygote_pids); do
    validate_zygote "$pid" && echo "$pid"
  done | paste -sd ' ' -
}

namespace_mount_ownership() {
  pid="$1"
  allowed_sources="$(known_mount_sources)" || return 1
  URV_ALLOWED_SOURCES="$allowed_sources" nsenter --mount="/proc/$pid/ns/mnt" -- awk \
    -v target="$URV_STOCK_PATH" '
      BEGIN { count=split(ENVIRON["URV_ALLOWED_SOURCES"], candidates, "\n") }
      $5 == target {
        total++
        separator=0
        for (i=1; i<=NF; i++) if ($i == "-") { separator=i; break }
        root=$4
        source=(separator > 0 ? $(separator+2) : "")
        matched=0
        for (candidate=1; candidate<=count; candidate++) {
          if (root == candidates[candidate] || source == candidates[candidate]) {
            matched=1
            break
          }
        }
        if (matched) owned++
      }
      END { print total+0 ":" owned+0 }
    ' /proc/self/mountinfo
}

namespace_matches_payload() {
  pid="$1"
  namespace_splits_match "$pid" || return 1
  ownership="$(namespace_mount_ownership "$pid")" || return 1
  expected_ownership="1:1"
  [ "$URV_PRESERVE_STOCK" = 1 ] && expected_ownership="2:2"
  [ "$ownership" = "$expected_ownership" ] || return 1
  source_inode="$(stat -c '%d:%i' "$URV_PATCHED_PATH" 2>/dev/null)" || return 1
  target_inode="$(nsenter --mount="/proc/$pid/ns/mnt" -- stat -c '%d:%i' "$URV_STOCK_PATH" 2>/dev/null)" || return 1
  [ "$source_inode" = "$target_inode" ] || return 1
  mounted_hash="$(nsenter --mount="/proc/$pid/ns/mnt" -- sha256sum "$URV_STOCK_PATH" 2>/dev/null | awk '{print $1}')" || return 1
  [ "$mounted_hash" = "$URV_PATCHED_SHA256" ]
}

namespace_matches_shadow() {
  pid="$1"
  [ "$URV_PRESERVE_STOCK" = 1 ] || return 1
  ownership="$(namespace_mount_ownership "$pid")" || return 1
  [ "$ownership" = "1:1" ] || return 1
  source_inode="$(stat -c '%d:%i' "$URV_STOCK_SHADOW_PATH" 2>/dev/null)" || return 1
  target_inode="$(nsenter --mount="/proc/$pid/ns/mnt" -- stat -c '%d:%i' "$URV_STOCK_PATH" 2>/dev/null)" || return 1
  [ "$source_inode" = "$target_inode" ]
}

namespace_target_is_mounted() {
  pid="$1"
  nsenter --mount="/proc/$pid/ns/mnt" -- awk -v target="$URV_STOCK_PATH" \
    '$5 == target { found=1 } END { exit !found }' /proc/self/mountinfo
}

namespace_target_clear() {
  ! namespace_target_is_mounted "$1"
}

namespace_visible_matches_urv_inode() {
  pid="$1"
  target_inode="$(nsenter --mount="/proc/$pid/ns/mnt" -- stat -c '%d:%i' "$URV_STOCK_PATH" 2>/dev/null)" || return 1
  rollback_path="${MODDIR%/*}/.$URV_PACKAGE-revanced.urv-rollback/$URV_PACKAGE.apk"
  backup_path="/data/adb/urv/transactions/$URV_PACKAGE/backup/module/$URV_PACKAGE.apk"
  rollback_shadow_path="${MODDIR%/*}/.$URV_PACKAGE-revanced.urv-rollback/$URV_PACKAGE-stock.apk"
  backup_shadow_path="/data/adb/urv/transactions/$URV_PACKAGE/backup/module/$URV_PACKAGE-stock.apk"
  legacy_path="/data/adb/revanced/$URV_PACKAGE/$URV_PACKAGE.apk"
  for source in "$URV_PATCHED_PATH" "$URV_STOCK_SHADOW_PATH" "$rollback_path" "$backup_path" \
      "$rollback_shadow_path" "$backup_shadow_path" "$legacy_path"; do
    [ -f "$source" ] || continue
    [ "$(stat -c '%d:%i' "$source" 2>/dev/null)" = "$target_inode" ] && return 0
  done
  return 1
}

namespace_has_urv_layer() {
  pid="$1"
  allowed_sources="$(known_mount_sources)" || return 1
  URV_ALLOWED_SOURCES="$allowed_sources" nsenter --mount="/proc/$pid/ns/mnt" -- awk -v target="$URV_STOCK_PATH" '
    BEGIN { count=split(ENVIRON["URV_ALLOWED_SOURCES"], candidates, "\n") }
    $5 == target {
      separator=0
      for (i=1; i<=NF; i++) if ($i == "-") { separator=i; break }
      root=$4
      source=(separator > 0 ? $(separator+2) : "")
      sub(/\\040\(deleted\)$/, "", root)
      sub(/\\040\(deleted\)$/, "", source)
      sub(/ \(deleted\)$/, "", root)
      sub(/ \(deleted\)$/, "", source)
      for (candidate=1; candidate<=count; candidate++) {
        if (root == candidates[candidate] || source == candidates[candidate]) owned=1
      }
    }
    END { exit !owned }
  ' /proc/self/mountinfo
}

mount_and_verify_zygotes() {
  preflight_stable=0
  preflight_attempt=0
  while [ "$preflight_attempt" -lt 20 ]; do
    before="$(live_zygote_pids)"
    if [ -z "$before" ]; then
      preflight_attempt=$((preflight_attempt + 1))
      sleep 0.1
      continue
    fi
    zygote_changed=0
    # Check every namespace before changing any of them. This prevents UPM from
    # temporarily covering a mount owned by another root tool.
    for pid in $before; do
      validate_zygote "$pid" || { zygote_changed=1; continue; }
      namespace_splits_match "$pid" || return 1
      if ! namespace_matches_payload "$pid" &&
         ! namespace_matches_shadow "$pid" &&
         ! namespace_target_clear "$pid"; then
        validate_zygote "$pid" || { zygote_changed=1; continue; }
        return 1
      fi
    done
    after="$(live_zygote_pids)"
    if [ "$zygote_changed" = 0 ] && [ "$before" = "$after" ]; then
      preflight_stable=1
      break
    fi
    preflight_attempt=$((preflight_attempt + 1))
    sleep 0.1
  done
  [ "$preflight_stable" = 1 ] || return 1

  mount_stable=0
  mount_attempt=0
  while [ "$mount_attempt" -lt 20 ]; do
    pids="$(live_zygote_pids)"
    if [ -z "$pids" ]; then
      mount_attempt=$((mount_attempt + 1))
      sleep 0.1
      continue
    fi
    zygote_changed=0
    for pid in $pids; do
      validate_zygote "$pid" || { zygote_changed=1; continue; }
      if ! namespace_matches_payload "$pid"; then
        shadow_ready=0
        if [ "$URV_PRESERVE_STOCK" = 1 ] && namespace_matches_shadow "$pid"; then
          shadow_ready=1
        elif namespace_target_clear "$pid"; then
          if [ "$URV_PRESERVE_STOCK" = 1 ]; then
            if ! nsenter --mount="/proc/$pid/ns/mnt" -- mount -o bind "$URV_STOCK_SHADOW_PATH" "$URV_STOCK_PATH"; then
              validate_zygote "$pid" || { zygote_changed=1; continue; }
              return 1
            fi
            shadow_ready=1
          fi
        else
          validate_zygote "$pid" || { zygote_changed=1; continue; }
          return 1
        fi
        if [ "$shadow_ready" = 1 ] &&
           ! nsenter --mount="/proc/$pid/ns/mnt" -- mount --make-private "$URV_STOCK_PATH" 2>/dev/null &&
           ! nsenter --mount="/proc/$pid/ns/mnt" -- mount -o private none "$URV_STOCK_PATH"; then
          validate_zygote "$pid" || { zygote_changed=1; continue; }
          return 1
        fi
        if ! nsenter --mount="/proc/$pid/ns/mnt" -- mount -o bind "$URV_PATCHED_PATH" "$URV_STOCK_PATH"; then
          validate_zygote "$pid" || { zygote_changed=1; continue; }
          return 1
        fi
      fi
      if ! namespace_matches_payload "$pid"; then
        validate_zygote "$pid" || { zygote_changed=1; continue; }
        return 1
      fi
    done
    after="$(live_zygote_pids)"
    if [ "$zygote_changed" = 0 ] && [ "$pids" = "$after" ]; then
      mount_stable=1
      break
    fi
    mount_attempt=$((mount_attempt + 1))
    sleep 0.1
  done
  [ "$mount_stable" = 1 ]
}

remove_zygote_payload_mounts() {
  for pid in $(zygote_pids); do
    validate_zygote "$pid" || continue
    [ "$(readlink /proc/self/ns/mnt)" != "$(readlink /proc/$pid/ns/mnt)" ] || continue
    attempts=0
    while namespace_target_is_mounted "$pid"; do
      if namespace_visible_matches_urv_inode "$pid"; then
        [ "$attempts" -lt 16 ] || return 1
        if ! nsenter --mount="/proc/$pid/ns/mnt" -- umount "$URV_STOCK_PATH" &&
           namespace_target_is_mounted "$pid"; then
          # Match Manager cleanup semantics: prove the visible layer is still UPM-owned
          # immediately before using lazy detach.
          namespace_visible_matches_urv_inode "$pid" || return 1
          nsenter --mount="/proc/$pid/ns/mnt" -- umount -l "$URV_STOCK_PATH" || return 1
        fi
        attempts=$((attempts + 1))
      elif namespace_has_urv_layer "$pid"; then
        # A foreign layer hides a UPM layer. Do not peel another owner's mount.
        return 1
      else
        # A foreign-only mount is unrelated to UPM and must remain untouched.
        break
      fi
    done
    namespace_has_urv_layer "$pid" && return 1
  done
  return 0
}

load_state() {
  [ -f "$state_file" ] || { log_status "Missing committed state; leaving stock active"; return 1; }
  [ "$(stat -c %a "$state_file" 2>/dev/null)" = 600 ] || {
    log_status "Unsafe state permissions; leaving stock active"
    return 1
  }
  [ "$(stat -c %u:%g "$state_file" 2>/dev/null)" = 0:0 ] || {
    log_status "Unsafe state ownership; leaving stock active"
    return 1
  }
  # state.env is generated by UPM with single-quoted values and mode 0600.
  URV_STOCK_SPLITS=''
  . "$state_file"
  [ "$URV_STATE_VERSION" = 1 ] || { log_status "Unsupported state version"; return 1; }
}

load_state || exit 0
locked_package="$URV_PACKAGE"

lock_dir="/data/adb/urv/locks"
lock_path="$lock_dir/$URV_PACKAGE.lock.d"
lock_owner="$lock_path/owner"
transaction_dir="/data/adb/urv/transactions/$URV_PACKAGE"
boot_id="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
boot_started_epoch="$(date +%s 2>/dev/null || echo 0)"
boot_started_uptime="$(awk '{print int($1)}' /proc/uptime)"
mkdir -p "$transaction_dir" || exit 0

write_boot_status() {
  status_epoch="$(date +%s 2>/dev/null || echo 0)"
  status_elapsed_ms="$(awk '{printf "%.0f", $1 * 1000}' /proc/uptime)" || return 1
  status_uptime=$((status_elapsed_ms / 1000))
  status_temp="$transaction_dir/boot-status.$$.tmp"
  (
    umask 077
    {
      printf '%s\n' "$1"
      printf 'boot_id=%s\nsource=service\ntransaction_id=%s\n' "$boot_id" "$URV_TRANSACTION_ID"
      printf 'started_epoch_seconds=%s\nupdated_epoch_seconds=%s\nelapsed_seconds=%s\n' \
        "$boot_started_epoch" "$status_epoch" "$((status_uptime - boot_started_uptime))"
    } >"$status_temp" && mv -f "$status_temp" "$transaction_dir/boot-status"
  ) || {
    rm -f "$status_temp"
    log_status "Unable to record boot recovery status: $1"
    return 1
  }
  boot_status="$1"
  log_status "Boot recovery status: $boot_status; elapsed $((status_uptime - boot_started_uptime)) seconds"
}

wait_for_feedback_user() {
  feedback_started="$(awk '{print int($1)}' /proc/uptime 2>/dev/null)"
  case "$feedback_started" in ''|*[!0-9]*) return 0 ;; esac
  while :; do
    feedback_user_state="$(timeout 5 am get-started-user-state "$URV_USER_ID" 2>/dev/null)" || return 0
    case "$feedback_user_state" in
      RUNNING_LOCKED|RUNNING_UNLOCKING) ;;
      *) return 0 ;;
    esac
    feedback_now="$(awk '{print int($1)}' /proc/uptime 2>/dev/null)"
    case "$feedback_now" in ''|*[!0-9]*) return 0 ;; esac
    [ "$((feedback_now - feedback_started))" -lt 300 ] || return 1
    sleep 2
  done
}

notify_boot_result() {
  case "$boot_status" in
    VERIFIED|REPAIR_REQUIRED|REPATCH_REQUIRED|VERIFY_FAILED) ;;
    *) return 0 ;;
  esac
  # The receiver uses a system text toast, so Manager's UI need not be open.
  # Delivery is best-effort and happens after releasing the package lock.
  # The receiver and Manager's ordinary storage are unavailable before user unlock.
  if ! wait_for_feedback_user; then
    log_status "Unable to deliver automatic remount feedback: Android user is still locked"
    return 0
  fi
  timeout 15 am broadcast --user "$URV_USER_ID" --receiver-include-background \
    -n "__MANAGER_PACKAGE__/app.universal.patch.manager.receiver.RootMountResultReceiver" \
    -a app.universal.patch.manager.action.ROOT_MOUNT_RESULT \
    --es package "$URV_PACKAGE" --es result "$boot_status" \
    --el completed_at "${status_elapsed_ms:-0}" >/dev/null 2>&1 ||
    log_status "Unable to deliver automatic remount feedback"
}

finish_boot_service() {
  case "$boot_status" in
    WAITING_*|VERIFYING|MOUNTING) write_boot_status DEFERRED ;;
  esac
  # Publish the final pass result, not an early verification checkpoint.
  (umask 077; printf '%s\n' "$boot_status" >"$MODDIR/boot-result") ||
    log_status "Unable to record the boot recovery pass result"
  notify_boot_result
}

write_boot_status WAITING_FOR_PACKAGE_MANAGER
trap finish_boot_service EXIT
# service.sh runs during late_start. Positive package identity and ownership checks
# can succeed before sys.boot_completed; do not add the rest of boot to mount time.
log_status "Checking PackageManager readiness and committed package ownership"
canonical_dir="$transaction_dir/backup/payload"
canonical_patched="$canonical_dir/patched"
canonical_stock="$canonical_dir/stock"
boot_pid=$$
boot_start="$(awk '{print $22}' /proc/$boot_pid/stat 2>/dev/null)"
[ -n "$boot_start" ] || {
  log_status "Unable to read boot lock owner identity"
  exit 0
}
mkdir -p "/data/adb/urv" "$lock_dir" || {
  log_status "Unable to initialize root lock storage"
  exit 0
}
chmod 700 "/data/adb/urv" "$lock_dir" || {
  log_status "Unable to secure root lock storage"
  exit 0
}

lock_is_old() {
  lock_mtime="$(stat -c %Y "$lock_path" 2>/dev/null || echo 0)"
  now="$(date +%s 2>/dev/null || echo 0)"
  [ "$lock_mtime" -gt 0 ] 2>/dev/null || return 1
  [ "$now" -ge "$lock_mtime" ] 2>/dev/null || return 1
  [ "$((now - lock_mtime))" -ge 5 ]
}

write_lock_owner() {
  printf '%s\n%s\n%s\n' "$boot_pid" "$boot_start" boot >"$lock_owner" || return 1
  chmod 600 "$lock_owner"
}

try_acquire_package_lock() {
  mkdir "$lock_path" 2>/dev/null || return 1
  chmod 700 "$lock_path" || {
    rmdir "$lock_path" 2>/dev/null || true
    return 1
  }
  write_lock_owner || {
    rm -f "$lock_owner"
    rmdir "$lock_path" 2>/dev/null || true
    return 1
  }
}

remove_stale_package_lock() {
  saved_pid=''
  saved_start=''
  if [ -f "$lock_owner" ]; then
    saved_pid="$(sed -n '1p' "$lock_owner" 2>/dev/null)"
    saved_start="$(sed -n '2p' "$lock_owner" 2>/dev/null)"
    case "$saved_pid" in
      ''|*[!0-9]*) saved_pid='' ;;
    esac
    current_start=''
    [ -z "$saved_pid" ] || current_start="$(awk '{print $22}' /proc/$saved_pid/stat 2>/dev/null || true)"
    if [ -n "$saved_pid" ] && [ -n "$saved_start" ]; then
      if [ -z "$current_start" ] || [ "$current_start" != "$saved_start" ]; then
        rm -rf "$lock_path"
        return 0
      fi
      return 1
    fi
  fi
  lock_is_old || return 1
  rm -rf "$lock_path"
}

acquire_package_lock() {
  lock_waited=0
  while [ "$lock_waited" -lt 5 ]; do
    try_acquire_package_lock && return 0
    [ -d "$lock_path" ] || return 1
    remove_stale_package_lock && continue
    sleep 1
    lock_waited=$((lock_waited + 1))
  done
  return 1
}

release_package_lock() {
  [ -d "$lock_path" ] || return 0
  [ -f "$lock_owner" ] || return 1
  saved_pid="$(sed -n '1p' "$lock_owner")"
  saved_start="$(sed -n '2p' "$lock_owner")"
  saved_transaction="$(sed -n '3p' "$lock_owner")"
  [ "$saved_pid" = "$boot_pid" ] &&
    [ "$saved_start" = "$boot_start" ] &&
    [ "$saved_transaction" = boot ] || return 1
  rm -f "$lock_owner"
  rmdir "$lock_path"
}

if [ -f "$transaction_dir/active.json" ]; then
  log_status "Incomplete transaction present; deferring entirely to Manager recovery"
  write_boot_status INCOMPLETE_TRANSACTION
  exit 0
fi

split_set_matches() {
  if [ "${1:-}" = refresh ]; then
    path_dump="$(timeout 10 pm path --user "$URV_USER_ID" "$URV_PACKAGE" 2>/dev/null)" || return 1
    printf '%s\n' "$path_dump" | grep -Fx "package:$current_path" >/dev/null || return 1
    path_count="$(printf '%s\n' "$path_dump" | grep -c '^package:')"
  fi
  split_count=0
  seen_splits=' '
  for entry in ${URV_STOCK_SPLITS:-}; do
    split_name="${entry%%:*}"
    split_hash="${entry#*:}"
    printf '%s\n' "$split_name" | grep -Eq '^[A-Za-z0-9_][A-Za-z0-9_.-]*\.apk$' || return 1
    [ "$split_name" != base.apk ] || return 1
    printf '%s\n' "$split_hash" | grep -Eq '^[0-9a-f]{64}$' || return 1
    case "$seen_splits" in *" $split_name "*) return 1 ;; esac
    seen_splits="$seen_splits$split_name "
    split_path="${current_path%/*}/$split_name"
    printf '%s\n' "$path_dump" | grep -Fx "package:$split_path" >/dev/null || return 1
    actual_split_hash="$(timeout 60 sha256sum "$split_path" 2>/dev/null)" || return 1
    [ "${actual_split_hash%% *}" = "$split_hash" ] || return 1
    split_count=$((split_count + 1))
  done
  [ "$path_count" = "$((split_count + 1))" ] || return 1
  case "$URV_TOPOLOGY:$split_count" in
    SINGLE:0) return 0 ;;
    SPLIT:0) return 1 ;;
    SPLIT:*) return 0 ;;
    *) return 1 ;;
  esac
}

namespace_splits_match() {
  for entry in ${URV_STOCK_SPLITS:-}; do
    split_path="${URV_STOCK_PATH%/*}/${entry%%:*}"
    split_source_inode="$(stat -c '%d:%i' "$split_path" 2>/dev/null)" || return 1
    split_target_inode="$(nsenter --mount="/proc/$1/ns/mnt" -- stat -c '%d:%i' "$split_path" 2>/dev/null)" || return 1
    [ "$split_source_inode" = "$split_target_inode" ] || return 1
  done
  return 0
}

read_package_state() {
  installed_users="$(installed_user_ids)" || return 1
  # A successful ownership query can prove removal without querying an absent app.
  if ! echo "$installed_users" | grep -Fx "$URV_USER_ID" >/dev/null; then
    # Early PackageManager queries can see an incomplete package registry.
    [ "$(getprop sys.boot_completed 2>/dev/null)" = 1 ] || return 1
    return 0
  fi
  path_dump="$(timeout 10 pm path --user "$URV_USER_ID" "$URV_PACKAGE" 2>/dev/null)" || return 1
  current_path="$(printf '%s\n' "$path_dump" | awk -F/ '/^package:/ && $NF ~ /^base/ { sub(/^package:/, ""); print; exit }')"
  [ -n "$current_path" ] || current_path="$(printf '%s\n' "$path_dump" | sed -n 's/^package://p' | head -n 1)"
  [ -n "$current_path" ] || return 1
  path_count="$(echo "$path_dump" | grep -c '^package:')"
  split_compatible=0
  split_set_matches && split_compatible=1
  version_dump="$(timeout 10 dumpsys package "$URV_PACKAGE" 2>/dev/null)" || return 1
  current_version_name="$(echo "$version_dump" | sed -n 's/^[[:space:]]*versionName=//p' | head -n 1)"
  current_version_code="$(echo "$version_dump" | sed -n 's/^[[:space:]]*versionCode=\([0-9]*\).*/\1/p' | head -n 1)"
  [ -n "$current_version_name" ] && [ -n "$current_version_code" ] || return 1
  disabled_packages="$(timeout 10 pm list packages -d --user "$URV_USER_ID" "$URV_PACKAGE" 2>/dev/null)" || return 1
  if echo "$disabled_packages" | grep -Fx "package:$URV_PACKAGE" >/dev/null; then
    current_enabled=0
  else
    current_enabled=1
  fi
  if [ "$(getprop sys.boot_completed 2>/dev/null)" != 1 ]; then
    # Mount early only from a matching snapshot; defer negative conclusions until boot completes.
    [ "$current_path" = "$URV_STOCK_PATH" ] &&
      [ "$current_version_name" = "$URV_VERSION_NAME" ] &&
      [ "$current_version_code" = "$URV_VERSION_CODE" ] &&
      [ "$current_enabled" = "$URV_ENABLED" ] &&
      [ "$split_compatible" = 1 ] || return 1
  fi
}

wait_for_package_manager() {
  ready_started="${1:-$(awk '{print int($1)}' /proc/uptime)}" || return 1
  while :; do
    ready_now="$(awk '{print int($1)}' /proc/uptime)" || return 1
    [ "$((ready_now - ready_started))" -lt 300 ] || return 1
    [ "${boot_recovery_deadline:-0}" = 0 ] ||
      [ "$ready_now" -lt "$boot_recovery_deadline" ] || return 1
    [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] || return 1
    if [ -f "$transaction_dir/active.json" ]; then
      write_boot_status INCOMPLETE_TRANSACTION
      return 1
    fi
    if read_package_state && [ -n "$(live_zygote_pids)" ]; then
      return 0
    fi
    sleep 1
  done
}

acquire_ready_package_lock() {
  recovery_started="$(awk '{print int($1)}' /proc/uptime)" || return 1
  while wait_for_package_manager "$recovery_started"; do
    write_boot_status WAITING_FOR_LOCK
    acquire_package_lock || return 1
    boot_lock_held=1
    # Every acquisition needs fresh state: Manager may have acted between retries.
    [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] || return 1
    if [ -f "$transaction_dir/active.json" ]; then
      log_status "Transaction appeared before the boot lock was acquired; deferring to Manager"
      write_boot_status INCOMPLETE_TRANSACTION
      return 1
    fi
    load_state || return 1
    [ "$URV_PACKAGE" = "$locked_package" ] || return 1

    # Use only a fresh locked snapshot for mount decisions.
    read_package_state && return 0
    log_status "PackageManager became unavailable; releasing lock before retry"
    release_package_lock || return 1
    boot_lock_held=0
    write_boot_status WAITING_FOR_PACKAGE_MANAGER
    sleep 1
  done
  return 1
}

zygote_mounts_verified() {
  checked_zygote_pids="$(live_zygote_pids)" || return 1
  [ -n "$checked_zygote_pids" ] || return 1
  for checked_zygote_pid in $checked_zygote_pids; do
    validate_zygote "$checked_zygote_pid" &&
      namespace_matches_payload "$checked_zygote_pid" &&
      namespace_splits_match "$checked_zygote_pid" || return 1
  done
  [ "$checked_zygote_pids" = "$(live_zygote_pids)" ]
}

finish_verified_mount() {
  write_boot_status VERIFIED || return 1
  [ "$(getprop sys.boot_completed 2>/dev/null)" != 1 ] || return 0
  verified_transaction="$URV_TRANSACTION_ID"
  verified_state_hash="$(sha256sum "$state_file" 2>/dev/null)" || return 1
  release_package_lock || return 1
  boot_lock_held=0
  # Keep the early mount usable without holding up Manager operations. A Zygote
  # restarted during boot may need its own mount when boot completion is reported.
  final_started="$(awk '{print int($1)}' /proc/uptime)" || return 1
  while [ "$(getprop sys.boot_completed 2>/dev/null)" != 1 ]; do
    [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] &&
      [ ! -f "$transaction_dir/active.json" ] || { boot_status=DEFERRED; return 0; }
    [ "$(sha256sum "$state_file" 2>/dev/null)" = "$verified_state_hash" ] ||
      { boot_status=DEFERRED; return 0; }
    final_now="$(awk '{print int($1)}' /proc/uptime)" || return 1
    [ "${boot_recovery_deadline:-0}" = 0 ] ||
      [ "$final_now" -lt "$boot_recovery_deadline" ] || return 1
    [ "$((final_now - final_started))" -lt 300 ] || {
      log_status "Boot completion not reported; retrying verification of the early mount"
      return 1
    }
    sleep 1
  done
  acquire_package_lock || return 1
  boot_lock_held=1
  [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] &&
    [ ! -f "$transaction_dir/active.json" ] || { boot_status=DEFERRED; return 0; }
  [ "$(sha256sum "$state_file" 2>/dev/null)" = "$verified_state_hash" ] ||
    { boot_status=DEFERRED; return 0; }
  load_state || return 1
  [ "$URV_PACKAGE" = "$locked_package" ] &&
    [ "$URV_TRANSACTION_ID" = "$verified_transaction" ] || { boot_status=DEFERRED; return 0; }
  read_package_state &&
    [ "$installed_users" = "$URV_USER_ID" ] &&
    [ "$current_path" = "$URV_STOCK_PATH" ] &&
    [ "$current_version_name" = "$URV_VERSION_NAME" ] &&
    [ "$current_version_code" = "$URV_VERSION_CODE" ] &&
    [ "$current_enabled" = "$URV_ENABLED" ] &&
    root_mount_layout_valid &&
    split_set_matches refresh &&
    [ "$(sha256sum "$URV_STOCK_PATH" 2>/dev/null | awk '{print $1}')" = "$URV_PATCHED_SHA256" ] || return 1
  if [ "$URV_PRESERVE_STOCK" = 1 ]; then
    [ "$(sha256sum "$URV_STOCK_SHADOW_PATH" 2>/dev/null | awk '{print $1}')" = "$URV_STOCK_SHADOW_SHA256" ] || return 1
  fi
  # An unchanged Zygote already launches the patched app. Stop processes only
  # when namespace drift requires repair, so a healthy early launch stays open.
  if ! zygote_mounts_verified; then
    stop_and_wait && mount_and_verify_zygotes && stop_and_wait &&
      root_mount_layout_valid && split_set_matches refresh || return 1
  fi
  log_status "Boot completion root and Zygote mounts verified"
  write_boot_status VERIFIED
}

complete_verified_mount() {
  finish_verified_mount || {
    log_status "Boot completion verification deferred; another service pass is required"
    # Manager may own the lock now; do not overwrite its recovery checkpoint.
    if [ "$boot_lock_held" = 0 ]; then
      boot_status=DEFERRED
    else
      write_boot_status REPAIR_REQUIRED
    fi
  }
}

boot_lock_held=0
trap '[ "$boot_lock_held" = 0 ] || release_package_lock || log_status "Unable to release transaction lock cleanly"; finish_boot_service' EXIT
trap 'exit 0' HUP INT TERM
if ! acquire_ready_package_lock; then
  log_status "PackageManager not ready, transaction lock busy, or module changed; deferring verification"
  exit 0
fi
write_boot_status VERIFYING
if ! echo "$installed_users" | grep -Fx "$URV_USER_ID" >/dev/null; then
  log_status "Committed Android user no longer owns the package; removing UPM mounts"
  if ! stop_and_wait || ! remove_target_mounts; then
    log_status "Unable to remove the stale user mount safely"
    write_boot_status REPAIR_REQUIRED
  else
    write_boot_status REPATCH_REQUIRED
  fi
  disable_module || log_status "Unable to persist module disable marker"
  exit 0
fi
other_users="$(echo "$installed_users" | awk -v committed="$URV_USER_ID" '$1 != committed')"
if [ -n "$other_users" ]; then
  log_status "Package is installed for another Android user; removing UPM mounts"
  if ! stop_and_wait || ! remove_target_mounts; then
    log_status "Unable to remove the cross-user mount safely"
    write_boot_status REPAIR_REQUIRED
  else
    write_boot_status REPATCH_REQUIRED
  fi
  disable_module || log_status "Unable to persist module disable marker"
  exit 0
fi

expected_path_count=1
for entry in ${URV_STOCK_SPLITS:-}; do expected_path_count=$((expected_path_count + 1)); done

# Discard binds left by the two recognized ReVanced layouts, including private
# namespaces of processes started before the UPM boot mount. Leave mixed or
# unidentified stacks for the normal conflict checks.
clear_competing_mounts() (
  legacy="/adb/revanced/$URV_PACKAGE/$URV_PACKAGE.apk"
  other="/adb/modules/$URV_PACKAGE-ReVanced/$URV_PACKAGE.apk"
  ownership_check='
    $5 == target {
      total++
      root=$4
      sub(/\\040\(deleted\)$/, "", root)
      if (root == legacy || root == other || root == "/data" legacy || root == "/data" other) found++
    }
    END { exit !(found && found == total) }
  '
  for info in /proc/[0-9]*/mountinfo; do
    [ -r "$info" ] || continue
    awk -v target="$URV_STOCK_PATH" -v legacy="$legacy" -v other="$other" \
      "$ownership_check" "$info" 2>/dev/null || continue
    pid="${info#/proc/}"
    pid="${pid%/mountinfo}"
    # The first check only finds candidates. Confirm ownership in the namespace
    # where the unmount actually runs.
    detached="$(nsenter --mount="/proc/$pid/ns/mnt" -- sh -c '
      target="$1"
      legacy="$2"
      other="$3"
      ownership_check="$4"
      competing_only() {
        awk -v target="$target" -v legacy="$legacy" -v other="$other" \
          "$ownership_check" /proc/self/mountinfo
      }
      count=0
      while [ "$count" -lt 8 ] && competing_only; do
        umount "$target" || { competing_only && umount -l "$target"; } || break
        count=$((count + 1))
      done
      printf "%s" "$count"
    ' sh "$URV_STOCK_PATH" "$legacy" "$other" "$ownership_check" 2>/dev/null)" || continue
    [ "$detached" -eq 0 ] || log_status "Removed $detached competing ReVanced APK bind(s) from pid $pid"
  done
)
if [ "$current_path" = "$URV_STOCK_PATH" ]; then
  clear_competing_mounts
fi

mount_count="$(awk -v target="$URV_STOCK_PATH" '$5 == target { count++ } END { print count+0 }' /proc/self/mountinfo)"
mounted_hash=""
[ "$mount_count" -gt 0 ] && mounted_hash="$(sha256sum "$URV_STOCK_PATH" 2>/dev/null | awk '{print $1}')"

if [ "$mount_count" -gt 0 ]; then
  ownership="$(target_mount_counts)"
  urv_mount_count="${ownership##*:}"
  [ "$mount_count" = 1 ] && target_matches_urv_inode && urv_mount_count=1
  if [ "$mount_count" != "$urv_mount_count" ]; then
    log_status "Non-UPM mount conflict at stock target; leaving it unchanged"
    write_boot_status REPAIR_REQUIRED
    disable_module || log_status "Unable to persist module disable marker"
    exit 0
  fi
  if root_mount_layout_valid && [ "$mounted_hash" = "$URV_PATCHED_SHA256" ]; then
    if [ "$current_path" = "$URV_STOCK_PATH" ] &&
       [ "$current_version_name" = "$URV_VERSION_NAME" ] &&
       [ "$current_version_code" = "$URV_VERSION_CODE" ] &&
       [ "$path_count" = "$expected_path_count" ] &&
       [ "$split_compatible" = 1 ] &&
       [ "$current_enabled" = "$URV_ENABLED" ]; then
      if zygote_mounts_verified && root_mount_layout_valid && split_set_matches refresh &&
         [ "$(sha256sum "$URV_STOCK_PATH" 2>/dev/null | awk '{print $1}')" = "$URV_PATCHED_SHA256" ]; then
        log_status "Early root and Zygote mounts verified"
        complete_verified_mount
        exit 0
      fi
      log_status "Zygote namespace verification failed; retrying after package quiescence"
      stop_and_wait || {
        log_status "Unable to quiesce package; existing root mount left for Manager recovery"
        exit 0
      }
      if mount_and_verify_zygotes && root_mount_layout_valid && split_set_matches refresh &&
         [ "$(sha256sum "$URV_STOCK_PATH" 2>/dev/null | awk '{print $1}')" = "$URV_PATCHED_SHA256" ]; then
        stop_and_wait || {
          log_status "Repaired mounts verified but package is busy; leaving mounts for Manager recovery"
          write_boot_status REPAIR_REQUIRED
          exit 0
        }
        log_status "Early Zygote namespace repair succeeded"
        complete_verified_mount
        exit 0
      fi
      log_status "Zygote namespace repair failed; falling back to verified stock"
      remove_target_mounts || { log_status "Failed to remove every stale mount"; exit 0; }
      mount_count=0
    fi
  fi
  if [ "$mount_count" -gt 0 ]; then
    log_status "Early mount metadata mismatch; removing stale mount"
    stop_and_wait || { log_status "Unable to quiesce package; stale mount left for Manager recovery"; exit 0; }
    remove_target_mounts || { log_status "Failed to remove every stale mount"; exit 0; }
    mount_count=0
  fi
fi

stock_hash="$(sha256sum "$URV_STOCK_PATH" 2>/dev/null | awk '{print $1}')"
shadow_hash="$(sha256sum "$URV_STOCK_SHADOW_PATH" 2>/dev/null | awk '{print $1}')"
payload_hash="$(sha256sum "$URV_PATCHED_PATH" 2>/dev/null | awk '{print $1}')"

repair_payload_file() {
  source="$1"
  target="$2"
  expected_hash="$3"
  [ -f "$source" ] && [ ! -L "$source" ] || return 1
  [ "$(sha256sum "$source" 2>/dev/null | awk '{print $1}')" = "$expected_hash" ] || return 1
  next="$target.urv-repair"
  rm -f "$next"
  cp "$source" "$next" || return 1
  chmod 644 "$next" || return 1
  chown 0:0 "$next" || return 1
  chcon u:object_r:apk_data_file:s0 "$next" || return 1
  [ "$(sha256sum "$next" 2>/dev/null | awk '{print $1}')" = "$expected_hash" ] || return 1
  sync -f "$next" 2>/dev/null || sync
  mv -f "$next" "$target" || return 1
}

repair_module_payloads() {
  [ "$payload_hash" = "$URV_PATCHED_SHA256" ] ||
    repair_payload_file "$canonical_patched" "$URV_PATCHED_PATH" "$URV_PATCHED_SHA256" || return 1
  if [ "$URV_PRESERVE_STOCK" = 1 ] && [ "$shadow_hash" != "$URV_STOCK_SHADOW_SHA256" ]; then
    repair_payload_file "$canonical_stock" "$URV_STOCK_SHADOW_PATH" "$URV_STOCK_SHADOW_SHA256" || return 1
  fi
}

if [ "$payload_hash" != "$URV_PATCHED_SHA256" ] ||
   { [ "$URV_PRESERVE_STOCK" = 1 ] && [ "$shadow_hash" != "$URV_STOCK_SHADOW_SHA256" ]; }; then
  if repair_module_payloads; then
    log_status "Restored altered module payloads from verified canonical copies"
    payload_hash="$(sha256sum "$URV_PATCHED_PATH" 2>/dev/null | awk '{print $1}')"
    shadow_hash="$(sha256sum "$URV_STOCK_SHADOW_PATH" 2>/dev/null | awk '{print $1}')"
  else
    log_status "Module payload integrity changed and no verified canonical copy could restore it"
  fi
fi

if [ "$current_path" != "$URV_STOCK_PATH" ] ||
   [ "$split_compatible" != 1 ] ||
   [ "$current_version_name" != "$URV_VERSION_NAME" ] ||
   [ "$current_version_code" != "$URV_VERSION_CODE" ] ||
   [ "$path_count" != "$expected_path_count" ] ||
   [ "$current_enabled" != "$URV_ENABLED" ] ||
   [ "$stock_hash" != "$URV_STOCK_SHA256" ] ||
   { [ "$URV_PRESERVE_STOCK" = 1 ] && [ "$shadow_hash" != "$URV_STOCK_SHADOW_SHA256" ]; } ||
   [ "$payload_hash" != "$URV_PATCHED_SHA256" ]; then
  [ "$current_path" = "$URV_STOCK_PATH" ] || log_status "Compatibility mismatch: installed base path changed"
  [ "$current_version_name" = "$URV_VERSION_NAME" ] || log_status "Compatibility mismatch: version name changed"
  [ "$current_version_code" = "$URV_VERSION_CODE" ] || log_status "Compatibility mismatch: version code changed"
  [ "$path_count" = "$expected_path_count" ] || log_status "Compatibility mismatch: APK topology changed"
  [ "$split_compatible" = 1 ] || log_status "Compatibility mismatch: split APK set changed"
  [ "$current_enabled" = "$URV_ENABLED" ] || log_status "Compatibility mismatch: enabled state changed"
  [ "$stock_hash" = "$URV_STOCK_SHA256" ] ||
    log_status "Compatibility mismatch: installed stock APK changed (expected $URV_STOCK_SHA256, found $stock_hash)"
  [ "$URV_PRESERVE_STOCK" = 0 ] || [ "$shadow_hash" = "$URV_STOCK_SHADOW_SHA256" ] ||
    log_status "Compatibility mismatch: stock-shadow payload changed"
  [ "$payload_hash" = "$URV_PATCHED_SHA256" ] || log_status "Compatibility mismatch: patched payload changed"
  log_status "Compatibility changed; stock left active and repatching is required"
  write_boot_status REPATCH_REQUIRED
  disable_module || log_status "Unable to persist module disable marker"
  exit 0
fi

write_boot_status MOUNTING
stop_and_wait || {
  log_status "Package processes did not exit; leaving stock active"
  exit 0
}
if [ "$URV_PRESERVE_STOCK" = 1 ]; then
  chcon u:object_r:apk_data_file:s0 "$URV_PATCHED_PATH" "$URV_STOCK_SHADOW_PATH" || {
    log_status "Failed to set payload or stock shadow context; leaving real stock active"
    exit 0
  }
  mount -o bind "$URV_STOCK_SHADOW_PATH" "$URV_STOCK_PATH" || {
    log_status "Stock shadow bind mount failed; leaving real stock active"
    exit 0
  }
  # BusyBox accepts --make-private; Android Toybox needs the -o form.
  mount --make-private "$URV_STOCK_PATH" 2>/dev/null ||
    mount -o private none "$URV_STOCK_PATH" || {
    log_status "Failed to isolate the stock shadow bind; restoring stock"
    remove_failed_urv_mounts || log_status "Unable to remove the partial stock-shadow mount"
    write_boot_status VERIFY_FAILED
    disable_module || log_status "Unable to persist module disable marker"
    exit 0
  }
else
  chcon u:object_r:apk_data_file:s0 "$URV_PATCHED_PATH" || {
    log_status "Failed to set payload context; leaving stock active"
    exit 0
  }
fi
mount -o bind "$URV_PATCHED_PATH" "$URV_STOCK_PATH" || {
  log_status "Late patched bind mount failed; removing any stock-shadow layer"
  if remove_failed_urv_mounts && ! target_is_mounted; then
    write_boot_status VERIFY_FAILED
  else
    log_status "Unable to remove the partial root mount; repair is required"
    write_boot_status REPAIR_REQUIRED
  fi
  disable_module || log_status "Unable to persist module disable marker"
  exit 0
}

mounted_hash="$(sha256sum "$URV_STOCK_PATH" 2>/dev/null | awk '{print $1}')"
post_mount_shadow_hash=""
[ "$URV_PRESERVE_STOCK" = 0 ] ||
  post_mount_shadow_hash="$(sha256sum "$URV_STOCK_SHADOW_PATH" 2>/dev/null | awk '{print $1}')"
if { [ "$URV_PRESERVE_STOCK" = 1 ] && [ "$post_mount_shadow_hash" != "$URV_STOCK_SHADOW_SHA256" ]; } ||
   ! split_set_matches refresh ||
   [ "$mounted_hash" != "$URV_PATCHED_SHA256" ] || ! mount_and_verify_zygotes ||
   ! root_mount_layout_valid; then
  log_status "Late root or Zygote mount verification failed; restoring stock"
  remove_zygote_payload_mounts || log_status "Unable to remove every Zygote mount"
  if remove_failed_urv_mounts && ! target_is_mounted; then
    write_boot_status VERIFY_FAILED
  else
    log_status "Unable to prove an unmounted stock target; repair is required"
    write_boot_status REPAIR_REQUIRED
  fi
  disable_module || log_status "Unable to persist module disable marker"
  exit 0
fi

stop_and_wait || {
  log_status "Mounts verified but package restarted; leaving mounts for Manager recovery"
  write_boot_status REPAIR_REQUIRED
  exit 0
}
log_status "Late root and Zygote mounts verified; app remains stopped"
complete_verified_mount
