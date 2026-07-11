# Tauri + React + Typescript

This template should help get you started developing with Tauri, React and Typescript in Vite.

## Recommended IDE Setup

- [VS Code](https://code.visualstudio.com/) + [Tauri](https://marketplace.visualstudio.com/items?itemName=tauri-apps.tauri-vscode) + [rust-analyzer](https://marketplace.visualstudio.com/items?itemName=rust-lang.rust-analyzer)

## Backend (Kotlin / Spring Boot)

The `backend/` directory contains a Kotlin + Spring Boot service (Gradle, Kotlin DSL) that
is started and managed by the Tauri app as a sidecar process. It listens on
`http://localhost:8721` and exposes a health check (`/actuator/health`) plus an example
endpoint (`/api/games`).

**How it's wired up:**

- `scripts/build-backend.mjs` runs `gradlew bootJar` and then `jpackage` to turn the fat jar
  into a self-contained **app-image**: a native launcher (`Game Collection Backend.exe` on
  Windows — this is also the name shown in Task Manager and Windows Firewall prompts, see
  `BACKEND_APP_NAME` in the script) bundled with a trimmed custom JRE (built via `jlink`,
  only the modules the app actually needs — see `JLINK_MODULES` in the script). End users
  do **not** need Java installed; the JRE ships inside the app.
- `npm run dev` / `npm run build` run this script first (via the `predev` / `prebuild` npm
  hooks), producing `backend/build/jpackage/Game Collection Backend/` (containing the exe,
  `app/`, `runtime/`).
- `src-tauri/src/lib.rs` spawns that native launcher directly in the `setup` hook and kills
  the process again when the main window is destroyed. The name is duplicated there as
  `BACKEND_APP_NAME` — keep both in sync if you rename it.
  - In dev mode it runs straight from
    `backend/build/jpackage/Game Collection Backend/Game Collection Backend.exe`.
  - In a production bundle, the whole app-image folder is shipped as an app resource (see
    `bundle.resources` in `src-tauri/tauri.conf.json`, mapped to `backend-runtime/`) and
    resolved via Tauri's resource directory at runtime. The launcher needs its `app/` and
    `runtime/` sibling folders to stay next to it, which is why the entire folder — not
    just the exe — is bundled as a resource.

**Requirements for *building*:** a JDK (21+) with `jpackage`/`jlink` on `PATH` (bundled with
any standard JDK 14+ distribution). **Requirements for *running* the built app:** none —
no Java installation needed on the end user's machine.

**Manual backend commands** (run from repo root):

```sh
node scripts/build-backend.mjs   # gradlew bootJar + jpackage app-image
```

Or, for backend-only iteration without the native image (needs a local `java` on `PATH`):

```sh
cd backend && ./gradlew.bat bootRun
```
