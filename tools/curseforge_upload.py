#!/usr/bin/env python3
"""Upload a built jar to CurseForge, and optionally update the project description.

Stdlib only. The API token is read from the CURSEFORGE_TOKEN environment variable
(or --token-file); it is never printed.

    # see what would be sent (no network when the token is absent)
    python tools/curseforge_upload.py --project 123456 \
        --file build/libs/AE2-Things-1.4.4.jar --mc 1.21.1 --loader neoforge \
        --changelog-file docs/curseforge/CHANGELOG-1.4.4.md --dry-run

    # the real upload
    CURSEFORGE_TOKEN=xxx python tools/curseforge_upload.py --project 123456 \
        --file forge-1.20.1/build/libs/AE2-Things-1.3.0.jar --mc 1.20.1 --loader forge \
        --changelog-file docs/curseforge/CHANGELOG-1.3.0.md --release-type release

    # push the project page description (HTML)
    CURSEFORGE_TOKEN=xxx python tools/curseforge_upload.py --project 123456 \
        --description-file docs/curseforge/DESCRIPTION.html
"""
import argparse
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.request
import uuid

API = "https://minecraft.curseforge.com/api"

# game version type ids used by the CurseForge website (resolved by name when possible)
EXPECTED_TYPES = {
    "minecraft": 75125,
    "forge": 68441,
    "neoforge": 1010836,
    "fabric": 68442,
}


def token_from(args):
    tok = os.environ.get("CURSEFORGE_TOKEN", "").strip()
    if not tok and args.token_file:
        with open(args.token_file, "r", encoding="utf-8") as fh:
            tok = fh.read().strip()
    return tok


def request(url, token, data=None, content_type=None, method=None):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("X-Api-Token", token)
    req.add_header("Accept", "application/json")
    if content_type:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")


def multipart(fields, files):
    """fields: dict name->str, files: dict name->(filename, bytes, mimetype)."""
    boundary = "----dshcf" + uuid.uuid4().hex
    out = bytearray()
    for name, value in fields.items():
        out += ("--%s\r\n" % boundary).encode()
        out += ('Content-Disposition: form-data; name="%s"\r\n\r\n' % name).encode()
        out += value.encode("utf-8") + b"\r\n"
    for name, (filename, blob, mime) in files.items():
        out += ("--%s\r\n" % boundary).encode()
        out += ('Content-Disposition: form-data; name="%s"; filename="%s"\r\n'
                % (name, filename)).encode()
        out += ("Content-Type: %s\r\n\r\n" % mime).encode()
        out += blob + b"\r\n"
    out += ("--%s--\r\n" % boundary).encode()
    return bytes(out), "multipart/form-data; boundary=%s" % boundary


def resolve_game_versions(token, mc, loader):
    status, body = request(API + "/game/versions", token)
    if status != 200:
        raise SystemExit("GET /game/versions failed: %s %s" % (status, body[:400]))
    versions = json.loads(body)
    wanted_loader = loader.lower()
    mc_hits = [v for v in versions if str(v.get("name")) == mc]
    loader_hits = [v for v in versions if str(v.get("name")).lower() == wanted_loader]
    print("  game versions named %r : %s" % (mc, [(v["id"], v.get("gameVersionTypeID")) for v in mc_hits]))
    print("  loaders named %r       : %s" % (loader, [(v["id"], v.get("gameVersionTypeID")) for v in loader_hits]))
    choice = []
    for hits, want_type, label in ((mc_hits, EXPECTED_TYPES["minecraft"], "minecraft"),
                                   (loader_hits, EXPECTED_TYPES.get(wanted_loader), loader)):
        if not hits:
            raise SystemExit("no CurseForge game version named %r" % label)
        typed = [v for v in hits if v.get("gameVersionTypeID") == want_type]
        pick = typed[0] if typed else hits[0]
        choice.append(pick["id"])
    return choice


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", required=True, help="CurseForge project id (numeric)")
    ap.add_argument("--file", help="jar to upload")
    ap.add_argument("--mc", help="e.g. 1.20.1")
    ap.add_argument("--loader", help="forge | neoforge | fabric")
    ap.add_argument("--changelog-file")
    ap.add_argument("--changelog-type", default="markdown", choices=["markdown", "text", "html"])
    ap.add_argument("--release-type", default="release", choices=["release", "beta", "alpha"])
    ap.add_argument("--display-name")
    ap.add_argument("--description-file", help="HTML file to push as the project page description")
    ap.add_argument("--token-file")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    token = token_from(args)
    if not token and not args.dry_run:
        raise SystemExit("CURSEFORGE_TOKEN is not set (or pass --token-file)")
    if not token:
        print("[warn] no token: only the local metadata can be shown")

    meta = {}
    if args.file:
        if not os.path.isfile(args.file):
            raise SystemExit("no such file: %s" % args.file)
        changelog = ""
        if args.changelog_file:
            with open(args.changelog_file, "r", encoding="utf-8") as fh:
                changelog = fh.read()
        meta = {
            "changelog": changelog,
            "changelogType": args.changelog_type,
            "displayName": args.display_name or os.path.basename(args.file),
            "releaseType": args.release_type,
        }
        if token and args.mc and args.loader:
            print("[1/2] resolving CurseForge game version ids")
            meta["gameVersions"] = resolve_game_versions(token, args.mc, args.loader)
        print("[2/2] file metadata")
        print(json.dumps(meta, indent=2, ensure_ascii=False)[:2000])

    if args.dry_run:
        print("[dry-run] nothing uploaded")
        return

    if args.file:
        with open(args.file, "rb") as fh:
            blob = fh.read()
        mime = mimetypes.guess_type(args.file)[0] or "application/java-archive"
        body, ctype = multipart({"metadata": json.dumps(meta, ensure_ascii=False)},
                                {"file": (os.path.basename(args.file), blob, mime)})
        status, resp = request("%s/projects/%s/uploadFile" % (API, args.project), token,
                               data=body, content_type=ctype, method="POST")
        print("uploadFile -> %s %s" % (status, resp[:2000]))
        if status != 200:
            raise SystemExit(1)

    if args.description_file:
        with open(args.description_file, "rb") as fh:
            blob = fh.read()
        body, ctype = multipart({"description": blob.decode("utf-8")}, {})
        status, resp = request("%s/projects/%s/description" % (API, args.project), token,
                               data=body, content_type=ctype, method="POST")
        print("description -> %s %s" % (status, resp[:1000]))


if __name__ == "__main__":
    main()
