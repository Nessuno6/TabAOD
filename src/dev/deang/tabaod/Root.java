package dev.deang.tabaod;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Runs a shell script as root and returns its output. Call off the main thread. */
final class Root {
    static String run(String script) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String l; (l = r.readLine()) != null; ) sb.append(l).append('\n');
            }
            sb.append("exit ").append(p.waitFor());
        } catch (Exception e) {
            sb.append("error: ").append(e);
        }
        return sb.toString();
    }
}
