import { spawnSync } from "node:child_process";
import { platform } from "node:os";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { existsSync, readdirSync, rmSync } from "node:fs";

const rootDir = join(dirname(fileURLToPath(import.meta.url)), "..");
const backendDir = join(rootDir, "backend");
const libsDir = join(backendDir, "build", "libs");
const jpackageDestDir = join(backendDir, "build", "jpackage");
const isWindows = platform() === "win32";
// Absolute path rather than a bare name: cmd.exe only searches the working directory
// when NoDefaultCurrentDirectoryInExePath is unset, which is not the case in hardened
// environments and some CI runners.
const gradlew = join(backendDir, isWindows ? "gradlew.bat" : "gradlew");

// Shown as the process name in Task Manager / Windows Firewall prompts, and
// used as the app-image folder + launcher executable name (kept in sync with
// BACKEND_APP_NAME in src-tauri/src/lib.rs).
export const BACKEND_APP_NAME = "Game Collection Backend";

// Modules required by the Spring Boot web app, determined via
// `jdeps --print-module-deps` against the exploded fat jar (BOOT-INF/classes + BOOT-INF/lib),
// plus java.logging/java.xml/jdk.crypto.ec added defensively.
const JLINK_MODULES = [
  "java.base",
  "java.compiler",
  "java.desktop",
  "java.instrument",
  "java.logging",
  "java.management",
  "java.naming",
  "java.net.http",
  "java.prefs",
  "java.rmi",
  "java.scripting",
  "java.security.jgss",
  "java.sql",
  "java.xml",
  "jdk.crypto.ec",
  "jdk.jfr",
  "jdk.management",
  "jdk.unsupported",
].join(",");

function run(command, args) {
  // shell: true is only needed on Windows so that gradlew.bat (not directly
  // executable) can run. On POSIX, spawnSync invokes the executable directly
  // (no shell involved), so args with spaces (e.g. "Game Collection Backend")
  // are passed through untouched.
  //
  // When shell is enabled, Node just does `[command].concat(args).join(' ')`
  // without quoting individual args, on both Windows and POSIX. So on Windows
  // we have to quote any arg containing whitespace ourselves, or it gets
  // word-split (e.g. jpackage's --name "Game Collection Backend").
  // The same applies to the command itself, which is an absolute path here and so
  // contains a space whenever the checkout does (e.g. "C:\Git Projekte\...").
  const quoteForShell = (value) => (/\s/.test(value) ? `"${value}"` : value);
  const shellSafeArgs = isWindows ? args.map(quoteForShell) : args;
  const shellSafeCommand = isWindows ? quoteForShell(command) : command;
  const result = spawnSync(shellSafeCommand, shellSafeArgs, {
    cwd: backendDir,
    stdio: "inherit",
    shell: isWindows,
  });
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

run(gradlew, ["bootJar"]);

// bootJar produces the runnable fat jar without a classifier. Gradle's `jar` task
// additionally leaves a "-plain.jar" behind (and it survives until a clean), which has
// no Main-Class — picking it makes jpackage silently skip the app image.
const CLASSIFIER_SUFFIXES = ["-plain.jar", "-sources.jar", "-javadoc.jar"];
const candidateJars = readdirSync(libsDir).filter(
  (f) => f.endsWith(".jar") && !CLASSIFIER_SUFFIXES.some((suffix) => f.endsWith(suffix)),
);
if (candidateJars.length !== 1) {
  console.error(
    candidateJars.length === 0
      ? `No runnable jar found in ${libsDir}`
      : `Expected exactly one runnable jar in ${libsDir}, found: ${candidateJars.join(", ")}`,
  );
  process.exit(1);
}
const jarName = candidateJars[0];

// Bundle a minimal custom JRE + native launcher with jpackage, so end users
// don't need Java installed.
// Output: backend/build/jpackage/<BACKEND_APP_NAME>/<BACKEND_APP_NAME>(.exe)
if (existsSync(jpackageDestDir)) {
  rmSync(jpackageDestDir, { recursive: true, force: true });
}

run("jpackage", [
  "--type",
  "app-image",
  "--input",
  join("build", "libs"),
  "--dest",
  join("build", "jpackage"),
  "--name",
  BACKEND_APP_NAME,
  "--main-jar",
  jarName,
  "--add-modules",
  JLINK_MODULES,
]);
