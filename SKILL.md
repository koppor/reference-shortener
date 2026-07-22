---
name: reference-shortener
description: Shortens a LaTeX paper's cited references (authors to "et al.", journal abbreviations, DOI cleanup) by recompiling with latexmk until the paper fits a target page count. Use when a paper's bibliography overflows onto an extra page and needs to be compacted without dropping entries.
license: MIT
---

# reference-shortener

Compiles a LaTeX paper with `latexmk` and applies cleanups to the cited references — recompiling
after each step and stopping as soon as the paper fits the target page count. The referenced `.bib`
file(s) are rewritten with the smallest set of cleanups that reaches the target.

Order:

1. Normalize DOIs (strip `https://doi.org/` prefixes and similar) — a cleanup any well-formed paper wants anyway
2. Shorten author lists to the first author + "and others" (rendered as "et al.")
3. Abbreviate journal names (JabRef's bundled journal-abbreviation list)

## Installation

Requires [JBang](https://www.jbang.dev/) and either a local TeX distribution (`latexmk`) or Docker
(falls back to the Island of TeX `texlive/texlive` image).

```bash
jbang app install reference-shortener@koppor/reference-shortener
```

One-off run without installation:

```bash
jbang reference-shortener@koppor/reference-shortener paper.tex
```

## Usage

```bash
# Shorten until the paper is one page shorter than it currently is
reference-shortener paper.tex

# Shorten until the paper fits 6 pages
reference-shortener paper.tex --pages 6

# Write the shortened bibliography elsewhere instead of overwriting (single .bib only)
reference-shortener paper.tex --pages 6 --output shortened.bib
```

## Notes for agents

- The `.tex` file must be a local path inside the paper's project directory; the whole directory is
  staged to a temp copy for compilation, so the user's tree is only touched once a validated result
  exists.
- Both BibTeX (`\bibliography`) and biblatex (`\addbibresource`) papers work; `latexmk` drives the
  needed `bibtex`/`biber` reruns automatically.
- If the target cannot be reached even after all cleanups, the tool applies its best effort,
  rewrites the `.bib`, reports the achieved page count on stderr, and still exits 0.
- A paper that already fits is left untouched ("nothing to shorten").
- Exit codes: 0 = OK (target reached, best effort, or nothing to do), 2 = usage error
  (bad `--pages`, missing bibliography, `--output` with multiple `.bib` files), 1 = compile or I/O
  failure.
