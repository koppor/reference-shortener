///usr/bin/env jbang "$0" "$@" ; exit $?

//DESCRIPTION Shortens a LaTeX paper's references until it fits a page count

//JAVA 25
//RUNTIME_OPTIONS --enable-native-access=ALL-UNNAMED
//FILES tinylog.properties=tinylog.properties
//FILES META-INF/native-image/reference-shortener/reflect-config.json=native-image/reflect-config.json

// raw is for the patched icu4j (https://github.com/unicode-org/icu/pull/2127) referenced by jablib's POM
//REPOS mavenlocal,mavencentral,mavencentralsnapshots=https://central.sonatype.com/repository/maven-snapshots/,raw=https://raw.githubusercontent.com/JabRef/jabref/refs/heads/main/jablib/lib/
//DEPS org.jabref:jablib:6.0-SNAPSHOT
// see https://github.com/gradlex-org/extra-java-module-info/issues/237 why e-adr is included here
//DEPS io.github.adr:e-adr:2.0.0
//DEPS info.picocli:picocli:4.7.7
// Generates GraalVM reflection config for the picocli annotations at compile time (native image).
// -proc:full is required since JDK 23 disabled implicit annotation processing.
//DEPS info.picocli:picocli-codegen:4.7.7
//COMPILE_OPTIONS -proc:full

//NATIVE_OPTIONS --no-fallback -H:IncludeResources=journals/.*|tinylog.properties

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jabref.logic.cleanup.AbbreviateJournalCleanup;
import org.jabref.logic.cleanup.DoiCleanup;
import org.jabref.logic.cleanup.FieldFormatterCleanup;
import org.jabref.logic.exporter.AtomicFileWriter;
import org.jabref.logic.exporter.BibDatabaseWriter;
import org.jabref.logic.formatter.minifier.MinifyNameListFormatter;
import org.jabref.logic.importer.ParserResult;
import org.jabref.logic.importer.fileformat.BibtexImporter;
import org.jabref.logic.journals.AbbreviationType;
import org.jabref.logic.journals.JournalAbbreviationLoader;
import org.jabref.logic.journals.JournalAbbreviationRepository;
import org.jabref.logic.preferences.JabRefCliPreferences;
import org.jabref.logic.texparser.DefaultLatexParser;
import org.jabref.model.database.BibDatabase;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.texparser.LatexParserResult;
import org.jabref.model.util.DummyFileUpdateMonitor;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/// Shortens the reference list cited by a LaTeX document until the compiled paper reaches a target
/// page count. Normalizes DOIs first (a cleanup any well-formed paper wants anyway), then applies
/// information-reducing cleanups — author minification, then journal abbreviation — recompiling with
/// latexmk after each and stopping as soon as the target is met. The referenced `.bib` file(s) are
/// rewritten with the smallest set of cleanups that reaches the target.
///
/// Built on jablib, JabRef's core library.
@Command(name = "reference-shortener",
        mixinStandardHelpOptions = true,
        // a plain version string (not an IVersionProvider) keeps the native image free of extra reflection
        version = "reference-shortener 0.1.0",
        description = "Shortens a LaTeX paper's references (via latexmk) until it fits a page count.")
public class ReferenceShortener implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "FILE.tex", description = "LaTeX document whose cited references should be shortened.")
    Path texFile;

    @Option(names = "--pages", description = "Target page count. Default: one page fewer than the current length.")
    Integer pages;

    @Option(names = "--output", description = "Write the shortened .bib here instead of overwriting the referenced file (single .bib only).")
    Path outputFile;

    // getInstance() is deprecation-marked to restrict it to application entry points — which this is
    // (the same sanctioned usage as the jablib-examples jbang scripts).
    private final JabRefCliPreferences preferences = JabRefCliPreferences.getInstance();

    public static void main(String[] args) {
        System.exit(new CommandLine(new ReferenceShortener()).execute(args));
    }

    /// Signals a user-facing failure; the message is printed to stderr and the exit code returned.
    static final class AbortException extends Exception {
        final int exitCode;

        AbortException(String message, int exitCode) {
            super(message);
            this.exitCode = exitCode;
        }
    }

    /// Persists the current entry state, compiles the document, and returns its page count.
    @FunctionalInterface
    interface PageCounter {
        int measure() throws AbortException;
    }

    /// A single shortening escalation level. `apply` mutates the loaded entries in place.
    record Step(String description, Runnable apply) {
    }

    record Result(int baselinePages, int finalPages, int targetPages, List<String> appliedSteps, boolean reachedTarget) {
    }

    /// Pairs a loaded database with the staged file it is compiled from and the cited entries to shorten.
    private record LoadedBib(BibDatabase database, Path stagedPath, List<BibEntry> citedEntries) {
    }

    @Override
    public Integer call() {
        try {
            return run();
        } catch (AbortException e) {
            System.err.println(e.getMessage());
            return e.exitCode;
        }
    }

    private Integer run() throws AbortException {
        if (pages != null && pages < 1) {
            throw new AbortException("--pages must be at least 1, was " + pages, CommandLine.ExitCode.USAGE);
        }
        if (!Files.isRegularFile(texFile)) {
            throw new AbortException("Not a readable file: " + texFile, CommandLine.ExitCode.USAGE);
        }

        // Work on a copy so the loop never touches the user's tree until a validated result exists,
        // and so latexmk's aux/pdf/log artifacts land in a throwaway directory. The directory is
        // created before the try so the finally removes it even if the copy itself fails partway.
        Path workDir = createStagingDirectory();
        try {
            copyDirectoryContents(texFile.toAbsolutePath().getParent(), workDir);
            return shorten(workDir);
        } finally {
            deleteRecursivelyQuietly(workDir);
        }
    }

    private Integer shorten(Path workDir) throws AbortException {
        Path workTex = workDir.resolve(texFile.getFileName());

        LatexParserResult parsed = new DefaultLatexParser().parse(workTex)
                .orElseThrow(() -> new AbortException("Could not read " + texFile, CommandLine.ExitCode.USAGE));

        if (parsed.getBibFiles().isEmpty()) {
            throw new AbortException("No bibliography file found for " + texFile, CommandLine.ExitCode.USAGE);
        }

        Set<String> citedKeys = parsed.getCitations().keySet();
        List<LoadedBib> loadedBibs = loadBibs(parsed.getBibFiles(), citedKeys);

        // --output can only name one destination, so reject multi-.bib papers before compiling or
        // mutating anything — silently falling back to in-place edits would defeat the point of the
        // flag (avoiding in-place modification).
        if (outputFile != null && loadedBibs.size() != 1) {
            throw new AbortException(
                    "--output supports a single .bib, but '%s' references %d".formatted(texFile, loadedBibs.size()),
                    CommandLine.ExitCode.USAGE);
        }

        List<Step> steps = buildSteps(loadedBibs);

        LatexCompiler compiler = new LatexCompiler(workTex);
        if (compiler.usesDocker()) {
            System.out.println("No local TeX found; compiling with the Island of TeX Docker image.");
        }

        PageCounter measure = () -> {
            for (LoadedBib bib : loadedBibs) {
                saveDatabase(bib.database(), bib.stagedPath());
            }
            return compiler.compileAndCountPages();
        };

        Result result = runShortener(steps, measure,
                pages == null ? OptionalInt.empty() : OptionalInt.of(pages));

        return report(workDir, loadedBibs, result);
    }

    /// Runs the escalation loop: applies steps one at a time, re-measuring after each, and stops as
    /// soon as the page count reaches the target. When no target is given, the target is one page
    /// shorter than the baseline — but never below 1, so a single-page paper is left untouched.
    static Result runShortener(List<Step> steps, PageCounter measure, OptionalInt targetPages) throws AbortException {
        int baseline = measure.measure();
        int target = targetPages.orElse(Math.max(1, baseline - 1));
        List<String> applied = new ArrayList<>();

        int currentPages = baseline;
        for (Step step : steps) {
            if (currentPages <= target) {
                break;
            }
            step.apply().run();
            applied.add(step.description());
            currentPages = measure.measure();
        }

        return new Result(baseline, currentPages, target, applied, currentPages <= target);
    }

    private List<Step> buildSteps(List<LoadedBib> loadedBibs) {
        JournalAbbreviationRepository journalRepository = JournalAbbreviationLoader.loadBuiltInRepository();
        FieldFormatterCleanup minifyAuthors = new FieldFormatterCleanup(StandardField.AUTHOR, new MinifyNameListFormatter());
        // DOI cleanup runs first: it is a normalization every well-formed paper wants anyway, not an
        // information-losing trade-off. Author minification (dropping names) has the biggest impact,
        // so it comes before journal abbreviation, which is left as a last-resort mop-up.
        return List.of(
                new Step("clean up DOIs",
                        () -> loadedBibs.forEach(bib -> bib.citedEntries().forEach(new DoiCleanup()::cleanup))),
                new Step("shorten authors to the first author",
                        () -> loadedBibs.forEach(bib -> bib.citedEntries().forEach(minifyAuthors::cleanup))),
                new Step("abbreviate journal names",
                        () -> loadedBibs.forEach(bib -> bib.citedEntries()
                                .forEach(new AbbreviateJournalCleanup(bib.database(), journalRepository, AbbreviationType.DEFAULT, false)::cleanup))));
    }

    private Integer report(Path workDir, List<LoadedBib> loadedBibs, Result result) throws AbortException {
        if (result.appliedSteps().isEmpty()) {
            System.out.println("'%s' already fits within %d pages; nothing to shorten.".formatted(texFile, result.baselinePages()));
            return CommandLine.ExitCode.OK;
        }

        writeBack(workDir, loadedBibs);

        String appliedSteps = String.join(", ", result.appliedSteps());
        if (result.reachedTarget()) {
            System.out.println("Shortened '%s' from %d to %d pages by applying: %s.".formatted(
                    texFile, result.baselinePages(), result.finalPages(), appliedSteps));
            return CommandLine.ExitCode.OK;
        }

        System.err.println("Could not reach the target of %d pages; best effort is %d pages after applying: %s.".formatted(
                result.targetPages(), result.finalPages(), appliedSteps));
        return CommandLine.ExitCode.OK;
    }

    private void writeBack(Path workDir, List<LoadedBib> loadedBibs) throws AbortException {
        Path sourceRoot = texFile.toAbsolutePath().getParent();
        for (LoadedBib bib : loadedBibs) {
            // outputFile is only reached with exactly one bib (validated earlier); otherwise map the
            // staged copy back onto the user's tree, preserving its subdirectory.
            Path destination = outputFile != null
                    ? outputFile
                    : resolveWriteBackDestination(sourceRoot, workDir, bib.stagedPath());
            saveDatabase(bib.database(), destination);
            System.out.println("Saved " + destination);
        }
    }

    /// Maps a staged `.bib` back to the user's source tree, preserving the path (e.g. subdirectory)
    /// it had relative to the staging root — the LaTeX parser resolves and keeps subdirectories, so
    /// writing only the basename would target the wrong file. Refuses paths that escape the source
    /// directory via `..`.
    static Path resolveWriteBackDestination(Path sourceRoot, Path workDir, Path stagedBib) throws AbortException {
        Path relative = workDir.relativize(stagedBib);
        Path destination = sourceRoot.resolve(relative).normalize();
        if (!destination.startsWith(sourceRoot.normalize())) {
            throw new AbortException(
                    "Refusing to write '%s' outside the project directory '%s'".formatted(destination, sourceRoot),
                    CommandLine.ExitCode.SOFTWARE);
        }
        return destination;
    }

    private List<LoadedBib> loadBibs(List<Path> bibFiles, Set<String> citedKeys) throws AbortException {
        BibtexImporter importer = new BibtexImporter(preferences.getImportFormatPreferences(), new DummyFileUpdateMonitor());
        List<LoadedBib> loadedBibs = new ArrayList<>();
        for (Path bibFile : bibFiles) {
            ParserResult parserResult;
            try {
                parserResult = importer.importDatabase(bibFile);
            } catch (IOException e) {
                throw new AbortException("Could not read " + bibFile + ": " + e.getMessage(), CommandLine.ExitCode.SOFTWARE);
            }
            BibDatabase database = parserResult.getDatabase();
            // No cite keys, or the \nocite{*} wildcard -> every entry may be printed, so shorten all.
            boolean shortenAll = citedKeys.isEmpty() || citedKeys.contains("*");
            List<BibEntry> citedEntries = database.getEntries().stream()
                    .filter(entry -> shortenAll
                            || entry.getCitationKey().map(citedKeys::contains).orElse(false))
                    .toList();
            loadedBibs.add(new LoadedBib(database, bibFile, citedEntries));
        }
        return loadedBibs;
    }

    private void saveDatabase(BibDatabase database, Path target) throws AbortException {
        try (AtomicFileWriter writer = new AtomicFileWriter(target, StandardCharsets.UTF_8)) {
            BibDatabaseContext context = new BibDatabaseContext(database);
            new BibDatabaseWriter(writer, context, preferences).writeDatabase(context);
        } catch (IOException e) {
            throw new AbortException("Could not write " + target + ": " + e.getMessage(), CommandLine.ExitCode.SOFTWARE);
        }
    }

    private static Path createStagingDirectory() throws AbortException {
        try {
            return Files.createTempDirectory("reference-shortener");
        } catch (IOException e) {
            throw new AbortException("Could not create a staging directory: " + e.getMessage(), CommandLine.ExitCode.SOFTWARE);
        }
    }

    private static void copyDirectoryContents(Path source, Path target) throws AbortException {
        try (var paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        } catch (IOException e) {
            throw new AbortException("Could not stage '%s' for compilation: %s".formatted(source, e.getMessage()), CommandLine.ExitCode.SOFTWARE);
        }
    }

    /// Best-effort recursive delete of the staging directory. Never throws: a leftover artifact
    /// (for example a Docker-created file with different ownership) must not fail an otherwise
    /// successful run.
    private static void deleteRecursivelyQuietly(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // per-file failure: reported via the existence warning below
                }
            });
        } catch (IOException e) {
            // walk failure: reported via the existence warning below
        }
        if (Files.exists(directory)) {
            System.err.println("Warning: could not fully remove staging directory " + directory);
        }
    }

    /// Compiles a LaTeX document with `latexmk` (which drives the pdflatex/bibtex/biber passes
    /// itself, and re-runs them when the `.bib` changes) and reports the resulting page count. The
    /// count is read from the pdflatex `.log` (`Output written on X.pdf (N pages, ...)`) rather than
    /// by parsing the PDF, so no PDF library is needed. Prefers a local `latexmk`; falls back to the
    /// Island of TeX `texlive/texlive` Docker image when no local TeX distribution is present.
    static class LatexCompiler {

        /// pdflatex writes this line once per run; the last occurrence reflects the final pass.
        private static final Pattern OUTPUT_PAGES = Pattern.compile("Output written on \\S+\\.pdf \\((\\d+) pages?");
        private static final String DOCKER_IMAGE = "texlive/texlive";
        private static final int TIMEOUT_SECONDS = 300;

        private final Path workingDir;
        private final String texFileName;
        private final boolean useDocker;

        LatexCompiler(Path texFile) {
            this.workingDir = texFile.toAbsolutePath().getParent();
            this.texFileName = texFile.getFileName().toString();
            this.useDocker = !isLocalLatexmkAvailable();
        }

        boolean usesDocker() {
            return useDocker;
        }

        /// Compiles the document and returns the page count of the produced PDF.
        int compileAndCountPages() throws AbortException {
            int exitCode = runLatexmk();
            return pageCountOrFail(exitCode, readLog(), texFileName);
        }

        /// Turns a compile run into a page count, or fails. A non-zero latexmk exit is a hard
        /// failure even when the log still carries a page count: pdflatex writes a PDF in
        /// nonstopmode despite errors, and shortening must not proceed on top of a broken compile.
        static int pageCountOrFail(int exitCode, String log, String texFileName) throws AbortException {
            if (exitCode != 0) {
                throw new AbortException(
                        "LaTeX compilation of '%s' failed (latexmk exit %d). Run latexmk manually to see the error.".formatted(texFileName, exitCode),
                        CommandLine.ExitCode.SOFTWARE);
            }
            OptionalInt parsedPages = parsePageCount(log);
            if (parsedPages.isEmpty()) {
                throw new AbortException(
                        "latexmk reported success but produced no page count for '%s'".formatted(texFileName),
                        CommandLine.ExitCode.SOFTWARE);
            }
            return parsedPages.getAsInt();
        }

        private int runLatexmk() throws AbortException {
            try {
                Process process = new ProcessBuilder(buildCommand())
                        .directory(workingDir.toFile())
                        .redirectErrorStream(true)
                        .start();
                // Drain the output so the process cannot block on a full pipe buffer.
                process.getInputStream().readAllBytes();
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new AbortException("latexmk timed out compiling '%s'".formatted(texFileName), CommandLine.ExitCode.SOFTWARE);
                }
                return process.exitValue();
            } catch (IOException e) {
                throw new AbortException("Could not run latexmk (install a TeX distribution or Docker): " + e.getMessage(), CommandLine.ExitCode.SOFTWARE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AbortException("Interrupted while compiling '%s'".formatted(texFileName), CommandLine.ExitCode.SOFTWARE);
            }
        }

        private List<String> buildCommand() {
            if (useDocker) {
                List<String> command = new ArrayList<>(List.of("docker", "run", "--rm"));
                // Without --user, the bind-mounted artifacts (pdf/log/aux) are created as the image's
                // default user (often root), which the invoking user then cannot read back or clean up.
                unixUserFlag().ifPresent(user -> {
                    command.add("--user");
                    command.add(user);
                });
                command.addAll(List.of(
                        "-v", workingDir + ":/work", "-w", "/work",
                        DOCKER_IMAGE,
                        "latexmk", "-pdf", "-interaction=nonstopmode", texFileName));
                return command;
            }
            return List.of("latexmk", "-pdf", "-interaction=nonstopmode", texFileName);
        }

        /// The invoking user's `uid:gid` for Docker's `--user`, read from the (self-created) working
        /// directory. Empty on non-Unix filesystems, where Docker manages bind-mount ownership itself.
        private Optional<String> unixUserFlag() {
            try {
                Object uid = Files.getAttribute(workingDir, "unix:uid");
                Object gid = Files.getAttribute(workingDir, "unix:gid");
                return Optional.of(uid + ":" + gid);
            } catch (IOException | UnsupportedOperationException e) {
                return Optional.empty();
            }
        }

        /// Read with Latin-1: TeX logs are not guaranteed UTF-8, and Latin-1 maps every byte without
        /// throwing while leaving the ASCII "Output written on" line intact.
        private String readLog() {
            Path log = workingDir.resolve(texFileName.replaceFirst("\\.tex$", "") + ".log");
            try {
                return Files.exists(log) ? Files.readString(log, StandardCharsets.ISO_8859_1) : "";
            } catch (IOException e) {
                return "";
            }
        }

        static OptionalInt parsePageCount(String log) {
            Matcher matcher = OUTPUT_PAGES.matcher(log);
            int parsedPages = -1;
            while (matcher.find()) {
                parsedPages = Integer.parseInt(matcher.group(1));
            }
            return parsedPages < 0 ? OptionalInt.empty() : OptionalInt.of(parsedPages);
        }

        private static boolean isLocalLatexmkAvailable() {
            String path = System.getenv("PATH");
            if (path == null) {
                return false;
            }
            for (String dir : path.split(File.pathSeparator)) {
                if (!dir.isBlank()
                        && (Files.isExecutable(Path.of(dir, "latexmk")) || Files.isExecutable(Path.of(dir, "latexmk.exe")))) {
                    return true;
                }
            }
            return false;
        }
    }
}
