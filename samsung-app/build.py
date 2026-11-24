"""Build the Samsung signage app, one signed package per screen.

    python samsung-app/build.py --store holt --codes L1-L6,R1-R6 --profile tmrw

Each screen gets its own package because Custom App doesn't tell an app which
address it was installed from, so the screen's position is built in. Typing
https://screens.smartechworld.com/tv/holt/L1 in Custom App fetches
/tv/holt/L1/sssp_config.xml and then /tv/holt/L1/Screens.wgt, which serve.py
finds under app/tv/apps/.

Signing needs the Tizen CLI and a Samsung certificate profile whose
distributor certificate lists every screen's Unique Device ID. The certificate
and its password live outside this repo (only the profile name is used here).
--unsigned makes plain zips in samsung-app/out for testing the contents; a
screen won't install those, so they're never written to app/.

Raise samsung-app/VERSION before rebuilding: a screen only downloads the app
again when <ver> in sssp_config.xml changes.
"""
import argparse
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
OUT = HERE / "out"
APPS = REPO / "app" / "tv" / "apps"
NAME = "Screens"          # must match <name> in config.xml and <widgetname>
SIGNED = {"author-signature.xml", "signature1.xml"}


def codes(spec: str) -> list:
    out = []
    for part in (p.strip() for p in spec.split(",")):
        m = re.fullmatch(r"([A-Za-z]+)(\d+)-([A-Za-z]+)(\d+)", part)
        if m and m[1].upper() == m[3].upper():
            out += [f"{m[1].upper()}{n}" for n in range(int(m[2]), int(m[4]) + 1)]
        elif re.fullmatch(r"[A-Za-z0-9]+", part):
            out.append(part.upper())
        else:
            sys.exit(f"can't read screen code {part!r}")
    return out


def stage(store: str, code: str, version: str, origin: str) -> Path:
    """A clean folder holding exactly what goes in this screen's package."""
    d = OUT / "stage" / store / code
    if d.exists():
        shutil.rmtree(d)
    d.mkdir(parents=True)
    subs = {"@VERSION@": version, "@ORIGIN@": origin, "@CODE@": code, "@PATH@": f"/tv/{store}/{code}"}
    for src in sorted((HERE / "src").iterdir()):
        if src.suffix in (".xml", ".html", ".js"):
            text = src.read_text(encoding="utf-8")
            for k, v in subs.items():
                text = text.replace(k, v)
            (d / src.name).write_text(text, encoding="utf-8", newline="\n")
        else:
            shutil.copyfile(src, d / src.name)
    return d


def sssp_config(version: str, size: int) -> str:
    return (f"<widget>\n    <ver>{version}</ver>\n    <size>{size}</size>\n"
            f"    <widgetname>{NAME}</widgetname>\n    <webtype>tizen</webtype>\n</widget>\n")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--store", default="holt")
    ap.add_argument("--codes", default="L1-L6,R1-R6")
    ap.add_argument("--origin", default="https://screens.smartechworld.com")
    ap.add_argument("--profile", help="Tizen certificate profile name")
    ap.add_argument("--tizen", default="tizen", help="path to tizen / tizen.bat")
    ap.add_argument("--unsigned", action="store_true")
    a = ap.parse_args()
    if not a.unsigned and not a.profile:
        ap.error("--profile is needed to sign (or use --unsigned to test the contents)")
    if not re.fullmatch(r"[a-z0-9-]+", a.store):
        ap.error("--store is the short name in the address, e.g. holt")
    version = (HERE / "VERSION").read_text(encoding="utf-8").strip()
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        sys.exit(f"samsung-app/VERSION must be x.y.z, not {version!r}")

    for code in codes(a.codes):
        d = stage(a.store, code, version, a.origin.rstrip("/"))
        dest = (OUT if a.unsigned else APPS) / a.store / code.lower()
        dest.mkdir(parents=True, exist_ok=True)
        wgt = dest / f"{NAME}.wgt"
        if a.unsigned:
            with zipfile.ZipFile(wgt, "w", zipfile.ZIP_DEFLATED) as z:
                for f in sorted(d.iterdir()):
                    z.write(f, f.name)
        else:
            tmp = OUT / "signed" / a.store / code
            if tmp.exists():
                shutil.rmtree(tmp)
            tmp.mkdir(parents=True)
            subprocess.run([a.tizen, "package", "-t", "wgt", "-s", a.profile,
                            "-o", str(tmp), "--", str(d)], check=True)
            built = list(tmp.glob("*.wgt"))
            if len(built) != 1:
                sys.exit(f"{code}: expected one package from the Tizen CLI, got {len(built)}")
            with zipfile.ZipFile(built[0]) as z:
                missing = SIGNED - set(z.namelist())
            if missing:
                sys.exit(f"{code}: the package isn't signed ({', '.join(sorted(missing))} missing)")
            shutil.copyfile(built[0], wgt)
        size = wgt.stat().st_size
        (dest / "sssp_config.xml").write_text(sssp_config(version, size), encoding="utf-8", newline="\n")
        print(f"{a.store}/{code}: {size} bytes, v{version}{' UNSIGNED' if a.unsigned else ''}")


if __name__ == "__main__":
    main()
