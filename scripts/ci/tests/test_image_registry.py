"""Isolated registry/CI job contract tests, requiring no Docker or GitLab access."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
REGISTRY = "registry.example.test/group/sharewise"
COMMIT = "a" * 40
DIGESTS = {"backend": "sha256:" + "1" * 64, "frontend": "sha256:" + "2" * 64}
MOCK_CRANE = r'''#!/bin/sh
set -eu
cmd=$1
if [ "$cmd" = auth ]; then
    read -r pw || :
    [ "${FAIL_AUTH:-}" != 1 ] && [ "$pw" = "$CI_REGISTRY_PASSWORD" ] || { echo unauthorized >&2; exit 1; }
    exit 0
fi
ref=$2
if [ "${FAIL_LOOKUP:-}" = 1 ]; then
    echo 'unauthorized: bad registry token' >&2
    exit 1
fi
case "$cmd" in
    digest)
        if ! jq -er --arg ref "$ref" '.[$ref].digest // empty' "$FAKE_REGISTRY_DB"; then
            echo 'MANIFEST_UNKNOWN: 404 Not Found' >&2
            exit 1
        fi
        ;;
    config)
        jq -ec --arg ref "$ref" '
          .[$ref] | select(. != null) |
          {config:{Labels:{"org.opencontainers.image.version":.version,"org.opencontainers.image.revision":.commit}}}
        ' "$FAKE_REGISTRY_DB" || { echo MANIFEST_UNKNOWN >&2; exit 1; }
        ;;
    tag)
        case "$ref" in
            *"${FAIL_TAG_ROLE:-__no_failure__}"*) echo 'simulated partial registry failure' >&2; exit 1 ;;
        esac
        dest="${ref%@*}:$3"
        tmp=$(mktemp)
        jq -e --arg src "$ref" --arg dst "$dest" '
            if .[$src] then .[$dst] = .[$src] else error("MANIFEST_UNKNOWN") end
        ' "$FAKE_REGISTRY_DB" > "$tmp" || { rm -f "$tmp"; exit 1; }
        mv "$tmp" "$FAKE_REGISTRY_DB"
        ;;
    *) echo "Unexpected crane command $cmd" >&2; exit 1 ;;
esac
'''
MOCK_KANIKO = r'''#!/bin/sh
set -eu
for arg do
    case "$arg" in
        --destination=*) destination=${arg#--destination=} ;;
        --digest-file=*) digest_file=${arg#--digest-file=} ;;
        --image-name-with-digest-file=*) ref_file=${arg#--image-name-with-digest-file=} ;;
    esac
done
role=${destination%:*}
role=${role##*/}
if [ "${FAIL_BUILD_ROLE:-}" = "$role" ]; then
    echo 'simulated Kaniko failure' >&2
    exit 1
fi
case "$role" in
    backend) digest=$MOCK_DIGEST_BACKEND ;;
    frontend) digest=$MOCK_DIGEST_FRONTEND ;;
    *) exit 1 ;;
esac
printf '%s\n' "$digest" > "$digest_file"
printf '%s@%s\n' "${destination%:*}" "$digest" > "$ref_file"
printf '%s\n' "$@" > "$FAKE_BUILD_ARGS"
'''


class ImageRegistryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.work = Path(self.tmp.name)
        (self.work / "scripts/ci").mkdir(parents=True)
        for source in (ROOT / "scripts/ci").glob("*.sh"):
            shutil.copy(source, self.work / "scripts/ci" / source.name)
        (self.work / "image-version").mkdir()
        (self.work / "image-version/version.txt").write_text("0.1.42\n")
        (self.work / "image-evidence").mkdir()
        (self.work / "bin").mkdir()
        for name, code in (("crane", MOCK_CRANE), ("kaniko", MOCK_KANIKO)):
            path = self.work / "bin" / name
            path.write_text(code)
            path.chmod(0o755)
        self.db = self.work / "registry.json"
        self.db.write_text("{}")
        # Tests simulate their own trusted GitLab context. In particular, a
        # real dev-* tag pipeline must not leak CI_COMMIT_TAG into the mocked
        # protected-main scenario (nor other CI or mock failure controls).
        host_env = {
            name: value for name, value in os.environ.items()
            if not name.startswith(("CI_", "FAKE_", "FAIL_", "MOCK_", "KANIKO_"))
        }
        self.env = {**host_env, "PATH": str(self.work / "bin") + ":" + os.environ["PATH"],
            "FAKE_REGISTRY_DB": str(self.db), "FAKE_BUILD_ARGS": str(self.work / "build-args.txt"),
            "CI_REGISTRY": "registry.example.test", "CI_REGISTRY_IMAGE": REGISTRY,
            "CI_REGISTRY_USER": "gitlab-ci-token", "CI_REGISTRY_PASSWORD": "hidden-password",
            "CI_COMMIT_SHA": COMMIT, "CI_COMMIT_BRANCH": "main", "CI_PIPELINE_IID": "42",
            "CI_PIPELINE_ID": "1042", "CI_PIPELINE_SOURCE": "push",
            "CI_COMMIT_REF_PROTECTED": "true", "CI_PROJECT_PATH": "min-favs/sharewise",
            "CI_PROJECT_DIR": str(self.work), "KANIKO_EXECUTOR": str(self.work / "bin/kaniko"),
            "KANIKO_DOCKER_CONFIG": str(self.work / "kaniko-config"),
            "MOCK_DIGEST_BACKEND": DIGESTS["backend"], "MOCK_DIGEST_FRONTEND": DIGESTS["frontend"],
        }
        for role in DIGESTS:
            (self.work / "src" / ("backend/sharewise" if role == "backend" else "frontend")).mkdir(parents=True)

    def test_fixture_isolated_from_invoking_pipeline(self):
        self.assertEqual(self.env["CI_COMMIT_BRANCH"], "main")
        self.assertNotIn("CI_COMMIT_TAG", self.env)
        self.assertNotIn("CI_COMMIT_REF_NAME", self.env)
        self.assertNotIn("CI_JOB_ID", self.env)

    def run_script(self, name, *args, env=None, success=True):
        result = subprocess.run(["sh", "scripts/ci/" + name, *args], cwd=self.work,
            env={**self.env, **(env or {})}, text=True, capture_output=True)
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def set_image(self, role, version="0.1.42", digest=None, commit=COMMIT, alias=None):
        digest = digest or DIGESTS[role]
        repo = REGISTRY + "/" + role
        payload = {"digest": digest, "version": version, "commit": commit}
        db = json.loads(self.db.read_text())
        db[repo + ":" + version] = payload
        db[repo + "@" + digest] = payload
        if alias:
            db[repo + ":" + alias] = payload
        self.db.write_text(json.dumps(db))
        (self.work / "image-evidence" / (role + ".ref")).write_text(repo + "@" + digest + "\n")

    def pair(self):
        for role in DIGESTS:
            self.set_image(role)
        self.run_script("release-images.sh")
        return json.loads((self.work / "image-release/release.json").read_text())

    def test_kaniko_uses_correct_context_and_build_metadata(self):
        for role in DIGESTS:
            self.run_script("publish-image.sh", role)
            args = (self.work / "build-args.txt").read_text()
            path = self.work / "src" / ("backend/sharewise" if role == "backend" else "frontend")
            self.assertIn("--context=dir://" + str(path), args)
            self.assertIn("--dockerfile=" + str(path / "Dockerfile"), args)
            self.assertIn("--build-arg=BUILD_VERSION=0.1.42", args)
            self.assertIn("--build-arg=VCS_REF=" + COMMIT, args)
            self.assertIn("--reproducible", args)
            self.assertIn("--destination=" + REGISTRY + "/" + role + ":0.1.42", args)
            self.assertNotIn("hidden-password", args)
            self.assertEqual((self.work / "image-evidence" / (role + ".digest")).read_text().strip(), DIGESTS[role])

    def test_kaniko_failure_does_not_produce_digest(self):
        self.run_script("publish-image.sh", "backend", env={"FAIL_BUILD_ROLE": "backend"}, success=False)
        self.assertFalse((self.work / "image-evidence/backend.ref").exists())

    def test_preflight_allows_new_version(self):
        self.run_script("check-unused-image-tags.sh")

    def test_preflight_rejects_existing_version(self):
        self.set_image("backend")
        self.run_script("check-unused-image-tags.sh", success=False)

    def test_preflight_fails_on_auth_and_lookup_error(self):
        self.run_script("check-unused-image-tags.sh", env={"FAIL_AUTH": "1"}, success=False)
        self.run_script("check-unused-image-tags.sh", env={"FAIL_LOOKUP": "1"}, success=False)

    def test_pair_release_contains_both_verified_digests(self):
        result = self.pair()
        self.assertEqual(result["version"], "0.1.42")
        self.assertEqual(result["commit"], COMMIT)
        self.assertEqual(result["pipeline_iid"], "42")
        for role in DIGESTS:
            self.assertEqual(result["images"][role]["ref"], REGISTRY + "/" + role + "@" + DIGESTS[role])

    def test_no_descriptor_when_one_image_missing(self):
        self.set_image("backend")
        self.run_script("release-images.sh", success=False)
        self.assertFalse((self.work / "image-release/release.json").exists())

    def test_no_descriptor_on_remote_digest_mismatch(self):
        self.set_image("backend")
        self.set_image("frontend")
        (self.work / "image-evidence/frontend.ref").write_text(REGISTRY + "/frontend@sha256:" + "f" * 64 + "\n")
        self.run_script("release-images.sh", success=False)
        self.assertFalse((self.work / "image-release/release.json").exists())

    def test_no_descriptor_on_revision_mismatch(self):
        self.set_image("backend", commit="b" * 40)
        self.set_image("frontend")
        self.run_script("release-images.sh", success=False)
        self.assertFalse((self.work / "image-release/release.json").exists())

    def test_promote_initial_latest_and_verify(self):
        self.pair()
        self.run_script("promote-images.sh")
        db = json.loads(self.db.read_text())
        for role in DIGESTS:
            self.assertEqual(db[REGISTRY + "/" + role + ":latest"]["digest"], DIGESTS[role])

    def test_promote_repairs_partial_latest_on_retry(self):
        self.pair()
        self.run_script("promote-images.sh", env={"FAIL_TAG_ROLE": "frontend"}, success=False)
        db = json.loads(self.db.read_text())
        self.assertIn(REGISTRY + "/backend:latest", db)
        self.assertNotIn(REGISTRY + "/frontend:latest", db)
        self.run_script("promote-images.sh")

    def test_promote_rejects_newer_latest(self):
        self.pair()
        self.set_image("backend", version="0.1.44", digest="sha256:" + "3"*64, alias="latest")
        self.run_script("promote-images.sh", success=False)

    def test_promote_rejects_equal_version_different_digest(self):
        self.pair()
        self.set_image("backend", version="0.1.42", digest="sha256:" + "3"*64, alias="latest")
        self.run_script("promote-images.sh", success=False)

    def test_promote_rejects_untrusted_ref(self):
        self.pair()
        self.run_script("promote-images.sh", env={"CI_COMMIT_BRANCH": "feature/foo"}, success=False)
        self.run_script("promote-images.sh", env={"CI_COMMIT_REF_PROTECTED": "false"}, success=False)
        self.run_script("promote-images.sh", env={"CI_COMMIT_TAG": "dev-x"}, success=False)

    def test_promote_rejects_release_descriptor_tampering(self):
        self.pair()
        p = self.work / "image-release/release.json"
        release = json.loads(p.read_text())
        release["pipeline_id"] = "666"
        p.write_text(json.dumps(release))
        self.run_script("promote-images.sh", success=False)

    def test_preview_tag_uses_the_shared_prerelease_version(self):
        (self.work / "image-version/version.txt").write_text("0.1.42-demo-run\n")
        env = {"CI_COMMIT_BRANCH": "", "CI_COMMIT_TAG": "dev-demo-run"}
        self.run_script("check-unused-image-tags.sh", env=env)
        self.run_script("publish-image.sh", "frontend", env=env)
        self.assertIn("--destination=" + REGISTRY + "/frontend:0.1.42-demo-run",
                      (self.work / "build-args.txt").read_text())

    def test_artifact_cannot_be_reused_for_a_different_pipeline(self):
        self.run_script("check-unused-image-tags.sh", env={"CI_PIPELINE_IID": "43"}, success=False)
        self.run_script("publish-image.sh", "backend", env={"CI_PIPELINE_IID": "43"}, success=False)

    def test_branch_push_cannot_use_publishing_scripts(self):
        env = {"CI_COMMIT_BRANCH": "feature/random", "CI_COMMIT_REF_PROTECTED": "false"}
        self.run_script("check-unused-image-tags.sh", env=env, success=False)
        self.run_script("publish-image.sh", "backend", env=env, success=False)

    def test_release_rejects_auth_failure(self):
        self.set_image("backend")
        self.set_image("frontend")
        self.run_script("release-images.sh", env={"FAIL_AUTH": "1"}, success=False)
        self.assertFalse((self.work / "image-release/release.json").exists())

    def test_successful_latest_promotion_preserves_old_digest_refs(self):
        self.pair()
        self.run_script("promote-images.sh")
        for role in DIGESTS:
            self.set_image(role, version="0.1.40", digest="sha256:" + ("3" if role == "backend" else "4") * 64)
        db = json.loads(self.db.read_text())
        for role in DIGESTS:
            self.assertIn(REGISTRY + "/" + role + "@" + DIGESTS[role], db)

    def test_preflight_rejects_multiline_version(self):
        (self.work / "image-version/version.txt").write_text("0.1.42\n0.1.41\n")
        self.run_script("check-unused-image-tags.sh", success=False)


if __name__ == "__main__":
    unittest.main()
