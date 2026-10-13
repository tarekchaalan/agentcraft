package dev.agentcraft.mp.state;

public final class MpText {
    private MpText() {}
    public static String sanitize(String text, int cap) {
        if (text == null || text.length() > cap) throw new IllegalArgumentException("string cap exceeded");
        StringBuilder clean = new StringBuilder();
        for (int i=0;i<text.length();) {
            int c=text.codePointAt(i); i+=Character.charCount(c);
            if (c=='§') { if (i<text.length()) i+=Character.charCount(text.codePointAt(i)); continue; }
            int type=Character.getType(c);
            if (Character.isISOControl(c) || type==Character.FORMAT || type==Character.LINE_SEPARATOR ||
                type==Character.PARAGRAPH_SEPARATOR || type==Character.SURROGATE) continue;
            clean.appendCodePoint(c);
        }
        return clean.toString();
    }
    /** The wire's caps are also refused where a record is built: an oversized one fails in its caller, on the caller's thread, and never reaches the encoder. */
    public static String cap(String field, String text, int cap) {
        if (text == null) throw new IllegalArgumentException(field+" is missing");
        if (text.length() > cap) throw new IllegalArgumentException(field+" exceeds "+cap+" characters");
        return text;
    }
    public static void capOptional(String field, String text, int cap) { if (text != null) cap(field,text,cap); }
    public static void cap(String field, java.util.Collection<?> items, int cap) {
        if (items.size() > cap) throw new IllegalArgumentException(field+" exceeds "+cap+" entries");
    }
    /** The game's own rule for an identifier path, {@code [a-z0-9/._-]}: what a published agent id or skin has to be. */
    public static boolean isPath(String text) { return text != null && net.minecraft.resources.Identifier.isValidPath(text); }
    public static void path(String field, String text, int cap) {
        if (!isPath(cap(field,text,cap))) throw new IllegalArgumentException(field+" is not a resource path");
    }
}
