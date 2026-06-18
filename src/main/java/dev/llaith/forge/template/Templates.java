package dev.llaith.forge.template;

import dev.llaith.forge.ForgeException;
import dev.llaith.forge.spec.WorkflowSpecCompiler;
import dev.llaith.forge.spec.WorkflowSpec;
import dev.llaith.forge.util.Filesystem;
import dev.llaith.forge.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

public final class Templates {
    static final String SOURCE_ROOT_ENV = "FORGE_SOURCE_ROOT";
    static final String SOURCE_ROOT_PROPERTY = "forge.sourceRoot";

    private static final List<String> BUILT_IN_ORDER = List.of(
            "implement-change",
            "review-only",
            "review-and-fix",
            "auto-review-and-fix",
            "architecture-guard",
            "qa-gap-guard"
    );
    private static final List<String> DEFAULT_SCAFFOLD_TEMPLATES = List.of(
            "implement-change",
            "review-only",
            "review-and-fix"
    );

    private Templates() {
    }

    public static List<String> defaultScaffoldTemplateIds() {
        return DEFAULT_SCAFFOLD_TEMPLATES;
    }

    public static List<String> builtInTemplateIds() {
        return BUILT_IN_ORDER;
    }

    public static Path builtInTemplateDir(String id) {
        return sourceRoot().resolve("templates").resolve(id).normalize();
    }

    public static List<TemplateBundle> loadBuiltInTemplates() {
        return BUILT_IN_ORDER.stream().map(Templates::loadBuiltInTemplate).toList();
    }

    public static TemplateBundle loadBuiltInTemplate(String id) {
        if (!BUILT_IN_ORDER.contains(id)) {
            throw new ForgeException("error: unknown built-in template '" + id + "'");
        }
        return loadTemplateBundleAt(builtInTemplateDir(id));
    }

    public static TemplateBundle loadTemplateBundleAt(Path root) {
        Path rootDir = Filesystem.requireExistingDirectory(root, "template root");
        TemplateManifest manifest = Json.read(rootDir.resolve("template.json"), TemplateManifest.class);
        validateManifest(rootDir, manifest);
        Path workflowPath = rootDir.resolve(manifest.workflow()).normalize();
        WorkflowSpec workflow = WorkflowSpecCompiler.compile(workflowPath).spec();
        validateNotificationHooks(rootDir, manifest.id(), workflow);
        List<ReferencedFile> referencedFiles = referencedFiles(rootDir, manifest, workflow);
        return new TemplateBundle(manifest, workflow, rootDir, workflowPath, referencedFiles);
    }

    public static WorkflowSpec loadSourceWorkflow(Path path) {
        return WorkflowSpecCompiler.compile(path).spec();
    }

    public static ScaffoldResult scaffoldRepo(
            Path repo,
            boolean all,
            List<String> templateIds,
            List<Path> templatePaths,
            boolean force
    ) {
        Path repoRoot = Filesystem.requireExistingDirectory(repo, "repo root");
        List<TemplateBundle> selected = selectScaffoldTemplates(all, templateIds, templatePaths);
        Path forgeDir = repoRoot.resolve(".forge");
        List<ScaffoldFileResult> files = new ArrayList<>();
        writeFile(forgeDir.resolve("README.md"), "# Forge\n\nRepo-local Forge assets.\n", "doc", force, files);
        for (TemplateBundle bundle : selected) {
            copyBundle(bundle.rootDir(), forgeDir.resolve("templates").resolve(bundle.manifest().id()), force, files);
        }
        return new ScaffoldResult(
                "scaffolded",
                repoRoot.toString(),
                forgeDir.toString(),
                selected.stream().map(bundle -> bundle.manifest().id()).toList(),
                force,
                files,
                List.of("Review .forge/templates before running Forge workflows.")
        );
    }

    private static List<TemplateBundle> selectScaffoldTemplates(
            boolean all,
            List<String> templateIds,
            List<Path> templatePaths
    ) {
        List<TemplateBundle> selected = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        if (all) {
            ids.addAll(BUILT_IN_ORDER);
        } else if (templateIds.isEmpty() && templatePaths.isEmpty()) {
            ids.addAll(DEFAULT_SCAFFOLD_TEMPLATES);
        } else {
            ids.addAll(templateIds);
        }
        for (String id : ids) {
            selected.add(loadBuiltInTemplate(id));
        }
        for (Path path : templatePaths) {
            selected.add(loadTemplateBundleAt(path));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (TemplateBundle bundle : selected) {
            if (!seen.add(bundle.manifest().id())) {
                throw new ForgeException("error: duplicate selected template '" + bundle.manifest().id() + "'");
            }
        }
        return selected;
    }

    private static void copyBundle(
            Path sourceRoot,
            Path targetRoot,
            boolean force,
            List<ScaffoldFileResult> files
    ) {
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            List<Path> paths = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !sourceRoot.relativize(path).startsWith("examples"))
                    .sorted(Comparator.comparing(path -> sourceRoot.relativize(path).toString()))
                    .toList();
            for (Path source : paths) {
                Path relative = sourceRoot.relativize(source);
                Path target = targetRoot.resolve(relative);
                copyFile(source, target, scaffoldFileKind(relative), force, files);
            }
        } catch (IOException error) {
            throw new ForgeException("failed to scaffold template from " + sourceRoot, error);
        }
    }

    private static void copyFile(
            Path source,
            Path target,
            String kind,
            boolean force,
            List<ScaffoldFileResult> files
    ) {
        if (Files.exists(target) && !force) {
            files.add(new ScaffoldFileResult(target.toString(), kind, "skipped"));
            return;
        }
        boolean existed = Files.exists(target);
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            files.add(new ScaffoldFileResult(target.toString(), kind, existed ? "overwritten" : "created"));
        } catch (IOException error) {
            throw new ForgeException("failed to scaffold file " + target, error);
        }
    }

    private static void writeFile(
            Path target,
            String content,
            String kind,
            boolean force,
            List<ScaffoldFileResult> files
    ) {
        if (Files.exists(target) && !force) {
            files.add(new ScaffoldFileResult(target.toString(), kind, "skipped"));
            return;
        }
        boolean existed = Files.exists(target);
        Filesystem.writeUtf8(target, content);
        files.add(new ScaffoldFileResult(target.toString(), kind, existed ? "overwritten" : "created"));
    }

    private static void validateManifest(Path rootDir, TemplateManifest manifest) {
        if (manifest.id() == null || manifest.id().isBlank()) {
            throw new ForgeException("template manifest at " + rootDir + " is missing id");
        }
        if (manifest.workflow() == null || manifest.workflow().isBlank()) {
            throw new ForgeException("template '" + manifest.id() + "' is missing workflow");
        }
        for (ReferencedFile file : referencedFiles(rootDir, manifest)) {
            if (!Files.isRegularFile(Path.of(file.path()))) {
                throw new ForgeException(
                        "template '" + manifest.id() + "' references missing "
                                + file.kind()
                                + " '"
                                + file.relativePath()
                                + "'"
                );
            }
        }
    }

    private static List<ReferencedFile> referencedFiles(Path rootDir, TemplateManifest manifest) {
        List<ReferencedFile> files = new ArrayList<>();
        files.add(reference(rootDir, "template.json", "manifest"));
        files.add(reference(rootDir, manifest.workflow(), "workflow"));
        manifest.promptFileList().forEach(path -> files.add(reference(rootDir, path, "prompt")));
        manifest.hookFileList().forEach(path -> files.add(reference(rootDir, path, "hook")));
        manifest.scriptFileList().forEach(path -> files.add(reference(rootDir, path, "script")));
        manifest.recommendedHooks().forEach(path -> files.add(reference(rootDir, path, "recommended_hook")));
        return files;
    }

    private static List<ReferencedFile> referencedFiles(Path rootDir, TemplateManifest manifest, WorkflowSpec workflow) {
        List<ReferencedFile> files = new ArrayList<>(referencedFiles(rootDir, manifest));
        addNotificationHook(files, rootDir, workflow.notifications(), "default_hook");
        addNotificationHook(files, rootDir, workflow.notifications(), "complete_hook");
        addNotificationHook(files, rootDir, workflow.notifications(), "escalate_hook");
        addNotificationHook(files, rootDir, workflow.notifications(), "human_review_hook");
        return List.copyOf(files);
    }

    private static void validateNotificationHooks(Path rootDir, String templateId, WorkflowSpec workflow) {
        validateNotificationHook(rootDir, templateId, workflow.notifications(), "default_hook");
        validateNotificationHook(rootDir, templateId, workflow.notifications(), "complete_hook");
        validateNotificationHook(rootDir, templateId, workflow.notifications(), "escalate_hook");
        validateNotificationHook(rootDir, templateId, workflow.notifications(), "human_review_hook");
    }

    private static void validateNotificationHook(
            Path rootDir,
            String templateId,
            com.fasterxml.jackson.databind.JsonNode notifications,
            String field
    ) {
        String path = notificationHookPath(notifications, field);
        if (path == null) {
            return;
        }
        if (!Files.isRegularFile(rootDir.resolve(path).normalize())) {
            throw new ForgeException("template '"
                    + templateId
                    + "' references missing workflow notification hook '"
                    + path
                    + "'");
        }
    }

    private static void addNotificationHook(
            List<ReferencedFile> files,
            Path rootDir,
            com.fasterxml.jackson.databind.JsonNode notifications,
            String field
    ) {
        String path = notificationHookPath(notifications, field);
        if (path != null) {
            files.add(reference(rootDir, path, "notification_hook"));
        }
    }

    private static String notificationHookPath(
            com.fasterxml.jackson.databind.JsonNode notifications,
            String field
    ) {
        if (notifications == null || notifications.isNull()) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode hook = notifications.get(field);
        if (hook == null || hook.isNull() || !hook.hasNonNull("path")) {
            return null;
        }
        return hook.get("path").asText();
    }

    private static ReferencedFile reference(Path rootDir, String path, String kind) {
        return new ReferencedFile(kind, path, rootDir.resolve(path).normalize().toString());
    }

    private static String scaffoldFileKind(Path relative) {
        Path fileName = relative.getFileName();
        if (fileName == null) {
            return "asset";
        }
        String name = fileName.toString();
        if ("workflow.json".equals(name)) {
            return "workflow";
        }
        if ("template.json".equals(name)) {
            return "manifest";
        }
        if ("README.md".equals(name)) {
            return "doc";
        }
        if (relative.startsWith("prompts")) {
            return "prompt";
        }
        if (relative.startsWith("hooks")) {
            return "hook";
        }
        if (relative.startsWith("scripts")) {
            return "script";
        }
        return "asset";
    }

    public record ScaffoldResult(
            String status,
            @com.fasterxml.jackson.annotation.JsonProperty("repo_root") String repoRoot,
            @com.fasterxml.jackson.annotation.JsonProperty("forge_dir") String forgeDir,
            List<String> templates,
            boolean force,
            List<ScaffoldFileResult> files,
            List<String> next
    ) {
        public ScaffoldResult {
            templates = templates == null ? List.of() : List.copyOf(templates);
            files = files == null ? List.of() : List.copyOf(files);
            next = next == null ? List.of() : List.copyOf(next);
        }
    }

    public record ScaffoldFileResult(String path, String kind, String status) {
    }

    private static Path sourceRoot() {
        String configured = configuredSourceRoot();
        if (configured != null) {
            return requireSourceRoot(Path.of(configured), "configured source root");
        }

        Path codeLocation = codeLocation();
        Path assetRoot = discoverPackagedAssetRoot(codeLocation);
        if (assetRoot != null) {
            return assetRoot;
        }

        Path cwdRoot = discoverSourceRoot(Path.of("").toAbsolutePath().normalize());
        if (cwdRoot != null) {
            return cwdRoot;
        }

        Path codeRoot = discoverSourceRoot(codeLocation);
        if (codeRoot != null) {
            return codeRoot;
        }

        throw new ForgeException("error: unable to locate built-in templates; set " + SOURCE_ROOT_ENV);
    }

    private static @Nullable String configuredSourceRoot() {
        String value = System.getProperty(SOURCE_ROOT_PROPERTY);
        if (value != null && !value.isBlank()) {
            return value;
        }
        value = System.getenv(SOURCE_ROOT_ENV);
        return value == null || value.isBlank() ? null : value;
    }

    private static Path requireSourceRoot(Path root, String label) {
        Path absolute = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute.resolve("templates"))) {
            throw new ForgeException("error: " + label + " does not contain built-in templates: " + absolute);
        }
        return absolute;
    }

    private static @Nullable Path discoverSourceRoot(Path start) {
        Path current = Files.isRegularFile(start) ? start.getParent() : start;
        while (current != null) {
            if (Files.isDirectory(current.resolve("templates"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static @Nullable Path discoverPackagedAssetRoot(Path codeLocation) {
        Path base = Files.isRegularFile(codeLocation) ? codeLocation.getParent() : codeLocation;
        if (base == null) {
            return null;
        }
        Path assets = base.resolve("forge-assets").normalize();
        return Files.isDirectory(assets.resolve("templates")) ? assets : null;
    }

    private static Path codeLocation() {
        try {
            java.security.CodeSource source = Templates.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return Path.of("").toAbsolutePath().normalize();
            }
            URI uri = source.getLocation().toURI();
            return Path.of(uri).toAbsolutePath().normalize();
        } catch (SecurityException | URISyntaxException | IllegalArgumentException error) {
            return Path.of("").toAbsolutePath().normalize();
        }
    }
}
