#!/usr/bin/env bash
# End-to-end smoke test: builds a small LaTeX project, runs reference-shortener on it, and asserts
# the escalation pipeline ran and rewrote the bibliography. Needs jbang and either a local latexmk
# or Docker. Deterministic: --pages 1 on a multi-page document is unreachable, so every step
# applies regardless of exact page-break behavior across TeX distributions.
#
# The fixture has no DOIs on purpose: it keeps the test fully offline, since the last step (DOI
# shortening) would otherwise call shortdoi.org. That network step is verified manually with a real DOI.
set -euo pipefail
cd "$(dirname "$0")"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# --- fixture: article with a plain-BibTeX bibliography of long-author entries ---------------------
for i in $(seq 1 10); do
  cat >> "$WORK/refs.bib" <<EOF
@article{key$i,
  author   = {Alpha$i, Anna and Beta$i, Bob and Gamma$i, Carol and Delta$i, Dan and Epsilon$i, Eve},
  title    = {A Study of Topic Number $i in Considerable Depth},
  journal  = {Journal of the American Chemical Society},
  location = {Nowhere Town, Some Province, Country},
  address  = {Old City, Faraway Land},
  month    = {jan},
  url      = {https://www.google.de/url?sa=t&url=https%3A%2F%2Fexample.org%2Fpaper$i&usg=AOvVaw1},
  year     = {200$((i % 10))},
}
EOF
done

{
  echo '\documentclass{article}'
  echo '\begin{document}'
  echo 'A smoke test.\cite{key1,key2,key3,key4,key5,key6,key7,key8,key9,key10}'
  for i in $(seq 1 60); do
    echo 'Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.'
    echo
  done
  echo '\bibliographystyle{plain}'
  echo '\bibliography{refs}'
  echo '\end{document}'
} > "$WORK/paper.tex"

# --- rejected: non-positive page target -----------------------------------------------------------
if jbang ReferenceShortener.java "$WORK/paper.tex" --pages 0 2>/dev/null; then
  echo "FAIL: --pages 0 should have been rejected"; exit 1
fi
echo "OK: --pages 0 rejected"

# --- unreachable target: all steps apply, bib rewritten -------------------------------------------
OUT=$(jbang ReferenceShortener.java "$WORK/paper.tex" --pages 1 2>&1)
echo "$OUT"
echo "$OUT" | grep -q "applying" || { echo "FAIL: no steps were applied"; exit 1; }
grep -q "and others" "$WORK/refs.bib" || { echo "FAIL: authors were not minified in refs.bib"; exit 1; }
! grep -q "location" "$WORK/refs.bib" || { echo "FAIL: location was not cleared in refs.bib"; exit 1; }
! grep -q "address" "$WORK/refs.bib" || { echo "FAIL: address was not cleared in refs.bib"; exit 1; }
! grep -q "month" "$WORK/refs.bib" || { echo "FAIL: month was not removed from refs.bib"; exit 1; }
! grep -q "google" "$WORK/refs.bib" || { echo "FAIL: URL redirect wrapper survived in refs.bib"; exit 1; }
grep -q "https://example.org/paper1" "$WORK/refs.bib" || { echo "FAIL: unwrapped URL missing from refs.bib"; exit 1; }
echo "OK: escalation pipeline ran and rewrote refs.bib"

echo "SMOKE TEST PASSED"
