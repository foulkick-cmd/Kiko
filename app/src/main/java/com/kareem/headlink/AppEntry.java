package com.kareem.headlink;

import java.util.ArrayList;
import java.util.List;

/** A launchable phone app, parsed from scrcpy-server's list_apps output. */
final class AppEntry {

    final String name;
    final String packageName;
    final boolean system;

    AppEntry(String name, String packageName, boolean system) {
        this.name = name;
        this.packageName = packageName;
        this.system = system;
    }

    /*
     * Format (LogUtils.buildAppListMessage, scrcpy 4.1):
     *   " * Name<padding to col 30> package"   (system app)
     *   " - Name<padding to col 30> package"   (user app)
     * Names of 30+ chars put the package on the next line, indented.
     */
    static List<AppEntry> parse(String output) {
        List<AppEntry> list = new ArrayList<>();
        String[] lines = output.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean system = line.startsWith(" * ");
            boolean user = line.startsWith(" - ");
            if (!system && !user) {
                continue;
            }
            String content = line.substring(3).trim();
            if (content.isEmpty()) {
                continue;
            }
            String next = i + 1 < lines.length ? lines[i + 1] : "";
            String nextTrim = next.trim();
            boolean pkgOnNextLine = next.startsWith("    ") && !nextTrim.isEmpty() && !nextTrim.contains(" ");
            String name;
            String pkg;
            if (pkgOnNextLine) {
                name = content;
                pkg = nextTrim;
                i++;
            } else {
                int sp = content.lastIndexOf(' ');
                if (sp < 0) {
                    continue;
                }
                name = content.substring(0, sp).trim();
                pkg = content.substring(sp + 1).trim();
            }
            if (!pkg.contains(".")) {
                continue;
            }
            list.add(new AppEntry(name.isEmpty() ? pkg : name, pkg, system));
        }
        return list;
    }
}
