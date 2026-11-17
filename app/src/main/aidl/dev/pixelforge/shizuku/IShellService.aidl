package dev.pixelforge.shizuku;

// Runs inside a Shizuku-hosted process with the shell (ADB) UID. It has exactly the
// privileges of `adb shell` — NOT root.
interface IShellService {
    // Transaction id required by Shizuku for UserService teardown.
    void destroy() = 16777114;

    // Executes /system/bin/sh -c <command>. Returns "<exitCode>\n<combined stdout/stderr>".
    String exec(String command, long timeoutMs) = 1;

    // Reads up to maxBytes of a file readable by the shell UID, or null.
    String readFile(String path, int maxBytes) = 2;

    int uid() = 3;
}
