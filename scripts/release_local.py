#!/usr/bin/env python3
"""Build an installable APK using a persistent private signing key."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess


ROOT = Path(__file__).resolve().parents[1]


def main():
    lock = json.loads((ROOT / "catalog.lock.json").read_text())
    catalog = ROOT / "catalog/exercise-catalog.json"
    if hashlib.sha256(catalog.read_bytes()).hexdigest() != lock["sha256"]:
        raise SystemExit("Catalog checksum differs from catalog.lock.json")
    environment = os.environ.copy()
    environment.setdefault("GRADLE_USER_HOME", str(ROOT / ".local/gradle"))
    environment.setdefault("ANDROID_USER_HOME", str(ROOT / ".local/android-user"))
    if "JAVA_HOME" not in environment and Path("/opt/homebrew/opt/openjdk@21").exists():
        environment["JAVA_HOME"] = "/opt/homebrew/opt/openjdk@21"
    signing = ROOT / ".local/signing"
    signing.mkdir(parents=True, exist_ok=True, mode=0o700)
    properties = signing / "release.properties"
    keystore = signing / "podhod.jks"
    if not properties.exists():
        if keystore.exists():
            raise SystemExit("Signing key exists without settings; restore its original settings before building")
        password = secrets.token_urlsafe(32)
        environment["PODHOD_SIGNING_PASSWORD"] = password
        keytool = str(Path(environment["JAVA_HOME"]) / "bin/keytool") if "JAVA_HOME" in environment else "keytool"
        subprocess.run([keytool, "-genkeypair", "-keystore", str(keystore), "-storetype", "JKS",
                        "-alias", "podhod", "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
                        "-dname", "CN=Podhod, OU=Mobile, O=Podhod", "-storepass:env", "PODHOD_SIGNING_PASSWORD",
                        "-keypass:env", "PODHOD_SIGNING_PASSWORD"], env=environment, check=True, capture_output=True)
        keystore.chmod(0o600)
        properties.write_text("storeFile=.local/signing/podhod.jks\nkeyAlias=podhod\nstorePassword=" + password + "\nkeyPassword=" + password + "\n")
        properties.chmod(0o600)
        environment.pop("PODHOD_SIGNING_PASSWORD", None)
    if not keystore.exists():
        raise SystemExit("Restore the existing signing key; refusing to create an incompatible update")
    subprocess.run([str(ROOT / "gradlew"), ":core:test", ":app:testDebugUnitTest", ":app:lintRelease", ":app:assembleRelease", "--console=plain"],
                   cwd=ROOT, env=environment, check=True)
    output = ROOT / "build/releases"
    output.mkdir(parents=True, exist_ok=True)
    built = ROOT / "app/build/outputs/apk/release"
    listing = json.loads((built / "output-metadata.json").read_text())
    artifact = listing["elements"][0]
    apk = output / ("Podhod-" + artifact["versionName"] + ".apk")
    shutil.copy2(built / artifact["outputFile"], apk)
    metadata = {"applicationId": listing["applicationId"], "versionName": artifact["versionName"], "versionCode": artifact["versionCode"],
                "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "catalogVersion": lock["catalogVersion"]}
    (output / "release.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print("Signed APK: " + str(apk))
    print("Keep a private backup of .local/signing; updates require the same key.")


if __name__ == "__main__":
    main()
