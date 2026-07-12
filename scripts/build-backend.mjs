import { spawnSync } from "node:child_process";
import { platform } from "node:os";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { chmodSync, existsSync, readdirSync, rmSync } from "node:fs";

const rootDir = join(dirname(fileURLToPath(import.meta.url)), "..");
const backendDir = join(rootDir, "backend");
const libsDir = join(backendDir, "build", "libs");
const jpackageDestDir = join(backendDir, "build", "jpackage");
const isWindows = platform() === "win32";
const gradlew = isWindows ? "gradlew.bat" : "./gradlew";

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
  const shellSafeArgs = isWindows
    ? args.map((arg) => (/\s/.test(arg) ? `"${arg}"` : arg))
    : args;
  const result = spawnSync(command, shellSafeArgs, {
    cwd: backendDir,
    stdio: "inherit",
    shell: isWindows,
  });
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

run(gradlew, ["bootJar"]);

const jarName = readdirSync(libsDir).find((f) => f.endsWith(".jar"));
if (!jarName) {
  console.error(`No jar found in ${libsDir}`);
  process.exit(1);
}

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

// jpackage marks its output files read-only. Windows' CopyFile preserves that
// attribute, so Tauri's build script (which copies this tree into
// target/debug/backend-runtime as a bundle resource) creates a read-only copy
// on the first build. Every subsequent build then fails with "Access is
// denied (os error 5)" trying to overwrite those read-only files. Clear the
// attribute here so the copy stays writable and future builds can overwrite it.
function clearReadOnlyRecursive(path) {
  chmodSync(path, 0o666);
  const entries = readdirSync(path, { withFileTypes: true });
  for (const entry of entries) {
    const entryPath = join(path, entry.name);
    if (entry.isDirectory()) {
      clearReadOnlyRecursive(entryPath);
    } else {
      chmodSync(entryPath, 0o666);
    }
  }
}
clearReadOnlyRecursive(jpackageDestDir);
