#!/usr/bin/env bash
set -euo pipefail

# ===================== Config =====================
JAVAC=${JAVAC:-javac}
JAVA=${JAVA:-java}
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="src"               # 用相对路径，避免 MSYS -> java.exe 路径不兼容
LOGS="$ROOT/logs"
CFG="$ROOT/network.config"

# 若只在 src/ 里有，则拷回根目录（Java 进程工作目录=根目录）
if [[ ! -f "$CFG" && -f "$ROOT/$SRC/network.config" ]]; then
  cp "$ROOT/$SRC/network.config" "$CFG"
fi

# ================== Utils / Cleanup =================
# 强制清理所有 java 进程（兼容 Windows / MSYS）
hard_kill_java() {
  if [[ "${OS:-}" == "Windows_NT" || "${MSYSTEM:-}" =~ (MINGW|MSYS) ]]; then
    taskkill //F //IM java.exe > /dev/null 2>&1 || true
  else
    pkill -f CouncilMember 2>/dev/null || true
  fi
  sleep 1
}

# 退出兜底清理
trap 'echo "[INFO] cleanup on exit"; hard_kill_java' EXIT

# 清空并重建 logs
ensure_logs_clean() {
  rm -rf "$LOGS" 2>/dev/null || true
  mkdir -p "$LOGS"
}

# 启动一个成员；可选：--propose <val> --propose-delay <ms>
launch_member() {
  local id="$1"
  local profile="$2"
  local propose_value="${3:-}"
  local propose_delay="${4:-300}"

  local args=( "$id" --profile "$profile" --config "network.config" )
  if [[ -n "$propose_value" ]]; then
    args+=( --propose "$propose_value" --propose-delay "$propose_delay" )
  fi

  local log_file="$LOGS/${id}.log"
  # 关键点：-cp 使用相对的 "src"；工作目录为项目根目录
  "$JAVA" -cp "$SRC" CouncilMember "${args[@]}" >"$log_file" 2>&1 &
  echo $! > "$LOGS/${id}.pid"
}

# 等待某目录下出现 CONSENSUS（最多 N 秒）
wait_for_consensus() {
  local dir="$1"; local timeout="${2:-25}"
  for ((i=0;i<timeout;i++)); do
    if grep -q "CONSENSUS:" "$dir"/*.log 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# 拷贝当前 logs 到指定目录
scenario_copy_logs() {
  local dst="$1"
  rm -rf "$ROOT/$dst" 2>/dev/null || true
  mkdir -p "$ROOT/$dst"
  cp -r "$LOGS"/* "$ROOT/$dst/" 2>/dev/null || true
}

# 收尾：等待共识 -> 拷贝 -> 强制清理
scenario_finish() {
  local tag="$1"
  if wait_for_consensus "$LOGS" 25; then
    echo "[INFO] $tag consensus detected."
  else
    echo "[WARN] $tag no consensus within timeout."
  fi
  scenario_copy_logs "$tag"
  hard_kill_java
}

# ===================== Build =======================
echo "Compiling..."
"$JAVAC" "$SRC/CouncilMember.java"

# ===================== Scenario 1 ===================
echo -e "\n=== Scenario 1: Ideal Network ==="
hard_kill_java
ensure_logs_clean
for i in $(seq 1 9); do
  if [[ "$i" == "4" ]]; then
    launch_member "M4" reliable "M5" 200   # M4 在自身进程内发起提案
  else
    launch_member "M$i" reliable
  fi
done
scenario_finish "logs-s1"

# ===================== Scenario 2 ===================
echo -e "\n=== Scenario 2: Concurrent Proposals ==="
hard_kill_java
ensure_logs_clean
for i in $(seq 1 9); do
  case "$i" in
    1) echo "[S2] M1 proposes M1 @120ms"; launch_member "M1" reliable "M1" 120 ;;
    8) echo "[S2] M8 proposes M8 @280ms"; launch_member "M8" reliable "M8" 280 ;;
    *) launch_member "M$i" reliable ;;
  esac
done
scenario_finish "logs-s2"

# ===================== Scenario 3 ===================
echo -e "\n=== Scenario 3: Fault Tolerance ==="
hard_kill_java
ensure_logs_clean
for i in $(seq 1 9); do
  case "$i" in
    1) launch_member "M1" reliable ;;
    2) echo "[S3] M2 latent proposes M6 @900ms";  launch_member "M2" latent   "M6" 900 ;;
    3) echo "[S3] M3 failing proposes M3 @300ms"; launch_member "M3" failing  "M3" 300 ;;
    4) echo "[S3] M4 standard proposes M5 @200ms";launch_member "M4" standard "M5" 200 ;;
    *) launch_member "M$i" standard ;;
  esac
done
scenario_finish "logs-s3"

# ===================== Summary ======================
echo -e "\n=== Summary (CONSENSUS lines) ==="
for d in logs-s1 logs-s2 logs-s3; do
  echo "S${d: -1}:"
  if compgen -G "$d/*.log" > /dev/null; then
    grep -h "CONSENSUS:" "$d"/*.log || true
  fi
  echo
done
echo "Done. Logs -> logs-s1/, logs-s2/, logs-s3/"
