#!/usr/bin/env bash
#
# 知识星球可控爬取入口（包装 ZsxqApiCrawlCli，省掉手拼 classpath）。
#
# 为什么要这个脚本：跑一次 Java 要先 compile、再导出依赖 classpath、再拼 java -cp，
# 三步里任何一步敲错都是一串难懂的报错。封装之后日常只需要一行。
#
# 用法：
#   ./scripts/zsxq-crawl.sh --list                       # 看有哪些栏目、各多少篇
#   ./scripts/zsxq-crawl.sh 面试相关,优质面经             # 爬指定栏目
#   ./scripts/zsxq-crawl.sh 技术问答 --limit=200         # 带上限
#   ./scripts/zsxq-crawl.sh 面试相关 --from=2026-07-01   # 只要 7 月之后的
#
# 崩了 / 中断了：**重跑同一条命令即可续上**，已取过的会自动跳过。
# 只有想重新拉一遍清单时才加 --refresh。
#
# 前置：登录态要存在 ~/.config/JLRADemo/state/wx.zsxq.com.json，
#       过期了先在带调试端口的 Chrome 里重新登录并存一次档。
#
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
mkdir -p tmp

# 依赖 classpath 缓存：只在缺文件或 pom 更新时重算，省掉每次几秒的 maven 往返
CP_FILE="$ROOT/tmp/.api-crawl-cp.txt"
if [[ ! -s "$CP_FILE" || "$ROOT/pom.xml" -nt "$CP_FILE" ]]; then
  echo ".. 首次运行，正在准备依赖 classpath"
  mvn -o -q compile
  mvn -o -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
fi

exec java -cp "$ROOT/target/classes:$(cat "$CP_FILE")" \
  com.example.domain.zsxq.crawl.ZsxqApiCrawlCli \
  "${@:-crawl-output/zsxq-api}"
