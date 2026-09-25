# Cutting a release

Pushing a version tag to `main` builds the install packages and publishes them
as a GitHub Release. This is the maintainer's side of it; for what someone does
with the resulting package, see [INSTALL.md](INSTALL.md).

## Cut a release

From an up-to-date `main`:

```bash
git checkout main
git pull
git tag -a v1.2.3 -m "v1.2.3"
git push origin v1.2.3
```

That is the whole process. [`.github/workflows/release.yml`](../.github/workflows/release.yml)
takes it from there and, a few minutes later, the
[Releases page](https://github.com/Synergy-Tooling-Systems/jasperServer/releases)
has a new release with three files attached:

| File | |
| --- | --- |
| `jasper-report-service-1.2.3-windows.zip` | Windows Service package, WinSW bundled |
| `jasper-report-service-1.2.3-linux.tar.gz` | Linux systemd package |
| `SHA256SUMS.txt` | Checksums for both |

## Tag rules

- **Tag format is `vMAJOR.MINOR.PATCH`**, optionally with a pre-release suffix:
  `v1.2.3`, `v1.2.3-rc1`. A malformed version such as `v1.2` or `v1.2.3.4`
  fails the workflow before it builds anything. A tag without the leading `v`,
  such as `1.2.3`, does not match the trigger at all — the workflow simply
  never runs, with no failure to notice, so watch for that one.
- **A suffix makes it a pre-release.** `v1.2.3-rc1` is published as a GitHub
  pre-release, so it is not served as "latest". `v1.2.3` is.
- **The tag must be on `main`.** GitHub cannot filter tag pushes by branch, so
  the workflow checks it: if the tagged commit is not an ancestor of `main`,
  the run fails immediately, before anything is built or published.
- **The version in `pom.xml` is not the source of truth.** It stays at
  `0.0.1-SNAPSHOT` in the repository; the workflow stamps the tag's version
  into the pom before building, so nothing needs bumping by hand.

## What the workflow does

1. Checks the tagged commit is on `main`.
2. Derives and validates the version from the tag (`v1.2.3` → `1.2.3`).
3. Sets that version in `pom.xml`.
4. `./mvnw clean package` — **this runs the tests, so a failing test blocks the
   release.**
5. Runs [`packaging/assemble.sh`](../packaging/assemble.sh), which lays out both
   packages, downloads the pinned WinSW build into the Windows one, and writes
   the checksums.
6. Uploads the packages as workflow artifacts (kept even if the next step
   fails).
7. Creates the GitHub Release for the tag and attaches the three files.

## Dry-running a change to the workflow

Run the **Release** workflow manually from the Actions tab (`workflow_dispatch`)
and give it a version such as `0.0.0-dev`. It builds both packages and uploads
them as workflow artifacts, and does **not** create or touch a GitHub Release.
Use this to test a change to the workflow or the packaging without burning a
version tag.

You can also assemble the packages locally on a machine with `bash`, `zip` and
`curl`:

```bash
./mvnw clean package
VERSION=0.0.0-dev packaging/assemble.sh   # writes build/dist/
```

On Windows, [`build-dist.ps1`](../build-dist.ps1) assembles the Windows folder
only, into `dist/`, and does not download WinSW — see the note below.

## If something goes wrong

**A release was tagged by mistake.** Delete the tag and the release, then tag
again. Re-pushing the same tag does not re-run the workflow reliably, so use a
new version number if the release was already published and may have been
downloaded.

```bash
git push origin :refs/tags/v1.2.3
git tag -d v1.2.3
gh release delete v1.2.3
```

**The run failed at "Verify the tag is on main".** The tag is on a commit that
is not in `main` — usually a tag created on a feature branch. Delete it, merge
to `main`, and tag the merged commit.

**The WinSW download failed.** The pinned version and asset are the
`WINSW_VERSION` / `WINSW_ASSET` environment variables at the top of the
workflow. They point at a GitHub release asset, so a failure here is normally
transient; the step already retries.

## Things worth knowing

- **Report templates are not in a release.** `reports/` is gitignored, so the
  packages ship that directory empty with a README explaining what belongs in
  it. Whoever installs the package supplies the `.jrxml` files. If report
  templates should ship *with* the application, they need to be committed and
  `packaging/assemble.sh` updated to copy them.
- **`build-dist.ps1` and `packaging/assemble.sh` are separate.** The PowerShell
  script is the local Windows convenience build into `dist/`; the bash script is
  what CI runs. They produce the same Windows layout, except that only the CI
  one bundles WinSW and writes a `VERSION` file. A change to what ships in the
  Windows package needs making in both.
- **WinSW is bundled under its MIT license**, with a copy of that license in
  the package under `third-party/`.
