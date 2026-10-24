#!/usr/bin/env python3
"""Generate Kotlin TL classes from Telegram Desktop's api.tl / mtproto.tl.

Naming mirrors tdesktop's generated C++ so the export code can be ported
almost line by line:
  type  messages.Messages        -> interface MTPmessages_Messages
  ctor  messages.messages        -> class     MTPDmessages_messages
  func  messages.getHistory      -> class     MTPmessages_GetHistory
  field from_id                  -> vfrom_id
  flag  out:flags.1?true         -> is_out

Usage: tlgen.py <out_dir> <schema.tl>...
"""
import os
import re
import sys

PKG = "org.tgbackup.mtproto.tl"
SKIP_TYPES = {"Bool", "True", "Vector", "Vector t", "Int", "Long", "Double", "String", "Int128", "Int256"}
CHUNK = 120


def split_ns(name):
    if "." in name:
        ns, base = name.rsplit(".", 1)
        return ns + "_", base
    return "", name


def type_class(name):
    ns, base = split_ns(name)
    return "MTP" + ns + base


def ctor_class(name):
    ns, base = split_ns(name)
    return "MTPD" + ns + base


def func_class(name):
    ns, base = split_ns(name)
    return "MTP" + ns + base[0].upper() + base[1:]


class Param:
    def __init__(self, name, ty):
        self.name = name
        self.flag = None  # (flags_field, bit)
        m = re.match(r"(\w+)\.(\d+)\?(.+)", ty)
        if m:
            self.flag = (m.group(1), int(m.group(2)))
            ty = m.group(3)
        self.ty = ty

    @property
    def is_flags(self):
        return self.ty == "#"

    @property
    def is_true(self):
        return self.flag is not None and self.ty == "true"

    @property
    def kname(self):
        return ("is_" if self.is_true else "v") + self.name


class Combinator:
    def __init__(self, name, cid, params, result, is_func):
        self.name = name
        self.cid = cid
        self.params = params
        self.result = result
        self.is_func = is_func


def parse(paths):
    ctors, funcs = {}, {}
    for path in paths:
        binary_strings = os.path.basename(path) == "mtproto.tl"
        section = "types"
        for raw in open(path, encoding="utf-8"):
            line = raw.split("//")[0].strip()
            if not line:
                continue
            if line == "---functions---":
                section = "functions"
                continue
            if line == "---types---":
                section = "types"
                continue
            m = re.match(r"^([\w.]+)(?:#([0-9a-fA-F]+))?\s*(.*?)\s*=\s*([^;]+);$", line)
            if not m:
                continue
            name, cid, rest, result = m.groups()
            if cid is None:
                continue  # core / no-id helper types
            if "{X:Type}" in rest:
                continue  # invoke* wrappers are written by hand
            if name in ("vector", "true", "boolFalse", "boolTrue"):
                continue
            params = []
            for tok in rest.split():
                if tok.startswith("{"):
                    continue
                pname, pty = tok.split(":", 1)
                if binary_strings:
                    # mtproto.tl uses "string" for raw binary data.
                    pty = re.sub(r"\bstring\b", "bytes", pty)
                params.append(Param(pname, pty))
            c = Combinator(name, int(cid, 16), params, result.strip(), section == "functions")
            (funcs if c.is_func else ctors)[name] = c
    return ctors, funcs


class Gen:
    def __init__(self, ctors, funcs):
        self.ctors = ctors
        self.funcs = funcs
        self.types = {}
        for c in ctors.values():
            self.types.setdefault(c.result, []).append(c)

    # ---- type helpers -------------------------------------------------------
    def kt_type(self, ty):
        if ty in ("int",):
            return "Int"
        if ty == "long":
            return "Long"
        if ty == "double":
            return "Double"
        if ty == "string":
            return "String"
        if ty == "bytes":
            return "ByteArray"
        if ty in ("int128", "int256"):
            return "ByteArray"
        if ty == "Bool":
            return "Boolean"
        m = re.match(r"^[Vv]ector<(.+)>$", ty)
        if m:
            return "List<" + self.kt_type(m.group(1)) + ">"
        if ty.startswith("%"):
            return ctor_class(ty[1:])
        if ty in self.types:
            return type_class(ty)
        if ty in self.ctors:  # bare reference by constructor name
            return ctor_class(ty)
        raise SystemExit("unknown type " + ty)

    def reader(self, ty):
        if ty == "int":
            return "r.int()"
        if ty == "long":
            return "r.long()"
        if ty == "double":
            return "r.double()"
        if ty == "string":
            return "r.string()"
        if ty == "bytes":
            return "r.bytes()"
        if ty == "int128":
            return "r.raw(16)"
        if ty == "int256":
            return "r.raw(32)"
        if ty == "Bool":
            return "r.bool()"
        m = re.match(r"^Vector<(.+)>$", ty)
        if m:
            return "r.vector { " + self.reader(m.group(1)) + " }"
        m = re.match(r"^vector<(.+)>$", ty)
        if m:
            return "r.bareVector { " + self.reader(m.group(1)) + " }"
        if ty.startswith("%"):
            return ctor_class(ty[1:]) + ".read(r)"
        if ty in self.types:
            return "r.obj() as " + type_class(ty)
        if ty in self.ctors:
            return ctor_class(ty) + ".read(r)"
        raise SystemExit("unknown type " + ty)

    def writer(self, ty, expr):
        if ty == "int":
            return "w.int(%s)" % expr
        if ty == "long":
            return "w.long(%s)" % expr
        if ty == "double":
            return "w.double(%s)" % expr
        if ty == "string":
            return "w.string(%s)" % expr
        if ty == "bytes":
            return "w.bytes(%s)" % expr
        if ty in ("int128", "int256"):
            return "w.raw(%s)" % expr
        if ty == "Bool":
            return "w.bool(%s)" % expr
        m = re.match(r"^Vector<(.+)>$", ty)
        if m:
            return "w.vector(%s) { %s }" % (expr, self.writer(m.group(1), "it"))
        m = re.match(r"^vector<(.+)>$", ty)
        if m:
            return "w.bareVector(%s) { %s }" % (expr, self.writer(m.group(1), "it"))
        if ty.startswith("%") or (ty not in self.types and ty in self.ctors):
            return "%s.serializeBody(w)" % expr
        return "%s.serialize(w)" % expr

    # ---- emitters -----------------------------------------------------------
    def body(self, c, cls, extends):
        out = []
        fields = [p for p in c.params if not p.is_flags]
        args = []
        for p in fields:
            t = "Boolean" if p.is_true else self.kt_type(p.ty)
            if p.flag and not p.is_true:
                args.append("    val %s: %s? = null" % (p.kname, t))
            elif p.is_true:
                args.append("    val %s: Boolean = false" % p.kname)
            else:
                args.append("    val %s: %s" % (p.kname, t))
        cid = "0x%08x.toInt()" % c.cid
        if not fields:
            if c.is_func:
                out.append("class %s : %s {" % (cls, extends))
            else:
                out.append("object %s : %s {" % (cls, extends))
        else:
            out.append("class %s(\n%s,\n) : %s {" % (cls, ",\n".join(args), extends))
        out.append("    override val constructorId: Int get() = CONSTRUCTOR")
        out.append("    override fun serializeBody(w: TlWriter) {")
        for p in c.params:
            if p.is_flags:
                parts = []
                for q in c.params:
                    if q.flag and q.flag[0] == p.name:
                        cond = q.kname if q.is_true else "%s != null" % q.kname
                        parts.append("(if (%s) %d else 0)" % (cond, 1 << q.flag[1]))
                out.append("        w.int(%s)" % (" or ".join(parts) if parts else "0"))
            elif p.is_true:
                continue
            elif p.flag:
                out.append("        %s?.let { %s }" % (p.kname, self.writer(p.ty, "it")))
            else:
                out.append("        " + self.writer(p.ty, p.kname))
        out.append("    }")
        if c.is_func:
            out.append("    override fun parseResult(r: TlReader): %s = %s" % (self.kt_type(c.result), self.reader(c.result)))
        if not fields and not c.is_func:
            out.append("    const val CONSTRUCTOR: Int = %s" % cid)
            out.append("    @Suppress(\"UNUSED_PARAMETER\") fun read(r: TlReader): %s = this" % cls)
            out.append("}")
            return "\n".join(out)
        out.append("    companion object {")
        out.append("        const val CONSTRUCTOR: Int = %s" % cid)
        if not c.is_func:
            ret = cls
            if True:
                out.append("        fun read(r: TlReader): %s {" % ret)
                for p in c.params:
                    if p.is_flags:
                        out.append("            val _%s = r.int()" % p.name)
                    elif p.is_true:
                        out.append("            val %s = (_%s and %d) != 0" % (p.kname, p.flag[0], 1 << p.flag[1]))
                    elif p.flag:
                        out.append("            val %s = if ((_%s and %d) != 0) %s else null" % (p.kname, p.flag[0], 1 << p.flag[1], self.reader(p.ty)))
                    else:
                        out.append("            val %s = %s" % (p.kname, self.reader(p.ty)))
                out.append("            return %s(%s)" % (cls, ", ".join(p.kname for p in fields)))
                out.append("        }")
        out.append("    }")
        out.append("}")
        return "\n".join(out)

    def run(self, out_dir):
        hdr = "// Generated by tools/tlgen.py from export-core/schema. Do not edit.\n@file:Suppress(\"ClassName\", \"PropertyName\", \"unused\", \"SpellCheckingInspection\")\npackage %s\n\n" % PKG
        names = set()
        def claim(n):
            if n in names:
                raise SystemExit("name clash " + n)
            names.add(n)
        chunks, cur = [], []
        for t in sorted(self.types):
            if t in SKIP_TYPES:
                continue
            claim(type_class(t))
            cur.append("sealed interface %s : TlObject" % type_class(t))
            for c in self.types[t]:
                claim(ctor_class(c.name))
                cur.append(self.body(c, ctor_class(c.name), type_class(t)))
            if len(cur) > CHUNK:
                chunks.append(cur)
                cur = []
        if cur:
            chunks.append(cur)
        fchunk, cur = [], []
        for f in sorted(self.funcs):
            c = self.funcs[f]
            claim(func_class(f))
            cur.append(self.body(c, func_class(f), "TlFunction<%s>" % self.kt_type(c.result)))
            if len(cur) > CHUNK:
                fchunk.append(cur)
                cur = []
        if cur:
            fchunk.append(cur)
        for f in os.listdir(out_dir):
            if f.startswith("Gen"):
                os.remove(os.path.join(out_dir, f))
        for i, ch in enumerate(chunks):
            open(os.path.join(out_dir, "GenTypes%02d.kt" % i), "w").write(hdr + "\n\n".join(ch) + "\n")
        for i, ch in enumerate(fchunk):
            open(os.path.join(out_dir, "GenFunctions%02d.kt" % i), "w").write(hdr + "\n\n".join(ch) + "\n")
        # Registry: constructor id -> bare reader, split to keep methods small.
        all_ctors = [c for t in sorted(self.types) if t not in SKIP_TYPES for c in self.types[t]]
        reg = [hdr, "internal object GenRegistry {\n    fun fill(m: HashMap<Int, (TlReader) -> TlObject>) {\n"]
        groups = [all_ctors[i:i + 200] for i in range(0, len(all_ctors), 200)]
        reg.append("".join("        fill%d(m)\n" % i for i in range(len(groups))))
        reg.append("    }\n")
        for i, g in enumerate(groups):
            reg.append("    private fun fill%d(m: HashMap<Int, (TlReader) -> TlObject>) {\n" % i)
            for c in g:
                reg.append("        m[%s.CONSTRUCTOR] = %s::read\n" % (ctor_class(c.name), ctor_class(c.name)))
            reg.append("    }\n")
        reg.append("}\n")
        open(os.path.join(out_dir, "GenRegistry.kt"), "w").write("".join(reg))
        print("types", len(self.types), "ctors", len(all_ctors), "funcs", len(self.funcs))


if __name__ == "__main__":
    ctors, funcs = parse(sys.argv[2:])
    Gen(ctors, funcs).run(sys.argv[1])
