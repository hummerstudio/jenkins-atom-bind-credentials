package com.jenkinschina.atom.bindcredentials;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * credentials-binding as an atom: turn the credentials the runner resolved into variables and files.
 *
 * <p>An atom never sees Jenkins' credential store — the runner does, and hands over the values declared in
 * {@code x-jenkins.secrets}. This atom is the part that matters to a build: put them where the tools expect them
 * (环境变量 or a file only the build can read), and keep them out of the log.
 */
public class BindCredentialsAtom implements AtomTask {

    /** secret name → (variable it becomes, file it may become). The names match x-jenkins.secrets. */
    private static final List<String> NAMES = List.of("secretText", "username", "password", "sshKey", "certificate");

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String fileMode = context.getParam("fileMode", "600");
        String envFile = context.getParam("envFile", "");
        boolean failOnMissing = context.getBooleanParam("failOnMissing", true);

        Map<String, String> values = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String name : NAMES) {
            String value = context.getSecret(name);
            if (value == null || value.isBlank()) {
                missing.add(name);
                continue;
            }
            values.put(name, value);
        }
        if (values.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "no credential was provided: bind at least one of "
                    + NAMES);
        }
        if (failOnMissing && !missing.isEmpty()) {
            context.log("[credentials] not bound (no credential id given): " + String.join(", ", missing));
        }

        // 1. files: a key needs to be a file at 0600, not a variable
        AtomResult result = AtomResult.success()
                .data("bound", String.join(",", values.keySet()))
                .data("count", String.valueOf(values.size()));
        List<String> written = new ArrayList<>();
        for (String name : NAMES) {
            String target = context.getParam(name + "File", "");
            if (target.isBlank() || !values.containsKey(name)) {
                continue;
            }
            Path path = workspace.resolve(target).normalize();
            if (!path.startsWith(workspace)) {
                return AtomResult.error(ErrorType.USER, 40002, "file target outside the workspace: " + target);
            }
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, values.get(name), StandardCharsets.UTF_8);
            restrict(path, fileMode);
            written.add(target);
        }
        if (!written.isEmpty()) {
            result.artifact("credentialFiles", List.copyOf(written));
            result.data("files", String.join(",", written));
        }

        // 2. variables: the output key is the variable name, so the caller decides what to call it
        for (String name : NAMES) {
            String as = context.getParam(name + "As", "");
            String value = values.get(name);
            if (value == null) {
                continue;
            }
            if (as.isBlank()) {
                continue;
            }
            if (!as.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                return AtomResult.error(ErrorType.USER, 40003, name + "As must be a variable name: " + as);
            }
            result.data(as, value);
            context.log("[credentials] " + name + " -> $" + as);
        }

        // 3. a dotenv file for tools that read one (docker --env-file, docker compose, dotenv-cli)
        if (!envFile.isBlank()) {
            Path path = workspace.resolve(envFile).normalize();
            if (!path.startsWith(workspace)) {
                return AtomResult.error(ErrorType.USER, 40004, "env file outside the workspace: " + envFile);
            }
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            StringBuilder dotenv = new StringBuilder();
            for (String name : NAMES) {
                String as = context.getParam(name + "As", "");
                String value = values.get(name);
                if (value == null || as.isBlank()) {
                    continue;
                }
                dotenv.append(as).append('=').append(quote(value)).append('\n');
            }
            Files.writeString(path, dotenv.toString(), StandardCharsets.UTF_8);
            restrict(path, fileMode);
            result.data("envFile", envFile);
        }

        context.log("[credentials] bound " + values.size() + " credential(s): " + String.join(", ", values.keySet()));
        return result;
    }

    /** 0600 by default: a private key readable by the whole agent is a leak waiting to happen. */
    private static void restrict(Path path, String mode) {
        try {
            EnumSet<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ);
            if (!"400".equals(mode)) {
                permissions.add(PosixFilePermission.OWNER_WRITE);
            }
            Files.setPosixFilePermissions(path, permissions);
        } catch (Exception ignored) {
            // a non-POSIX filesystem (Windows agent) keeps its default ACL
        }
    }

    private static String quote(String value) {
        if (value.contains("\n") || value.contains("\"") || value.contains(" ")) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }
        return value;
    }
}
