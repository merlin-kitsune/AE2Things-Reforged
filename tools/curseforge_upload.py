#!/usr/bin/env python3
"""Upload a release jar to CurseForge, and optionally update the project description.

Facts this script encodes (all verified on 2026-11-05):

* The upload endpoint is the LEGACY one and it is hyphenated:
      POST https://minecraft.curseforge.com/api/projects/{projectId}/upload-file
  "uploadFile" (no hyphen) is the ASP.NET error path and answers a 302 to
  /error?aspxerrorpath=..., which is easy to mistake for a failure of the token.
* Authentication is the header X-Api-Token. The token is a UUID-form project token
  from the CurseForge project page, NOT a Core API key.
* gameVersions must carry four ids (Client, Server, Minecraft <version>, Modloader);
  they are resolved by TYPE NAME through /game/version-types, because the plain
  /game/versions list contains the same name (for example "1.21.1") under several
  unrelated types.
* CurseForge sits behind Cloudflare. The stock Python agent is refused with
  "403 browser_signature_banned" (error 1010), and a redirect-following urllib POST
  hides the 302 above, so every request here goes through curl with an explicit,
  honest User-Agent.

    # plan only, no network
    python tools/curseforge_upload.py --project 1729932 \
        --file build/libs/AE2-Things-1.4.5.jar --mc 1.21.1 --loader neoforge --dry-run

    # the real upload
    CURSEFORGE_TOKEN=... python tools/curseforge_upload.py --project 1729932 \
        --file build/libs/AE2-Things-1.4.5.jar --mc 1.21.1 --loader neoforge \
        --changelog-file docs/curseforge/CHANGELOG-1.4.5.md \
        --display-name "AE2 Things Reforged 1.4.5 (NeoForge 1.21.1)"

    # the project page
    CURSEFORGE_TOKEN=... python tools/curseforge_upload.py --project 1729932 \
        --description-file docs/curseforge/DESCRIPTION.md
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile

API = "https://minecraft.curseforge.com/api"
CORE_API = "https://api.curseforge.com/v1"
USER_AGENT = ("AE2ThingsReforged-Uploader/1.1 "
              "(+https://github.com/merlin-kitsune/AE2Things-Reforged)")
CURL = shutil.which("curl") or "curl"


def token_from(args):
    tok = (os.environ.get("CURSEFORGE_TOKEN") or "").strip()
    if not tok and args.token_file:
        with open(args.token_file, "r", encoding="utf-8") as fh:
            tok = fh.read().strip()
    return tok


def curl(argv, timeout=300):
    """Run curl, return (status_code, body_text). status 0 means curl itself failed."""
    cmd = [CURL, "-sS", "--max-time", str(timeout),
           "-A", USER_AGENT, "-H", "Accept: application/json"]
    cmd += argv
    try:
        out = subprocess.run(cmd, capture_output=True, timeout=timeout + 30)
    except Exception as exc:  # noqa: BLE001 - surface any curl problem as a body
        return 0, "curl failed: %s" % exc
    body = out.stdout.decode("utf-8", "replace")
    err = out.stderr.decode("utf-8", "replace").strip()
    if out.returncode != 0 and not body:
        return 0, err or ("curl exit %d" % out.returncode)
    return out.returncode if False else _status(body), _strip_status(body)


def _status(body):
    """curl -w appends the status code as the very last line."""
    tail = body.rstrip("\n").rsplit("\n", 1)
    if len(tail) == 2 and tail[1].strip().isdigit():
        return int(tail[1].strip())
    return 0


def _strip_status(body):
    tail = body.rstrip("\n").rsplit("\n", 1)
    if len(tail) == 2 and tail[1].strip().isdigit():
        return tail[0]
    return body


def api_get(path, token):
    """GET an API path (relative to API) and return decoded JSON or raise SystemExit."""
    status, body = curl(["-H", "X-Api-Token: " + token,
                         "-w", "\n%{http_code}", API + path])
    if status != 200:
        raise SystemExit("GET %s failed: HTTP %s %s" % (path, status, body[:400]))
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        raise SystemExit("GET %s did not answer JSON: %s" % (path, body[:400]))


def resolve_game_versions(token, mc, loader):
    """(Client, Server, Minecraft <mc>, Modloader <loader>) by TYPE NAME."""
    types = {str(t["id"]): t["name"] for t in api_get("/game/version-types", token)}
    versions = api_get("/game/versions", token)

    def pick(type_name=None, type_prefix=None, name=None, ci=False):
        for v in versions:
            tname = types.get(str(v.get("gameVersionTypeID")), "")
            if type_name is not None and tname != type_name:
                continue
            if type_prefix is not None and not tname.startswith(type_prefix):
                continue
            a = str(v.get("name", ""))
            if a.lower() == name.lower() if ci else a == name:
                return v
        return None

    chosen = []
    for env in ("Client", "Server"):
        v = pick(type_name="Environment", name=env)
        if v is None:
            raise SystemExit("CurseForge has no Environment/%s version" % env)
        chosen.append(v["id"])
    v = pick(type_prefix="Minecraft ", name=mc)
    if v is None:
        raise SystemExit("CurseForge has no Minecraft %r version" % mc)
    chosen.append(v["id"])
    v = pick(type_name="Modloader", name=loader, ci=True)
    if v is None:
        raise SystemExit("CurseForge has no Modloader %r" % loader)
    chosen.append(v["id"])
    detail = dict(zip(("Client", "Server", mc, loader), chosen))
    return chosen, detail


def upload_file(token, project, jar, metadata):
    """POST the jar and its metadata.

    The metadata JSON goes through a FILE (-F "metadata=<file"): passing it inline makes
    curl treat ";" and friends as form-field syntax, and CurseForge then answers
    {"errorCode":1002,"errorMessage":"Error in field `metadata`: Invalid JSON."}
    """
    handle, path = tempfile.mkstemp(suffix=".json", prefix="cf-meta-")
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as fh:
            json.dump(metadata, fh, ensure_ascii=False)
        status, body = curl([
            "-X", "POST", "-H", "X-Api-Token: " + token,
            "-F", "metadata=<" + path,
            "-F", "file=@" + jar + ";type=application/java-archive",
            "-w", "\n%{http_code}",
            "%s/projects/%s/upload-file" % (API, project),
        ])
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass
    return status, body


def push_description(token, project, path):
    status, body = curl([
        "-X", "POST", "-H", "X-Api-Token: " + token,
        "-F", "description=<" + path,
        "-w", "\n%{http_code}",
        "%s/projects/%s/description" % (API, project),
    ])
    if status == 200:
        return status, body
    # Fall back to the Core API, which takes the same value via PUT /v1/mods/{id}
    with open(path, "r", encoding="utf-8") as fh:
        payload = json.dumps({"description": fh.read()})
    status2, body2 = curl([
        "-X", "PUT", "-H", "x-api-key: " + token, "-H", "Content-Type: application/json",
        "--data-binary", payload, "-w", "\n%{http_code}",
        "%s/mods/%s" % (CORE_API, project),
    ])
    return status2, body2


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

    if args.file and not os.path.isfile(args.file):
        raise SystemExit("no such file: %s" % args.file)

    if args.file:
        changelog = ""
        if args.changelog_file:
            with open(args.changelog_file, "r", encoding="utf-8") as fh:
                changelog = fh.read()
        metadata = {
            "changelog": changelog,
            "changelogType": args.changelog_type,
            "displayName": args.display_name or os.path.basename(args.file),
            "releaseType": args.release_type,
        }
        if args.mc and args.loader:
            if token:
                print("[1/2] resolving game version ids by type name")
                ids, detail = resolve_game_versions(token, args.mc, args.loader)
                metadata["gameVersions"] = ids
                print("      " + json.dumps(detail, ensure_ascii=False))
            else:
                print("[1/2] skipped (no token, dry run)")
        print("[2/2] file metadata")
        shown = dict(metadata)
        shown["changelog"] = (shown.get("changelog") or "")[:120] + ("..." if len(shown.get("changelog") or "") > 120 else "")
        print(json.dumps(shown, indent=2, ensure_ascii=False))

        if args.dry_run:
            print("[dry-run] nothing uploaded")
            return
        if not metadata.get("gameVersions"):
            raise SystemExit("refusing to upload without gameVersions (pass --mc and --loader)")
        print("uploading %s ..." % args.file)
        status, body = upload_file(token, args.project, args.file, metadata)
        print("upload-file -> HTTP %s" % status)
        print(body[:1500])
        if status != 200:
            raise SystemExit(1)

    if args.description_file:
        if args.dry_run:
            print("[dry-run] would push %s as the project description" % args.description_file)
            return
        status, body = push_description(token, args.project, args.description_file)
        print("description -> HTTP %s" % status)
        print(body[:600])
        if status != 200:
            raise SystemExit(1)


if __name__ == "__main__":
    main()
