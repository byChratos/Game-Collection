use std::path::Path;

// jpackage marks the JRE/launcher files it produces as read-only. tauri-build's
// resource copier (`fs::copy`) can't overwrite a read-only destination on Windows,
// so a stale "backend-runtime" dir from a previous build makes every subsequent
// build fail with "Access is denied. (os error 5)". Clear it out first so
// tauri-build always starts from a clean, writable destination.
fn clear_stale_backend_runtime() {
    let out_dir = match std::env::var_os("OUT_DIR") {
        Some(dir) => std::path::PathBuf::from(dir),
        None => return,
    };
    // OUT_DIR is target/<profile>/build/<pkg-hash>/out
    let Some(target_dir) = out_dir.parent().and_then(Path::parent).and_then(Path::parent) else {
        return;
    };
    let backend_runtime = target_dir.join("backend-runtime");
    if !backend_runtime.exists() {
        return;
    }
    let _ = remove_readonly_recursive(&backend_runtime);
    let _ = std::fs::remove_dir_all(&backend_runtime);
}

fn remove_readonly_recursive(dir: &Path) -> std::io::Result<()> {
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_dir() {
            remove_readonly_recursive(&path)?;
        } else {
            let metadata = std::fs::metadata(&path)?;
            let mut permissions = metadata.permissions();
            if permissions.readonly() {
                permissions.set_readonly(false);
                std::fs::set_permissions(&path, permissions)?;
            }
        }
    }
    Ok(())
}

fn main() {
    clear_stale_backend_runtime();
    tauri_build::build()
}
