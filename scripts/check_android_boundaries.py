"""Small CI guard for the approved Android module and visual boundaries."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
ALLOWED = {
    "app": {":core:domain", ":core:data", ":core:knowledge", ":core:designsystem"},
    "core/domain": set(),
    "core/designsystem": set(),
    "core/data": {":core:domain"},
    "core/knowledge": {":core:domain"},
}
PROJECT = re.compile(r'\bproject\s*\(\s*(?:path\s*=\s*)?"([^"]+)"\s*\)', re.MULTILINE)


def dependency_errors(module, source):
    return [f"forbidden module dependency {dependency}" for dependency in PROJECT.findall(source)
            if dependency not in ALLOWED[module]]


def main():
    errors = []
    for module, allowed in ALLOWED.items():
        build = ROOT / "android" / module / "build.gradle.kts"
        for problem in dependency_errors(module, build.read_text(encoding="utf-8")):
            errors.append(f"{build.relative_to(ROOT)}: {problem}")
    for source in (ROOT / "android/app/src/main").rglob("*.kt"):
        for number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), 1):
            if re.search(r"\bColor\s*\(\s*0[xX]", line):
                errors.append(f"{source.relative_to(ROOT)}:{number}: use designsystem color tokens")
    for source in (ROOT / "android/core/designsystem/src/main").rglob("*.kt"):
        for number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), 1):
            if re.match(r"\s*import\s+com\.shengjidaguai\.goutoujunshi\.(domain|data|knowledge|counsel|ui)\.", line):
                errors.append(f"{source.relative_to(ROOT)}:{number}: designsystem must own its display models")
    for error in errors:
        print(error, file=sys.stderr)
    if not errors:
        print("Android module and color boundaries passed")
    return bool(errors)


if __name__ == "__main__":
    sys.exit(main())
