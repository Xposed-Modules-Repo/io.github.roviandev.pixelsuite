package io.github.pixelsuite;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds Pixel Studio's renamed classes and methods by reading its own dex files, for a Pixel Studio build
 * that {@link StudioNames} has no table for. R8 gives everything new short names on every update, but some
 * things survive it: the Compose group keys inside each composable (a fixed number per function), the text
 * of toString() in data classes ("UndoRedoState(canUndo=", "RenderConfig("), library class names kept for
 * reflection, and the shape of method signatures. Each name is found from one of those anchors; anything
 * that cannot be found is left out and that one feature stays off.
 *
 * Plain Java (no Android classes), so it is tested on a PC against the real APKs. It runs once per Pixel
 * Studio build; the result is cached by {@link StudioNames}.
 */
final class DexScan {
    // Compose group keys of the editor's composables (the same in 192285 and 192717).
    static final int KEY_PEN = 287371071, KEY_TOOL = 430381793, KEY_UNDO_BAR = -1593035142, KEY_DONE = 643694645,
            KEY_TOOLBAR_ROW = 268564011;

    static final class Cls {
        String desc, sup; String[] ifaces = new String[0];
        final List<Fld> fields = new ArrayList<Fld>();
        final List<Mth> methods = new ArrayList<Mth>();
    }
    static final class Fld { String name, type; boolean isStatic; }
    static final class Mth {
        Dex dex; Cls owner; String name, ret; String[] params; boolean isStatic; int codeOff;
        String sig() { StringBuilder b = new StringBuilder("("); for (String p : params) b.append(p); return b.append(')').append(ret).toString(); }
    }
    static final class Insn { int op, index; long literal; }

    static final class Dex {
        final byte[] d; String[] strings; String[] types; int[] protoRet; String[][] protoParams;
        int[] fieldClass, fieldType, fieldName; int[] methodClass, methodProto, methodName;
        final Map<String, Integer> stringIndex = new HashMap<String, Integer>();
        Dex(byte[] d) { this.d = d; }
        int u16(int o) { return (d[o] & 0xff) | (d[o + 1] & 0xff) << 8; }
        int u32(int o) { return (d[o] & 0xff) | (d[o + 1] & 0xff) << 8 | (d[o + 2] & 0xff) << 16 | (d[o + 3] & 0xff) << 24; }
        int pos;
        int uleb() { int r = 0, s = 0, b; do { b = d[pos++] & 0xff; r |= (b & 0x7f) << s; s += 7; } while ((b & 0x80) != 0); return r; }
        String mutf8(int o) {
            pos = o; int len = uleb(); char[] out = new char[len]; int p = pos;
            for (int i = 0; i < len; i++) {
                int a = d[p++] & 0xff;
                if (a < 0x80) out[i] = (char) a;
                else if ((a & 0xe0) == 0xc0) out[i] = (char) ((a & 0x1f) << 6 | (d[p++] & 0x3f));
                else { int b = d[p++] & 0x3f, c = d[p++] & 0x3f; out[i] = (char) ((a & 0x0f) << 12 | b << 6 | c); }
            }
            return new String(out);
        }
        String[] typeList(int off) {
            if (off == 0) return new String[0];
            int n = u32(off); String[] r = new String[n];
            for (int i = 0; i < n; i++) r[i] = types[u16(off + 4 + i * 2)];
            return r;
        }
    }

    final Map<String, Cls> classes = new HashMap<String, Cls>();
    final List<Mth> allMethods = new ArrayList<Mth>();

    static DexScan read(String apk) throws Exception {
        DexScan s = new DexScan();
        ZipFile z = new ZipFile(apk);
        try {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                String n = entry.getName();
                if (!n.startsWith("classes") || !n.endsWith(".dex") || n.contains("/")) continue;
                InputStream in = z.getInputStream(entry);
                ByteArrayOutputStream buf = new ByteArrayOutputStream((int) Math.max(entry.getSize(), 1 << 16));
                byte[] b = new byte[1 << 16]; int r;
                while ((r = in.read(b)) > 0) buf.write(b, 0, r);
                in.close();
                s.parse(new Dex(buf.toByteArray()));
            }
        } finally { z.close(); }
        return s;
    }

    void parse(Dex x) {
        int stringsSize = x.u32(0x38), stringsOff = x.u32(0x3c), typesSize = x.u32(0x40), typesOff = x.u32(0x44);
        int protosSize = x.u32(0x48), protosOff = x.u32(0x4c), fieldsSize = x.u32(0x50), fieldsOff = x.u32(0x54);
        int methodsSize = x.u32(0x58), methodsOff = x.u32(0x5c), classesSize = x.u32(0x60), classesOff = x.u32(0x64);
        x.strings = new String[stringsSize];
        for (int i = 0; i < stringsSize; i++) { x.strings[i] = x.mutf8(x.u32(stringsOff + i * 4)); x.stringIndex.put(x.strings[i], i); }
        x.types = new String[typesSize];
        for (int i = 0; i < typesSize; i++) x.types[i] = x.strings[x.u32(typesOff + i * 4)];
        x.protoRet = new int[protosSize]; x.protoParams = new String[protosSize][];
        for (int i = 0; i < protosSize; i++) { int o = protosOff + i * 12; x.protoRet[i] = x.u32(o + 4); x.protoParams[i] = x.typeList(x.u32(o + 8)); }
        x.fieldClass = new int[fieldsSize]; x.fieldType = new int[fieldsSize]; x.fieldName = new int[fieldsSize];
        for (int i = 0; i < fieldsSize; i++) { int o = fieldsOff + i * 8; x.fieldClass[i] = x.u16(o); x.fieldType[i] = x.u16(o + 2); x.fieldName[i] = x.u32(o + 4); }
        x.methodClass = new int[methodsSize]; x.methodProto = new int[methodsSize]; x.methodName = new int[methodsSize];
        for (int i = 0; i < methodsSize; i++) { int o = methodsOff + i * 8; x.methodClass[i] = x.u16(o); x.methodProto[i] = x.u16(o + 2); x.methodName[i] = x.u32(o + 4); }
        for (int i = 0; i < classesSize; i++) {
            int o = classesOff + i * 32;
            Cls c = new Cls();
            c.desc = x.types[x.u32(o)];
            int sup = x.u32(o + 8); c.sup = sup == -1 ? null : x.types[sup];
            c.ifaces = x.typeList(x.u32(o + 12));
            int data = x.u32(o + 24);
            if (data != 0) {
                x.pos = data;
                int sf = x.uleb(), inf = x.uleb(), dm = x.uleb(), vm = x.uleb();
                int idx = 0;
                for (int k = 0; k < sf + inf; k++) {
                    if (k == sf) idx = 0;
                    idx += x.uleb(); x.uleb();
                    Fld f = new Fld(); f.name = x.strings[x.fieldName[idx]]; f.type = x.types[x.fieldType[idx]]; f.isStatic = k < sf;
                    c.fields.add(f);
                }
                idx = 0;
                for (int k = 0; k < dm + vm; k++) {
                    if (k == dm) idx = 0;
                    idx += x.uleb(); int access = x.uleb(), code = x.uleb();
                    Mth m = new Mth(); m.dex = x; m.owner = c; m.name = x.strings[x.methodName[idx]];
                    int proto = x.methodProto[idx]; m.ret = x.types[x.protoRet[proto]]; m.params = x.protoParams[proto];
                    m.isStatic = (access & 0x8) != 0; m.codeOff = code;
                    c.methods.add(m); allMethods.add(m);
                }
            }
            classes.put(c.desc, c);
        }
    }

    // ------------------------------------------------------------------ bytecode

    private static final int[] LEN = new int[256];
    static {
        String spec =   // opcode ranges and their size in 16-bit units
                "00-00:1,01-01:1,02-02:2,03-03:3,04-04:1,05-05:2,06-06:3,07-07:1,08-08:2,09-09:3,0a-12:1,13-13:2,14-14:3,"
              + "15-16:2,17-17:3,18-18:5,19-1a:2,1b-1b:3,1c-1c:2,1d-1e:1,1f-20:2,21-21:1,22-23:2,24-26:3,27-28:1,29-29:2,"
              + "2a-2c:3,2d-3d:2,3e-43:1,44-6d:2,6e-72:3,73-73:1,74-78:3,79-8f:1,90-af:2,b0-cf:1,d0-e2:2,e3-f9:1,fa-fb:4,"
              + "fc-fd:3,fe-ff:2";
        for (String part : spec.split(",")) {
            String[] r = part.split("[-:]");
            for (int op = Integer.parseInt(r[0], 16); op <= Integer.parseInt(r[1], 16); op++) LEN[op] = Integer.parseInt(r[2]);
        }
    }

    /** The method's instructions, decoded one by one (payload tables skipped). */
    static List<Insn> code(Mth m) {
        List<Insn> out = new ArrayList<Insn>();
        if (m.codeOff == 0) return out;
        Dex x = m.dex;
        int size = x.u32(m.codeOff + 12), base = m.codeOff + 16;
        int i = 0;
        while (i < size) {
            int unit = x.u16(base + i * 2), op = unit & 0xff, len;
            if (op == 0 && unit != 0) {
                int kind = unit >> 8;
                if (kind == 1) len = 4 + x.u16(base + i * 2 + 2) * 2;
                else if (kind == 2) len = 2 + x.u16(base + i * 2 + 2) * 4;
                else if (kind == 3) { int w = x.u16(base + i * 2 + 2); long n = x.u32(base + i * 2 + 4) & 0xffffffffL; len = (int) (4 + (n * w + 1) / 2); }
                else len = 1;
            } else {
                len = LEN[op];
                Insn in = new Insn(); in.op = op;
                if (op == 0x14) in.literal = x.u32(base + i * 2 + 2);
                else if (op == 0x1a || op == 0x22 || (op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78) || (op >= 0x52 && op <= 0x6d))
                    in.index = x.u16(base + i * 2 + 2);
                else if (op == 0x1b) in.index = x.u32(base + i * 2 + 2);
                out.add(in);
            }
            i += Math.max(len, 1);
        }
        return out;
    }

    static boolean isInvoke(int op) { return (op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78); }

    List<Mth> withConst(int value) {
        List<Mth> r = new ArrayList<Mth>();
        for (Mth m : allMethods) for (Insn in : code(m)) if (in.op == 0x14 && (int) in.literal == value) { r.add(m); break; }
        return r;
    }

    List<Mth> withString(String s) {
        List<Mth> r = new ArrayList<Mth>();
        for (Mth m : allMethods) {
            Integer idx = m.dex.stringIndex.get(s);
            if (idx == null) continue;
            for (Insn in : code(m)) if ((in.op == 0x1a || in.op == 0x1b) && in.index == idx) { r.add(m); break; }
        }
        return r;
    }

    /** Descriptor -> plain class name ("Labc;" -> "abc"). */
    static String name(String desc) {
        if (desc == null || !desc.startsWith("L")) return desc;
        return desc.substring(1, desc.length() - 1).replace('/', '.');
    }

    static boolean same(String[] p, String... want) {
        if (p.length != want.length) return false;
        for (int i = 0; i < p.length; i++) if (want[i] != null && !want[i].equals(p[i])) return false;
        return true;
    }

    Mth find(Cls c, String name, String sig) { if (c != null) for (Mth m : c.methods) if (m.name.equals(name) && (sig == null || m.sig().equals(sig))) return m; return null; }

    // ------------------------------------------------------------------ the names

    /** Everything that could be found, keyed like {@link StudioNames}. */
    Map<String, String> resolve() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        put(out, "unit", classOf(withString("kotlin.Unit")));
        put(out, "undoState", classOf(withString("UndoRedoState(canUndo=")));
        String undo = desc(out.get("undoState"));
        if (undo != null) for (Mth m : allMethods) {
            if (m.isStatic || m.params.length != 0 || !"V".equals(m.ret) || m.name.startsWith("<") || m.owner.desc.equals(undo)) continue;
            if (creates(m, undo)) { put(out, "history", m.owner.desc); out.put("history.refresh", m.name); break; }
        }

        Mth pen = one(withConst(KEY_PEN), true, 8);
        if (pen != null && same(pen.params, null, null, pen.params[1], "J", "Z", null, null, "I")) {
            put(out, "ui", pen.owner.desc); out.put("ui.pen", pen.name);
            put(out, "modifier", pen.params[0]); put(out, "painter", pen.params[1]);
            put(out, "fn0", pen.params[5]); put(out, "composer", pen.params[6]);
            String painter = pen.params[1], fn0 = pen.params[5], composer = pen.params[6], modifier = pen.params[0];
            // the pen's own restart lambda: the last object it creates that holds both of its painters
            List<Insn> code = code(pen);
            for (int i = code.size() - 1; i >= 0; i--) {
                Insn in = code.get(i);
                if (in.op != 0x22) continue;
                Cls c = classes.get(pen.dex.types[in.index]);
                if (c != null && (count(c, painter) >= 2 || ctorHas(c, "J", "Z", fn0))) { put(out, "penRestart", c.desc); break; }
            }
            Mth tool = one(withConst(KEY_TOOL), true, 6);
            if (tool != null && same(tool.params, "I", fn0, "Z", modifier, composer, "I")) {
                out.put("ui.tool", tool.name);
                for (Insn in : code(tool)) {
                    if (!isInvoke(in.op)) continue;
                    Dex x = tool.dex; int proto = x.methodProto[in.index];
                    if (same(x.protoParams[proto], "I", composer, "I") && painter.equals(x.types[x.protoRet[proto]])) {
                        put(out, "painterRes", x.types[x.methodClass[in.index]]); out.put("painterRes.m", x.strings[x.methodName[in.index]]); break;
                    }
                }
            }
            // (The 4dp gap before our button, Spacer(Modifier.width), is left out here: a width and a height
            // modifier have the same signature, so it only comes from a checked table.)
            Mth bar = one(withConst(KEY_UNDO_BAR), true, 6);
            if (bar != null && same(bar.params, "Z", "Z", fn0, fn0, composer, "I")) out.put("ui.undoBar", bar.name);
            Mth done = one(withConst(KEY_DONE), true, 7);
            if (done != null && same(done.params, "Ljava/lang/String;", "Z", fn0, "I", composer, "I", "I")) out.put("ui.done", done.name);
            for (Mth m : withConst(KEY_TOOLBAR_ROW)) {
                if (!m.owner.desc.equals(pen.owner.desc)) continue;
                // the row's lambda object is created just before the key is loaded: lambda(key, row, composer)
                String last = null;
                for (Insn in : code(m)) {
                    if (in.op == 0x22) last = m.dex.types[in.index];
                    else if (in.op == 0x14 && (int) in.literal == KEY_TOOLBAR_ROW) { put(out, "toolbarLambda", last); break; }
                }
                if (out.containsKey("toolbarLambda")) break;
            }
            for (Cls c : classes.values()) {
                if (!painter.equals(c.sup)) continue;
                for (Mth m : c.methods) {
                    if (!m.name.equals("<init>") || m.params.length != 1) continue;
                    Cls image = classes.get(m.params[0]);
                    if (image != null && find(image, "<init>", "(Landroid/graphics/Bitmap;)V") != null) {
                        put(out, "bitmapPainter", c.desc); put(out, "imageBitmap", image.desc);
                    }
                }
                if (out.containsKey("bitmapPainter")) break;
            }
        }

        Cls mutable = cls(classOf(withString("MutableState(value=")));
        if (mutable != null) for (Mth m : mutable.methods)
            if (m.name.equals("<init>") && same(m.params, "Ljava/lang/Object;", null)) { put(out, "mutable", mutable.desc); put(out, "policy", m.params[1]); }
        put(out, "policy.impl", classOf(withString("StructuralEqualityPolicy")));

        for (Cls c : classes.values()) {
            boolean handler = false;
            for (String i : c.ifaces) if (i.equals("Landroidx/compose/ui/input/pointer/PointerInputEventHandler;")) handler = true;
            if (!handler) continue;
            for (Mth m : c.methods) {
                if (!m.name.equals("<init>") || !same(m.params, null, m.params.length == 5 ? m.params[0] : "?", null, "F", null)) continue;
                if (!m.params[0].startsWith("L") || !m.params[2].startsWith("L") || !m.params[4].startsWith("L")) continue;
                Cls rect = classes.get(m.params[2]);
                if (rect == null || find(rect, "<init>", "(FFFF)V") == null) continue;
                put(out, "crop", c.desc); put(out, "state", m.params[0]); put(out, "rect", m.params[2]); put(out, "fn1", m.params[4]);
                Mth invoke = find(c, "invoke", null);
                if (invoke != null) for (Insn in : code(invoke)) if (in.op == 0x22) { put(out, "cropLambda", invoke.dex.types[in.index]); break; }
            }
            if (out.containsKey("crop")) break;
        }
        for (Mth m : withString("INSIDE")) if (m.name.equals("<clinit>") && withStringIn(m, "TOP_LEFT")) { put(out, "edges", m.owner.desc); break; }

        String config = classOf(withStringPrefix("RenderConfig("));
        if (config != null) for (Mth m : allMethods) {
            if (m.isStatic || !same(m.params, config) || !"Landroid/graphics/Picture;".equals(m.ret)) continue;
            put(out, "config", config); put(out, "renderer", m.owner.desc); out.put("renderer.picture", m.name);
            Dex x = m.dex; String area = null;
            for (Insn in : code(m)) {
                if (!isInvoke(in.op)) continue;
                int proto = x.methodProto[in.index]; String owner = x.types[x.methodClass[in.index]], ret = x.types[x.protoRet[proto]];
                if (x.protoParams[proto].length != 0) continue;
                if (area == null && "I".equals(ret) && hasField(m.owner, owner)) { area = owner; put(out, "area", owner); out.put("area.width", x.strings[x.methodName[in.index]]); }
                else if (owner.equals(area) && "Landroid/graphics/Matrix;".equals(ret)) { out.put("area.matrix", x.strings[x.methodName[in.index]]); break; }
            }
            break;
        }
        String renderer = desc(out.get("renderer")), area = desc(out.get("area"));
        if (renderer != null && area != null) {
            for (Cls c : classes.values()) {
                if (c.desc.equals(renderer) || !hasField(c, renderer)) continue;
                Mth get = null;
                for (Mth m : c.methods) if (!m.isStatic && m.params.length == 0 && area.equals(m.ret)) { get = m; break; }
                if (get == null) continue;
                for (Cls v : classes.values()) if ("Landroid/view/View;".equals(v.sup) && hasField(v, c.desc)) { put(out, "view", v.desc); break; }
                if (out.containsKey("view")) { put(out, "scene", c.desc); out.put("scene.area", get.name); break; }
            }
        }
        return out;
    }

    List<Mth> withStringPrefix(String prefix) {
        List<Mth> r = new ArrayList<Mth>();
        for (Mth m : allMethods) {
            for (Insn in : code(m)) if ((in.op == 0x1a || in.op == 0x1b) && m.dex.strings[in.index].startsWith(prefix)) { r.add(m); break; }
        }
        return r;
    }
    private static boolean ctorHas(Cls c, String... types) {
        for (Mth m : c.methods) {
            if (!m.name.equals("<init>")) continue;
            java.util.List<String> p = java.util.Arrays.asList(m.params);
            boolean all = true; for (String t : types) all &= p.contains(t);
            if (all) return true;
        }
        return false;
    }
    private boolean withStringIn(Mth m, String s) {
        Integer idx = m.dex.stringIndex.get(s);
        if (idx != null) for (Insn in : code(m)) if ((in.op == 0x1a || in.op == 0x1b) && in.index == idx) return true;
        return false;
    }
    private static boolean creates(Mth m, String type) { for (Insn in : code(m)) if (in.op == 0x22 && type.equals(m.dex.types[in.index])) return true; return false; }
    private static int count(Cls c, String type) { int n = 0; for (Fld f : c.fields) if (!f.isStatic && type.equals(f.type)) n++; return n; }
    private static boolean hasField(Cls c, String type) { return count(c, type) > 0; }
    private Cls cls(String nameOrDesc) { return nameOrDesc == null ? null : classes.get(desc(nameOrDesc)); }
    private static String desc(String name) { return name == null ? null : name.startsWith("L") && name.endsWith(";") ? name : "L" + name.replace('.', '/') + ";"; }
    private static void put(Map<String, String> out, String key, String desc) { if (desc != null && !out.containsKey(key)) out.put(key, name(desc)); }

    /** The class (descriptor) of the methods found, if they all agree. */
    private static String classOf(List<Mth> ms) {
        String c = null;
        for (Mth m : ms) { if (c == null) c = m.owner.desc; else if (!c.equals(m.owner.desc)) return null; }
        return c;
    }
    /** The only static method of that size among those found. */
    private static Mth one(List<Mth> ms, boolean isStatic, int params) {
        Mth r = null;
        for (Mth m : ms) if (m.isStatic == isStatic && m.params.length == params) { if (r != null) return null; r = m; }
        return r;
    }

    /** PC test: java DexScan.java base.apk */
    public static void main(String[] a) throws Exception {
        long t = System.currentTimeMillis();
        DexScan s = read(a[0]);
        Map<String, String> r = s.resolve();
        for (Map.Entry<String, String> e : r.entrySet()) System.out.println(e.getKey() + "=" + e.getValue());
        System.out.println("# " + r.size() + " names in " + (System.currentTimeMillis() - t) + " ms");
    }
}
