#!/usr/bin/env python3
"""
宿主类方法表导出 —— 移植时用来 diff 新旧版本的小米桌面。

用途：反编译目标宿主的某个类，打印【方法签名 + 字段】表，
      与 app/src/host/java/** 里的桩逐条对比，找出签名漂移。

用法：
    # 单个类
    python tools/dump_class_methods.py <宿主机APK> \
        com.miui.home.launcher.allapps.LauncherMode

    # 一次性导出移植关心的全部类（含 Mode/Controller/SuperDraglayer/Launcher）
    python tools/dump_class_methods.py <宿主机APK> --preset

    # 与基准桩文件对比（粗略，只比方法名+参数个数）
    python tools/dump_class_methods.py <宿主机APK> \
        com.miui.home.launcher.allapps.LauncherMode \
        --diff app/src/host/java/com/miui/home/launcher/allapps/LauncherMode.java

依赖：androguard  (pip install androguard)
"""

import argparse
import os
import re
import sys

# 移植最关心的类（--preset 一次全导）
PRESET_CLASSES = [
    "com.miui.home.launcher.allapps.LauncherMode",
    "com.miui.home.launcher.allapps.LauncherModeController",
    "com.miui.home.launcher.SuperDraglayer",
    "com.miui.home.launcher.Launcher",
]


def load_dex(apk_path):
    try:
        from androguard.core.dex import DEX
    except ImportError:
        sys.exit("缺少 androguard，请先安装：pip install androguard")
    import zipfile

    # androguard 默认把解析过程 DEBUG 打到 stdout，这里压掉（只保留我们的表格输出）
    try:
        from loguru import logger
        logger.remove()
    except ImportError:
        pass

    dexes = []
    with zipfile.ZipFile(apk_path) as z:
        names = sorted(n for n in z.namelist()
                       if n.startswith("classes") and n.endswith(".dex"))
        for n in names:
            data = z.read(n)
            d = DEX(data)
            dexes.append(d)
    return dexes


def find_class(dexes, qualified):
    for d in dexes:
        for c in d.get_classes():
            if c.get_name() == "L" + qualified.replace(".", "/") + ";":
                return c
    return None


def render(cls):
    """输出方法/字段表"""
    out = []
    out.append("=" * 78)
    out.append("CLASS  " + cls.get_name()[1:-1].replace("/", "."))
    sup = cls.get_superclassname()
    if sup:
        out.append("SUPER  " + sup[1:-1].replace("/", "."))
    out.append("=" * 78)

    out.append("")
    out.append("--- METHODS (%d) ---" % len(list(cls.get_methods())))
    for m in sorted(cls.get_methods(), key=lambda x: x.get_name()):
        name = m.get_name()
        proto = m.get_descriptor()
        access = []
        if m.get_access_flags_string():
            access.append(m.get_access_flags_string())
        args = ""
        ret = "?"
        mm = re.match(r"\((.*)\)(.+)", proto)
        if mm:
            raw_args, ret = mm.group(1), mm.group(2)
            args = ", ".join(split_descriptor(raw_args))
            ret = pretty(ret)
        abstract = "  [ABSTRACT]" if "abstract" in (m.get_access_flags_string() or "") else ""
        out.append("  %-28s (%s) -> %s%s" % (name, args, ret, abstract))

    out.append("")
    out.append("--- FIELDS (%d) ---" % len(list(cls.get_fields())))
    for f in sorted(cls.get_fields(), key=lambda x: x.get_name()):
        out.append("  %-32s %s" % (f.get_name(), pretty(f.get_descriptor())))

    return "\n".join(out)


def split_descriptor(s):
    """把 DEX 参数描述串切分成人类可读类型列表"""
    out, i = [], 0
    while i < len(s):
        start = i
        while i < len(s) and s[i] == "[":
            i += 1
        if i >= len(s):
            break
        if s[i] == "L":
            j = s.find(";", i)
            if j < 0:
                break
            out.append(pretty(s[start:j + 1]))
            i = j + 1
        else:
            out.append(pretty(s[start:i + 1]))
            i += 1
    return [t for t in out if t and t != "?"] or ["<none>"]


def pretty(desc):
    """DEX 类型描述 → 人类可读"""
    if not desc:
        return "?"
    arr = desc.count("[")
    d = desc[arr:]
    prim = {"V": "void", "Z": "boolean", "B": "byte", "S": "short",
            "C": "char", "I": "int", "J": "long", "F": "float", "D": "double"}
    if d in prim:
        t = prim[d]
    elif d.startswith("L") and d.endswith(";"):
        t = d[1:-1].replace("/", ".")
    else:
        t = d
    return t + "[]" * arr


def diff_against_stub(rendered, stub_path):
    """
    极粗粒度 diff：比对方法名集合。
    重点标出【抽象方法】—— 桩里没声明 / CustomLauncherMode 没实现的话，
    运行期会 AbstractMethodError 直接崩桌面。其余未声明的普通方法是安全的
    （运行期自然继承宿主实现）。
    """
    try:
        stub = open(stub_path, encoding="utf-8").read()
    except OSError as e:
        return "无法读取桩文件 %s: %s" % (stub_path, e)

    host_methods = set(re.findall(r"^\s{2}(\w+)\s+\(", rendered, re.M))
    host_abstract = set(re.findall(r"^\s{2}(\w+)\s+\(.*\)\s+->\s+\S+\s+\[ABSTRACT\]", rendered, re.M))
    stub_methods = set(re.findall(r"\b(\w+)\s*\([^)]*\)\s*(?:throws\s+\w+\s*)?\{", stub))
    stub_abstract = set(re.findall(r"\babstract\b[^;{]*?\b(\w+)\s*\(", stub))

    missing_abstract = sorted(host_abstract - stub_abstract - stub_methods)
    only_host = sorted(host_methods - stub_methods - host_abstract)

    lines = ["", "=" * 78, "DIFF vs 桩文件: " + stub_path, "=" * 78]

    if missing_abstract:
        lines.append("  [!!] 宿主【抽象方法】桩中未声明 —— 必须补进桩 + 在 CustomLauncherMode 实现：")
        for n in missing_abstract:
            lines.append("       * " + n)
        lines.append("       否则运行期 AbstractMethodError（桌面崩溃）。")
    else:
        lines.append("  [OK] 宿主全部抽象方法都已在桩中声明。")

    if only_host:
        lines.append("")
        lines.append("  [i]  宿主有、桩未声明的普通方法（安全——运行期自然继承宿主实现）：")
        for n in only_host:
            lines.append("       - " + n)

    lines.append("")
    lines.append("  [i]  更严格的做法：把本表与 app/src/host/java/** 逐条人工比对参数类型，")
    lines.append("       参数类型变化会导致 override 变重载，且【不报错、只行为异常】。")
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser(description="宿主类方法表导出（移植 diff 用）")
    ap.add_argument("apk", help="小米桌面 APK 路径")
    ap.add_argument("klass", nargs="?", help="全限定类名，如 com.miui.home.launcher.allapps.LauncherMode")
    ap.add_argument("--preset", action="store_true", help="一次导出移植关心的全部类")
    ap.add_argument("--diff", metavar="STUB.java", help="与桩文件做粗略 diff")
    ap.add_argument("--out", metavar="FILE", help="同时写入文件（默认仅 stdout）")
    args = ap.parse_args()

    if not args.preset and not args.klass:
        ap.error("需要 <klass> 或 --preset")

    print("加载 %s ..." % args.apk, file=sys.stderr)
    dexes = load_dex(args.apk)
    print("  %d 个 dex 已载入" % len(dexes), file=sys.stderr)

    targets = PRESET_CLASSES if args.preset else [args.klass]
    chunks = []
    for q in targets:
        cls = find_class(dexes, q)
        if cls is None:
            chunks.append("=" * 78 + "\nCLASS NOT FOUND: " + q + "\n" + "=" * 78)
            continue
        text = render(cls)
        chunks.append(text)
        if args.diff:
            chunks.append(diff_against_stub(text, args.diff))

    result = "\n\n".join(chunks)
    print(result)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(result)
        print("\n已写入: " + args.out, file=sys.stderr)


if __name__ == "__main__":
    main()
