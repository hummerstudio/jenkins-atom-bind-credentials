package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The credentials atom, started the way the runner starts it.
 *
 * <p>These are the checks the old {@code tools/smoke.sh} made — a variable per credential, a private key as a 0600
 * file, a dotenv file, no secret in the log, and a refusal when nothing was bound — written as JUnit so a failure is a
 * test result with the workspace and {@code output.json} attached.
 */
class BindCredentialsIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("credentials become variables, a 0600 key file and a dotenv file, and never reach the log")
    void bindsEverythingTheRunnerResolved() throws Exception {
        AtomResult run = atom.file(".secrets.json",
                        "{\"secretText\": \"s3cr3t\", \"username\": \"robot\", \"sshKey\": \"KEY\"}")
                .input("{\"secretTextAs\": \"API_TOKEN\", \"usernameAs\": \"ARTIFACT_USER\","
                        + " \"sshKeyFile\": \"secrets/deploy.key\", \"envFile\": \"build.env\"}")
                .run();

        run.assertOk();
        assertEquals("success", run.status());
        assertEquals("s3cr3t", run.field("API_TOKEN"), "the variable is the credential's value");
        assertEquals("robot", run.field("ARTIFACT_USER"));

        assertTrue(run.exists("secrets/deploy.key"), "the key must be written where ssh looks for it");
        assertEquals("KEY", run.file("secrets/deploy.key"));
        Path key = run.work().resolve("secrets/deploy.key");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(key)),
                "a private key readable by the whole agent is a leak waiting to happen");

        assertTrue(run.file("build.env").contains("API_TOKEN=s3cr3t"), "dotenv: " + run.file("build.env"));
        assertFalse(run.console().contains("s3cr3t"), "the secret leaked to the log:\n" + run.console());
    }

    @Test
    @DisplayName("no credential at all is refused by name, not silently bound as empty variables")
    void refusesWithoutACredential() throws Exception {
        AtomResult run = atom.file(".secrets.json", "{}").input("{}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("no credential was provided"),
                "message: " + run.file("output.json"));
    }
}
