import { check } from "@tauri-apps/plugin-updater";
import { ask, message } from "@tauri-apps/plugin-dialog";
import { invoke } from "@tauri-apps/api/core";

export async function checkForAppUpdates(onUserClick: boolean) {
  const update = await check();
  if (update === null || !update.available) {
    if (onUserClick) {
      await message("You are on the latest version. Stay awesome!", {
        title: "No Update Available",
        kind: "info",
        okLabel: "OK",
      });
    }
    return;
  }

  const yes = await ask(
    `Update to ${update.version} is available!\n\nRelease notes: ${update.body}`,
    {
      title: "Update Available",
      kind: "info",
      okLabel: "Update",
      cancelLabel: "Cancel",
    },
  );
  if (yes) {
    // Stop the backend sidecar first: Windows won't let the installer
    // overwrite backend-runtime files while that process still has its own
    // executable open.
    await invoke("stop_backend");
    await update.downloadAndInstall();
    await invoke("graceful_restart");
  }
}
