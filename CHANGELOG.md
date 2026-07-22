# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Cross-platform native binaries (Linux, macOS, Windows) are built by CI with GraalVM `native-image` (via jbang) and uploaded as workflow artifacts; tag builds also attach them to the GitHub release.

### Changed

- DOI cleanup now runs first — before the information-reducing author and journal shortening — because normalizing DOIs belongs in any well-formed paper regardless of length.

## [0.1.0] - 2026-07-21

### Added

- Initial release: `reference-shortener FILE.tex [--pages N] [--output FILE.bib]` compiles a LaTeX paper with `latexmk` and applies escalating, information-reducing cleanups to its cited references — author minification (first author + "et al."), journal-name abbreviation, DOI normalization — recompiling after each and stopping as soon as the paper fits the target page count (default: one page fewer than the current length).
- Fallback to the Island of TeX `texlive/texlive` Docker image when no local `latexmk` is available.
- JBang packaging: run via `jbang reference-shortener@koppor/reference-shortener`, or build a native binary with `jbang --native`.

[Unreleased]: https://github.com/koppor/reference-shortener/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/koppor/reference-shortener/releases/tag/v0.1.0
