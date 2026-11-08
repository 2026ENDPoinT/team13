#!/usr/bin/env python3
"""Build an ERDCloud snapshot (Import > Upload Snapshot) from a schema built by the Flyway migrations.

Engines: MySQL (root ASTRA schema, Korean names from COMMENT) and PostgreSQL (backend/ Campus On
schema; it has no COMMENTs, so Korean names come from a --names JSON file).

The snapshot carries what ERDCloud's SQL import loses or gets wrong: Korean logical names,
UNSIGNED types, comments with brackets, PK/FK placement, and 1:1 cardinality for FK columns that
are a PK or a single-column UNIQUE key. Entity positions are kept from the previous snapshot by
physical table name; tables new to the schema are placed in a fresh column.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SNAPSHOT = ROOT / "db/erdcloud/snapshot.json"
ID_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTWXYZabcdefghijkmnopqrstuvwxyz"  # Meteor Random.id alphabet
NULL = "__ASTRA_NULL__"

MYSQL_QUERIES = {
    "tables": "SELECT table_name, table_comment FROM information_schema.tables "
              "WHERE table_schema='{s}' AND table_type='BASE TABLE'",
    "columns": "SELECT table_name, column_name, column_type, is_nullable, IFNULL(column_default,'{null}'), "
               "extra, column_comment FROM information_schema.columns WHERE table_schema='{s}' "
               "ORDER BY table_name, ordinal_position",
    "pks": "SELECT table_name, column_name FROM information_schema.key_column_usage "
           "WHERE table_schema='{s}' AND constraint_name='PRIMARY'",
    "fks": "SELECT table_name, column_name, referenced_table_name, referenced_column_name "
           "FROM information_schema.key_column_usage WHERE table_schema='{s}' AND referenced_table_name IS NOT NULL",
    "uniques": "SELECT k.table_name, k.column_name FROM information_schema.key_column_usage k "
               "JOIN information_schema.table_constraints c ON c.constraint_schema=k.constraint_schema "
               "AND c.table_name=k.table_name AND c.constraint_name=k.constraint_name "
               "WHERE k.table_schema='{s}' AND c.constraint_type='UNIQUE' AND (SELECT COUNT(*) FROM "
               "information_schema.key_column_usage k2 WHERE k2.constraint_schema=k.constraint_schema "
               "AND k2.table_name=k.table_name AND k2.constraint_name=k.constraint_name)=1",
}

_PG_KEY_COLUMNS = ("FROM pg_constraint k JOIN pg_class cl ON cl.oid = k.conrelid "
                   "CROSS JOIN LATERAL unnest(k.conkey) AS u(attnum) "
                   "JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = u.attnum "
                   "WHERE k.connamespace = '{s}'::regnamespace")
PG_QUERIES = {
    "tables": "SELECT c.relname, COALESCE(obj_description(c.oid, 'pg_class'), '') FROM pg_class c "
              "WHERE c.relnamespace = '{s}'::regnamespace AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'",
    "columns": "SELECT c.relname, a.attname, format_type(a.atttypid, a.atttypmod), "
               "CASE WHEN a.attnotnull THEN 'NO' ELSE 'YES' END, "
               "COALESCE(pg_get_expr(d.adbin, d.adrelid), '{null}'), '', COALESCE(col_description(c.oid, a.attnum), '') "
               "FROM pg_class c JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped "
               "LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum "
               "WHERE c.relnamespace = '{s}'::regnamespace AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history' "
               "ORDER BY c.relname, a.attnum",
    "pks": f"SELECT cl.relname, a.attname {_PG_KEY_COLUMNS} AND k.contype = 'p'",
    "fks": "SELECT cl.relname, a.attname, rcl.relname, ra.attname FROM pg_constraint k "
           "JOIN pg_class cl ON cl.oid = k.conrelid JOIN pg_class rcl ON rcl.oid = k.confrelid "
           "CROSS JOIN LATERAL unnest(k.conkey, k.confkey) AS u(attnum, rattnum) "
           "JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = u.attnum "
           "JOIN pg_attribute ra ON ra.attrelid = k.confrelid AND ra.attnum = u.rattnum "
           "WHERE k.connamespace = '{s}'::regnamespace AND k.contype = 'f'",
    "uniques": f"SELECT cl.relname, a.attname {_PG_KEY_COLUMNS} AND k.contype = 'u' AND cardinality(k.conkey) = 1",
}
ENGINE_DEFAULTS = {
    "mysql": {"container": "astra-erd-mysql", "schema": "astra", "queries": MYSQL_QUERIES},
    "postgres": {"container": "campus-on-erd-pg", "schema": "public", "queries": PG_QUERIES},
}
PG_TYPES = [(r"^character varying", "VARCHAR"), (r"^timestamp(\(\d+\))? with time zone$", r"TIMESTAMPTZ\1"),
            (r"^timestamp(\(\d+\))? without time zone$", r"TIMESTAMP\1"), (r"^integer$", "INTEGER"),
            (r"^character\b", "CHAR")]


def query(args, name: str) -> list[list[str]]:
    sql = ENGINE_DEFAULTS[args.engine]["queries"][name].format(s=args.schema, null=NULL)
    if args.engine == "mysql":
        cmd = ["docker", "exec", args.container, "mysql", "-uroot", f"-p{args.password}",
               "--default-character-set=utf8mb4", "-N", "-B", "-e", sql]
    else:
        cmd = ["docker", "exec", args.container, "psql", "-U", "postgres", "-d", args.database,
               "-At", "-F", "\t", "-c", sql]
    out = subprocess.run(cmd, capture_output=True, check=True).stdout.decode("utf-8")
    return [line.split("\t") for line in out.splitlines() if line]


def pg_type(raw: str) -> str:
    for pattern, repl in PG_TYPES:
        raw = re.sub(pattern, repl, raw)
    return raw


def pg_default(raw: str) -> str:
    """"'K001'::character varying" -> "K001"; numbers, booleans and expressions stay as they are."""
    m = re.fullmatch(r"'((?:[^']|'')*)'(::[\w\s]+(\(\d+\))?)?", raw)
    return m.group(1).replace("''", "'") if m else raw


def stable_id(key: str) -> str:
    n = int.from_bytes(hashlib.sha256(key.encode("utf-8")).digest(), "big")
    chars = []
    for _ in range(17):
        n, r = divmod(n, len(ID_ALPHABET))
        chars.append(ID_ALPHABET[r])
    return "".join(chars)


def logical_name(comment: str, fallback: str) -> str:
    """'역할: STUDENT|TA' -> '역할', '학년(학생만, 1~6)' -> '학년', '유형(a:객관식) [추정]' -> '유형'."""
    name, depth = "", 0
    for ch in comment.replace("[추정]", ""):
        depth += (ch == "(") - (ch == ")")
        if ch == ":" and depth == 0:
            break
        name += ch
    name = name.strip()
    while (m := re.search(r"\s*\([^()]*\)\s*$", name)):
        name = name[:m.start()].rstrip()
    return name.strip(" ,.") or comment or fallback


def default_value(raw: str, extra: str, column_type: str) -> str:
    if raw == NULL:
        return ""
    if "DEFAULT_GENERATED" in extra.upper() or re.fullmatch(r"-?\d+(\.\d+)?|true|false", raw) and not column_type.lower().startswith(("char", "varchar")):
        return raw
    return "'" + raw.replace("'", "''") + "'"


def build(args) -> dict:
    names = json.loads(args.names.read_text(encoding="utf-8")) if args.names else {}
    table_names, column_names = names.get("tables", {}), names.get("columns", {})
    tables = {t: table_names.get(t) or comment for t, comment in query(args, "tables")}
    columns = defaultdict(list)
    for t, c, ctype, nullable, dflt, extra, comment in query(args, "columns"):
        if args.engine == "postgres":
            ctype, dflt = pg_type(ctype), (dflt if dflt == NULL else pg_default(dflt))
        columns[t].append((c, ctype, nullable == "YES", dflt, extra, column_names.get(f"{t}.{c}") or comment))
    pks = {(t, c) for t, c in query(args, "pks")}
    fks = {(t, c): (rt, rc) for t, c, rt, rc in query(args, "fks")}
    pk_count = defaultdict(int)
    for t, _ in pks:
        pk_count[t] += 1
    # 1:1 when the FK column alone is unique: a single-column PK or a single-column UNIQUE key
    one_to_one = {(t, c) for t, c in query(args, "uniques")} | {(t, c) for t, c in pks if pk_count[t] == 1}

    previous = json.loads(args.previous.read_text(encoding="utf-8")) if args.previous.exists() else {}
    positions = {e["pName"]: e["position"] for e in previous.get("entityData", [])}
    new_x = max((p["x"] for p in positions.values()), default=-650) + 650
    for column in names.get("layout", []):  # first layout for tables a previous snapshot does not place
        y = 50
        for t in column:
            if t in tables and t not in positions:
                positions[t] = {"x": new_x, "y": y}
                y += 120 + 26 * len(columns[t])
        new_x += 650
    new_y = 50

    entities = []
    for t in sorted(tables, key=lambda name: (name not in positions, name)):
        if t not in positions:
            positions[t] = {"x": new_x, "y": new_y}
            new_y += 120 + 26 * len(columns[t])
        keys = {"pks": [], "fks": []}
        fields = []
        for c, ctype, nullable, dflt, extra, comment in columns[t]:
            ref = fks.get((t, c))
            field = {
                "_id": stable_id(f"{t}.{c}"), "name": logical_name(comment, c), "pName": c, "domain": "",
                "type": ctype.upper(), "defaultValue": default_value(dflt, extra, ctype),
                "isAllowNull": nullable, "comment": comment,
                "relEntity": stable_id(ref[0]) if ref else None,
                "relFieldId": stable_id(f"{ref[0]}.{ref[1]}") if ref else None,
                "relType": ("ZERO_OR_ONE" if (t, c) in one_to_one else "ZERO_OR_ONE_OR_MANY") if ref else None,
            }
            if (t, c) in pks:
                keys["fks" if ref else "pks"].append(field)  # ERDCloud keeps PK+FK (PFK) under keys.fks
            else:
                fields.append(field)
        entities.append({"_id": stable_id(t), "_diagramId": args.diagram, "pName": t,
                         "name": logical_name(tables[t], t), "position": positions[t],
                         "keys": keys, "fields": fields})
    return {"entityData": entities, "memoData": previous.get("memoData", []),
            "domainData": previous.get("domainData", [])}


def normalize(snapshot: dict) -> dict:
    """Table -> (logical name, rows) with relations by physical name; ids and positions are ignored."""
    entities = snapshot["entityData"]
    table_of = {e["_id"]: e["pName"] for e in entities}
    column_of = {f["_id"]: f["pName"] for e in entities for f in e["keys"]["pks"] + e["keys"]["fks"] + e["fields"]}
    out = {}
    for e in entities:
        rows = []
        for section, items in (("pk", e["keys"]["pks"]), ("pfk", e["keys"]["fks"]), ("col", e["fields"])):
            for f in items:
                ref = (table_of.get(f["relEntity"]), column_of.get(f["relFieldId"])) if f.get("relEntity") else None
                rows.append((section, f["pName"], f["name"], f["type"], f["isAllowNull"], f.get("defaultValue") or "",
                             f.get("comment") or "", f.get("relType") if ref else None, ref))
        out[e["pName"]] = (e["name"], rows)
    return out


def compare(expected_path: Path, actual_path: Path) -> int:
    expected = normalize(json.loads(expected_path.read_text(encoding="utf-8")))
    actual = normalize(json.loads(actual_path.read_text(encoding="utf-8")))
    problems = [f"missing table {t}" for t in sorted(expected.keys() - actual.keys())]
    problems += [f"extra table {t}" for t in sorted(actual.keys() - expected.keys())]
    for t in sorted(expected.keys() & actual.keys()):
        if expected[t][0] != actual[t][0]:
            problems.append(f"{t}: logical name {actual[t][0]!r} != {expected[t][0]!r}")
        exp_rows, act_rows = expected[t][1], actual[t][1]
        for row in [r for r in exp_rows if r not in act_rows]:
            problems.append(f"{t}.{row[1]}: expected {row}")
        for row in [r for r in act_rows if r not in exp_rows]:
            problems.append(f"{t}.{row[1]}: found    {row}")
    for line in problems[:40]:
        print(line)
    print(f"{len(expected)} tables compared, {len(problems)} differences")
    return 1 if problems else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--engine", choices=sorted(ENGINE_DEFAULTS), default="mysql")
    parser.add_argument("--container", help="Docker container with the loaded schema (default per engine)")
    parser.add_argument("--password", default="erd", help="MySQL root password")
    parser.add_argument("--schema", help="MySQL database or PostgreSQL schema (default per engine)")
    parser.add_argument("--database", default="campus", help="PostgreSQL database")
    parser.add_argument("--names", type=Path, help="JSON with Korean table/column names and an initial layout")
    parser.add_argument("--diagram", default="B5bWYfGmFisJTYLka", help="ERDCloud diagram id (URL /d/<id>)")
    parser.add_argument("--previous", type=Path, default=DEFAULT_SNAPSHOT, help="snapshot whose positions are kept")
    parser.add_argument("--out", type=Path, default=DEFAULT_SNAPSHOT)
    parser.add_argument("--compare", type=Path, metavar="DOWNLOADED",
                        help="compare a snapshot downloaded from ERDCloud with --out instead of generating")
    args = parser.parse_args()
    args.container = args.container or ENGINE_DEFAULTS[args.engine]["container"]
    args.schema = args.schema or ENGINE_DEFAULTS[args.engine]["schema"]
    if args.compare:
        return compare(args.out, args.compare)
    snapshot = build(args)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(snapshot, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    rels = sum(1 for e in snapshot["entityData"] for f in e["keys"]["fks"] + e["fields"] if f["relEntity"])
    cols = sum(len(e["keys"]["pks"]) + len(e["keys"]["fks"]) + len(e["fields"]) for e in snapshot["entityData"])
    print(f"{args.out}: {len(snapshot['entityData'])} tables, {cols} columns, {rels} relationships")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
