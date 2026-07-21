# reference-shortener

Shortens a LaTeX paper's references until it fits a page count.

The tool compiles the paper with `latexmk`, then applies increasingly aggressive,
information-reducing cleanups to the cited references — recompiling after each step and stopping as
soon as the target page count is reached:

1. **Author lists** → first author + "et al."
2. **Journal names** → standard abbreviations ([JabRef](https://www.jabref.org/)'s bundled list)
3. **DOIs** → normalized (no `https://doi.org/` prefixes)

The referenced `.bib` file(s) are rewritten with the *smallest* set of cleanups that reaches the
target — a paper that already fits is left untouched.

Built on [jablib](https://github.com/JabRef/jabref), JabRef's core library.

## Requirements

- [JBang](https://www.jbang.dev/)
- A TeX distribution with `latexmk` — or Docker (automatic fallback to the
  [Island of TeX](https://gitlab.com/islandoftex/images/texlive) `texlive/texlive` image)

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

## Native binary

```bash
jbang --native ReferenceShortener.java --version
```

Requires a GraalVM JDK with `native-image` (e.g. `JBANG_JDK_VENDOR=graalvm_community jbang jdk install 25`).

## Versioning

This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html); see
[CHANGELOG.md](CHANGELOG.md) (kept in [Keep a Changelog](https://keepachangelog.com/) format, linted
with [heylogs](https://github.com/nbbrd/heylogs)).

## License

[MIT](LICENSE)
