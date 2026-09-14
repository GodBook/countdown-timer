"""Package source and verification evidence, explicitly excluding signing secrets."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import hashlib
import json

root = Path(__file__).resolve().parents[1]
dist = root / "dist"
dist.mkdir(exist_ok=True)
metadata = json.loads((root / "app/build/outputs/apk/release/output-metadata.json").read_text())
version = metadata["elements"][0]["versionName"]
archive = dist / f"countdown-timer-source-{version}.zip"
roots = [root / name for name in (
    ".gitignore", "README.md", "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
    "gradlew", "gradlew.bat", "gradle", "app/src", "app/build.gradle.kts", "scripts", "verification", ".github", "release-notes.md"
)]
with ZipFile(archive, "w", ZIP_DEFLATED) as output:
    for entry in roots:
        for path in ([entry] if entry.is_file() else sorted(entry.rglob("*"))):
            if not path.is_file() or "__pycache__" in path.parts:
                continue
            if path.name in {"signing.properties", "local.properties"} or path.suffix in {".jks", ".keystore", ".pyc"}:
                raise RuntimeError(f"Private file must not be packaged: {path}")
            output.write(path, path.relative_to(root))
with ZipFile(archive) as result:
    assert result.testzip() is None
    assert "gradle/wrapper/gradle-wrapper.jar" in result.namelist()
for name in ("README.md",):
    (dist / name).write_bytes((root / name).read_bytes())
(dist / "verification-results.md").write_bytes((root / "verification/RESULTS.md").read_bytes())
artifacts = [dist / f"countdown-timer-{version}.apk", archive]
(dist / "SHA256SUMS.txt").write_text("".join(
    f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in artifacts
), encoding="utf-8")
print(f"Packaged {len(ZipFile(archive).namelist())} files; no signing secrets included.")
for path in artifacts:
    print(f"{path.name}: {path.stat().st_size:,} bytes")
