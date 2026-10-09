# 从原版 strings.xml 抽取缺失条目合并进本仓（C 组设置全家桶资源搬运）
import re, sys, io

SRC_EN = "GraceLauncher-src/app/src/main/res/values/strings.xml"
SRC_ZH = "GraceLauncher-src/app/src/main/res/values-zh-rCN/strings.xml"
DST_EN = "app/src/main/res/values/strings.xml"
DST_ZH = "app/src/main/res/values-zh-rCN/strings.xml"

needed = set()
with io.open("tools/needed_refs.txt", encoding="utf-8") as f:
    for line in f:
        m = re.search(r"R\.(string|plurals)\.([a-zA-Z0-9_]+)", line)
        if m:
            needed.add(m.group(2))

def load_names(path):
    with io.open(path, encoding="utf-8") as f:
        return f.read()

def extract_entries(src_xml, needed):
    """返回 (strings_map, plurals_map)，保留原始条目全文（含注释不保留）。"""
    strings, plurals = {}, {}
    # <string name="x" ...>...</string>（单行/多行，非贪婪）
    for m in re.finditer(r'<string\s+name="([a-zA-Z0-9_]+)"[^>]*>.*?</string>', src_xml, re.S):
        name = m.group(1)
        if name in needed and name not in strings:
            strings[name] = m.group(0)
    for m in re.finditer(r'<plurals\s+name="([a-zA-Z0-9_]+)"[^>]*>.*?</plurals>', src_xml, re.S):
        name = m.group(1)
        if name in needed and name not in plurals:
            plurals[name] = m.group(0)
    return strings, plurals

def merge(dst_path, src_path, tag):
    dst = load_names(dst_path)
    have = set(re.findall(r'name="([a-zA-Z0-9_]+)"', dst))
    src = load_names(src_path)
    strings, plurals = extract_entries(src, needed)
    add = [v for k, v in sorted(strings.items()) if k not in have] + \
          [v for k, v in sorted(plurals.items()) if k not in have]
    if not add:
        print(f"{dst_path}: nothing to add")
        return
    block = f"\n    <!-- {tag}（C 组设置全家桶搬运） -->\n    " + "\n    ".join(add) + "\n\n"
    new = dst.replace("</resources>", block + "</resources>")
    with io.open(dst_path, "w", encoding="utf-8") as f:
        f.write(new)
    print(f"{dst_path}: +{len(add)} entries")

merge(DST_EN, SRC_EN, "GraceLauncher settings strings (EN)")
merge(DST_ZH, SRC_ZH, "GraceLauncher settings strings (zh-CN)")
