# reference-shortener

Shortens a LaTeX paper's references until it fits a page count.

The tool compiles the paper with `latexmk`, then applies cleanups to the cited references —
recompiling after each step and stopping as soon as the target page count is reached:

1. **`location`** (and BibTeX **`address`**) → cleared — few styles print it, so it is the cheapest information to lose
2. **DOIs** → normalized (no `https://doi.org/` prefixes) — a cleanup any nice paper wants anyway
3. **URLs** → cleaned up (redirect wrappers unwrapped, escapes decoded) — likewise
4. **Author lists** → first author + "et al."
5. **`month`** → removed
6. **Journal names** → standard abbreviations ([JabRef](https://www.jabref.org/)'s bundled list)
7. **DOIs** → shortened to the `10/xxx` form (via [shortdoi.org](https://shortdoi.org/)) — the very last resort, since not everyone likes short DOIs

The referenced `.bib` file(s) are rewritten with the *smallest* set of cleanups that reaches the
target — a paper that already fits is left untouched.

Built on [jablib](https://github.com/JabRef/jabref), JabRef's core library.

## Requirements

- [JBang](https://www.jbang.dev/)
- A TeX distribution with `latexmk` — or Docker (automatic fallback to the
  [Island of TeX](https://gitlab.com/islandoftex/images/texlive) `texlive/texlive` image)
- Network access is only needed for the last-resort DOI-shortening step (shortdoi.org); it is
  skipped gracefully when offline. Everything else runs without a network.

## Usage

```bash
# one-off
jbang reference-shortener@koppor/reference-shortener paper.tex --pages 6

# or install as a command
jbang app install reference-shortener@koppor/reference-shortener
reference-shortener paper.tex --pages 6
```

Without `--pages`, the target is one page fewer than the paper's current length.
`--output shortened.bib` writes the result elsewhere instead of overwriting (single-`.bib` papers
only).

## Use as an agent skill

This repository ships a [`SKILL.md`](SKILL.md), so a coding agent can install it as a skill from
[skills.sh](https://www.skills.sh/):

```bash
npx skills add koppor/reference-shortener
```

See the skill page at
[skills.sh/koppor/reference-shortener](https://www.skills.sh/koppor/reference-shortener).

## Faster startup

Two independent knobs, both optional:

- **Native jbang launcher** — run jbang itself as a native binary, skipping the JVM boot on every
  invocation. Enable once (add to your shell profile):

  ```bash
  export JBANG_USE_NATIVE=true
  ```

  jbang downloads a native build of *itself* on the next run. This speeds up the `jbang` command in
  general, not just this tool.

- **Native build of this tool** — the biggest win for repeated use; runs without a JVM at all:

  ```bash
  jbang app install --native reference-shortener@koppor/reference-shortener
  ```

  Building natively requires a GraalVM JDK with `native-image`
  (e.g. `JBANG_JDK_VENDOR=graalvm_community jbang jdk install 25`).

## Native binary

Pre-built native binaries for Linux, macOS, and Windows are attached to each
[release](https://github.com/koppor/reference-shortener/releases) and to every
[Binaries workflow run](https://github.com/koppor/reference-shortener/actions/workflows/binaries.yml)
(as artifacts) — download one and run it directly, no JBang or JVM required.

To build one yourself from a checkout:

```bash
jbang export native --output reference-shortener ReferenceShortener.java
```

## Versioning

This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html); see
[CHANGELOG.md](CHANGELOG.md) (kept in [Keep a Changelog](https://keepachangelog.com/) format, linted
with [heylogs](https://github.com/nbbrd/heylogs)).

## License

[MIT](LICENSE)
