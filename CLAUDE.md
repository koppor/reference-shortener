# reference-shortener — contributor guide

A single-file [JBang](https://www.jbang.dev/) tool that shortens a LaTeX paper's cited references
until the compiled paper fits a target page count. Built on `org.jabref:jablib` (JabRef's core
library). The whole program is `ReferenceShortener.java`; there is no build file.

## Layout

- `ReferenceShortener.java` — the entire tool. `//DEPS` / `//REPOS` / `//NATIVE_OPTIONS` headers at
  the top declare dependencies and the native-image build.
- `smoke-test.sh` — end-to-end test (needs `latexmk` or Docker). Must stay **offline**.
- `README.md`, `SKILL.md`, `CHANGELOG.md` — user/agent docs and release notes.
- `jbang-catalog.json`, `tinylog.properties`, `native-image/reflect-config.json` — packaging.
- `.github/workflows/ci.yml` (heylogs + smoke), `.github/workflows/binaries.yml` (3-OS native →
  artifacts, release assets on tags), `.github/dependabot.yml` (GitHub Actions only).

## Adding a reference cleanup step

The cleanups are the ordered list built in `buildSteps(List<LoadedBib> loadedBibs)` — that method is
the **source of truth**. When you add (or reorder) a cleanup, update **all** of these so they stay
consistent:

1. **`buildSteps(...)`** in `ReferenceShortener.java` — add a `new Step("<description>", () -> ...)`
   at the right position (see *Ordering* below). A `Step` is `record Step(String description,
   Runnable apply)`; the `apply` runnable mutates `bib.citedEntries()` via a jablib cleanup/formatter.
2. **Import** the jablib formatter/cleanup at the top of `ReferenceShortener.java`.
3. **Class docstring** — the `///` markdown ordered list at the top of the file; mirror the new order.
4. **`README.md`** — the numbered "applies cleanups … :" list.
5. **`SKILL.md`** — the "Order:" numbered list (and a bullet under *Notes for agents* if the step has
   a caveat, e.g. it needs the network).
6. **`CHANGELOG.md`** — an entry under `## [Unreleased]` (`### Added` or `### Changed`).
7. **`smoke-test.sh`** — if the step acts on a field, make sure the fixture exercises it; keep the
   test **offline and deterministic** (see *Networked steps*). It uses `--pages 1` on a multi-page
   doc (unreachable) so every step runs regardless of TeX page-breaking.

### Ordering (where to insert)

`runShortener(...)` applies the steps in order and **stops at the first one that reaches the
target**, so position = precedence. Insert by this rule of thumb:

- **Cheap losses** — fields most styles barely print (`location`, `month`) — and **non-destructive
  normalizations** that belong in any paper (DOI, URL cleanup) → **first**.
- **Page reducers, biggest impact first** (author minification, then journal abbreviation) — reaching
  the target with one big step preserves more of the other fields.
- **Anything users might dislike, or that is networked/slow** → **last resort** (e.g. DOI shortening
  to the `10/xxx` form).

### Networked / failure-prone steps

Wrap them so a failure **skips the step** instead of aborting the run — see `shortenDoisQuietly(...)`.
Also:

- Native image: if the step needs the network, add the protocol to `//NATIVE_OPTIONS` (e.g.
  `--enable-url-protocols=https`); if it needs a bundled resource, add it to `-H:IncludeResources=…`.
- Keep the smoke-test fixture from triggering the step (the fixture has **no DOIs** precisely so the
  DOI-shortening step stays a no-op and CI stays offline). Verify networked steps manually with real
  data.

## Build & verify

```bash
jbang build ReferenceShortener.java                    # compile
./smoke-test.sh                                         # end-to-end (needs latexmk or Docker)
jbang trust add https://github.com/nbbrd/jbang-catalog/ # once
jbang heylogs@nbbrd check -v semver CHANGELOG.md        # lint the changelog
```

Native binary (needs a GraalVM JDK with `native-image` — e.g.
`JBANG_JDK_VENDOR=graalvm_community jbang jdk install 25`, then put its `bin` on `PATH`):

```bash
jbang --native ReferenceShortener.java --version
jbang export native --output reference-shortener ReferenceShortener.java
```

## Conventions

- **English only** — no `Localization.lang`, no i18n.
- **Colored output** uses [chalk](https://github.com/tomas-langer/chalk): `Chalk.on(msg).green()` etc.
  Coloring is gated once in `main()` via `Chalk.setColorEnabled(NO_COLOR unset && System.console() != null)`
  — chalk does not strip on its own, so keep new user messages wrapped in `Chalk.on(...)` and let that
  switch handle piped/`NO_COLOR` output (green = success, yellow = warning, red = error, gray = info).
- **SemVer**; `CHANGELOG.md` follows [Keep a Changelog](https://keepachangelog.com/) and is
  heylogs-linted in CI.
- Direct pushes to `main` (own repo, no PR flow). CI must stay **offline**.
- `jablib` is a moving `6.0-SNAPSHOT`; the `//REPOS` header needs the Sonatype snapshots repo **and**
  JabRef's `raw` repo (for a patched `icu4j` its POM pins). On the classpath (jbang is non-modular),
  jablib's unexported packages are usable directly.

## Release

Bump `version = "reference-shortener X.Y.Z"` in the `@Command` annotation, add a `## [X.Y.Z]` section
to `CHANGELOG.md` (with the version link references), then `git tag -a vX.Y.Z` and push `main` + the
tag. The tag build attaches the native binaries to the GitHub release.
