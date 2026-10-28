package com.gathin.tvhome;

/** Safely normalizes public shared-storage relative paths. No special storage access. */
final class RowaSourceFolder {
    private RowaSourceFolder() { }

    static String normalize(String value) {
        if (value == null) return null;
        String path = value.trim().replace('\\', '/');
        for (String prefix : new String[]{"/storage/emulated/0/", "/sdcard/", "storage/emulated/0/", "sdcard/"}) {
            if (path.startsWith(prefix)) { path = path.substring(prefix.length()); break; }
        }
        if (path.isEmpty() || path.startsWith("/") || path.length() > 200 || path.indexOf(':') >= 0)
            return null;
        String[] segments = path.split("/", -1);
        int count = segments.length;
        if (count > 0 && segments[count - 1].isEmpty()) count--;
        if (count < 1 || count > 8) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            String part = segments[i];
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith(".")) return null;
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (Character.isISOControl(c)) return null;
            }
            if (i == 0 && part.equalsIgnoreCase("Android")) return null; // App-private directories
            if (i != 0) out.append('/');
            out.append(part);
        }
        out.append('/');
        return out.toString();
    }
}
