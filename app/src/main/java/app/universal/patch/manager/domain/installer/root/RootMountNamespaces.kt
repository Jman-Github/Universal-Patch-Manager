package app.universal.patch.manager.domain.installer.root

/** Mirrors and verifies UPM-owned binds, and removes them from Zygote and Manager namespaces. */
class RootMountNamespaces(
    private val shell: RootShellGateway,
    private val managerPid: Int? = null
) {
    init {
        require(managerPid == null || managerPid > 0) { "Invalid Manager process ID" }
    }

    suspend fun mount(expected: RootCommittedState) {
        shell.runIsolatedBounded(
            namespaceHelpers(expected) + """
                set -eu
                preflight_stable=0
                preflight_attempt=0
                while [ "${'$'}preflight_attempt" -lt $ZYGOTE_STABILITY_ATTEMPTS ]; do
                  before="${'$'}(live_zygote_pids)"
                  if [ -z "${'$'}before" ]; then
                    preflight_attempt=${'$'}((preflight_attempt + 1))
                    sleep 0.1
                    continue
                  fi
                  zygote_changed=0
                  for pid in ${'$'}before; do
                    validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                    namespace_splits_match "${'$'}pid" || {
                      echo "Split APKs differ in Zygote namespace ${'$'}pid" >&2
                      exit 1
                    }
                    if ! namespace_matches "${'$'}pid"; then
                      if [ ${if (expected.preserveStockAcrossBoot) "1" else "0"} = 1 ] &&
                          namespace_matches_shadow "${'$'}pid"; then
                        :
                      elif ! namespace_target_clear "${'$'}pid"; then
                        validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                        echo "Foreign mount conflict in Zygote namespace ${'$'}pid" >&2
                        exit 1
                      fi
                    fi
                  done
                  after="${'$'}(live_zygote_pids)"
                  if [ "${'$'}zygote_changed" = 0 ] && [ "${'$'}before" = "${'$'}after" ]; then
                    preflight_stable=1
                    break
                  fi
                  preflight_attempt=${'$'}((preflight_attempt + 1))
                  sleep 0.1
                done
                [ "${'$'}preflight_stable" = 1 ] || {
                  echo "Zygote namespaces did not stabilize during mount preflight" >&2
                  exit 1
                }
                if [ ${if (expected.preserveStockAcrossBoot) "1" else "0"} = 1 ]; then
                  chcon u:object_r:apk_data_file:s0 ${shellQuote(expected.patchedPath)} ${shellQuote(expected.stockShadowPath.orEmpty())}
                else
                  chcon u:object_r:apk_data_file:s0 ${shellQuote(expected.patchedPath)}
                fi
                validate_shadow_hash
                self_pid="${'$'}${'$'}"
                if ! namespace_matches "${'$'}self_pid"; then
                  if ! namespace_target_clear "${'$'}self_pid"; then
                    echo "Root APK target is occupied by a non-matching mount" >&2
                    exit 1
                  fi
                  if [ ${if (expected.preserveStockAcrossBoot) "1" else "0"} = 1 ]; then
                    mount -o bind ${shellQuote(expected.stockShadowPath.orEmpty())} ${shellQuote(expected.stockPath)}
                    # BusyBox accepts --make-private; Android Toybox needs the -o form.
                    mount --make-private ${shellQuote(expected.stockPath)} 2>/dev/null ||
                      mount -o private none ${shellQuote(expected.stockPath)} || {
                      echo "Failed to isolate the stock shadow bind mount" >&2
                      exit 1
                    }
                  fi
                  mount -o bind ${shellQuote(expected.patchedPath)} ${shellQuote(expected.stockPath)}
                fi
                mount_stable=0
                mount_attempt=0
                while [ "${'$'}mount_attempt" -lt $ZYGOTE_STABILITY_ATTEMPTS ]; do
                  pids="${'$'}(live_zygote_pids)"
                  if [ -z "${'$'}pids" ]; then
                    mount_attempt=${'$'}((mount_attempt + 1))
                    sleep 0.1
                    continue
                  fi
                  zygote_changed=0
                  for pid in ${'$'}pids; do
                    validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                    if ! namespace_matches "${'$'}pid"; then
                      shadow_ready=0
                      if [ ${if (expected.preserveStockAcrossBoot) "1" else "0"} = 1 ] && namespace_matches_shadow "${'$'}pid"; then
                        shadow_ready=1
                      elif namespace_target_clear "${'$'}pid"; then
                        if [ ${if (expected.preserveStockAcrossBoot) "1" else "0"} = 1 ]; then
                          if ! nsenter --mount="/proc/${'$'}pid/ns/mnt" -- mount -o bind ${shellQuote(expected.stockShadowPath.orEmpty())} ${shellQuote(expected.stockPath)}; then
                            validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                            echo "Failed to mount the stock shadow in Zygote namespace ${'$'}pid" >&2
                            exit 1
                          fi
                          shadow_ready=1
                        fi
                      else
                        validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                        echo "Foreign mount conflict in Zygote namespace ${'$'}pid" >&2
                        exit 1
                      fi
                      if [ "${'$'}shadow_ready" = 1 ] &&
                          ! nsenter --mount="/proc/${'$'}pid/ns/mnt" -- mount --make-private ${shellQuote(expected.stockPath)} 2>/dev/null &&
                          ! nsenter --mount="/proc/${'$'}pid/ns/mnt" -- mount -o private none ${shellQuote(expected.stockPath)}; then
                        validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                        echo "Failed to isolate the stock shadow in Zygote namespace ${'$'}pid" >&2
                        exit 1
                      fi
                      if ! nsenter --mount="/proc/${'$'}pid/ns/mnt" -- mount -o bind ${shellQuote(expected.patchedPath)} ${shellQuote(expected.stockPath)}; then
                        validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                        echo "Failed to mount the patched APK in Zygote namespace ${'$'}pid" >&2
                        exit 1
                      fi
                    fi
                    if ! namespace_matches "${'$'}pid"; then
                      validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                      echo "Failed to verify the patched APK in Zygote namespace ${'$'}pid" >&2
                      exit 1
                    fi
                  done
                  after="${'$'}(live_zygote_pids)"
                  if [ "${'$'}zygote_changed" = 0 ] && [ "${'$'}pids" = "${'$'}after" ]; then
                    mount_stable=1
                    break
                  fi
                  mount_attempt=${'$'}((mount_attempt + 1))
                  sleep 0.1
                done
                [ "${'$'}mount_stable" = 1 ] || {
                  echo "Zygote namespaces did not stabilize while applying mounts" >&2
                  exit 1
                }
            """.trimIndent(),
            MOUNT_TIMEOUT_SECONDS,
            "root and Zygote namespace mount"
        ).requireSuccess("Mount patched APK in root and Zygote namespaces")
    }

    suspend fun verify(expected: RootCommittedState) {
        shell.runIsolatedBounded(
            namespaceHelpers(expected) + """
                set -eu
                # RootMountVerifier validates the stock-shadow hash after this namespace check,
                # so only verify that the source file still exists while inspecting namespaces.
                validate_shadow_file
                verify_stable=0
                verify_attempt=0
                while [ "${'$'}verify_attempt" -lt $ZYGOTE_STABILITY_ATTEMPTS ]; do
                  pids="${'$'}(live_zygote_pids)"
                  if [ -z "${'$'}pids" ]; then
                    verify_attempt=${'$'}((verify_attempt + 1))
                    sleep 0.1
                    continue
                  fi
                  zygote_changed=0
                  for pid in ${'$'}pids; do
                    validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                    if ! namespace_matches "${'$'}pid"; then
                      validate_zygote "${'$'}pid" || { zygote_changed=1; continue; }
                      echo "Patched APK is not mounted in Zygote namespace ${'$'}pid" >&2
                      exit 1
                    fi
                  done
                  after="${'$'}(live_zygote_pids)"
                  if [ "${'$'}zygote_changed" = 0 ] && [ "${'$'}pids" = "${'$'}after" ]; then
                    verify_stable=1
                    break
                  fi
                  verify_attempt=${'$'}((verify_attempt + 1))
                  sleep 0.1
                done
                [ "${'$'}verify_stable" = 1 ] || {
                  echo "Zygote namespaces did not stabilize during verification" >&2
                  exit 1
                }
            """.trimIndent(),
            VERIFY_TIMEOUT_SECONDS,
            "Zygote namespace verification"
        ).requireSuccess("Verify patched APK in every Zygote namespace")
    }

    suspend fun verifyProcesses(expected: RootCommittedState, pids: List<Int>) {
        if (pids.isEmpty()) return
        require(pids.all { it > 1 }) { "Invalid target process ID" }
        val pidWords = pids.distinct().joinToString(" ")
        shell.runIsolatedBounded(
            namespaceHelpers(expected) +
                processIdentityHelpers(expected.packageName, expected.userId) + """
                set -eu
                for pid in $pidWords; do
                  if [ ! -r "/proc/${'$'}pid/ns/mnt" ]; then
                    [ -d "/proc/${'$'}pid" ] || continue
                    echo "Could not inspect target process mount namespace ${'$'}pid" >&2
                    exit 1
                  fi
                  namespace_id="${'$'}(readlink "/proc/${'$'}pid/ns/mnt" 2>/dev/null)" || {
                    [ -d "/proc/${'$'}pid" ] || continue
                    echo "Could not identify target process mount namespace ${'$'}pid" >&2
                    exit 1
                  }
                  if ! process_matches_package "${'$'}pid"; then
                    [ -d "/proc/${'$'}pid" ] || continue
                    echo "Target process identity changed before namespace verification for ${'$'}pid" >&2
                    exit 1
                  fi
                  if ! namespace_matches "${'$'}pid"; then
                    [ -d "/proc/${'$'}pid" ] || continue
                    echo "Patched APK is not visible in target process namespace ${'$'}pid" >&2
                    exit 1
                  fi
                  verified_namespace_id="${'$'}(readlink "/proc/${'$'}pid/ns/mnt" 2>/dev/null)" || {
                    [ -d "/proc/${'$'}pid" ] || continue
                    echo "Could not re-inspect target process mount namespace ${'$'}pid" >&2
                    exit 1
                  }
                  if [ "${'$'}namespace_id" != "${'$'}verified_namespace_id" ]; then
                    echo "Target process mount namespace changed during verification for ${'$'}pid" >&2
                    exit 1
                  fi
                  if ! process_matches_package "${'$'}pid"; then
                    [ -d "/proc/${'$'}pid" ] || continue
                    echo "Target process identity changed during namespace verification for ${'$'}pid" >&2
                    exit 1
                  fi
                done
            """.trimIndent(),
            VERIFY_TIMEOUT_SECONDS,
            "target process namespace verification"
        ).requireSuccess("Verify patched APK in running target processes")
    }

    suspend fun verifyStockProcesses(
        packageName: String,
        userId: Int,
        stockPath: String,
        pids: List<Int>,
        splitPaths: List<String> = emptyList()
    ) {
        if (pids.isEmpty()) return
        require(pids.all { it > 1 }) { "Invalid target process ID" }
        val pidWords = pids.distinct().joinToString(" ")
        shell.runIsolatedBounded(
            processIdentityHelpers(packageName, userId) + """
                set -eu
                stock_path=${shellQuote(stockPath)}
                stock_inode="${'$'}(stat -c '%d:%i' "${'$'}stock_path")"
                for pid in $pidWords; do
                  if [ ! -r "/proc/${'$'}pid/ns/mnt" ]; then
                    [ -d "/proc/${'$'}pid" ] || continue
                    echo "Could not inspect target process mount namespace ${'$'}pid" >&2
                    exit 1
                  fi
                  namespace_id="${'$'}(readlink "/proc/${'$'}pid/ns/mnt" 2>/dev/null)" || {
                    [ -d "/proc/${'$'}pid" ] || continue
                    echo "Could not identify target process mount namespace ${'$'}pid" >&2
                    exit 1
                  }
                  if ! process_matches_package "${'$'}pid"; then
                    [ -d "/proc/${'$'}pid" ] || continue
                    echo "Target process identity changed before stock namespace verification for ${'$'}pid" >&2
                    exit 1
                  fi
                  target_inode="${'$'}(nsenter --mount="/proc/${'$'}pid/ns/mnt" -- stat -c '%d:%i' "${'$'}stock_path" 2>/dev/null)" || {
                    [ -d "/proc/${'$'}pid" ] || continue
                    echo "Stock APK is not visible in target process namespace ${'$'}pid" >&2
                    exit 1
                  }
                  if [ "${'$'}stock_inode" != "${'$'}target_inode" ]; then
                    [ -d "/proc/${'$'}pid" ] || continue
                    echo "Target process namespace ${'$'}pid still sees a non-stock APK" >&2
                    exit 1
                  fi
                  for split_path in ${splitPaths.joinToString(" ", transform = ::shellQuote)}; do
                    split_inode="${'$'}(stat -c '%d:%i' "${'$'}split_path")" || exit 1
                    split_target_inode="${'$'}(nsenter --mount="/proc/${'$'}pid/ns/mnt" -- stat -c '%d:%i' "${'$'}split_path" 2>/dev/null)" || exit 1
                    [ "${'$'}split_inode" = "${'$'}split_target_inode" ] || {
                      echo "Stock split APK differs in target process namespace ${'$'}pid" >&2
                      exit 1
                    }
                  done
                  verified_namespace_id="${'$'}(readlink "/proc/${'$'}pid/ns/mnt" 2>/dev/null)" || {
                    [ -d "/proc/${'$'}pid" ] || continue
                    echo "Could not re-inspect target process mount namespace ${'$'}pid" >&2
                    exit 1
                  }
                  if [ "${'$'}namespace_id" != "${'$'}verified_namespace_id" ]; then
                    echo "Target process mount namespace changed during stock verification for ${'$'}pid" >&2
                    exit 1
                  fi
                  if ! process_matches_package "${'$'}pid"; then
                    [ -d "/proc/${'$'}pid" ] || continue
                    echo "Target process identity changed during stock namespace verification for ${'$'}pid" >&2
                    exit 1
                  fi
                done
            """.trimIndent(),
            VERIFY_TIMEOUT_SECONDS,
            "target process stock namespace verification"
        ).requireSuccess("Verify stock APK in running target processes")
    }

    suspend fun removeOwned(
        packageName: String,
        targets: Set<String>,
        allowLazyRecovery: Boolean = false,
        competingTargets: Set<String> = emptySet()
    ): List<String> {
        if (targets.isEmpty()) return emptyList()
        require(competingTargets.all { it in targets }) { "Competing APK targets must be scoped to this cleanup" }
        val competingPayloads = if (competingTargets.isEmpty()) emptyList() else RootPaths.competingPayloads(packageName)
        val allowedPaths = mountInfoAliases(
            listOf(
                RootPaths.moduleApk(packageName),
                RootPaths.moduleStockApk(packageName),
                "${RootPaths.rollbackModule(packageName)}/$packageName.apk",
                "${RootPaths.rollbackModule(packageName)}/$packageName-stock.apk",
                "${RootPaths.backup(packageName)}/module/$packageName.apk",
                "${RootPaths.backup(packageName)}/module/$packageName-stock.apk",
                "${RootPaths.legacyPackage(packageName)}/$packageName.apk"
            )
        )
        val competingPaths = mountInfoAliases(competingPayloads)
        val competingSourceList = shellQuote(competingPaths.joinToString("\n"))
        val allowed = allowedPaths.joinToString(" ", transform = ::shellQuote)
        val allowedWithCompeting = (allowedPaths + competingPaths).distinct()
        val allAllowed = allowedWithCompeting.joinToString(" ", transform = ::shellQuote)
        // Pass multiline sources through ENVIRON because Toybox awk rejects them in -v values.
        val allowedSourceList = shellQuote(allowedPaths.joinToString("\n"))
        val allAllowedSourceList = shellQuote(allowedWithCompeting.joinToString("\n"))
        val targetWords = targets.joinToString(" ", transform = ::shellQuote)
        val competingTargetWords = competingTargets.joinToString(" ", transform = ::shellQuote)
        val cleanup = shell.runIsolatedBounded(
            discoveryHelpers() + """
                set -eu
                allowed_sources=$allowedSourceList
                namespace_layer_counts() (
                  pid="${'$'}1"
                  target="${'$'}2"
                  URV_ALLOWED_SOURCES="${'$'}allowed_sources" nsenter --mount="/proc/${'$'}pid/ns/mnt" -- awk -v target="${'$'}target" '
                    BEGIN { count=split(ENVIRON["URV_ALLOWED_SOURCES"], candidates, "\n") }
                    ${'$'}5 == target {
                      total++
                      separator=0
                      for (i=1; i<=NF; i++) if (${'$'}i == "-") { separator=i; break }
                      root=${'$'}4
                      source=(separator > 0 ? ${'$'}(separator+2) : "")
                      sub(/\\040\(deleted\)${'$'}/, "", root)
                      sub(/\\040\(deleted\)${'$'}/, "", source)
                      for (candidate=1; candidate<=count; candidate++) {
                        if (root == candidates[candidate] || source == candidates[candidate]) {
                          owned++
                          break
                        }
                      }
                    }
                    END { print total+0 ":" owned+0 }
                  ' /proc/self/mountinfo && return 0
                  # A process can disappear after enumeration. It has no mount left to remove.
                  [ ! -r "/proc/${'$'}pid/ns/mnt" ] && { printf '%s\n' 0:0; return 0; }
                  return 1
                )
                visible_layer_is_owned() (
                  pid="${'$'}1"
                  target="${'$'}2"
                  counts="${'$'}3"
                  # Exact mountinfo paths still identify all layers after their
                  # source APKs have been deleted and can no longer be stat'ed.
                  [ "${'$'}{counts%%:*}" = "${'$'}{counts##*:}" ] && return 0
                  inode="${'$'}(nsenter --mount="/proc/${'$'}pid/ns/mnt" -- stat -c '%d:%i' "${'$'}target" 2>/dev/null)" ||
                    return 1
                  shift 3
                  for candidate do
                    [ -f "${'$'}candidate" ] || continue
                    [ "${'$'}(stat -c '%d:%i' "${'$'}candidate" 2>/dev/null)" = "${'$'}inode" ] && return 0
                  done
                  return 1
                )
                cleanup_namespace() {
                  pid="${'$'}1"
                  namespace_label="${'$'}2"
                  shift 2
                  for target do
                    cleanup_target_in_namespace "${'$'}pid" "${'$'}namespace_label" "${'$'}target"
                  done
                }
                cleanup_target_in_namespace() (
                  pid="${'$'}1"
                  namespace_label="${'$'}2"
                  target="${'$'}3"
                  self_namespace="${'$'}(readlink /proc/self/ns/mnt)" || {
                    echo "Failed to inspect the root mount namespace" >&2
                    exit 1
                  }
                  target_namespace="${'$'}(readlink /proc/${'$'}pid/ns/mnt)" || return 0
                  [ "${'$'}self_namespace" != "${'$'}target_namespace" ] || return 0
                  set -- $allowed
                  for competing_target in $competingTargetWords; do
                    if [ "${'$'}target" = "${'$'}competing_target" ]; then
                      allowed_sources=$allAllowedSourceList
                      set -- $allAllowed
                      break
                    fi
                  done
                  attempts=0
                  max_attempts=${if (allowLazyRecovery) "16" else "8"}
                  while :; do
                    counts="${'$'}(namespace_layer_counts "${'$'}pid" "${'$'}target")" || exit 1
                    [ "${'$'}{counts%%:*}" != 0 ] || break
                    if ! visible_layer_is_owned "${'$'}pid" "${'$'}target" "${'$'}counts" "${'$'}@"; then
                      if [ "${'$'}{counts##*:}" != 0 ]; then
                        echo "Foreign ${'$'}namespace_label mount covers a UPM layer at ${'$'}target" >&2
                        exit 1
                      fi
                      break
                    fi
                    [ "${'$'}attempts" -lt "${'$'}max_attempts" ] || {
                      echo "Too many UPM mount layers remained at ${'$'}target in ${'$'}namespace_label namespace ${'$'}pid" >&2
                      exit 1
                    }
                    if ! nsenter --mount="/proc/${'$'}pid/ns/mnt" -- umount "${'$'}target"; then
                      if [ ${if (allowLazyRecovery) "1" else "0"} = 1 ]; then
                        counts="${'$'}(namespace_layer_counts "${'$'}pid" "${'$'}target")" || exit 1
                        if [ "${'$'}{counts%%:*}" != 0 ]; then
                          visible_layer_is_owned "${'$'}pid" "${'$'}target" "${'$'}counts" "${'$'}@" || {
                            echo "${'$'}namespace_label mount ownership changed before lazy unmount at ${'$'}target" >&2
                            exit 1
                          }
                          nsenter --mount="/proc/${'$'}pid/ns/mnt" -- umount -l "${'$'}target" || {
                            echo "Failed to lazily unmount ${'$'}target in ${'$'}namespace_label namespace ${'$'}pid" >&2
                            exit 1
                          }
                          printf 'URV_LAZY_UNMOUNT:%s\n' "${'$'}target"
                        fi
                      else
                        echo "Failed to unmount ${'$'}target in ${'$'}namespace_label namespace ${'$'}pid" >&2
                        exit 1
                      fi
                    fi
                    attempts=${'$'}((attempts + 1))
                  done
                )
                ${managerPid?.let { pid ->
                    "if [ -r /proc/$pid/ns/mnt ]; then cleanup_namespace $pid Manager $targetWords; fi"
                }.orEmpty()}
                for pid in ${'$'}(zygote_pids); do
                  validate_zygote "${'$'}pid" || continue
                  cleanup_namespace "${'$'}pid" Zygote $targetWords
                done
                # Earlier installs can leave a stale APK in long-lived processes, even
                # after root and Zygote have a clear view of the same stock target.
                if [ ${if (competingTargets.isNotEmpty()) "1" else "0"} = 1 ]; then
                  for target in $competingTargetWords; do
                    competing_pids="${'$'}(
                      URV_COMPETING_SOURCES=$competingSourceList awk -v target="${'$'}target" '
                        BEGIN { count=split(ENVIRON["URV_COMPETING_SOURCES"], candidates, "\n") }
                        ${'$'}5 == target {
                          root=${'$'}4
                          sub(/\\040\(deleted\)${'$'}/, "", root)
                          for (i=1; i<=count; i++) if (root == candidates[i]) {
                            split(FILENAME, path, "/")
                            if (path[3] ~ /^[0-9]+$/) print path[3]
                            break
                          }
                        }
                      ' /proc/[0-9]*/mountinfo 2>/dev/null | sort -u
                    )"
                    for pid in ${'$'}competing_pids; do
                      cleanup_namespace "${'$'}pid" "Legacy ReVanced process" "${'$'}target"
                    done
                  done
                fi
            """.trimIndent(),
            REMOVE_TIMEOUT_SECONDS,
            "Zygote namespace cleanup"
        ).requireSuccess("Remove UPM mounts from Zygote namespaces")
        return cleanup.stdout.mapNotNull { line ->
            line.substringAfter(LAZY_UNMOUNT_MARKER, "")
                .takeIf(String::isNotEmpty)
        }.distinct()
    }

    private fun processIdentityHelpers(packageName: String, userId: Int): String {
        require(userId >= 0) { "Invalid Android user ID" }
        val userUidStart = userId * PER_USER_RANGE
        val userUidEnd = (userId + 1) * PER_USER_RANGE
        return """
            package_name=${shellQuote(packageName)}
            package_uid="${'$'}(
              pm list packages -U --user $userId "${'$'}package_name" 2>/dev/null |
                awk -v wanted="package:${'$'}package_name" '
                  ${'$'}1 == wanted {
                    for (i=2; i<=NF; i++) {
                      if (${'$'}i ~ /^uid:[0-9]+${'$'}/) {
                        sub(/^uid:/, "", ${'$'}i)
                        print ${'$'}i
                        exit
                      }
                    }
                  }
                '
            )"
            process_matches_package() {
              pid="${'$'}1"
              process_name="${'$'}(tr '\000' '\n' < "/proc/${'$'}pid/cmdline" 2>/dev/null | head -n 1)" || return 1
              process_uid="${'$'}(awk '/^Uid:/ { print ${'$'}2; exit }' "/proc/${'$'}pid/status" 2>/dev/null)" || return 1
              case "${'$'}process_uid" in ''|*[!0-9]*) return 1 ;; esac
              [ "${'$'}process_uid" -ge $userUidStart ] && [ "${'$'}process_uid" -lt $userUidEnd ] || return 1
              case "${'$'}process_name" in
                "${'$'}package_name"|"${'$'}package_name":*) return 0 ;;
              esac
              [ -n "${'$'}package_uid" ] || return 1
              [ "${'$'}process_uid" = "${'$'}package_uid" ]
            }
        """.trimIndent() + "\n"
    }

    private fun namespaceHelpers(expected: RootCommittedState): String {
        val patchedRoot = mountInfoRootAlias(expected.patchedPath)
        val shadowRoot = mountInfoRootAlias(expected.stockShadowPath.orEmpty())
        require(validSplitIdentity(expected.topology, expected.stockSplits)) { "Invalid committed split APK set" }
        val splitPaths = expected.stockSplits.keys.sorted().joinToString(" ") {
            shellQuote(expected.stockPath.substringBeforeLast('/') + "/" + it)
        }
        return discoveryHelpers() + """
            namespace_splits_match() {
              for split_path in $splitPaths; do
                split_source_inode="${'$'}(stat -c '%d:%i' "${'$'}split_path" 2>/dev/null)" || return 1
                split_target_inode="${'$'}(nsenter --mount="/proc/${'$'}1/ns/mnt" -- stat -c '%d:%i' "${'$'}split_path" 2>/dev/null)" || return 1
                [ "${'$'}split_source_inode" = "${'$'}split_target_inode" ] || return 1
              done
              return 0
            }
            validate_shadow_file() {
              [ ${if (expected.preserveStockAcrossBoot) "1" else "0"} = 0 ] && return 0
              [ -f ${shellQuote(expected.stockShadowPath.orEmpty())} ]
            }
            validate_shadow_hash() {
              validate_shadow_file || return 1
              [ ${if (expected.preserveStockAcrossBoot) "1" else "0"} = 0 ] && return 0
              shadow_hash="${'$'}(sha256sum ${shellQuote(expected.stockShadowPath.orEmpty())} 2>/dev/null | awk '{print ${'$'}1}')"
              [ "${'$'}shadow_hash" = ${shellQuote(expected.stockShadowSha256.orEmpty())} ]
            }
            namespace_target_clear() {
              pid="${'$'}1"
              ! nsenter --mount="/proc/${'$'}pid/ns/mnt" -- awk -v target=${shellQuote(expected.stockPath)} \
                '${'$'}5 == target { found=1 } END { exit !found }' /proc/self/mountinfo
            }
            namespace_matches_shadow() {
              [ ${if (expected.preserveStockAcrossBoot) "1" else "0"} = 1 ] || return 1
              pid="${'$'}1"
              ownership="${'$'}(nsenter --mount="/proc/${'$'}pid/ns/mnt" -- awk \
                -v target=${shellQuote(expected.stockPath)} \
                -v shadow=${shellQuote(expected.stockShadowPath.orEmpty())} \
                -v shadow_root=${shellQuote(shadowRoot)} '
                  ${'$'}5 == target {
                    total++
                    separator=0
                    for (i=1; i<=NF; i++) if (${'$'}i == "-") { separator=i; break }
                    root=${'$'}4
                    source=(separator > 0 ? ${'$'}(separator+2) : "")
                    if (root == shadow || root == shadow_root || source == shadow || source == shadow_root) owned++
                  }
                  END { print total+0 ":" owned+0 }
                ' /proc/self/mountinfo)" || return 1
              [ "${'$'}ownership" = "1:1" ] || return 1
              source_inode="${'$'}(stat -c '%d:%i' ${shellQuote(expected.stockShadowPath.orEmpty())} 2>/dev/null)" || return 1
              target_inode="${'$'}(nsenter --mount="/proc/${'$'}pid/ns/mnt" -- stat -c '%d:%i' ${shellQuote(expected.stockPath)} 2>/dev/null)" || return 1
              [ "${'$'}source_inode" = "${'$'}target_inode" ]
            }
            namespace_matches() {
              pid="${'$'}1"
              namespace_splits_match "${'$'}pid" || return 1
              validate_shadow_file || return 1
              ownership="${'$'}(nsenter --mount="/proc/${'$'}pid/ns/mnt" -- awk \
                -v target=${shellQuote(expected.stockPath)} \
                -v patched=${shellQuote(expected.patchedPath)} \
                -v patched_root=${shellQuote(patchedRoot)} \
                -v shadow=${shellQuote(expected.stockShadowPath.orEmpty())} \
                -v shadow_root=${shellQuote(shadowRoot)} '
                  ${'$'}5 == target {
                    total++
                    separator=0
                    for (i=1; i<=NF; i++) if (${'$'}i == "-") { separator=i; break }
                    root=${'$'}4
                    source=(separator > 0 ? ${'$'}(separator+2) : "")
                    if (root == patched || root == patched_root || source == patched || source == patched_root ||
                        (shadow != "" && (root == shadow || root == shadow_root ||
                            source == shadow || source == shadow_root))) owned++
                  }
                  END { print total+0 ":" owned+0 }
                ' /proc/self/mountinfo)" || return 1
              [ "${'$'}ownership" = "${if (expected.preserveStockAcrossBoot) "2:2" else "1:1"}" ] || return 1
              source_inode="${'$'}(stat -c '%d:%i' ${shellQuote(expected.patchedPath)} 2>/dev/null)" || return 1
              target_inode="${'$'}(nsenter --mount="/proc/${'$'}pid/ns/mnt" -- stat -c '%d:%i' ${shellQuote(expected.stockPath)} 2>/dev/null)" || return 1
              [ "${'$'}source_inode" = "${'$'}target_inode" ]
            }
        """.trimIndent() + "\n"
    }

    private fun discoveryHelpers(): String = """
        zygote_pids() {
          { pidof zygote64 2>/dev/null || true; pidof zygote 2>/dev/null || true; } |
            tr ' ' '\n' | awk '/^[0-9]+${'$'}/ && ${'$'}0 > 1' | sort -n -u | paste -sd ' ' -
        }
        validate_zygote() {
          pid="${'$'}1"
          cmdline="${'$'}(cat "/proc/${'$'}pid/cmdline" 2>/dev/null)" || return 1
          case "${'$'}cmdline" in
            zygote|zygote64|zygote\ *|zygote64\ *|*--zygote*|*--nice-name=zygote*) return 0 ;;
          esac
          return 1
        }
        live_zygote_pids() {
          for pid in ${'$'}(zygote_pids); do
            validate_zygote "${'$'}pid" && printf '%s ' "${'$'}pid"
          done | sed 's/[[:space:]]*${'$'}//'
        }
    """.trimIndent() + "\n"

    private fun mountInfoAliases(paths: List<String>): List<String> =
        paths.flatMap { path -> listOf(path, mountInfoRootAlias(path)) }.distinct()

    private fun mountInfoRootAlias(path: String): String =
        if (path.startsWith("/data/")) path.removePrefix("/data") else path

    private companion object {
        const val PER_USER_RANGE = 100_000
        const val LAZY_UNMOUNT_MARKER = "URV_LAZY_UNMOUNT:"
        const val ZYGOTE_STABILITY_ATTEMPTS = 20
        const val MOUNT_TIMEOUT_SECONDS = 60L
        const val VERIFY_TIMEOUT_SECONDS = 60L
        const val REMOVE_TIMEOUT_SECONDS = 60L
    }
}
