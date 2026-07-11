use std::path::PathBuf;
use std::process::{Child, Command};
use std::sync::Mutex;
use tauri::Manager;

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x08000000;

struct BackendProcess(Mutex<Option<Child>>);

#[tauri::command]
fn greet(name: &str) -> String {
    format!("Hello, {}! You've been greeted from Rust!", name)
}

// Kept in sync with BACKEND_APP_NAME in scripts/build-backend.mjs — this is what shows
// up as the process name in Task Manager and in Windows Firewall prompts.
const BACKEND_APP_NAME: &str = "Game Collection Backend";

// `jpackage --type app-image` lays the app-image out differently per OS, so both
// the top-level folder name and the launcher's path inside it are platform-specific:
//   Windows: <name>/<name>.exe                (app/, runtime/ are siblings)
//   Linux:   <name>/bin/<name>                (app/, runtime/ live under <name>/lib/)
//   macOS:   <name>.app/Contents/MacOS/<name> (app/, runtime/ live under Contents/)
// The launcher only needs its path — jpackage bakes in the right relative lookup
// for its app/runtime dirs — but the *whole* bundle dir must be shipped intact
// (see tauri.windows.conf.json / tauri.linux.conf.json / tauri.macos.conf.json).

#[cfg(target_os = "macos")]
fn backend_bundle_dir_name() -> String {
    format!("{BACKEND_APP_NAME}.app")
}
#[cfg(not(target_os = "macos"))]
fn backend_bundle_dir_name() -> String {
    BACKEND_APP_NAME.to_string()
}

#[cfg(windows)]
fn backend_launcher_relative_path() -> PathBuf {
    PathBuf::from(format!("{BACKEND_APP_NAME}.exe"))
}
#[cfg(target_os = "macos")]
fn backend_launcher_relative_path() -> PathBuf {
    PathBuf::from("Contents").join("MacOS").join(BACKEND_APP_NAME)
}
#[cfg(all(unix, not(target_os = "macos")))]
fn backend_launcher_relative_path() -> PathBuf {
    PathBuf::from("bin").join(BACKEND_APP_NAME)
}

/// Path to the jpackage-produced native launcher, which bundles its own JRE
/// so end users don't need Java installed. See `scripts/build-backend.mjs`.
fn backend_launcher_path(app: &tauri::AppHandle) -> PathBuf {
    let bundle_dir_name = backend_bundle_dir_name();
    let launcher_relative_path = backend_launcher_relative_path();
    if cfg!(debug_assertions) {
        // In dev, use the app-image produced by `npm run predev` (jpackage) directly.
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../backend/build/jpackage")
            .join(&bundle_dir_name)
            .join(&launcher_relative_path)
    } else {
        // In a bundled app, the whole app-image folder is shipped as a resource
        // (see tauri.<platform>.conf.json -> bundle.resources) so the launcher can
        // find its sibling app/runtime directories at runtime.
        use tauri::path::BaseDirectory;
        app.path()
            .resolve(
                PathBuf::from("backend-runtime").join(&launcher_relative_path),
                BaseDirectory::Resource,
            )
            .expect("failed to resolve bundled backend launcher")
    }
}

fn spawn_backend(app: &tauri::AppHandle) -> std::io::Result<Child> {
    let launcher_path = backend_launcher_path(app);
    let mut cmd = Command::new(&launcher_path);

    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }

    cmd.spawn()
}

fn kill_backend(state: &BackendProcess) {
    if let Ok(mut guard) = state.0.lock() {
        if let Some(mut child) = guard.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .manage(BackendProcess(Mutex::new(None)))
        .setup(|app| {
            let handle = app.handle().clone();
            match spawn_backend(&handle) {
                Ok(child) => {
                    let state = handle.state::<BackendProcess>();
                    *state.0.lock().unwrap() = Some(child);
                }
                Err(err) => {
                    eprintln!("failed to start backend sidecar: {err}");
                }
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::Destroyed = event {
                let state = window.state::<BackendProcess>();
                kill_backend(&state);
            }
        })
        .invoke_handler(tauri::generate_handler![greet])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
