#!/usr/bin/env python3
"""Static check used instead of an Android build on a machine without the SDK.

It verifies the things that break a resource-based Compose app silently:

1. every ``R.string`` / ``R.plurals`` / ``R.array`` used in Kotlin exists in
   ``values/strings.xml``;
2. the isiZulu (``values-zu``) and Sesotho (``values-st``) catalogs have the same
   keys as English, so the language switch never falls back to English silently;
3. placeholders such as ``%1$s`` survive translation;
4. no duplicate keys and every resources XML file is well formed.

Exit code is 0 when everything is fine, 1 with a per-file report otherwise.
"""

from __future__ import annotations

import collections
import pathlib
import re
import sys
import xml.dom.minidom

RES = pathlib.Path("app/src/main/res")
SRC = pathlib.Path("app/src/main/java")
MANIFEST = pathlib.Path("app/src/main/AndroidManifest.xml")

KEY_RE = re.compile(r'<(?:string|string-array|plurals|integer-array)\s+name="([^"]+)"')
VALUE_RE = re.compile(r'<string name="([^"]+)">(.*?)</string>', re.S)
PLACEHOLDER_RE = re.compile(r"%(\d+\$[sd])")
USED_RE = re.compile(r"R\.(string|plurals|array)\.([A-Za-z0-9_]+)")
XML_REF_RE = re.compile(r"@(drawable|color|style|string|mipmap|xml)/([A-Za-z0-9_.]+)")
DEF_RE = re.compile(r'<(?:drawable|color|style|string)\s+name="([^"]+)"')

problems: list[str] = []


def read(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8")


def keys(path: pathlib.Path) -> set[str]:
    return set(KEY_RE.findall(read(path)))


def main() -> int:
    # 1. every resources XML file must parse
    for path in sorted(RES.rglob("*.xml")):
        try:
            xml.dom.minidom.parseString(path.read_bytes())
        except Exception as error:  # noqa: BLE001 - report, do not crash
            problems.append(f"{path}: not well-formed XML ({error})")

    used: dict[str, set[str]] = collections.defaultdict(set)
    for path in SRC.rglob("*.kt"):
        for kind, name in USED_RE.findall(read(path)):
            used[kind].add(name)

    catalogs = {
        "en": RES / "values/strings.xml",
        "zu": RES / "values-zu/strings.xml",
        "st": RES / "values-st/strings.xml",
    }
    defined = {code: keys(path) for code, path in catalogs.items()}

    for code, path in catalogs.items():
        if not path.exists():
            problems.append(f"{path}: missing catalog")
            continue

    english = defined["en"]
    for kind, names in used.items():
        missing = sorted(names - english)
        if missing:
            problems.append(f"values/strings.xml: used but not defined -> {', '.join(missing)}")

    for code in ("zu", "st"):
        missing = sorted(english - defined[code])
        if missing:
            problems.append(
                f"values-{code}/strings.xml: {len(missing)} key(s) missing vs English -> "
                + ", ".join(missing[:12])
                + (" ..." if len(missing) > 12 else "")
            )

    values = {code: dict(VALUE_RE.findall(read(path))) for code, path in catalogs.items()}
    for key, english_value in values["en"].items():
        expected = sorted(PLACEHOLDER_RE.findall(english_value))
        for code in ("zu", "st"):
            translated = values[code].get(key)
            if translated is None:
                continue
            actual = sorted(PLACEHOLDER_RE.findall(translated))
            if actual != expected:
                problems.append(
                    f"{key}: placeholders {actual} in values-{code} do not match English {expected}"
                )

    for code, path in catalogs.items():
        names = KEY_RE.findall(read(path))
        duplicates = sorted(name for name, count in collections.Counter(names).items() if count > 1)
        if duplicates:
            problems.append(f"values-{code}/strings.xml: duplicate keys -> {', '.join(duplicates)}")

    # 2. @drawable/@style/@string references used from XML and the manifest
    resource_defs: set[str] = set()
    for path in RES.rglob("*.xml"):
        if path.parent.name.startswith("values"):
            resource_defs |= set(DEF_RE.findall(read(path)))
        else:
            resource_defs.add(path.stem)

    for path in sorted(RES.rglob("*.xml")) + [MANIFEST]:
        for kind, name in XML_REF_RE.findall(read(path)):
            # Material supplies these styles. The Android resource linker verifies them.
            if kind == "style" and name.startswith("Widget.Material3."):
                continue
            if kind == "mipmap" or name in resource_defs:
                continue
            problems.append(f"{path}: @{kind}/{name} has no definition")

    if problems:
        print("VIBE resource check failed:")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    print(
        "VIBE resource check passed: "
        f"{len(english)} keys in English, isiZulu and Sesotho; "
        f"{len(used['string'])} strings, {len(used['plurals'])} plurals and "
        f"{len(used['array'])} arrays used from Kotlin all resolve."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
