#!/usr/bin/env python3
"""Build an ERDCloud snapshot (Import > Upload Snapshot) from a MySQL schema built by the Flyway migrations.

The snapshot carries what ERDCloud's SQL import loses or gets wrong: Korean logical names taken
from COMMENT, UNSIGNED types, comments with brackets, PK/FK placement, and 1:1 cardinality for FK
columns that are a PK or a single-column UNIQUE key. Entity positions are kept from the previous
snapshot by physical table name; tables new to the schema are placed in a fresh column.
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

QUERIES = {
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


def query(args, sql: str) -> list[list[str]]:
    cmd = ["docker", "exec", args.container, "mysql", "-uroot", f"-p{args.password}",
           "--default-character-set=utf8mb4", "-N", "-B", "-e", sql.format(s=args.schema, null=NULL)]
    out = subprocess.run(cmd, capture_output=True, check=True).stdout.decode("utf-8")
    return [line.split("\t") for line in out.splitlines() if line]


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
    if "DEFAULT_GENERATED" in extra.upper() or re.fullmatch(r"-?\d+(\.\d+)?", raw) and not column_type.lower().startswith(("char", "varchar")):
        return raw
    return "'" + raw.replace("'", "''") + "'"


def build(args) -> dict:
    tables = dict(query(args, QUERIES["tables"]))
    columns = defaultdict(list)
    for t, c, ctype, nullable, dflt, extra, comment in query(args, QUERIES["columns"]):
        columns[t].append((c, ctype, nullable == "YES", dflt, extra, comment))
    pks = {(t, c) for t, c in query(args, QUERIES["pks"])}
    fks = {(t, c): (rt, rc) for t, c, rt, rc in query(args, QUERIES["fks"])}
    one_to_one = {(t, c) for t, c in query(args, QUERIES["uniques"])} | pks

    previous = json.loads(args.previous.read_text(encoding="utf-8")) if args.previous.exists() else {}
    positions = {e["pName"]: e["position"] for e in previous.get("entityData", [])}
    new_x = max((p["x"] for p in positions.values()), default=-600) + 600
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
    parser.add_argument("--container", default="astra-erd-mysql")
    parser.add_argument("--password", default="erd")
    parser.add_argument("--schema", default="astra")
    parser.add_argument("--diagram", default="B5bWYfGmFisJTYLka", help="ERDCloud diagram id (URL /d/<id>)")
    parser.add_argument("--previous", type=Path, default=DEFAULT_SNAPSHOT, help="snapshot whose positions are kept")
    parser.add_argument("--out", type=Path, default=DEFAULT_SNAPSHOT)
    parser.add_argument("--compare", type=Path, metavar="DOWNLOADED",
                        help="compare a snapshot downloaded from ERDCloud with --out instead of generating")
    args = parser.parse_args()
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
