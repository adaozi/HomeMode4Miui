#!/usr/bin/env python3
"""
宿主类引用完整性分析 —— 回答"OS3 会不会踩 NewHomeView 同款雷"。

做法（纯静态，无类加载语义）：
  1. 收集 APK 内全部类名；
  2. 从 Launcher 沿 superclass 链上溯（到 android.*/java.* 框架类为止）；
  3. 对链上每个类，解析其【方法签名】中引用的全部类型；
  4. 报告：APK 内不存在、且非框架类的"缺失引用"。
这些缺失引用就是 getDeclaredMethods() 全量扫描会炸 NoClassDefFoundError 的雷点；
getDeclaredMethod(name, params) 惰性匹配不解析兄弟方法，天然免疫。

用法: python tools/check_class_refs.py <apk> <fqcn> [fqcn2 ...]
"""
import re
import sys
import zipfile

from androguard.core.dex import DEX

FRAMEWORK_PREFIXES = ("Ljava/", "Ljavax/", "Landroid/", "Lcom/android/", "Lkotlin/",
                      "Lkotlinx/", "Lorg/xmlpull/", "Ljava8/", "Lmicloud/")


def load(apk_path):
    dexes = []
    with zipfile.ZipFile(apk_path) as z:
        for n in sorted(x for x in z.namelist()
                        if x.startswith("classes") and x.endswith(".dex")):
            dexes.append(DEX(z.read(n)))
    return dexes


def main():
    apk = sys.argv[1]
    targets = sys.argv[2:]
    print("loading %s ..." % apk, file=sys.stderr)
    dexes = load(apk)

    all_classes = set()
    class_map = {}
    for d in dexes:
        for c in d.get_classes():
            name = c.get_name()
            all_classes.add(name)
            class_map.setdefault(name, c)

    def types_of(desc):
        return set(re.findall(r"L[\w/$\-]+;", desc))

    for target in targets:
        print("=" * 78)
        print("TARGET  " + target)
        print("=" * 78)
        cur = "L" + target.replace(".", "/") + ";"
        chain = []
        missing_in_chain = set()
        while cur and cur in class_map:
            cls = class_map[cur]
            sup = cls.get_superclassname()
            chain.append((cur, sup))
            # 方法签名里的全部类型（含参数/返回）
            for m in cls.get_methods():
                for t in types_of(m.get_descriptor()):
                    if t not in all_classes and not t.startswith(FRAMEWORK_PREFIXES):
                        missing_in_chain.add((cur, t))
            # 字段类型
            for f in cls.get_fields():
                for t in types_of(f.get_descriptor()):
                    if t not in all_classes and not t.startswith(FRAMEWORK_PREFIXES):
                        missing_in_chain.add((cur, t))
            # 上溯
            if sup and not sup.startswith(("Ljava/", "Landroid/")):
                cur = sup
            else:
                cur = None

        print("superclass chain:")
        for name, sup in chain:
            print("  %s  extends  %s" % (name[1:-1].replace("/", "."), sup))
        print()
        print("NewHomeView in APK: %s" % (
            "Lcom/miui/newhome/view/gestureview/NewHomeView;" in all_classes))
        if missing_in_chain:
            print("MISSING refs on chain (getDeclaredMethods 全量扫描会炸的点):")
            by_cls = {}
            for owner, t in sorted(missing_in_chain):
                by_cls.setdefault(owner, set()).add(t)
            for owner, ts in by_cls.items():
                print("  " + owner[1:-1].replace("/", "."))
                for t in sorted(ts):
                    print("      -> " + t[1:-1].replace("/", "."))
        else:
            print("[OK] 链上全部方法/字段引用类型均在 APK 内 —— 无缺失类雷点")


if __name__ == "__main__":
    main()
