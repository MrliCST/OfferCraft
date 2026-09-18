#!/usr/bin/env bash
#
# 把日常 Chrome 的登录态同步到专用 profile 副本（~/.config/google-chrome-jlra）。
#
# 为什么要这个脚本：程序不能用你日常那份 profile（正被 Chrome 占着），只能用副本；
# 而副本是**快照** —— 你在 Chrome 里重新登录、token 刷新之后，不同步一次就会掉线，
# 表现是"代码没动，突然就抓到登录墙了"。
#
# 什么时候跑：
#   1. 刚在某个网站登录完，想让程序也能看到登录后的内容；
#   2. 程序突然只能抓到登录页时（先跑它再重试）。
#
# 用法：
#   ./scripts/sync-chrome-profile.sh
#
set -euo pipefail

SRC="${HOME}/.config/google-chrome"
DST="${HOME}/.config/google-chrome-jlra"

if [ ! -d "$SRC" ]; then
    echo "找不到源 profile：$SRC" >&2
    exit 1
fi

mkdir -p "${DST}/Default"

# 加密密钥（cookie 靠它解密），必须一起复制，否则 cookie 全是乱码
cp "${SRC}/Local State" "${DST}/"

# Cookies 后面必须带通配符：Chrome 运行期间新数据还在 Cookies-wal 里，
# 只复制 Cookies 会拿到一份"几乎是空的"旧库（这次就踩过，1273 条变 1 条）。
cp "${SRC}/Default"/Cookies* "${DST}/Default/" 2>/dev/null || true

cp "${SRC}/Default/Preferences" "${DST}/Default/" 2>/dev/null || true
cp "${SRC}/Default/Web Data" "${DST}/Default/" 2>/dev/null || true

# 很多站点把 token 放在 localStorage / IndexedDB 里，光复制 cookie 不够
for d in "Local Storage" "Session Storage" "IndexedDB"; do
    if [ -d "${SRC}/Default/${d}" ]; then
        rm -rf "${DST}/Default/${d}"
        cp -r "${SRC}/Default/${d}" "${DST}/Default/"
    fi
done

echo "登录态已同步到 ${DST}"
